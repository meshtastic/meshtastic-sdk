/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.Paxcount
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.StatusMessage
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `NodeInfo` has no field for a node status string or a paxcounter reading (firmware keeps
 * the status in a separate per-node map and the paxcount nowhere), so these packets only
 * refresh the sender's presence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveStatusPaxcounterTest {

    private val peer = 0x22222222

    private val status = StatusMessage.Builder().also { wb -> wb.status = "On the trail" }.build()
    private val pax = Paxcount.Builder().also { wb ->
        wb.wifi = 3
        wb.ble = 4
        wb.uptime = 99
    }.build()

    @Test
    fun nodeStatusPacketKeepsUserAndPositionAndRefreshesLastHeard() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        transport.injectPacket(
            packet(peer, PortNum.NODEINFO_APP, User.ADAPTER.encode(user()), rxTime = 1_700_000_100),
        )
        val position = Position.Builder().also { wb ->
            wb.latitude_i = 374_220_000
            wb.longitude_i = -1_220_840_000
        }.build()
        transport.injectPacket(
            packet(peer, PortNum.POSITION_APP, Position.ADAPTER.encode(position), rxTime = 1_700_000_110),
        )
        runCurrent()
        val before = assertNotNull(client.nodeSnapshot()[NodeId(peer)])

        transport.injectPacket(
            packet(peer, PortNum.NODE_STATUS_APP, StatusMessage.ADAPTER.encode(status), rxTime = 1_700_000_200),
        )
        runCurrent()

        val after = assertNotNull(client.nodeSnapshot()[NodeId(peer)])
        assertEquals(before.user, after.user)
        assertEquals(before.position, after.position)
        assertEquals(1_700_000_200, after.last_heard)
        client.disconnect()
    }

    @Test
    fun paxcounterPacketKeepsUserAndRefreshesLastHeard() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        transport.injectPacket(
            packet(peer, PortNum.NODEINFO_APP, User.ADAPTER.encode(user()), rxTime = 1_700_000_100),
        )
        runCurrent()

        transport.injectPacket(
            packet(peer, PortNum.PAXCOUNTER_APP, Paxcount.ADAPTER.encode(pax), rxTime = 1_700_000_300),
        )
        runCurrent()

        val after = assertNotNull(client.nodeSnapshot()[NodeId(peer)])
        assertEquals("Peer", after.user?.long_name)
        assertEquals(1_700_000_300, after.last_heard)
        client.disconnect()
    }

    @Test
    fun unknownSenderStatusPacketCreatesAnEntryWithoutUserOrPosition() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        transport.injectPacket(
            packet(peer, PortNum.NODE_STATUS_APP, StatusMessage.ADAPTER.encode(status), rxTime = 1_700_000_200),
        )
        runCurrent()

        val node = assertNotNull(client.nodeSnapshot()[NodeId(peer)])
        assertNull(node.user)
        assertNull(node.position)
        assertEquals(1_700_000_200, node.last_heard)
        client.disconnect()
    }

    @Test
    fun malformedStatusAndPaxcounterPayloadsAreIgnored() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        transport.injectPacket(
            packet(peer, PortNum.NODEINFO_APP, User.ADAPTER.encode(user()), rxTime = 1_700_000_100),
        )
        runCurrent()

        val garbage = byteArrayOf(0x0A, 0x7F, 0x01)
        transport.injectPacket(packet(peer, PortNum.NODE_STATUS_APP, garbage, rxTime = 1_700_000_200))
        transport.injectPacket(packet(peer, PortNum.PAXCOUNTER_APP, garbage, rxTime = 1_700_000_210))
        runCurrent()

        val node = assertNotNull(client.nodeSnapshot()[NodeId(peer)])
        assertEquals("Peer", node.user?.long_name)
        client.disconnect()
    }

    private fun user() = User.Builder().also { wb ->
        wb.long_name = "Peer"
        wb.short_name = "PR"
    }.build()

    private fun packet(from: Int, port: PortNum, payload: ByteArray, rxTime: Int): MeshPacket =
        MeshPacket.Builder().also { wb ->
            wb.from = from
            wb.to = 0
            wb.rx_time = rxTime
            wb.decoded = Data.Builder().also { d ->
                d.portnum = port
                d.payload = ByteString.of(*payload)
            }.build()
        }.build()

    private fun TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:live-status"),
            autoHandshake = true,
            nodeNum = 0x11111111,
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
