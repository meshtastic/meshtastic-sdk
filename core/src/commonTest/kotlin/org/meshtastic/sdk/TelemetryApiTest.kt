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

        val expected = DeviceMetrics(battery_level = 87, voltage = 4.1f, uptime_seconds = 3600)
        transport.injectTelemetryResponse(requestId = request.id, telemetry = Telemetry(device_metrics = expected))
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

        val expected = EnvironmentMetrics(
            temperature = 21.5f,
            relative_humidity = 62.0f,
            barometric_pressure = 1013.2f,
        )
        transport.injectTelemetryResponse(requestId = request.id, telemetry = Telemetry(environment_metrics = expected), fromNode = node.raw)
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
        val expected = AirQualityMetrics(
            pm10_standard = 5,
            pm25_standard = 12,
            pm100_standard = 20,
            particles_03um = 41,
        )
        transport.injectTelemetryResponse(requestId = request.id, telemetry = Telemetry(air_quality_metrics = expected), fromNode = node.raw)
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
        val expected = PowerMetrics(ch1_voltage = 4.18f, ch1_current = 0.42f, ch2_voltage = 5.0f)
        transport.injectTelemetryResponse(requestId = request.id, telemetry = Telemetry(power_metrics = expected), fromNode = node.raw)
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

        val expected = LocalStats(
            uptime_seconds = 55,
            num_packets_tx = 12,
            num_packets_rx = 9,
            num_online_nodes = 3,
        )
        transport.injectTelemetryResponse(requestId = request.id, telemetry = Telemetry(local_stats = expected), fromNode = localNodeNum)
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
        val expected = HealthMetrics(heart_bpm = 72, spO2 = 98, temperature = 36.7f)
        transport.injectTelemetryResponse(requestId = request.id, telemetry = Telemetry(health_metrics = expected), fromNode = node.raw)
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
        val expected = HostMetrics(
            uptime_seconds = 1000,
            freemem_bytes = 2048,
            diskfree1_bytes = 4096,
            load1 = 23,
            load5 = 17,
            load15 = 11,
        )
        transport.injectTelemetryResponse(requestId = request.id, telemetry = Telemetry(host_metrics = expected), fromNode = node.raw)
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
        val expected = TrafficManagementStats(
            packets_inspected = 100,
            position_dedup_drops = 2,
            rate_limit_drops = 3,
            router_hops_preserved = 4,
        )
        transport.injectTelemetryResponse(
            requestId = request.id,
            telemetry = Telemetry(traffic_management_stats = expected),
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
            Telemetry(environment_metrics = EnvironmentMetrics(temperature = 19.8f)),
            Telemetry(power_metrics = PowerMetrics(ch1_voltage = 4.05f, ch1_current = 0.31f)),
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
        val expected = Telemetry(device_metrics = DeviceMetrics(battery_level = 15))
        val collected = backgroundScope.async {
            client.telemetry.observe(node).take(1).toList()
        }
        runCurrent()

        transport.injectTelemetryResponse(
            requestId = 0,
            telemetry = Telemetry(environment_metrics = EnvironmentMetrics(temperature = 30.0f)),
            fromNode = 1111,
        )
        transport.injectPacket(
            MeshPacket(
                from = node.raw,
                decoded = Data(
                    portnum = PortNum.TEXT_MESSAGE_APP,
                    payload = okio.ByteString.of(*"ignored".encodeToByteArray()),
                ),
            ),
        )
        transport.injectPacket(
            MeshPacket(
                from = node.raw,
                decoded = Data(
                    portnum = PortNum.TELEMETRY_APP,
                    payload = okio.ByteString.of(0x80.toByte()),
                ),
            ),
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
