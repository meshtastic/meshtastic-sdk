/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolLoggingTest {

    private class Recorded(val level: LogLevel, val tag: String, val message: String)

    private class RecordingSink : LogSink {
        val entries = mutableListOf<Recorded>()

        override fun log(level: LogLevel, tag: String, message: String, cause: Throwable?) {
            entries += Recorded(level, tag, message)
        }
    }

    private fun textPacket(secret: String): FromRadio = FromRadio.Builder().also { wb ->
        wb.packet = MeshPacket.Builder().also { pb ->
            pb.from = 7
            pb.to = 9
            pb.id = 1234
            pb.decoded = Data.Builder().also { db ->
                db.portnum = PortNum.TEXT_MESSAGE_APP
                db.payload = secret.encodeUtf8()
            }.build()
        }.build()
    }.build()

    private suspend fun TestScope.run(sink: RecordingSink, configure: RadioClient.Builder.() -> Unit): RadioClient {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:protocol-log"),
            frames = listOf(textPacket("correct horse battery staple").toFrame()),
            autoHandshake = true,
            nodeNum = 1,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .logger(sink)
            .apply(configure)
            .build()
        client.connect()
        runCurrent()
        return client
    }

    @Test
    fun offByDefaultLogsNoProtocolLines() = runTest {
        val sink = RecordingSink()
        val client = run(sink) {}
        client.disconnect()
        assertTrue(sink.entries.none { it.tag == "Protocol" })
    }

    @Test
    fun defaultRedactorLogsRoutingMetadataButNeverPayloadText() = runTest {
        val sink = RecordingSink()
        val client = run(sink) { protocolLogging(LogLevel.DEBUG) }
        client.disconnect()
        val protocol = sink.entries.filter { it.tag == "Protocol" }
        assertTrue(protocol.any { it.message.startsWith("rx FromRadio") && "variant=packet" in it.message })
        assertTrue(protocol.any { it.message.startsWith("tx ToRadio") })
        assertTrue(protocol.all { it.level == LogLevel.DEBUG })
        assertFalse(protocol.any { "correct horse" in it.message })
        val packetLine = protocol.first { "from=7 to=9 packet_id=1234" in it.message }
        assertEquals(true, "payload_bytes=28" in packetLine.message)
    }

    @Test
    fun customRedactorIsApplied() = runTest {
        val sink = RecordingSink()
        val custom = object : PayloadRedactor {
            override fun describe(fromRadio: FromRadio): String = "CUSTOM-RX"
        }
        val client = run(sink) { protocolLogging(LogLevel.INFO, custom) }
        client.disconnect()
        assertTrue(sink.entries.any { it.message == "rx CUSTOM-RX" && it.level == LogLevel.INFO })
    }
}
