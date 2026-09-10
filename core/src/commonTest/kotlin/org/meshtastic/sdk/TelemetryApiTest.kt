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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.AirQualityMetrics
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.HealthMetrics
import org.meshtastic.proto.HostMetrics
import org.meshtastic.proto.LocalStats
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.PowerMetrics
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.TrafficManagementStats
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryApiTest {

    @Test
    fun requestDeviceUsesResolvedLocalNodeAndReturnsDeviceMetrics() = runTest {
        val localNodeNum = 12345
        val (transport, client) = connectedClient(nodeNum = localNodeNum)
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.telemetry.requestDevice() }
        runCurrent()

        val request = transport.lastTelemetryRequest(outboundBefore)
        assertEquals(localNodeNum, request.from)
        assertEquals(localNodeNum, request.to)
        assertTrue(request.decoded?.want_response == true)

        val expected = DeviceMetrics.Builder().also { wb ->
            wb.battery_level = 87
            wb.voltage = 4.1f
            wb.uptime_seconds = 3600
        }.build()
        transport.injectTelemetryResponse(
            requestId = request.id,
            telemetry = Telemetry.Builder().also { wb ->
                wb.device_metrics = expected
            }.build(),
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<DeviceMetrics>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }

    @Test
    fun requestEnvironmentReturnsTemperatureHumidityAndPressure() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(2222)
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.telemetry.requestEnvironment(node) }
        runCurrent()

        val request = transport.lastTelemetryRequest(outboundBefore)
        assertEquals(node.raw, request.to)

        val expected = EnvironmentMetrics.Builder().also { wb ->
            wb.temperature = 21.5f
            wb.relative_humidity = 62.0f
            wb.barometric_pressure = 1013.2f
        }.build()
        transport.injectTelemetryResponse(
            requestId = request.id,
            telemetry = Telemetry.Builder().also { wb -> wb.environment_metrics = expected }.build(),
            fromNode = node.raw,
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<EnvironmentMetrics>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }

    @Test
    fun requestAirQualityReturnsPmValues() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(3333)
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.telemetry.requestAirQuality(node) }
        runCurrent()

        val request = transport.lastTelemetryRequest(outboundBefore)
        val expected = AirQualityMetrics.Builder().also { wb ->
            wb.pm10_standard = 5
            wb.pm25_standard = 12
            wb.pm100_standard = 20
            wb.particles_03um = 41
        }.build()
        transport.injectTelemetryResponse(
            requestId = request.id,
            telemetry = Telemetry.Builder().also { wb -> wb.air_quality_metrics = expected }.build(),
            fromNode = node.raw,
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<AirQualityMetrics>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }

    @Test
    fun requestPowerReturnsVoltageAndCurrentMetrics() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(4444)
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.telemetry.requestPower(node) }
        runCurrent()

        val request = transport.lastTelemetryRequest(outboundBefore)
        val expected = PowerMetrics.Builder().also { wb ->
            wb.ch1_voltage = 4.18f
            wb.ch1_current = 0.42f
            wb.ch2_voltage = 5.0f
        }.build()
        transport.injectTelemetryResponse(
            requestId = request.id,
            telemetry = Telemetry.Builder().also { wb -> wb.power_metrics = expected }.build(),
            fromNode = node.raw,
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<PowerMetrics>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }

    @Test
    fun requestLocalStatsReturnsLocalStatsTelemetry() = runTest {
        val localNodeNum = 54321
        val (transport, client) = connectedClient(nodeNum = localNodeNum)
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.telemetry.requestLocalStats() }
        runCurrent()

        val request = transport.lastTelemetryRequest(outboundBefore)
        assertEquals(localNodeNum, request.to)

        val expected = LocalStats.Builder().also { wb ->
            wb.uptime_seconds = 55
            wb.num_packets_tx = 12
            wb.num_packets_rx = 9
            wb.num_online_nodes = 3
        }.build()
        transport.injectTelemetryResponse(
            requestId = request.id,
            telemetry = Telemetry.Builder().also { wb -> wb.local_stats = expected }.build(),
            fromNode = localNodeNum,
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<LocalStats>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }

    @Test
    fun requestHealthReturnsHealthMetrics() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(5555)
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.telemetry.requestHealth(node) }
        runCurrent()

        val request = transport.lastTelemetryRequest(outboundBefore)
        val expected = HealthMetrics.Builder().also { wb ->
            wb.heart_bpm = 72
            wb.spO2 = 98
            wb.temperature = 36.7f
        }.build()
        transport.injectTelemetryResponse(
            requestId = request.id,
            telemetry = Telemetry.Builder().also { wb -> wb.health_metrics = expected }.build(),
            fromNode = node.raw,
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<HealthMetrics>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }

    @Test
    fun requestHostReturnsHostMetrics() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(6666)
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.telemetry.requestHost(node) }
        runCurrent()

        val request = transport.lastTelemetryRequest(outboundBefore)
        val expected = HostMetrics.Builder().also { wb ->
            wb.uptime_seconds = 1000
            wb.freemem_bytes = 2048
            wb.diskfree1_bytes = 4096
            wb.load1 = 23
            wb.load5 = 17
            wb.load15 = 11
        }.build()
        transport.injectTelemetryResponse(
            requestId = request.id,
            telemetry = Telemetry.Builder().also { wb -> wb.host_metrics = expected }.build(),
            fromNode = node.raw,
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<HostMetrics>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }

    @Test
    fun requestTrafficManagementReturnsTrafficStats() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(7777)
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.telemetry.requestTrafficManagement(node) }
        runCurrent()

        val request = transport.lastTelemetryRequest(outboundBefore)
        val expected = TrafficManagementStats.Builder().also { wb ->
            wb.packets_inspected = 100
            wb.position_dedup_drops = 2
            wb.rate_limit_drops = 3
            wb.router_hops_preserved = 4
        }.build()
        transport.injectTelemetryResponse(
            requestId = request.id,
            telemetry = Telemetry.Builder().also { wb ->
                wb.traffic_management_stats = expected
            }.build(),
            fromNode = node.raw,
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<TrafficManagementStats>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }

    @Test
    fun observeEmitsMatchingTelemetryPacketsInOrder() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(8888)
        val expected = listOf(
            Telemetry.Builder().also { wb ->
                wb.environment_metrics = EnvironmentMetrics.Builder().also { wb ->
                    wb.temperature = 19.8f
                }.build()
            }.build(),
            Telemetry.Builder().also { wb ->
                wb.power_metrics = PowerMetrics.Builder().also { wb ->
                    wb.ch1_voltage = 4.05f
                    wb.ch1_current = 0.31f
                }.build()
            }.build(),
        )
        val collected = backgroundScope.async {
            client.telemetry.observe(node).take(expected.size).toList()
        }
        runCurrent()

        transport.injectTelemetryResponse(requestId = 0, telemetry = expected[0], fromNode = node.raw)
        transport.injectTelemetryResponse(requestId = 0, telemetry = expected[1], fromNode = node.raw)
        runCurrent()

        assertEquals(expected, collected.await())
        client.disconnect()
    }

    @Test
    fun observeIgnoresOtherNodesWrongPortsAndInvalidPayload() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(9999)
        val expected = Telemetry.Builder().also { wb ->
            wb.device_metrics = DeviceMetrics.Builder().also { wb ->
                wb.battery_level = 15
            }.build()
        }.build()
        val collected = backgroundScope.async {
            client.telemetry.observe(node).take(1).toList()
        }
        runCurrent()

        transport.injectTelemetryResponse(
            requestId = 0,
            telemetry = Telemetry.Builder().also { wb ->
                wb.environment_metrics = EnvironmentMetrics.Builder().also { wb ->
                    wb.temperature = 30.0f
                }.build()
            }.build(),
            fromNode = 1111,
        )
        transport.injectPacket(
            MeshPacket.Builder().also { wb ->
                wb.from = node.raw
                wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.TEXT_MESSAGE_APP
                    wb.payload = okio.ByteString.of(*"ignored".encodeToByteArray())
                }.build()
            }.build(),
        )
        transport.injectPacket(
            MeshPacket.Builder().also { wb ->
                wb.from = node.raw
                wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.TELEMETRY_APP
                    wb.payload = okio.ByteString.of(0x80.toByte())
                }.build()
            }.build(),
        )
        transport.injectTelemetryResponse(requestId = 0, telemetry = expected, fromNode = node.raw)
        runCurrent()

        assertEquals(listOf(expected), collected.await())
        client.disconnect()
    }

    private fun TestScope.connectedClient(
        nodeNum: Int = 1234,
        rpcTimeout: Duration = 60.seconds,
    ): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:telemetry-api"),
            autoHandshake = true,
            nodeNum = nodeNum,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .rpcTimeout(rpcTimeout)
            .sendTimeout(60.seconds)
            .build()
        return transport to client
    }

    private fun FakeRadioTransport.lastTelemetryRequest(outboundBefore: Int): MeshPacket =
        outboundPackets().drop(outboundBefore).last { it.decoded?.portnum == PortNum.TELEMETRY_APP }
}
