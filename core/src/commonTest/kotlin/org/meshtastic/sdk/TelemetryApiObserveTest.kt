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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.PowerMetrics
import org.meshtastic.proto.Telemetry
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryApiObserveTest {

    @Test
    fun `observe emits telemetry from specified node`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(0x22222222)
        val expected = Telemetry(device_metrics = DeviceMetrics(battery_level = 85))
        val received = backgroundScope.async { client.telemetry.observe(node).first() }
        runCurrent()

        transport.injectTelemetryResponse(requestId = 0, telemetry = expected, fromNode = node.raw)
        runCurrent()

        val actual = received.await()
        assertEquals(expected, actual)
        client.disconnect()
    }

    @Test
    fun `observe filters out telemetry from other nodes`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val observedNode = NodeId(0x22222222)
        val otherNode = 0x33333333
        val collected = mutableListOf<Telemetry>()
        val collector = backgroundScope.launch {
            client.telemetry.observe(observedNode).collect { collected += it }
        }
        runCurrent()

        transport.injectTelemetryResponse(
            requestId = 0,
            telemetry = Telemetry(device_metrics = DeviceMetrics(battery_level = 42)),
            fromNode = otherNode,
        )
        runCurrent()

        assertEquals(0, collected.size)
        collector.cancelAndJoin()
        client.disconnect()
    }

    @Test
    fun `observe with LOCAL emits from all nodes`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val expected = listOf(
            Telemetry(device_metrics = DeviceMetrics(battery_level = 81)),
            Telemetry(environment_metrics = EnvironmentMetrics(temperature = 21.5f)),
            Telemetry(power_metrics = PowerMetrics(ch1_voltage = 4.2f, ch1_current = 0.48f)),
        )
        val fromNodes = listOf(0x22222222, 0x33333333, 0x44444444)
        val received = backgroundScope.async {
            client.telemetry.observe(NodeId.LOCAL).take(expected.size).toList()
        }
        runCurrent()

        expected.zip(fromNodes).forEach { (telemetry, fromNode) ->
            transport.injectTelemetryResponse(requestId = 0, telemetry = telemetry, fromNode = fromNode)
        }
        runCurrent()

        val actual = received.await()
        assertEquals(expected, actual)
        client.disconnect()
    }

    @Test
    fun `observe emits device metrics`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val expected = DeviceMetrics(battery_level = 90, voltage = 4.1f, uptime_seconds = 600)
        val received = backgroundScope.async { client.telemetry.observe(NodeId.LOCAL).first() }
        runCurrent()

        transport.injectTelemetryResponse(
            requestId = 0,
            telemetry = Telemetry(device_metrics = expected),
            fromNode = 0x22222222,
        )
        runCurrent()

        val actual = received.await().device_metrics
        assertEquals(expected, actual)
        client.disconnect()
    }

    @Test
    fun `observe emits environment metrics`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val expected = EnvironmentMetrics(
            temperature = 23.4f,
            relative_humidity = 56.0f,
            barometric_pressure = 1008.7f,
        )
        val received = backgroundScope.async { client.telemetry.observe(NodeId.LOCAL).first() }
        runCurrent()

        transport.injectTelemetryResponse(
            requestId = 0,
            telemetry = Telemetry(environment_metrics = expected),
            fromNode = 0x33333333,
        )
        runCurrent()

        val actual = received.await().environment_metrics
        assertEquals(expected, actual)
        client.disconnect()
    }

    @Test
    fun `observe emits power metrics`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val expected = PowerMetrics(ch1_voltage = 4.18f, ch1_current = 0.42f, ch2_voltage = 5.0f)
        val received = backgroundScope.async { client.telemetry.observe(NodeId.LOCAL).first() }
        runCurrent()

        transport.injectTelemetryResponse(
            requestId = 0,
            telemetry = Telemetry(power_metrics = expected),
            fromNode = 0x44444444,
        )
        runCurrent()

        val actual = received.await().power_metrics
        assertEquals(expected, actual)
        client.disconnect()
    }

    @Test
    fun `multiple concurrent observers receive same data`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(0x55555555)
        val expected = Telemetry(device_metrics = DeviceMetrics(battery_level = 73))
        val first = backgroundScope.async { client.telemetry.observe(node).first() }
        val second = backgroundScope.async { client.telemetry.observe(node).first() }
        runCurrent()

        transport.injectTelemetryResponse(requestId = 0, telemetry = expected, fromNode = node.raw)
        runCurrent()

        val firstActual = first.await()
        val secondActual = second.await()
        assertEquals(expected, firstActual)
        assertEquals(expected, secondActual)
        client.disconnect()
    }

    @Test
    fun `cancelling observer does not affect other observers`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(0x66666666)
        val cancelledCollectorValues = mutableListOf<Telemetry>()
        val cancelledCollector = backgroundScope.launch {
            client.telemetry.observe(node).collect { cancelledCollectorValues += it }
        }
        val survivingCollector = backgroundScope.async { client.telemetry.observe(node).first() }
        runCurrent()

        cancelledCollector.cancelAndJoin()

        val expected = Telemetry(environment_metrics = EnvironmentMetrics(temperature = 18.2f))
        transport.injectTelemetryResponse(requestId = 0, telemetry = expected, fromNode = node.raw)
        runCurrent()

        val actual = survivingCollector.await()
        assertEquals(0, cancelledCollectorValues.size)
        assertEquals(expected, actual)
        client.disconnect()
    }

    @Test
    fun `observe after disconnect emits nothing`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        client.disconnect()

        val collected = mutableListOf<Telemetry>()
        val collector = backgroundScope.launch {
            client.telemetry.observe(NodeId.LOCAL).collect { collected += it }
        }
        runCurrent()

        transport.injectTelemetryResponse(
            requestId = 0,
            telemetry = Telemetry(device_metrics = DeviceMetrics(battery_level = 1)),
            fromNode = 0x22222222,
        )
        runCurrent()

        assertEquals(0, collected.size)
        collector.cancelAndJoin()
    }

    @Test
    fun `rapid telemetry packets all emitted`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(0x77777777)
        val expected = (1..10).map { level ->
            Telemetry(device_metrics = DeviceMetrics(battery_level = level))
        }
        val received = backgroundScope.async {
            client.telemetry.observe(node).take(expected.size).toList()
        }
        runCurrent()

        expected.forEach { telemetry ->
            transport.injectTelemetryResponse(requestId = 0, telemetry = telemetry, fromNode = node.raw)
        }
        runCurrent()

        val actual = received.await()
        assertEquals(expected, actual)
        client.disconnect()
    }

    @Test
    fun `observe is cold and does not replay earlier packets`() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val node = NodeId(0x22222222)
        val beforeSubscription = Telemetry(device_metrics = DeviceMetrics(battery_level = 10))
        transport.injectTelemetryResponse(requestId = 0, telemetry = beforeSubscription, fromNode = node.raw)
        runCurrent()

        val collected = mutableListOf<Telemetry>()
        val collector = backgroundScope.launch {
            client.telemetry.observe(node).collect { collected += it }
        }
        runCurrent()

        assertEquals(0, collected.size)

        val afterSubscription = Telemetry(device_metrics = DeviceMetrics(battery_level = 11))
        transport.injectTelemetryResponse(requestId = 0, telemetry = afterSubscription, fromNode = node.raw)
        runCurrent()

        val actual = collected.toList()
        assertEquals(listOf(afterSubscription), actual)
        collector.cancelAndJoin()
        client.disconnect()
    }

    private fun TestScope.connectedClient(nodeNum: Int = 0x11111111): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:telemetry-observe"),
            autoHandshake = true,
            nodeNum = nodeNum,
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
