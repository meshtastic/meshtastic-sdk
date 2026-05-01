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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.LocalStats
import org.meshtastic.proto.Telemetry
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class P2TelemetryRpcTest {

    private fun kotlinx.coroutines.test.TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:p2-telem"),
            autoHandshake = true,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .build()
        return transport to client
    }

    @Test
    fun requestDeviceReturnsMatchingArm() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.telemetry.requestDevice() }
        runCurrent()

        val req = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == org.meshtastic.proto.PortNum.TELEMETRY_APP }
        val expected = DeviceMetrics(battery_level = 87, voltage = 4.05f, uptime_seconds = 3600)
        transport.injectTelemetryResponse(
            requestId = req.id,
            telemetry = Telemetry(device_metrics = expected),
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<DeviceMetrics>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }

    @Test
    fun requestEnvironmentMissingArmReportsFailed() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.telemetry.requestEnvironment() }
        runCurrent()

        val req = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == org.meshtastic.proto.PortNum.TELEMETRY_APP }
        // Reply with the wrong arm (DeviceMetrics) — must surface as Failed(NO_RESPONSE).
        transport.injectTelemetryResponse(
            requestId = req.id,
            telemetry = Telemetry(device_metrics = DeviceMetrics(battery_level = 50)),
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Failed>(result)
        assertEquals(org.meshtastic.proto.Routing.Error.NO_RESPONSE, result.routingError)
        client.disconnect()
    }

    @Test
    fun requestLocalStatsTimesOut() = runTest {
        val (_, client) = connectedClient()
        client.connect()
        runCurrent()

        val deferred = async { client.telemetry.requestLocalStats() }
        runCurrent()
        advanceTimeBy(70.seconds)
        runCurrent()

        val result = deferred.await()
        assertEquals(AdminResult.Timeout, result)
        client.disconnect()
    }

    @Test
    fun observeFiltersByNodeAndPortnum() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val nodeOfInterest = NodeId(0x77777777)
        val collected = mutableListOf<Telemetry>()
        val collector = backgroundScope.launch {
            client.telemetry.observe(nodeOfInterest).collect { collected += it }
        }
        runCurrent()

        // Inject a Telemetry packet from the wrong node — must be filtered out.
        transport.injectTelemetryResponse(
            requestId = 0,
            telemetry = Telemetry(local_stats = LocalStats(uptime_seconds = 1)),
            fromNode = 0x42,
        )
        // Inject a matching packet — must reach the collector.
        val matchEnv = EnvironmentMetrics(temperature = 21.5f)
        transport.injectTelemetryResponse(
            requestId = 0,
            telemetry = Telemetry(environment_metrics = matchEnv),
            fromNode = nodeOfInterest.raw,
        )
        runCurrent()

        assertEquals(1, collected.size, "Only the matching node's telemetry should arrive: $collected")
        assertEquals(matchEnv, collected.first().environment_metrics)

        collector.cancel()
        client.disconnect()
    }

    @Test
    fun observeLocalAcceptsAnyOrigin() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val first = backgroundScope.async { client.telemetry.observe(NodeId.LOCAL).first() }
        runCurrent()
        val telem = Telemetry(device_metrics = DeviceMetrics(battery_level = 12))
        transport.injectTelemetryResponse(requestId = 0, telemetry = telem, fromNode = 0xAB)
        runCurrent()

        val received = first.await()
        assertNotNull(received.device_metrics)
        assertEquals(12, received.device_metrics!!.battery_level)
        client.disconnect()
    }
}
