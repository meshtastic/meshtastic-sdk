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
        val payload = Telemetry.ADAPTER.encode(
            Telemetry.Builder().also { wb ->
                wb.device_metrics = deviceMetrics
            }.build(),
        ).toByteString()
        injectPacket(
            MeshPacket.Builder().also { wb ->
                wb.from = fromNode
                wb.to = 0
                wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.TELEMETRY_APP
                    wb.payload = payload
                }.build()
            }.build(),
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

        transport.injectTelemetry(
            deviceMetrics = DeviceMetrics.Builder().also { wb ->
                wb.air_util_tx = 10f
                wb.channel_utilization = 10f
            }.build(),
        )
        runCurrent()
        transport.injectTelemetry(
            deviceMetrics = DeviceMetrics.Builder().also { wb ->
                wb.air_util_tx = 55f
                wb.channel_utilization = 10f
            }.build(),
        )
        runCurrent()
        transport.injectTelemetry(
            deviceMetrics = DeviceMetrics.Builder().also { wb ->
                wb.air_util_tx = 60f
                wb.channel_utilization = 15f
            }.build(),
        )
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

        transport.injectTelemetry(
            deviceMetrics = DeviceMetrics.Builder().also { wb ->
                wb.air_util_tx = 0f
                wb.channel_utilization = 0f
            }.build(),
        )
        runCurrent()
        transport.injectTelemetry(
            deviceMetrics = DeviceMetrics.Builder().also { wb ->
                wb.air_util_tx = 55f
                wb.channel_utilization = 0f
            }.build(),
        )
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
            deviceMetrics = DeviceMetrics.Builder().also { wb ->
                wb.air_util_tx = 55f
                wb.channel_utilization = 10f
            }.build(),
        )
        runCurrent()
        transport.injectTelemetry(
            fromNode = 0x10101010,
            deviceMetrics = DeviceMetrics.Builder().also { wb ->
                wb.air_util_tx = 60f
                wb.channel_utilization = 15f
            }.build(),
        )
        runCurrent()
        transport.injectTelemetry(
            fromNode = 0x20202020,
            deviceMetrics = DeviceMetrics.Builder().also { wb ->
                wb.air_util_tx = 65f
                wb.channel_utilization = 12f
            }.build(),
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
