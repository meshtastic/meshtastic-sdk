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
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolLoggingTest {

    private class Line(val level: LogLevel, val tag: String, val message: String)

    private class Recorder : LogSink {
        val lines = mutableListOf<Line>()
        override fun log(level: LogLevel, tag: String, message: String, cause: Throwable?) {
            lines += Line(level, tag, message)
        }

        fun protocol(prefix: String) = lines.filter { it.tag == "Protocol" && it.message.startsWith(prefix) }
    }

    private class CountingRedactor : PayloadRedactor {
        val calls = mutableListOf<Pair<PortNum, ByteString>>()
        override fun redact(portnum: PortNum, payload: ByteString): String {
            calls += portnum to payload
            return "<test-redacted>"
        }
    }

    @Test
    fun noProtocolLinesAndNoRedactorCallsWhenOff() = runTest {
        val log = Recorder()
        val redactor = CountingRedactor()
        val (transport, client) = connect(log) { protocolLogging(LogLevel.NONE, redactor) }

        client.sendText("hello")
        transport.injectPacket(textPacket("reply"))
        runCurrent()

        assertTrue(log.lines.none { it.tag == "Protocol" })
        assertTrue(redactor.calls.isEmpty())
    }

    @Test
    fun noProtocolLinesWhenNeverConfigured() = runTest {
        val log = Recorder()
        val (transport, client) = connect(log) {}

        client.sendText("hello")
        transport.injectPacket(textPacket("reply"))
        runCurrent()

        assertTrue(log.lines.none { it.tag == "Protocol" })
    }

    @Test
    fun oneLinePerFrameInEachDirectionAtTheChosenLevelWithTheRedactorApplied() = runTest {
        val log = Recorder()
        val redactor = CountingRedactor()
        val (transport, client) = connect(log) { protocolLogging(LogLevel.INFO, redactor) }

        log.lines.clear()
        redactor.calls.clear()
        val framesBefore = transport.outboundFrames().size
        client.sendText("hello")
        runCurrent()
        val sentFrames = transport.outboundFrames().size - framesBefore
        assertEquals(sentFrames, log.protocol("TX").size)
        val tx = log.protocol("TX").single { "TEXT_MESSAGE_APP" in it.message }
        assertEquals(LogLevel.INFO, tx.level)
        assertTrue("<test-redacted>" in tx.message)
        assertFalse("hello" in tx.message)
        assertTrue(redactor.calls.any { it.first == PortNum.TEXT_MESSAGE_APP && it.second == "hello".encodeUtf8() })

        transport.injectPacket(textPacket("reply"))
        runCurrent()
        val rx = log.protocol("RX").single()
        assertEquals(LogLevel.INFO, rx.level)
        assertTrue("TEXT_MESSAGE_APP" in rx.message)
        assertTrue("<test-redacted>" in rx.message)
        assertFalse("reply" in rx.message)
        assertTrue(redactor.calls.any { it.second == "reply".encodeUtf8() })
    }

    @Test
    fun defaultRedactorKeepsPasskeysAndPayloadsOutOfTheLog() = runTest {
        val log = Recorder()
        val (transport, client) = connect(log) { protocolLogging(LogLevel.DEBUG) }
        val remote = NodeId(0x22222222)

        val outboundBefore = transport.outboundPackets().size
        client.send(
            MeshPacket.Builder().also { wb ->
                wb.to = remote.raw
                wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.ADMIN_APP
                    wb.payload = AdminMessage.ADAPTER.encode(
                        AdminMessage.Builder().also { wb ->
                            wb.get_owner_request = true
                            wb.session_passkey = SECRET.toByteString()
                        }.build(),
                    ).toByteString()
                }.build()
            }.build(),
        )
        runCurrent()
        val request = transport.outboundPackets().drop(outboundBefore).last { it.to == remote.raw }
        transport.injectAdminResponse(
            requestId = request.id,
            response = AdminMessage.Builder().also { wb ->
                wb.get_device_metadata_response = DeviceMetadata.Builder().also { wb ->
                    wb.firmware_version = "2.7.0"
                }.build()
                wb.session_passkey = SECRET.toByteString()
            }.build(),
            fromNode = remote.raw,
        )
        runCurrent()

        val admin = log.lines.filter { it.tag == "Protocol" && "ADMIN_APP" in it.message }
        assertTrue(admin.size >= 2, "expected a TX and an RX admin line")
        val all = log.lines.joinToString("\n") { it.message }
        assertFalse(SECRET.toByteString().hex() in all)
        assertFalse(SECRET.joinToString(",") in all)
        assertFalse(SECRET.toByteString().base64() in all)
    }

    @Test
    fun noneRedactorPrintsThePayloadForDevelopers() = runTest {
        val log = Recorder()
        val (_, client) = connect(log) { protocolLogging(LogLevel.DEBUG, PayloadRedactor.None) }

        client.sendText("hello")
        runCurrent()

        assertTrue(log.protocol("TX").any { "hello".encodeUtf8().hex() in it.message })
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private suspend fun TestScope.connect(
        log: Recorder,
        configure: RadioClient.Builder.() -> Unit,
    ): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:protocol-log"),
            autoHandshake = true,
            nodeNum = 0x11111111,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .logger(log)
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .apply(configure)
            .build()
        client.connect()
        runCurrent()
        return transport to client
    }

    private fun textPacket(text: String): MeshPacket = MeshPacket.Builder().also { wb ->
        wb.id = 4242
        wb.from = 0x33333333
        wb.to = 0
        wb.decoded = Data.Builder().also { wb ->
            wb.portnum = PortNum.TEXT_MESSAGE_APP
            wb.payload = text.encodeUtf8()
        }.build()
    }.build()

    private companion object {
        val SECRET = byteArrayOf(0x5A, 0x4B, 0x3C, 0x2D, 0x1E, 0x0F, 0x71, 0x62)
    }
}
