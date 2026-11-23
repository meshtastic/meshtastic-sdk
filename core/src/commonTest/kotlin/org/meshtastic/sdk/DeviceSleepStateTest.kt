/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.meshtastic.sdk.internal.sleepExpectation
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSleepStateTest {

    private val reconnect = AutoReconnectConfig(
        enabled = true,
        initialBackoff = 1.seconds,
        maxBackoff = 60.seconds,
        jitter = 0.0,
    )

    @Test
    fun powerSavingBleDropFromConnectedEntersDeviceSleeping() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:AA"), powerSaving = true, lsSecs = 300)
        val client = buildClient(transport, reconnect)
        client.connect()
        runCurrent()

        val droppedAt = currentTime
        transport.drop()
        runCurrent()

        val sleeping = assertIs<ConnectionState.DeviceSleeping>(client.connection.value)
        assertEquals(300, sleeping.expectedWakeSeconds)
        assertEquals(droppedAt, sleeping.since.toEpochMilliseconds())
    }

    @Test
    fun autoReconnectKeepsTryingWhileSleepingAndReturnsToConnected() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:AB"), powerSaving = true, lsSecs = 300)
        val client = buildClient(transport, reconnect)
        client.connect()
        runCurrent()

        transport.failReconnects = 3
        val droppedAt = currentTime
        transport.drop()
        runCurrent()
        advanceTimeBy(5_000L)
        drain()

        val sleeping = assertIs<ConnectionState.DeviceSleeping>(client.connection.value)
        assertTrue(transport.connectAttempts >= 3, "attempts continue while sleeping")
        assertEquals(droppedAt, sleeping.since.toEpochMilliseconds(), "failed attempts keep the drop time")

        advanceTimeBy(20_000L)
        drain()
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun writeFailureOutOfReadyWithASleepingConfigEntersDeviceSleeping() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:A1"), powerSaving = true, lsSecs = 60)
        val client = buildClient(transport, reconnect)
        client.connect()
        runCurrent()

        // Desktop BLE notices a sleeping radio as a failed write, never as a transport error.
        transport.failWrites = true
        client.send(textPacket())
        runCurrent()

        assertEquals(60, assertIs<ConnectionState.DeviceSleeping>(client.connection.value).expectedWakeSeconds)
    }

    @Test
    fun livenessTimeoutWithASleepingConfigReconnectsAsDeviceSleeping() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:A2"), powerSaving = true, lsSecs = 60)
        val client = buildClient(transport, reconnect)
        val states = recordStates(client)
        client.connect()
        advanceTimeBy(31_000L)
        drain()

        // The link stays up through light sleep while the radio answers nothing.
        transport.silent = true
        advanceTimeBy(125_000L)
        drain()

        assertTrue(states.any { it is ConnectionState.DeviceSleeping })
        assertTrue(transport.connectAttempts >= 2, "the engine keeps trying to reach the device")
    }

    @Test
    fun livenessTimeoutWithASleepingConfigAndAutoReconnectOffSettlesOnDeviceSleeping() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:A3"), powerSaving = true, lsSecs = 60)
        val client = buildClient(transport, AutoReconnectConfig.Disabled)
        client.connect()
        advanceTimeBy(31_000L)
        drain()

        transport.silent = true
        advanceTimeBy(125_000L)
        drain()

        assertIs<ConnectionState.DeviceSleeping>(client.connection.value)
    }

    @Test
    fun nonPowerSavingDropIsReconnecting() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:AC"), powerSaving = false, lsSecs = 300)
        val client = buildClient(transport, reconnect)
        client.connect()
        runCurrent()

        transport.drop()
        runCurrent()

        assertIs<ConnectionState.Reconnecting>(client.connection.value)
    }

    @Test
    fun dropDuringTheHandshakeAfterWakingIsReconnecting() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:AD"), powerSaving = true, lsSecs = 300)
        val client = buildClient(transport, reconnect)
        val states = recordStates(client)
        client.connect()
        runCurrent()

        transport.drop()
        runCurrent()
        assertIs<ConnectionState.DeviceSleeping>(client.connection.value)

        // The device is back and the link drops again mid-handshake: that is not a sleep.
        transport.dropOnStage2 = true
        advanceTimeBy(5_000L)
        drain()

        val afterWake = states.drop(states.indexOfFirst { it is ConnectionState.DeviceSleeping } + 1)
        assertTrue(afterWake.any { it is ConnectionState.Configuring }, "the device woke and handshook")
        assertTrue(afterWake.any { it is ConnectionState.Reconnecting })
        assertTrue(afterWake.none { it is ConnectionState.DeviceSleeping })
    }

    @Test
    fun disconnectDuringTheTeardownOfASleepEndsDisconnected() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:AE"), powerSaving = true, lsSecs = 300)
        val client = buildClient(transport, AutoReconnectConfig.Disabled)
        client.connect()
        runCurrent()

        val gate = CompletableDeferred<Unit>()
        transport.disconnectGate = gate
        transport.drop()
        runCurrent()
        // The teardown is parked in transport.disconnect(), so disconnect() takes the supervisor first.
        val disconnecting = backgroundScope.launch { client.disconnect() }
        runCurrent()
        gate.complete(Unit)
        drain()

        assertTrue(disconnecting.isCompleted, "disconnect() returned")
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    @Test
    fun dropRightAfterARebootAnnouncementNeverEntersDeviceSleeping() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:AF"), powerSaving = true, lsSecs = 300)
        val client = buildClient(transport, reconnect)
        val states = recordStates(client)
        client.connect()
        runCurrent()

        // Both reach the actor before the reboot's teardown does.
        transport.inject(FromRadio.Builder().also { wb -> wb.rebooted = true }.build())
        transport.drop()
        drain()
        advanceTimeBy(5_000L)
        drain()

        assertTrue(states.none { it is ConnectionState.DeviceSleeping })
    }

    @Test
    fun autoReconnectOffSettlesOnDeviceSleepingUntilDisconnect() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:B0"), powerSaving = true, lsSecs = 300)
        val client = buildClient(transport, AutoReconnectConfig.Disabled)
        client.connect()
        runCurrent()

        transport.drop()
        drain()

        assertIs<ConnectionState.DeviceSleeping>(client.connection.value)

        client.disconnect()
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    @Test
    fun sleepingAttemptsDoNotCountAgainstMaxAttemptsUntilWakeBoundPasses() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:B3"), powerSaving = true, lsSecs = 600)
        val client = buildClient(transport, reconnect.copy(maxAttempts = 2))
        client.connect()
        runCurrent()

        transport.failReconnects = Int.MAX_VALUE
        transport.drop()
        runCurrent()

        advanceTimeBy(600_000L)
        drain()
        assertIs<ConnectionState.DeviceSleeping>(client.connection.value)

        advanceTimeBy(150_000L)
        drain()
        assertIs<ConnectionState.Reconnecting>(client.connection.value)

        advanceTimeBy(300_000L)
        drain()
        assertIs<ConnectionState.Failed>(client.connection.value)
    }

    @Test
    fun retryNowWhileSleepingAttemptsAtOnce() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:B6"), powerSaving = true, lsSecs = 300)
        val client = buildClient(transport, reconnect.copy(initialBackoff = 30.seconds))
        client.connect()
        runCurrent()
        val before = transport.connectAttempts

        transport.drop()
        runCurrent()
        assertIs<ConnectionState.DeviceSleeping>(client.connection.value)
        advanceTimeBy(1_000L)
        drain()
        assertEquals(before, transport.connectAttempts, "still inside the 30 s backoff")

        assertTrue(client.retryNow())
        drain()

        assertEquals(before + 1, transport.connectAttempts)
        advanceTimeBy(300L)
        drain()
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun pendingSendsFailDisconnectedWhenLinkDropsIntoSleep() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:B5"), powerSaving = true, lsSecs = 300)
        val client = buildClient(transport, reconnect)
        client.connect()
        runCurrent()

        val handle = client.send(textPacket())
        runCurrent()
        transport.drop()
        runCurrent()

        assertIs<ConnectionState.DeviceSleeping>(client.connection.value)
        assertEquals(SendState.Failed(SendFailure.Disconnected), handle.state.value)
    }

    @Test
    fun sleepExpectationFollowsFirmwareConditions() {
        val ble = TransportIdentity("ble:AA")

        assertEquals(300, sleepExpectation(ble, sleepBundle())?.expectedWakeSeconds)
        assertNull(sleepExpectation(ble, sleepBundle(powerSaving = false)))
        assertNull(sleepExpectation(ble, sleepBundle(hasWifi = false)))
        assertNull(sleepExpectation(ble, sleepBundle(wifiSsid = "home")))
        // WiFiAPClient.cpp isWifiAvailable: WiFi counts only with an SSID, Ethernet only on an Ethernet build.
        assertEquals(300, sleepExpectation(ble, sleepBundle(wifiEnabled = true))?.expectedWakeSeconds)
        assertNull(sleepExpectation(ble, sleepBundle(hasEthernet = true, ethEnabled = true)))
        assertEquals(300, sleepExpectation(ble, sleepBundle(ethEnabled = true))?.expectedWakeSeconds)
        assertNull(sleepExpectation(ble, sleepBundle(lsSecs = 0)))
        assertNull(sleepExpectation(TransportIdentity("tcp:host:4403"), sleepBundle()))
        assertNull(sleepExpectation(TransportIdentity("serial-jvm:COM3"), sleepBundle()))
        assertNull(sleepExpectation(ble, null))

        // A router light-sleeps without is_power_saving and is back within ls_secs: setBluetoothEnable(false)
        // is a no-op on ESP32 (main-esp32.cpp), the only platform that light-sleeps (PowerFSM.cpp).
        val router = sleepBundle(role = Config.DeviceConfig.Role.ROUTER_LATE, powerSaving = false, lsSecs = 7_200)
        assertEquals(7_200, sleepExpectation(ble, router)?.expectedWakeSeconds)
    }

    @Test
    fun deepSleepBoundIsTheBroadcastIntervalTheDeviceSleepsFor() {
        val ble = TransportIdentity("ble:AA")
        val tracker = Config.DeviceConfig.Role.TRACKER
        val sensor = Config.DeviceConfig.Role.SENSOR

        // PositionModule::runOnce sleeps position_broadcast_secs, one hour when 0 (Default.cpp, Default.h).
        assertEquals(
            900,
            sleepExpectation(
                ble,
                sleepBundle(role = tracker, hasWifi = false, broadcastSecs = 900),
            )?.expectedWakeSeconds,
        )
        assertEquals(
            3600,
            sleepExpectation(ble, sleepBundle(role = Config.DeviceConfig.Role.TAK_TRACKER))?.expectedWakeSeconds,
        )
        assertNull(sleepExpectation(ble, sleepBundle(role = tracker, powerSaving = false)))

        // EnvironmentTelemetryModule::runOnce sleeps environment_update_interval, one hour when 0.
        assertEquals(
            600,
            sleepExpectation(
                ble,
                sleepBundle(role = sensor, envMeasurement = true, envInterval = 600),
            )?.expectedWakeSeconds,
        )
        assertEquals(
            3600,
            sleepExpectation(ble, sleepBundle(role = sensor, envMeasurement = true))?.expectedWakeSeconds,
        )
        // With environment measurement off the module disables itself and the node never sleeps.
        assertNull(sleepExpectation(ble, sleepBundle(role = sensor, envMeasurement = false)))
        assertNull(sleepExpectation(ble, sleepBundle(role = sensor, powerSaving = false, envMeasurement = true)))
    }

    private fun sleepBundle(
        role: Config.DeviceConfig.Role = Config.DeviceConfig.Role.CLIENT,
        powerSaving: Boolean = true,
        lsSecs: Int = 300,
        hasWifi: Boolean = true,
        wifiSsid: String = "",
        wifiEnabled: Boolean = wifiSsid.isNotEmpty(),
        hasEthernet: Boolean = false,
        ethEnabled: Boolean = false,
        broadcastSecs: Int = 0,
        envMeasurement: Boolean = false,
        envInterval: Int = 0,
    ) = ConfigBundle(
        myInfo = MyNodeInfo.Builder().build(),
        metadata = DeviceMetadata.Builder().also { wb ->
            wb.hasWifi = hasWifi
            wb.hasEthernet = hasEthernet
        }.build(),
        configs = listOf(
            Config.Builder().also { wb ->
                wb.device = Config.DeviceConfig.Builder().also { wb -> wb.role = role }.build()
            }.build(),
            Config.Builder().also { wb ->
                wb.power = Config.PowerConfig.Builder().also { wb ->
                    wb.is_power_saving = powerSaving
                    wb.ls_secs = lsSecs
                }.build()
            }.build(),
            Config.Builder().also { wb ->
                wb.network = Config.NetworkConfig.Builder().also { wb ->
                    wb.wifi_enabled = wifiEnabled
                    wb.wifi_ssid = wifiSsid
                    wb.eth_enabled = ethEnabled
                }.build()
            }.build(),
            Config.Builder().also { wb ->
                wb.position =
                    Config.PositionConfig.Builder().also { wb -> wb.position_broadcast_secs = broadcastSecs }.build()
            }.build(),
        ),
        moduleConfigs = listOf(
            ModuleConfig.Builder().also { wb ->
                wb.telemetry = ModuleConfig.TelemetryConfig.Builder().also { wb ->
                    wb.environment_measurement_enabled = envMeasurement
                    wb.environment_update_interval = envInterval
                }.build()
            }.build(),
        ),
    )

    private fun textPacket() = MeshPacket.Builder().also { wb ->
        wb.to = 5
        wb.want_ack = true
        wb.decoded = Data.Builder().also { wb -> wb.portnum = PortNum.TEXT_MESSAGE_APP }.build()
    }.build()

    private fun TestScope.buildClient(transport: RadioTransport, autoReconnect: AutoReconnectConfig): RadioClient =
        RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .clock(object : kotlin.time.Clock {
                override fun now(): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(currentTime)
            })
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .rpcTimeout(60.seconds)
            .autoReconnect(autoReconnect)
            .build()

    private fun TestScope.recordStates(client: RadioClient): MutableList<ConnectionState> {
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.connection.collect { states += it } }
        return states
    }

    private fun TestScope.drain() {
        repeat(20) { runCurrent() }
    }

    private class SleepTransport(
        override val identity: TransportIdentity,
        private val powerSaving: Boolean,
        private val lsSecs: Int,
        private val role: Config.DeviceConfig.Role = Config.DeviceConfig.Role.CLIENT,
    ) : RadioTransport {
        private val stateFlow = MutableStateFlow<TransportState>(TransportState.Disconnected)
        private val inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 128)
        var failReconnects = 0
        var connectAttempts = 0
        var dropOnStage2 = false
        var disconnectGate: CompletableDeferred<Unit>? = null
        var failWrites = false
        var silent = false
        private var connected = false

        override val state: StateFlow<TransportState> = stateFlow

        override suspend fun connect() {
            connectAttempts += 1
            if (connected && failReconnects > 0) {
                failReconnects -= 1
                stateFlow.value = TransportState.Disconnected
                throw MeshtasticException.Transport("device asleep")
            }
            stateFlow.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            disconnectGate?.await()
            stateFlow.value = TransportState.Disconnected
        }

        override suspend fun send(frame: Frame) {
            if (failWrites) throw MeshtasticException.Transport("ATT 0x0e")
            if (silent) return
            val bytes = frame.bytes.toByteArray()
            if (bytes.size < 4) return
            val toRadio = runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull() ?: return
            toRadio.packet?.let(::answerGetOwner)
            // Firmware answers a heartbeat with QueueStatus mesh_packet_id 0.
            if (toRadio.heartbeat != null) {
                inject(FromRadio.Builder().also { wb -> wb.queueStatus = QueueStatus.Builder().build() }.build())
            }
            when (toRadio.want_config_id) {
                NONCE_STAGE1 -> stage1()

                NONCE_STAGE2 -> if (dropOnStage2) {
                    stateFlow.value = TransportState.Error(MeshtasticException.Transport("dropped"), true)
                } else {
                    inject(FromRadio.Builder().also { wb -> wb.config_complete_id = NONCE_STAGE2 }.build())
                    connected = true
                }
            }
        }

        override fun frames(): Flow<Frame> = inbound

        fun inject(fromRadio: FromRadio) {
            inbound.tryEmit(fromRadio.toFrame())
        }

        fun drop() {
            stateFlow.value = TransportState.Error(MeshtasticException.Transport("peripheral disconnected"), true)
        }

        private fun answerGetOwner(packet: MeshPacket) {
            val payload = packet.decoded?.payload ?: return
            val admin = runCatching { AdminMessage.ADAPTER.decode(payload) }.getOrNull() ?: return
            if (admin.get_owner_request != true) return
            val response = AdminMessage.Builder().also { wb ->
                wb.get_owner_response = User.Builder().also { it.id = "!00000001" }.build()
                wb.session_passkey = byteArrayOf(1, 2, 3, 4).toByteString()
            }.build()
            inject(
                FromRadio.Builder().also { wb ->
                    wb.packet = MeshPacket.Builder().also { p ->
                        p.from = 1
                        p.to = 0
                        p.decoded = Data.Builder().also { d ->
                            d.portnum = PortNum.ADMIN_APP
                            d.payload = AdminMessage.ADAPTER.encode(response).toByteString()
                            d.request_id = packet.id
                        }.build()
                    }.build()
                }.build(),
            )
        }

        private fun stage1() {
            inject(
                FromRadio.Builder().also { wb ->
                    wb.my_info =
                        MyNodeInfo.Builder().also { it.my_node_num = 1 }.build()
                }.build(),
            )
            inject(
                FromRadio.Builder().also { wb ->
                    wb.metadata = DeviceMetadata.Builder().also { it.hasWifi = true }.build()
                }.build(),
            )
            inject(
                FromRadio.Builder().also { wb ->
                    wb.config = Config.Builder().also { c ->
                        c.device = Config.DeviceConfig.Builder().also { it.role = role }.build()
                    }.build()
                }.build(),
            )
            inject(
                FromRadio.Builder().also { wb ->
                    wb.config = Config.Builder().also { c ->
                        c.power = Config.PowerConfig.Builder().also {
                            it.is_power_saving = powerSaving
                            it.ls_secs = lsSecs
                        }.build()
                    }.build()
                }.build(),
            )
            inject(FromRadio.Builder().also { wb -> wb.config_complete_id = NONCE_STAGE1 }.build())
        }
    }

    private companion object {
        const val NONCE_STAGE1 = 69420
        const val NONCE_STAGE2 = 69421
    }
}
