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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.DeviceConnectionStatus
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.NetworkConnectionStatus
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.WifiConnectionStatus
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class QueueAndMqttEventsTest {

    @Test
    fun queueStatusAfterHandshakeEmitsQueueStatusChanged() = runTest {
        val (transport, client) = connectedClient()
        val events = collectEvents(client)
        client.connect()
        runCurrent()

        val idle = queueStatus(free = 16, meshPacketId = 0)
        val tracked = queueStatus(free = 15, meshPacketId = 0x1234)
        transport.injectFrame(FromRadio.Builder().also { wb -> wb.queueStatus = idle }.build().toFrame())
        transport.injectFrame(FromRadio.Builder().also { wb -> wb.queueStatus = tracked }.build().toFrame())
        runCurrent()

        assertEquals(
            listOf(idle, tracked),
            events.filterIsInstance<MeshEvent.QueueStatusChanged>().map { it.status },
        )
        runCatching { client.disconnect() }
    }

    @Test
    fun connectionStatusConnectedThenDisconnectedEmitsEachOnce() = runTest {
        val (transport, client) = connectedClient()
        val events = collectEvents(client)
        client.connect()
        runCurrent()

        answerConnectionStatus(transport, client.admin, mqttConnected = true)
        answerConnectionStatus(transport, client.admin, mqttConnected = false)

        assertEquals(
            listOf<MeshEvent>(MeshEvent.MqttConnected, MeshEvent.MqttDisconnected(reason = null)),
            events.mqttEvents(),
        )
        runCatching { client.disconnect() }
    }

    @Test
    fun repeatedIdenticalConnectionStatusEmitsNothing() = runTest {
        val (transport, client) = connectedClient()
        val events = collectEvents(client)
        client.connect()
        runCurrent()

        answerConnectionStatus(transport, client.admin, mqttConnected = true)
        answerConnectionStatus(transport, client.admin, mqttConnected = true)
        answerConnectionStatus(transport, client.admin, mqttConnected = false)
        answerConnectionStatus(transport, client.admin, mqttConnected = false)

        assertEquals(
            listOf<MeshEvent>(MeshEvent.MqttConnected, MeshEvent.MqttDisconnected(reason = null)),
            events.mqttEvents(),
        )
        runCatching { client.disconnect() }
    }

    @Test
    fun firstObservationOfDisconnectedEmitsNothing() = runTest {
        val (transport, client) = connectedClient()
        val events = collectEvents(client)
        client.connect()
        runCurrent()

        answerConnectionStatus(transport, client.admin, mqttConnected = false)
        assertTrue(events.mqttEvents().isEmpty(), "got: ${events.mqttEvents()}")

        answerConnectionStatus(transport, client.admin, mqttConnected = true)
        assertEquals(listOf<MeshEvent>(MeshEvent.MqttConnected), events.mqttEvents())
        runCatching { client.disconnect() }
    }

    @Test
    fun connectionStatusWithoutNetworkInterfaceEmitsNothing() = runTest {
        val (transport, client) = connectedClient()
        val events = collectEvents(client)
        client.connect()
        runCurrent()

        answerConnectionStatus(transport, client.admin, status = DeviceConnectionStatus.Builder().build())
        assertTrue(events.mqttEvents().isEmpty(), "got: ${events.mqttEvents()}")
        runCatching { client.disconnect() }
    }

    @Test
    fun remoteConnectionStatusEmitsNothing() = runTest {
        val (transport, client) = connectedClient()
        val events = collectEvents(client)
        client.connect()
        runCurrent()

        answerConnectionStatus(
            transport,
            client.admin.forNode(REMOTE),
            status = statusWithMqtt(connected = true),
            fromNode = REMOTE.raw,
        )
        assertTrue(events.mqttEvents().isEmpty(), "got: ${events.mqttEvents()}")

        // The remote response must not seed the local state either.
        answerConnectionStatus(transport, client.admin, mqttConnected = true)
        assertEquals(listOf<MeshEvent>(MeshEvent.MqttConnected), events.mqttEvents())
        runCatching { client.disconnect() }
    }

    private suspend fun TestScope.answerConnectionStatus(
        transport: FakeRadioTransport,
        admin: AdminApi,
        mqttConnected: Boolean,
    ) = answerConnectionStatus(transport, admin, statusWithMqtt(mqttConnected))

    private suspend fun TestScope.answerConnectionStatus(
        transport: FakeRadioTransport,
        admin: AdminApi,
        status: DeviceConnectionStatus,
        fromNode: Int = LOCAL,
    ) {
        val before = transport.outboundPackets().size
        val call = async { admin.getDeviceConnectionStatus() }
        runCurrent()
        val request = transport.outboundPackets().drop(before).last { packet ->
            packet.decoded?.portnum == PortNum.ADMIN_APP &&
                AdminMessage.ADAPTER.decode(packet.decoded!!.payload).get_device_connection_status_request == true
        }
        transport.injectAdminResponse(
            request.id,
            AdminMessage.Builder().also { wb -> wb.get_device_connection_status_response = status }.build(),
            fromNode = fromNode,
        )
        runCurrent()
        assertIs<AdminResult.Success<DeviceConnectionStatus>>(call.await())
    }

    private fun statusWithMqtt(connected: Boolean): DeviceConnectionStatus =
        DeviceConnectionStatus.Builder().also { wb ->
            wb.wifi = WifiConnectionStatus.Builder().also { wifi ->
                wifi.status = NetworkConnectionStatus.Builder().also { net ->
                    net.is_connected = true
                    net.is_mqtt_connected = connected
                }.build()
                wifi.ssid = "mesh"
            }.build()
        }.build()

    private fun queueStatus(free: Int, meshPacketId: Int): QueueStatus = QueueStatus.Builder().also { wb ->
        wb.res = 0
        wb.free = free
        wb.maxlen = 16
        wb.mesh_packet_id = meshPacketId
    }.build()

    private fun List<MeshEvent>.mqttEvents(): List<MeshEvent> =
        filter { it is MeshEvent.MqttConnected || it is MeshEvent.MqttDisconnected }

    private fun TestScope.collectEvents(client: RadioClient): List<MeshEvent> {
        val events = mutableListOf<MeshEvent>()
        backgroundScope.launch { client.events.collect { events.add(it) } }
        return events
    }

    private fun TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:queue-mqtt"),
            autoHandshake = true,
            nodeNum = LOCAL,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .build()
        return transport to client
    }

    private companion object {
        const val LOCAL = 0x11111111
        val REMOTE = NodeId(0x22222222)
    }
}
