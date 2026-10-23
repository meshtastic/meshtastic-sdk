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
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Live NODEINFO_APP / POSITION_APP / any-packet traffic keeps the node DB current after the handshake. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveNodeUpdateTest {

    private val peer = 0x22222222

    private fun packet(port: PortNum, payload: ByteArray, configure: MeshPacket.Builder.() -> Unit = {}) =
        MeshPacket.Builder().also { wb ->
            wb.from = peer
            wb.to = -1
            wb.id = (1..Int.MAX_VALUE).random()
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = port
                wb.payload = payload.toByteString()
            }.build()
            wb.configure()
        }.build()

    @Test
    fun `a peer heard after connect is added and then follows its position and last heard`() = runTest {
        val (transport, client) = connectedClient()
        val changes = mutableListOf<NodeChange>()
        backgroundScope.launch { client.nodes.collect { changes += it } }
        client.connect()
        runCurrent()

        val user = User.Builder().also { wb ->
            wb.long_name = "Bravo"
            wb.short_name = "BR"
        }.build()
        transport.injectPacket(packet(PortNum.NODEINFO_APP, User.ADAPTER.encode(user)) { rx_time = 1_800_000_000 })
        runCurrent()

        val position = Position.Builder().also { wb ->
            wb.latitude_i = 417_000_000
            wb.longitude_i = 447_000_000
        }.build()
        transport.injectPacket(
            packet(PortNum.POSITION_APP, Position.ADAPTER.encode(position)) {
                rx_time = 1_800_000_060
                rx_snr = 6.5f
                rx_rssi = -100
                hop_start = 3
                hop_limit = 3
            },
        )
        runCurrent()

        val added = changes.filterIsInstance<NodeChange.Added>().single { it.node.num == peer }
        assertEquals("Bravo", added.node.user?.long_name)
        assertEquals("!22222222", added.node.user?.id)

        val moved = changes.filterIsInstance<NodeChange.Updated>().last { it.node.num == peer }
        assertTrue(NodeField.Position in moved.changed, "changed=${moved.changed}")
        assertTrue(NodeField.LastSeen in moved.changed, "changed=${moved.changed}")
        assertTrue(NodeField.SignalQuality in moved.changed, "changed=${moved.changed}")

        val node = client.nodeSnapshot().getValue(NodeId(peer))
        assertEquals(417_000_000, node.position?.latitude_i)
        assertEquals(1_800_000_060, node.last_heard)
        assertEquals(6.5f, node.snr)
        assertEquals(0, node.hops_away)
        client.disconnect()
    }

    @Test
    fun `packets from unknown senders add no nodes`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        transport.injectPacket(packet(PortNum.TEXT_MESSAGE_APP, "hi".encodeToByteArray()))
        runCurrent()

        assertTrue(NodeId(peer) !in client.nodeSnapshot())
        client.disconnect()
    }

    private fun TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:live-node-update"),
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
