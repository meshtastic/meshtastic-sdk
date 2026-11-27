/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.ClientNotification
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.LogRecord
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MqttClientProxyMessage
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
    fun aPacketFromAnOfflineNodeComesBeforeItsCameOnline() = runTest {
        val (transport, client, events) = connected(presenceTimeout = 1.seconds)
        transport.injectPacket(textFrom(PEER, id = 901))
        runCurrent()
        advanceTimeBy(30.seconds)
        runCurrent()
        assertTrue(events.any { it is RadioSessionEvent.NodeChanged && it.change is NodeChange.WentOffline })

        transport.injectPacket(textFrom(PEER, id = 902))
        runCurrent()

        val packetIndex = events.indexOfFirst { it is RadioSessionEvent.Packet && it.packet.id == 902 }
        val onlineIndex = events.indexOfFirst {
            it is RadioSessionEvent.NodeChanged &&
                it.change is NodeChange.CameOnline
        }
        assertTrue(packetIndex >= 0 && onlineIndex > packetIndex, "packet then CameOnline: $events")
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
        // Sections the write did not touch stay in the bundle.
        assertEquals(Config.LoRaConfig.RegionCode.EU_868, changed.bundle.configs.firstNotNullOf { it.lora }.region)
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

    @Test
    fun anAckedMuteToggleFlipsTheNodesMutedFlag() = runTest {
        val (transport, client, events) = connected()
        transport.injectFrame(
            FromRadio.Builder().also { wb ->
                wb.node_info = NodeInfo.Builder().also { it.num = PEER }.build()
            }.build().toFrame(),
        )
        runCurrent()

        ackLastAdmin(transport) { client.admin.toggleMuted(NodeId(PEER)) }

        val muted = assertIs<NodeChange.Updated>(assertIs<RadioSessionEvent.NodeChanged>(events.last()).change)
        assertTrue(muted.node.is_muted)
        client.disconnect()
    }

    @Test
    fun anAckedFixedPositionMovesOurNodeAndSetsTheConfigFlag() = runTest {
        val (transport, client, events) = connected()
        val position = org.meshtastic.proto.Position.Builder().also { wb ->
            wb.latitude_i = 377_749_000
            wb.longitude_i = -1_224_194_000
        }.build()

        ackLastAdmin(transport) { client.admin.setFixedPosition(position) }

        val moved = events.filterIsInstance<RadioSessionEvent.NodeChanged>().map { it.change }
            .filterIsInstance<NodeChange.Updated>().last { it.node.num == LOCAL }
        assertEquals(377_749_000, moved.node.position?.latitude_i)
        val config = assertIs<RadioSessionEvent.ConfigChanged>(events.last())
        assertEquals(true, config.bundle.configs.firstNotNullOf { it.position }.fixed_position)
        client.disconnect()
    }

    @Test
    fun aBatchMirrorsItsWritesInTheOrderTheDeviceAppliedThem() = runTest {
        val (transport, client, _) = connected()

        // AdminModule: set_fixed_position sets config.position.fixed_position, and a later set_config
        // replaces config.position whole, so the device ends with the flag cleared.
        val edit = async {
            client.admin.editSettings {
                setFixedPosition(HERE)
                setConfig(positionConfig(fixed = false))
            }
        }
        ackAdminUntilSettled(transport)

        assertIs<AdminResult.Success<Unit>>(edit.await())
        assertEquals(false, client.configBundle.value?.configs?.firstNotNullOfOrNull { it.position }?.fixed_position)
        client.disconnect()
    }

    @Test
    fun aBatchPersistsTheConfigItsLastWriteLeaves() = runTest {
        val storage = InMemoryStorage()
        val (transport, client, _) = connected(storage = storage)

        // The device ends with fixed_position set: set_fixed_position comes after the set_config that cleared it.
        val edit = async {
            client.admin.editSettings {
                setConfig(positionConfig(fixed = false))
                setFixedPosition(HERE)
            }
        }
        ackAdminUntilSettled(transport)

        assertIs<AdminResult.Success<Unit>>(edit.await())
        assertEquals(true, storage.loadConfig()?.configs?.firstNotNullOfOrNull { it.position }?.fixed_position)
        client.disconnect()
    }

    @Test
    fun aConfigSaveThatFinishesLateDoesNotOverwriteANewerOne() = runTest {
        val inner = InMemoryStorage()
        val firstSave = CompletableDeferred<Unit>()
        var gateNextSave = false
        val storage = object : DeviceStorage by inner {
            override suspend fun saveConfig(config: ConfigBundle) {
                if (gateNextSave) {
                    gateNextSave = false
                    firstSave.await()
                }
                inner.saveConfig(config)
            }
        }
        val (transport, client, _) = connected(storage = storage)

        gateNextSave = true
        ackLastAdmin(transport) { client.admin.setConfig(positionConfig(fixed = true)) }
        ackLastAdmin(transport) { client.admin.setConfig(positionConfig(fixed = false)) }
        firstSave.complete(Unit)
        runCurrent()

        assertEquals(false, inner.loadConfig()?.configs?.firstNotNullOfOrNull { it.position }?.fixed_position)
        client.disconnect()
    }

    @Test
    fun storedChannelsFillAReadySnapshotWhoseHandshakeHasNone() = runTest {
        val stored = listOf(
            Channel.Builder().also { wb ->
                wb.role = Channel.Role.PRIMARY
                wb.settings = ChannelSettings.Builder().also { it.name = "stored" }.build()
            }.build(),
        )
        val storage = InMemoryStorage()
        storage.recordOwnNode(NodeId(LOCAL), "2.7.0", null)
        storage.saveChannels(stored)
        // A reconnect whose handshake carries no channels: the engine falls back to the stored ones.
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:restored-channels"),
            autoHandshake = true,
            nodeNum = LOCAL,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(PreloadedStorageProvider(storage))
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .sessionReceive()
            .build()
        val events = mutableListOf<RadioSessionEvent>()
        backgroundScope.launch { client.sessionEvents.collect { events += it } }
        client.connect()
        runCurrent()

        assertEquals(stored, client.channels.value)
        assertEquals(stored, assertIs<RadioSessionEvent.Ready>(events.first()).channels)
        client.disconnect()
    }

    /**
     * Firmware queues client notifications while no client is connected (`main.cpp` sends the
     * low-entropy key warning at boot) and hands them out first once Stage 1 completes
     * (`PhoneAPI::getFromRadio`, `STATE_SEND_PACKETS`: `clientNotification` before `packetForPhone`).
     */
    @Test
    fun handshakeEventsFollowTheReadySnapshotInReceiveOrder() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:handshake-events"), autoQueueStatus = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .sessionReceive()
            .build()
        val events = mutableListOf<RadioSessionEvent>()
        backgroundScope.launch { client.sessionEvents.collect { events += it } }
        val connectJob = backgroundScope.async { client.connect() }
        runCurrent()
        transport.nodeNum = LOCAL
        transport.inject { it.my_info = MyNodeInfo.Builder().also { m -> m.my_node_num = LOCAL }.build() }
        transport.inject { it.config_complete_id = NONCE_STAGE1 }
        runCurrent()
        val notification = ClientNotification.Builder().also { wb ->
            wb.level = LogRecord.Level.WARNING
            wb.message = "low entropy key"
        }.build()
        transport.inject { it.clientNotification = notification }
        transport.injectPacket(textFrom(PEER, id = 950))
        val proxied = MqttClientProxyMessage.Builder().also { wb -> wb.topic = "msh/test" }.build()
        transport.inject { it.mqttClientProxyMessage = proxied }
        repeat(2) {
            advanceTimeBy(INTER_STAGE_SETTLE_MS + 1)
            runCurrent()
        }
        transport.inject { it.node_info = NodeInfo.Builder().also { n -> n.num = LOCAL }.build() }
        transport.inject { it.config_complete_id = NONCE_STAGE2 }
        runCurrent()
        connectJob.await()

        assertIs<RadioSessionEvent.Ready>(events.first())
        val ordered = events.drop(1).mapNotNull { event ->
            when (event) {
                is RadioSessionEvent.Event -> (event.event as? MeshEvent.Notification)?.notification
                    ?: (event.event as? MeshEvent.MqttProxyMessage)?.message

                is RadioSessionEvent.Packet -> event.packet.id.takeIf { it == 950 }

                else -> null
            }
        }
        assertEquals(listOf(notification, 950, proxied), ordered)
        client.disconnect()
    }

    // A reconnect skips handleConnect, so channels read for the old device must not outlive its replacement.
    @Test
    fun anotherDeviceOnTheTransportNeverInheritsTheStoredChannels() = runTest {
        val stored = listOf(
            Channel.Builder().also { wb ->
                wb.role = Channel.Role.PRIMARY
                wb.settings = ChannelSettings.Builder().also { it.name = "stored" }.build()
            }.build(),
        )
        val storage = InMemoryStorage()
        storage.recordOwnNode(NodeId(LOCAL), "2.7.0", null)
        storage.saveChannels(stored)
        // The engine saves the bundle with the channels after each handshake; its my_info names the old device.
        storage.saveConfig(
            ConfigBundle(
                myInfo = MyNodeInfo.Builder().also { it.my_node_num = LOCAL }.build(),
                metadata = DeviceMetadata.Builder().build(),
                configs = emptyList(),
                moduleConfigs = emptyList(),
            ),
        )
        // A different radio on the same transport, whose handshakes report no channels.
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:replaced-device"),
            autoHandshake = true,
            nodeNum = OTHER_DEVICE,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(PreloadedStorageProvider(storage))
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .autoReconnect(AutoReconnectConfig(enabled = true, initialBackoff = 1.seconds, jitter = 0.0))
            .sessionReceive()
            .build()
        val events = mutableListOf<RadioSessionEvent>()
        backgroundScope.launch { client.sessionEvents.collect { events += it } }
        client.connect()
        runCurrent()
        assertEquals(null, client.channels.value)

        transport.simulateError(IllegalStateException("link lost"))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        val readies = events.filterIsInstance<RadioSessionEvent.Ready>()
        assertEquals(2, readies.size, "the reconnect reached Ready: $events")
        assertEquals(null, readies.last().channels)
        assertEquals(null, client.channels.value)
        client.disconnect()
    }

    private class PreloadedStorageProvider(private val storage: DeviceStorage) : StorageProvider {
        override suspend fun activate(identity: TransportIdentity): DeviceStorage = storage
    }

    // Acks every acked admin write, the batch's begin and commit among them, until none is left.
    private fun TestScope.ackAdminUntilSettled(transport: FakeRadioTransport) {
        val acked = mutableSetOf<Int>()
        while (true) {
            runCurrent()
            val pending = transport.outboundPackets()
                .filter { it.decoded?.portnum == PortNum.ADMIN_APP && it.want_ack && it.id !in acked }
            if (pending.isEmpty()) return
            for (packet in pending) {
                acked += packet.id
                transport.injectRoutingAck(packet.id)
            }
        }
    }

    private fun positionConfig(fixed: Boolean) = Config.Builder().also { wb ->
        wb.position = Config.PositionConfig.Builder().also { it.fixed_position = fixed }.build()
    }.build()

    private data class Connected(
        val transport: FakeRadioTransport,
        val client: RadioClient,
        val events: MutableList<RadioSessionEvent>,
    )

    private suspend fun TestScope.connected(
        presenceTimeout: kotlin.time.Duration = kotlin.time.Duration.INFINITE,
        storage: DeviceStorage? = null,
    ): Connected {
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
                FromRadio.Builder().also { wb ->
                    wb.config = Config.Builder().also { wb ->
                        wb.lora =
                            Config.LoRaConfig.Builder().also { it.region = Config.LoRaConfig.RegionCode.EU_868 }.build()
                    }.build()
                }.build().toFrame(),
            ),
            autoHandshake = true,
            autoQueueStatus = true,
            nodeNum = LOCAL,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(storage?.let(::PreloadedStorageProvider) ?: InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .sessionReceive()
            .clock(object : kotlin.time.Clock {
                override fun now() = kotlin.time.Instant.fromEpochMilliseconds(currentTime)
            })
            .apply { if (presenceTimeout.isFinite()) presenceTimeout(presenceTimeout) }
            .build()
        val events = mutableListOf<RadioSessionEvent>()
        backgroundScope.launch { client.sessionEvents.collect { events += it } }
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

    private fun FakeRadioTransport.inject(block: (FromRadio.Builder) -> Unit) {
        injectFrame(FromRadio.Builder().also(block).build().toFrame())
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
        const val OTHER_DEVICE = 0x3333
        const val NONCE_STAGE1 = 69420
        const val NONCE_STAGE2 = 69421
        const val INTER_STAGE_SETTLE_MS = 100L
        val HERE: org.meshtastic.proto.Position = org.meshtastic.proto.Position.Builder().also { wb ->
            wb.latitude_i = 377_749_000
            wb.longitude_i = -1_224_194_000
        }.build()
    }
}
