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
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HeardChannelHelpersTest {

    private val own = 0x11111111
    private val peer = 0x22222222

    @Test
    fun channelForKnownNodeIsTheChannelItWasHeardOn() = runTest {
        val (transport, client) = connectedClient()
        try {
            hearPeerOnChannel(transport, 2)
            assertEquals(ChannelIndex(2), client.channelFor(NodeId(peer)))
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun channelForUnknownNodeIsZero() = runTest {
        val (_, client) = connectedClient()
        try {
            assertEquals(ChannelIndex(0), client.channelFor(NodeId(0x44444444)))
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun channelForOwnNodeIsZero() = runTest {
        val (_, client) = connectedClient()
        try {
            assertEquals(ChannelIndex(0), client.channelFor(NodeId(own)))
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun directMessageOnHeardChannelUsesTheNodeChannel() = runTest {
        val (transport, client) = connectedClient()
        try {
            hearPeerOnChannel(transport, 3)
            client.sendDirectMessageOnHeardChannel(NodeId(peer), "hi")
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(3, sent.channel)
            assertEquals(peer, sent.to)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun explicitChannelArgumentIsUnchanged() = runTest {
        val (transport, client) = connectedClient()
        try {
            hearPeerOnChannel(transport, 3)
            client.sendDirectMessage(NodeId(peer), "hi", channel = ChannelIndex(1))
            runCurrent()
            assertEquals(1, transport.outboundPackets().last().channel)
            client.sendDirectMessage(NodeId(peer), "hi")
            runCurrent()
            assertEquals(0, transport.outboundPackets().last().channel)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun requestPositionIsEmptyWantResponseAtBackgroundPriority() = runTest {
        val (transport, client) = connectedClient()
        try {
            client.requestPosition(NodeId(peer), ChannelIndex(2))
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(peer, sent.to)
            assertEquals(2, sent.channel)
            assertEquals(MeshPacket.Priority.BACKGROUND, sent.priority)
            assertEquals(PortNum.POSITION_APP, sent.decoded?.portnum)
            assertEquals(true, sent.decoded?.want_response)
            assertEquals(0, sent.decoded?.payload?.size)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun requestPositionOnHeardChannelUsesTheNodeChannel() = runTest {
        val (transport, client) = connectedClient()
        try {
            hearPeerOnChannel(transport, 2)
            client.requestPositionOnHeardChannel(NodeId(peer))
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(2, sent.channel)
            assertEquals(MeshPacket.Priority.BACKGROUND, sent.priority)
            assertTrue(sent.decoded?.want_response == true)
        } finally {
            client.disconnect()
        }
    }

    private fun TestScope.hearPeerOnChannel(transport: FakeRadioTransport, channel: Int) {
        val user = User.Builder().also { wb ->
            wb.long_name = "Peer"
            wb.short_name = "PR"
        }.build()
        transport.injectPacket(
            MeshPacket.Builder().also { wb ->
                wb.from = peer
                wb.to = 0
                wb.channel = channel
                wb.rx_time = 1_700_000_100
                wb.decoded = Data.Builder().also { d ->
                    d.portnum = PortNum.NODEINFO_APP
                    d.payload = ByteString.of(*User.ADAPTER.encode(user))
                }.build()
            }.build(),
        )
        runCurrent()
    }

    private suspend fun TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:heard-channel"),
            autoHandshake = true,
            nodeNum = own,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        client.connect()
        runCurrent()
        return transport to client
    }
}
