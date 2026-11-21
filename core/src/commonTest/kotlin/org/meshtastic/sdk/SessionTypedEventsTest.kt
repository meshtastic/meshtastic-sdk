/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The typed events a session consumer projects from, and their order relative to packets. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTypedEventsTest {

    @Test
    fun aLivePacketComesBeforeTheNodeChangeItCauses() = runTest {
        val (transport, client, events) = connected()

        transport.injectPacket(textFrom(PEER, id = 900))
        runCurrent()

        val packetIndex = events.indexOfFirst { it is RadioSessionEvent.Packet && it.packet.id == 900 }
        val nodeIndex = events.indexOfFirst {
            it is RadioSessionEvent.NodeChanged && (it.change as? NodeChange.Added)?.node?.num == PEER
        }
        assertTrue(packetIndex >= 0 && nodeIndex > packetIndex, "packet then node change: $events")
        client.disconnect()
    }

    @Test
    fun anAckedConfigWriteEmitsConfigChangedWithTheWholeBundle() = runTest {
        val (transport, client, events) = connected()
        val router = Config.Builder().also { wb ->
            wb.device = Config.DeviceConfig.Builder().also { it.role = Config.DeviceConfig.Role.ROUTER }.build()
        }.build()

        ackLastAdmin(transport) { client.admin.setConfig(router) }

        val changed = assertIs<RadioSessionEvent.ConfigChanged>(events.last())
        assertEquals(Config.DeviceConfig.Role.ROUTER, changed.bundle.configs.firstNotNullOf { it.device }.role)
        assertEquals("2.7.0", changed.bundle.metadata.firmware_version)
        client.disconnect()
    }

    @Test
    fun anAckedChannelWriteEmitsChannelsChanged() = runTest {
        val (transport, client, events) = connected()
        val secondary = Channel.Builder().also { wb ->
            wb.index = 1
            wb.role = Channel.Role.SECONDARY
            wb.settings = ChannelSettings.Builder().also { it.name = "ops" }.build()
        }.build()

        ackLastAdmin(transport) { client.admin.setChannel(secondary) }

        val changed = assertIs<RadioSessionEvent.ChannelsChanged>(events.last())
        assertEquals("ops", changed.channels[1].settings?.name)
        client.disconnect()
    }

    @Test
    fun ackedNodeWritesEmitNodeChanged() = runTest {
        val (transport, client, events) = connected()
        transport.injectFrame(
            FromRadio.Builder().also { wb ->
                wb.node_info = NodeInfo.Builder().also { it.num = PEER }.build()
            }.build().toFrame(),
        )
        runCurrent()

        ackLastAdmin(transport) { client.admin.setFavorite(NodeId(PEER), favorite = true) }
        val favorite = assertIs<NodeChange.Updated>(assertIs<RadioSessionEvent.NodeChanged>(events.last()).change)
        assertTrue(favorite.node.is_favorite)

        ackLastAdmin(transport) { client.admin.setOwner(User.Builder().also { it.long_name = "Base" }.build()) }
        val owner = assertIs<NodeChange.Updated>(assertIs<RadioSessionEvent.NodeChanged>(events.last()).change)
        assertEquals("Base", owner.node.user?.long_name)
        assertEquals(LOCAL, owner.node.num)

        ackLastAdmin(transport) { client.admin.removeNode(NodeId(PEER)) }
        assertEquals(NodeChange.Removed(NodeId(PEER)), assertIs<RadioSessionEvent.NodeChanged>(events.last()).change)
        assertTrue(NodeId(PEER) !in client.nodeSnapshot())
        client.disconnect()
    }

    private data class Connected(
        val transport: FakeRadioTransport,
        val client: RadioClient,
        val events: MutableList<RadioSessionEvent>,
    )

    private suspend fun TestScope.connected(): Connected {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:typed-events"),
            frames = listOf(
                FromRadio.Builder().also { wb ->
                    wb.channel = Channel.Builder().also { it.role = Channel.Role.PRIMARY }.build()
                }.build().toFrame(),
                FromRadio.Builder().also { wb ->
                    wb.metadata = DeviceMetadata.Builder().also { it.firmware_version = "2.7.0" }.build()
                }.build().toFrame(),
                FromRadio.Builder().also { wb ->
                    wb.node_info = NodeInfo.Builder().also { wb ->
                        wb.num = LOCAL
                        wb.user = User.Builder().also { it.long_name = "Old" }.build()
                    }.build()
                }.build().toFrame(),
                FromRadio.Builder().also { wb ->
                    wb.config = Config.Builder().also { wb ->
                        wb.device =
                            Config.DeviceConfig.Builder().also { it.role = Config.DeviceConfig.Role.CLIENT }.build()
                    }.build()
                }.build().toFrame(),
            ),
            autoHandshake = true,
            autoQueueStatus = true,
            nodeNum = LOCAL,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .sessionReceive()
            .build()
        val events = mutableListOf<RadioSessionEvent>()
        backgroundScope.launch { client.sessionEvents?.collect { events += it } }
        client.connect()
        runCurrent()
        assertIs<RadioSessionEvent.Ready>(events.first())
        return Connected(transport, client, events)
    }

    private suspend fun TestScope.ackLastAdmin(transport: FakeRadioTransport, call: suspend () -> AdminResult<Unit>) {
        val before = transport.outboundPackets().size
        val result = async { call() }
        runCurrent()
        val write = transport.outboundPackets().drop(before).last { it.decoded?.portnum == PortNum.ADMIN_APP }
        transport.injectRoutingAck(write.id)
        runCurrent()
        assertIs<AdminResult.Success<Unit>>(result.await())
        runCurrent()
    }

    private fun textFrom(node: Int, id: Int): MeshPacket = MeshPacket.Builder().also { wb ->
        wb.id = id
        wb.from = node
        wb.to = LOCAL
        wb.rx_time = 1_700_000_000
        wb.decoded = Data.Builder().also { data ->
            data.portnum = PortNum.TEXT_MESSAGE_APP
            data.payload = "hi".encodeUtf8()
        }.build()
    }.build()

    private companion object {
        const val LOCAL = 1
        const val PEER = 0x2222
    }
}
