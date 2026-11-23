/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveNodeDbMergeTest {

    private val peer = 0x22222222

    @Test
    fun `unknown node appears from a NODEINFO packet`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val changes = collectChanges(client)

        transport.injectPacket(nodeInfoPacket(peer, user("Peer One", "P1"), rxTime = 1_700_000_100))
        runCurrent()

        val node = assertNotNull(client.nodeSnapshot()[NodeId(peer)])
        assertEquals("Peer One", node.user?.long_name)
        assertEquals(1_700_000_100, node.last_heard)
        assertTrue(changes.any { it is NodeChange.Added && it.node.num == peer })
        client.disconnect()
    }

    @Test
    fun `known node user is updated by a NODEINFO packet`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        transport.injectPacket(nodeInfoPacket(peer, user("Old", "OL"), rxTime = 1_700_000_100))
        runCurrent()
        val changes = collectChanges(client)

        transport.injectPacket(nodeInfoPacket(peer, user("New", "NW"), rxTime = 1_700_000_100))
        runCurrent()

        assertEquals("New", client.nodeSnapshot()[NodeId(peer)]?.user?.long_name)
        val updated = changes.filterIsInstance<NodeChange.Updated>().single { it.node.num == peer }
        assertTrue(NodeField.Name in updated.changed)
        assertTrue(NodeField.User in updated.changed)
        client.disconnect()
    }

    @Test
    fun `placeholder NODEINFO does not overwrite a good user`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        transport.injectPacket(nodeInfoPacket(peer, user("Good", "GD"), rxTime = 1_700_000_100))
        runCurrent()

        transport.injectPacket(nodeInfoPacket(peer, User.Builder().build(), rxTime = 1_700_000_200))
        runCurrent()

        assertEquals("Good", client.nodeSnapshot()[NodeId(peer)]?.user?.long_name)
        client.disconnect()
    }

    @Test
    fun `POSITION packet sets the node position and creates an unknown node`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val changes = collectChanges(client)

        val position = Position.Builder().also { wb ->
            wb.latitude_i = 374_220_000
            wb.longitude_i = -1_220_840_000
            wb.altitude = 12
        }.build()
        transport.injectPacket(
            packet(peer, PortNum.POSITION_APP, Position.ADAPTER.encode(position), rxTime = 1_700_000_100),
        )
        runCurrent()

        val node = assertNotNull(client.nodeSnapshot()[NodeId(peer)])
        assertEquals(374_220_000, node.position?.latitude_i)
        assertEquals(-1_220_840_000, node.position?.longitude_i)
        assertTrue(changes.any { it is NodeChange.Added && it.node.num == peer })
        client.disconnect()
    }

    @Test
    fun `empty POSITION request leaves the node position unchanged`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val position = Position.Builder().also { wb ->
            wb.latitude_i = 374_220_000
            wb.longitude_i = -1_220_840_000
        }.build()
        transport.injectPacket(
            packet(peer, PortNum.POSITION_APP, Position.ADAPTER.encode(position), rxTime = 1_700_000_100),
        )
        runCurrent()

        transport.injectPacket(packet(peer, PortNum.POSITION_APP, ByteArray(0), rxTime = 1_700_000_200))
        runCurrent()

        assertEquals(374_220_000, client.nodeSnapshot()[NodeId(peer)]?.position?.latitude_i)
        client.disconnect()
    }

    @Test
    fun `text packet updates last_heard snr and hops_away`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val changes = collectChanges(client)

        transport.injectPacket(
            packet(peer, PortNum.TEXT_MESSAGE_APP, "hi".encodeToByteArray(), rxTime = 1_700_000_300) {
                rx_snr = 6.5f
                rx_rssi = -80
                hop_start = 3
                hop_limit = 1
                transport_mechanism = MeshPacket.TransportMechanism.TRANSPORT_LORA
            },
        )
        runCurrent()

        val node = assertNotNull(client.nodeSnapshot()[NodeId(peer)])
        assertEquals(1_700_000_300, node.last_heard)
        assertEquals(6.5f, node.snr)
        assertEquals(2, node.hops_away)
        val added = changes.filterIsInstance<NodeChange.Added>().single { it.node.num == peer }
        assertEquals(peer, added.node.num)

        transport.injectPacket(
            packet(peer, PortNum.TEXT_MESSAGE_APP, "again".encodeToByteArray(), rxTime = 1_700_000_400) {
                rx_snr = 7.5f
                rx_rssi = -79
                hop_start = 3
                hop_limit = 3
                transport_mechanism = MeshPacket.TransportMechanism.TRANSPORT_LORA
            },
        )
        runCurrent()

        val updated = changes.filterIsInstance<NodeChange.Updated>().single { it.node.num == peer }
        assertEquals(setOf(NodeField.SignalQuality, NodeField.LastSeen), updated.changed)
        assertEquals(0, updated.node.hops_away)
        client.disconnect()
    }

    @Test
    fun `hop_start of zero leaves hops_away unchanged`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        transport.injectPacket(
            packet(peer, PortNum.TEXT_MESSAGE_APP, "a".encodeToByteArray(), rxTime = 1_700_000_300) {
                hop_start = 3
                hop_limit = 2
            },
        )
        runCurrent()
        assertEquals(1, client.nodeSnapshot()[NodeId(peer)]?.hops_away)

        transport.injectPacket(
            packet(peer, PortNum.TEXT_MESSAGE_APP, "b".encodeToByteArray(), rxTime = 1_700_000_400) {
                hop_start = 0
                hop_limit = 3
            },
        )
        runCurrent()

        assertEquals(1, client.nodeSnapshot()[NodeId(peer)]?.hops_away)
        client.disconnect()
    }

    @Test
    fun `a zero-hop packet carrying the bitfield marks a direct neighbor`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        transport.injectPacket(
            packet(peer, PortNum.TEXT_MESSAGE_APP, "a".encodeToByteArray(), rxTime = 1_700_000_300) {
                hop_start = 3
                hop_limit = 1
            },
        )
        runCurrent()

        // Firmware 2.5+ stamps Data.bitfield on every packet it originates (Router.cpp perhapsEncode), so
        // getHopsAway trusts its hop_start of 0: a zero-hop packet, such as range test sends, came direct.
        transport.injectPacket(
            packet(peer, PortNum.TEXT_MESSAGE_APP, "b".encodeToByteArray(), rxTime = 1_700_000_400) {
                hop_start = 0
                hop_limit = 0
                decoded = decoded!!.newBuilder().also { d -> d.bitfield = 0 }.build()
            },
        )
        runCurrent()

        assertEquals(0, client.nodeSnapshot()[NodeId(peer)]?.hops_away)
        client.disconnect()
    }

    @Test
    fun `packet without rx_rssi does not record snr`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        transport.injectPacket(
            packet(peer, PortNum.TEXT_MESSAGE_APP, "a".encodeToByteArray(), rxTime = 1_700_000_300) {
                rx_snr = 6.5f
                transport_mechanism = MeshPacket.TransportMechanism.TRANSPORT_LORA
            },
        )
        runCurrent()

        assertEquals(0f, client.nodeSnapshot()[NodeId(peer)]?.snr ?: 0f)
        client.disconnect()
    }

    @Test
    fun `unknown hops stays null when hop_start is zero on a new node`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        transport.injectPacket(packet(peer, PortNum.TEXT_MESSAGE_APP, "a".encodeToByteArray(), rxTime = 1_700_000_300))
        runCurrent()

        assertNull(client.nodeSnapshot()[NodeId(peer)]?.hops_away)
        client.disconnect()
    }

    @Test
    fun `own node packets do not touch snr or hops_away`() = runTest {
        val own = 0x11111111
        val (transport, client) = connectedClient(own)
        client.connect()
        runCurrent()

        transport.injectPacket(
            packet(own, PortNum.TEXT_MESSAGE_APP, "a".encodeToByteArray(), rxTime = 1_700_000_300) {
                rx_snr = 9f
                rx_rssi = -50
                hop_start = 3
                hop_limit = 1
                transport_mechanism = MeshPacket.TransportMechanism.TRANSPORT_LORA
            },
        )
        runCurrent()

        val node = client.nodeSnapshot()[NodeId(own)]
        assertNull(node?.hops_away)
        assertEquals(0f, node?.snr ?: 0f)
        client.disconnect()
    }

    private fun user(longName: String, shortName: String) = User.Builder().also { wb ->
        wb.long_name = longName
        wb.short_name = shortName
    }.build()

    private fun nodeInfoPacket(from: Int, user: User, rxTime: Int) =
        packet(from, PortNum.NODEINFO_APP, User.ADAPTER.encode(user), rxTime)

    private fun packet(
        from: Int,
        port: PortNum,
        payload: ByteArray,
        rxTime: Int,
        extra: MeshPacket.Builder.() -> Unit = {},
    ): MeshPacket = MeshPacket.Builder().also { wb ->
        wb.from = from
        wb.to = 0
        wb.rx_time = rxTime
        wb.decoded = Data.Builder().also { d ->
            d.portnum = port
            d.payload = ByteString.of(*payload)
        }.build()
        wb.extra()
    }.build()

    private fun TestScope.collectChanges(client: RadioClient): MutableList<NodeChange> {
        val out = mutableListOf<NodeChange>()
        backgroundScope.launch { client.nodes.collect { out += it } }
        runCurrent()
        return out
    }

    private fun TestScope.connectedClient(nodeNum: Int = 0x11111111): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:live-nodedb"),
            autoHandshake = true,
            nodeNum = nodeNum,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        return transport to client
    }
}
