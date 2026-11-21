/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.ExcludedModules
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.LoRaPresetGroup
import org.meshtastic.proto.LoRaRegionPresetMap
import org.meshtastic.proto.LoRaRegionPresets
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Device facts the handshake delivers: the region preset map and the module exclusion bitfield. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceFactsHandshakeTest {

    @Test
    fun regionPresetsAreCapturedFromStage1() = runTest {
        val transport = FactsDevice(TransportIdentity("fake:presets"), presets = PRESETS)
        val client = buildClient(transport)

        client.connect()

        assertEquals(PRESETS, client.configBundle.value?.regionPresets)
    }

    @Test
    fun regionPresetsAreNullWhenFirmwareNeverSendsThem() = runTest {
        val transport = FactsDevice(TransportIdentity("fake:no-presets"), presets = null)
        val client = buildClient(transport)

        client.connect()

        val bundle = assertNotNull(client.configBundle.value)
        assertNull(bundle.regionPresets)
    }

    @Test
    fun regionPresetsArriveOnAConfigOnlySession() = runTest {
        val transport = FactsDevice(TransportIdentity("fake:presets-config-only"), presets = PRESETS)
        val client = buildClient(transport, skipNodeDb = true)

        client.connect()

        assertEquals(listOf(NONCE_STAGE1), transport.outboundToRadio().mapNotNull { it.want_config_id })
        assertEquals(PRESETS, client.configBundle.value?.regionPresets)
    }

    @Test
    fun regionPresetsAreRedeliveredOnReconnect() = runTest {
        val transport = FactsDevice(TransportIdentity("fake:presets-reconnect"), presets = PRESETS)
        val client = buildClient(transport, skipNodeDb = true, reconnect = true)

        client.connect()
        runCurrent()
        transport.drop()
        runCurrent()
        assertNull(client.configBundle.value)
        advanceTimeBy(1_000L)
        repeat(20) { runCurrent() }
        repeat(10) {
            if (client.connection.value == ConnectionState.Connected) return@repeat
            advanceTimeBy(100L)
            repeat(20) { runCurrent() }
        }

        assertEquals(ConnectionState.Connected, client.connection.value)
        assertEquals(PRESETS, client.configBundle.value?.regionPresets)
    }

    @Test
    fun bundleCapabilitiesReadTheExcludedModuleBitfield() = runTest {
        val excluded = ExcludedModules.MQTT_CONFIG.value or ExcludedModules.AUDIO_CONFIG.value
        val transport = FactsDevice(TransportIdentity("fake:excluded"), excludedModules = excluded)
        val client = buildClient(transport)

        client.connect()

        val capabilities = assertNotNull(client.configBundle.value).capabilities
        assertFalse(capabilities.hasModule(ExcludedModules.MQTT_CONFIG))
        assertFalse(capabilities.hasModule(ExcludedModules.AUDIO_CONFIG))
        assertTrue(capabilities.hasModule(ExcludedModules.TELEMETRY_CONFIG))
    }

    @Test
    fun excludedModulesQueryTreatsAnAbsentBitfieldAsNothingExcluded() {
        val capabilities = DeviceCapabilities(firmwareVersion = "2.7.0")

        assertTrue(capabilities.hasModule(ExcludedModules.MQTT_CONFIG))
        assertTrue(capabilities.hasModule(ExcludedModules.MESHBEACON_CONFIG))
    }

    @Test
    fun excludedModulesQueryChecksEachBitIndependently() {
        val capabilities = DeviceCapabilities(
            firmwareVersion = "2.8.0",
            excludedModules = ExcludedModules.TAK_CONFIG.value or ExcludedModules.MESHBEACON_CONFIG.value,
        )

        assertFalse(capabilities.hasModule(ExcludedModules.TAK_CONFIG))
        assertFalse(capabilities.hasModule(ExcludedModules.MESHBEACON_CONFIG))
        assertTrue(capabilities.hasModule(ExcludedModules.TRAFFICMANAGEMENT_CONFIG))
        assertTrue(capabilities.hasModule(ExcludedModules.EXCLUDED_NONE))
    }

    private fun TestScope.buildClient(
        transport: RadioTransport,
        skipNodeDb: Boolean = false,
        reconnect: Boolean = false,
    ): RadioClient = RadioClient.Builder()
        .transport(transport)
        .autoReconnect(
            if (reconnect) {
                AutoReconnectConfig(
                    enabled = true,
                    initialBackoff = 1.seconds,
                    maxBackoff = 8.seconds,
                    jitter = 0.0,
                )
            } else {
                AutoReconnectConfig.Disabled
            },
        )
        .storage(InMemoryStorageProvider())
        .clock(SchedulerClock { currentTime })
        .coroutineContext(backgroundScope.coroutineContext)
        .autoSyncTimeOnConnect(false)
        .rpcTimeout(60.seconds)
        .skipNodeDb(skipNodeDb)
        .build()

    private class SchedulerClock(private val nowMs: () -> Long) : kotlin.time.Clock {
        override fun now(): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(nowMs())
    }

    private class FactsDevice(
        override val identity: TransportIdentity,
        private val presets: LoRaRegionPresetMap? = null,
        private val excludedModules: Int = 0,
    ) : RadioTransport {

        private val stateFlow = MutableStateFlow<TransportState>(TransportState.Disconnected)
        private val inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 128)
        private val sent = mutableListOf<Frame>()

        override val state: StateFlow<TransportState> = stateFlow

        override suspend fun connect() {
            stateFlow.value = TransportState.Connecting
            stateFlow.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            stateFlow.value = TransportState.Disconnected
        }

        fun drop() {
            stateFlow.value = TransportState.Error(MeshtasticException.Transport("drop"), true)
        }

        override suspend fun send(frame: Frame) {
            sent += frame
            val toRadio = decodeToRadio(frame) ?: return
            when (toRadio.want_config_id) {
                NONCE_STAGE1 -> streamStage1()
                NONCE_STAGE2 -> emit(FromRadio.Builder().also { wb -> wb.config_complete_id = NONCE_STAGE2 }.build())
            }
            toRadio.packet?.let(::answerGetOwner)
        }

        override fun frames(): Flow<Frame> = inbound

        fun outboundToRadio(): List<ToRadio> = sent.mapNotNull(::decodeToRadio)

        // Firmware order: my_info, own node, metadata, region_presets, channels, configs, complete.
        private fun streamStage1() {
            emit(
                FromRadio.Builder().also { wb ->
                    wb.my_info = MyNodeInfo.Builder().also { wb -> wb.my_node_num = DEVICE_NODE_NUM }.build()
                }.build(),
            )
            emit(
                FromRadio.Builder().also { wb ->
                    wb.node_info = NodeInfo.Builder().also { wb -> wb.num = DEVICE_NODE_NUM }.build()
                }.build(),
            )
            emit(
                FromRadio.Builder().also { wb ->
                    wb.metadata = DeviceMetadata.Builder().also { wb ->
                        wb.firmware_version = "2.8.2"
                        wb.excluded_modules = excludedModules
                    }.build()
                }.build(),
            )
            presets?.let { map -> emit(FromRadio.Builder().also { wb -> wb.region_presets = map }.build()) }
            emit(
                FromRadio.Builder().also { wb ->
                    wb.channel = Channel.Builder().also { wb ->
                        wb.index = 0
                        wb.role = Channel.Role.PRIMARY
                    }.build()
                }.build(),
            )
            emit(
                FromRadio.Builder().also { wb ->
                    wb.config = Config.Builder().also { wb ->
                        wb.lora = Config.LoRaConfig.Builder().also { wb -> wb.use_preset = true }.build()
                    }.build()
                }.build(),
            )
            emit(FromRadio.Builder().also { wb -> wb.config_complete_id = NONCE_STAGE1 }.build())
        }

        private fun answerGetOwner(packet: MeshPacket) {
            val payload = packet.decoded?.payload ?: return
            val admin = runCatching { AdminMessage.ADAPTER.decode(payload) }.getOrNull() ?: return
            if (admin.get_owner_request != true) return
            val response = AdminMessage.Builder().also { wb ->
                wb.get_owner_response = User.Builder().also { wb ->
                    wb.id = "!0000002a"
                    wb.long_name = "FactsNode"
                    wb.short_name = "FN"
                    wb.hw_model = HardwareModel.UNSET
                }.build()
                wb.session_passkey = byteArrayOf(1, 2, 3, 4).toByteString()
            }.build()
            emit(
                FromRadio.Builder().also { wb ->
                    wb.packet = MeshPacket.Builder().also { wb ->
                        wb.from = DEVICE_NODE_NUM
                        wb.to = 0
                        wb.decoded = Data.Builder().also { wb ->
                            wb.portnum = PortNum.ADMIN_APP
                            wb.payload = AdminMessage.ADAPTER.encode(response).toByteString()
                            wb.request_id = packet.id
                        }.build()
                    }.build()
                }.build(),
            )
        }

        private fun emit(fromRadio: FromRadio) {
            inbound.tryEmit(fromRadio.toFrame())
        }

        private fun decodeToRadio(frame: Frame): ToRadio? {
            val bytes = frame.bytes.toByteArray()
            if (bytes.size < 4) return null
            return runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull()
        }
    }

    private companion object {
        const val NONCE_STAGE1 = 69420
        const val NONCE_STAGE2 = 69421
        const val DEVICE_NODE_NUM = 42

        val PRESETS: LoRaRegionPresetMap = LoRaRegionPresetMap.Builder().also { wb ->
            wb.groups = listOf(
                LoRaPresetGroup.Builder().also { g ->
                    g.presets = listOf(
                        Config.LoRaConfig.ModemPreset.LONG_FAST,
                        Config.LoRaConfig.ModemPreset.MEDIUM_FAST,
                    )
                    g.default_preset = Config.LoRaConfig.ModemPreset.LONG_FAST
                }.build(),
            )
            wb.region_groups = listOf(
                LoRaRegionPresets.Builder().also { r ->
                    r.region = Config.LoRaConfig.RegionCode.US
                    r.group_index = 0
                }.build(),
            )
        }.build()
    }
}
