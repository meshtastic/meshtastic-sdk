/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

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
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.PortNum
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
        transport.drop()
        runCurrent()
        advanceTimeBy(5_000L)
        drain()

        assertIs<ConnectionState.DeviceSleeping>(client.connection.value)
        assertTrue(transport.connectAttempts >= 3, "attempts continue while sleeping")

        advanceTimeBy(20_000L)
        drain()
        assertEquals(ConnectionState.Connected, client.connection.value)
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
    fun dropDuringHandshakeNeverEntersDeviceSleeping() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:AD"), powerSaving = true, lsSecs = 300)
        transport.dropOnStage2 = true
        val client = buildClient(transport, reconnect)
        val states = recordStates(client)

        val job = backgroundScope.launch { runCatching { client.connect() } }
        runCurrent()
        advanceTimeBy(5_000L)
        drain()

        assertTrue(states.none { it is ConnectionState.DeviceSleeping })
        job.cancel()
    }

    @Test
    fun disconnectNeverEntersDeviceSleeping() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:AE"), powerSaving = true, lsSecs = 300)
        val client = buildClient(transport, reconnect)
        val states = recordStates(client)
        client.connect()
        runCurrent()

        client.disconnect()
        runCurrent()

        assertEquals(ConnectionState.Disconnected, client.connection.value)
        assertTrue(states.none { it is ConnectionState.DeviceSleeping })
    }

    @Test
    fun rebootAnnouncementNeverEntersDeviceSleeping() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:AF"), powerSaving = true, lsSecs = 300)
        val client = buildClient(transport, reconnect)
        val states = recordStates(client)
        client.connect()
        runCurrent()

        transport.inject(FromRadio.Builder().also { wb -> wb.rebooted = true }.build())
        runCurrent()
        transport.drop()
        runCurrent()
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
    fun nonPowerSavingDropWithAutoReconnectOffIsDisconnected() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:B2"), powerSaving = false, lsSecs = 300)
        val client = buildClient(transport, AutoReconnectConfig.Disabled)
        client.connect()
        runCurrent()

        transport.drop()
        drain()

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
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    @Test
    fun unboundedSleepAttemptsCountAgainstMaxAttempts() = runTest {
        val transport =
            SleepTransport(
                TransportIdentity("ble:B4"),
                powerSaving = true,
                lsSecs = 300,
                role = Config.DeviceConfig.Role.ROUTER,
            )
        val client = buildClient(transport, reconnect.copy(maxAttempts = 2))
        client.connect()
        runCurrent()

        transport.failReconnects = Int.MAX_VALUE
        transport.drop()
        runCurrent()
        assertNull(assertIs<ConnectionState.DeviceSleeping>(client.connection.value).expectedWakeSeconds)

        advanceTimeBy(300_000L)
        drain()
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    @Test
    fun pendingSendsFailDisconnectedWhenLinkDropsIntoSleep() = runTest {
        val transport = SleepTransport(TransportIdentity("ble:B5"), powerSaving = true, lsSecs = 300)
        val client = buildClient(transport, reconnect)
        client.connect()
        runCurrent()

        val handle = client.send(
            org.meshtastic.proto.MeshPacket.Builder().also { wb ->
                wb.to = 5
                wb.want_ack = true
                wb.decoded = org.meshtastic.proto.Data.Builder().also { wb ->
                    wb.portnum = org.meshtastic.proto.PortNum.TEXT_MESSAGE_APP
                }.build()
            }.build(),
        )
        runCurrent()
        transport.drop()
        runCurrent()

        assertIs<ConnectionState.DeviceSleeping>(client.connection.value)
        assertEquals(SendState.Failed(SendFailure.Disconnected), handle.state.value)
    }

    @Test
    fun sleepExpectationFollowsFirmwareConditions() {
        fun bundle(
            role: Config.DeviceConfig.Role = Config.DeviceConfig.Role.CLIENT,
            powerSaving: Boolean = true,
            lsSecs: Int = 300,
            hasWifi: Boolean = true,
            wifiSsid: String = "",
        ) = ConfigBundle(
            myInfo = MyNodeInfo.Builder().build(),
            metadata = DeviceMetadata.Builder().also { wb -> wb.hasWifi = hasWifi }.build(),
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
                        wb.wifi_enabled = wifiSsid.isNotEmpty()
                        wb.wifi_ssid = wifiSsid
                    }.build()
                }.build(),
            ),
            moduleConfigs = emptyList(),
        )
        val ble = TransportIdentity("ble:AA")

        assertEquals(300, sleepExpectation(ble, bundle())?.expectedWakeSeconds)
        assertNull(sleepExpectation(ble, bundle(powerSaving = false)))
        assertNull(sleepExpectation(ble, bundle(hasWifi = false)))
        assertNull(sleepExpectation(ble, bundle(wifiSsid = "home")))
        assertNull(sleepExpectation(ble, bundle(lsSecs = 0)))
        assertNull(sleepExpectation(TransportIdentity("tcp:host:4403"), bundle()))
        assertNull(sleepExpectation(TransportIdentity("serial-jvm:COM3"), bundle()))
        assertNull(sleepExpectation(ble, null))

        val router = sleepExpectation(ble, bundle(role = Config.DeviceConfig.Role.ROUTER_LATE, powerSaving = false))
        assertNull(router?.expectedWakeSeconds)
        assertTrue(router != null)

        val tracker = sleepExpectation(ble, bundle(role = Config.DeviceConfig.Role.TRACKER, hasWifi = false))
        assertTrue(tracker != null)
        assertNull(tracker.expectedWakeSeconds)
        assertNull(sleepExpectation(ble, bundle(role = Config.DeviceConfig.Role.SENSOR, powerSaving = false)))
    }

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
            stateFlow.value = TransportState.Disconnected
        }

        override suspend fun send(frame: Frame) {
            val bytes = frame.bytes.toByteArray()
            if (bytes.size < 4) return
            val toRadio = runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull() ?: return
            toRadio.packet?.let(::answerGetOwner)
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
