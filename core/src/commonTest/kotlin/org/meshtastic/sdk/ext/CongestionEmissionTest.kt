/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Telemetry
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CongestionEmissionTest {
    private fun TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:congestion-emission"),
            autoHandshake = true,
            nodeNum = 1,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .build()
        return transport to client
    }

    private fun FakeRadioTransport.injectTelemetry(fromNode: Int = nodeNum, deviceMetrics: DeviceMetrics) {
        val payload = Telemetry.ADAPTER.encode(Telemetry(device_metrics = deviceMetrics)).toByteString()
        injectPacket(
            MeshPacket(
                from = fromNode,
                to = 0,
                decoded = Data(
                    portnum = PortNum.TELEMETRY_APP,
                    payload = payload,
                ),
            ),
        )
    }

    @Test
    fun criticalMetricsResolveToCriticalLevel() {
        val metrics = CongestionMetrics(airUtilTx = 80f, channelUtil = 30f)

        assertEquals(CongestionLevel.CRITICAL, metrics.level)
    }

    @Test
    fun levelTransitionsEmitOnlyWhenCrossingThresholds() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val events = mutableListOf<MeshEvent.CongestionWarning>()
        val collectJob = launch(backgroundScope.coroutineContext) {
            client.events.collect { event ->
                if (event is MeshEvent.CongestionWarning) {
                    events += event
                }
            }
        }
        runCurrent()

        transport.injectTelemetry(deviceMetrics = DeviceMetrics(air_util_tx = 10f, channel_utilization = 10f))
        runCurrent()
        transport.injectTelemetry(deviceMetrics = DeviceMetrics(air_util_tx = 55f, channel_utilization = 10f))
        runCurrent()
        transport.injectTelemetry(deviceMetrics = DeviceMetrics(air_util_tx = 60f, channel_utilization = 15f))
        runCurrent()

        assertEquals(listOf(CongestionLevel.LOW, CongestionLevel.HIGH), events.map { it.metrics.level })
        assertEquals(55f, events.last().metrics.airUtilTx)

        collectJob.cancel()
        client.disconnect()
    }

    @Test
    fun zeroMetricsDoNotEmitWarnings() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val events = mutableListOf<MeshEvent.CongestionWarning>()
        val collectJob = launch(backgroundScope.coroutineContext) {
            client.events.collect { event ->
                if (event is MeshEvent.CongestionWarning) {
                    events += event
                }
            }
        }
        runCurrent()

        transport.injectTelemetry(deviceMetrics = DeviceMetrics(air_util_tx = 0f, channel_utilization = 0f))
        runCurrent()
        transport.injectTelemetry(deviceMetrics = DeviceMetrics(air_util_tx = 55f, channel_utilization = 0f))
        runCurrent()

        assertEquals(1, events.size)
        assertEquals(CongestionLevel.HIGH, events.single().metrics.level)

        collectJob.cancel()
        client.disconnect()
    }

    @Test
    fun multipleNodesAreTrackedIndependently() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val events = mutableListOf<MeshEvent.CongestionWarning>()
        val collectJob = launch(backgroundScope.coroutineContext) {
            client.events.collect { event ->
                if (event is MeshEvent.CongestionWarning) {
                    events += event
                }
            }
        }
        runCurrent()

        transport.injectTelemetry(
            fromNode = 0x10101010,
            deviceMetrics = DeviceMetrics(air_util_tx = 55f, channel_utilization = 10f),
        )
        runCurrent()
        transport.injectTelemetry(
            fromNode = 0x10101010,
            deviceMetrics = DeviceMetrics(air_util_tx = 60f, channel_utilization = 15f),
        )
        runCurrent()
        transport.injectTelemetry(
            fromNode = 0x20202020,
            deviceMetrics = DeviceMetrics(air_util_tx = 65f, channel_utilization = 12f),
        )
        runCurrent()

        assertEquals(2, events.size)
        assertEquals(55f, events[0].metrics.airUtilTx)
        assertEquals(65f, events[1].metrics.airUtilTx)
        assertTrue(events.all { it.metrics.level == CongestionLevel.HIGH })

        collectJob.cancel()
        client.disconnect()
    }
}
