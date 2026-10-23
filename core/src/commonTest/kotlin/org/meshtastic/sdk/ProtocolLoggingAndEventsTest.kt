/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
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
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.QueueStatus
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolLoggingAndEventsTest {

    private data class Line(val level: LogLevel, val tag: String, val message: String)

    @Test
    fun protocolLoggingLogsPacketSummariesWithoutPayloads() = runTest {
        val lines = mutableListOf<Line>()
        val transport = FakeRadioTransport(TransportIdentity("fake:protocol-log"), autoHandshake = true)
        val client = buildClient(transport) {
            logger { level, tag, message, _ -> lines += Line(level, tag, message) }
            protocolLogging(LogLevel.INFO)
        }
        client.connect()
        runCurrent()

        client.sendText("secret text", to = NodeId.BROADCAST)
        transport.injectPacket(
            MeshPacket.Builder().also { wb ->
                wb.from = 0x22222222
                wb.to = -1
                wb.id = 99
                wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.TEXT_MESSAGE_APP
                    wb.payload = "incoming secret".encodeToByteArray().toByteString()
                }.build()
            }.build(),
        )
        runCurrent()

        val protocol = lines.filter { it.tag == "MeshProtocol" }
        assertTrue(protocol.all { it.level == LogLevel.INFO })
        assertTrue(protocol.any { it.message.startsWith("tx ") && "port=TEXT_MESSAGE_APP" in it.message }, "$protocol")
        assertTrue(protocol.any { it.message.startsWith("rx id=99 from=!22222222") }, "$protocol")
        assertTrue(lines.none { "secret" in it.message }, "payload text must never be logged")
        client.disconnect()
    }

    @Test
    fun protocolLoggingIsOffByDefault() = runTest {
        val lines = mutableListOf<Line>()
        val transport = FakeRadioTransport(TransportIdentity("fake:protocol-log-off"), autoHandshake = true)
        val client = buildClient(transport) { logger { level, tag, message, _ -> lines += Line(level, tag, message) } }
        client.connect()
        runCurrent()
        client.sendText("hi", to = NodeId.BROADCAST)
        runCurrent()

        assertTrue(lines.none { it.tag == "MeshProtocol" })
        client.disconnect()
    }

    @Test
    fun queueStatusIsEmittedAsAnEvent() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:queue-status"), autoHandshake = true)
        val client = buildClient(transport) {}
        val events = mutableListOf<MeshEvent>()
        backgroundScope.launch { client.events.collect { events += it } }
        client.connect()
        runCurrent()

        val status = QueueStatus.Builder().also { wb ->
            wb.free = 14
            wb.maxlen = 16
            wb.mesh_packet_id = 1234
        }.build()
        transport.injectFrame(FromRadio.Builder().also { wb -> wb.queueStatus = status }.build().toFrame())
        runCurrent()

        assertEquals(listOf(status), events.filterIsInstance<MeshEvent.QueueStatusChanged>().map { it.status })
        client.disconnect()
    }

    private fun TestScope.buildClient(
        transport: RadioTransport,
        configure: RadioClient.Builder.() -> Unit,
    ): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .autoSyncTimeOnConnect(false)
        .coroutineContext(backgroundScope.coroutineContext)
        .apply(configure)
        .build()
}
