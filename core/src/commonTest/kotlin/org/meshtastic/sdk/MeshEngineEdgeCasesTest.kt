/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.bytestring.ByteString as KByteString

@OptIn(ExperimentalCoroutinesApi::class)
class MeshEngineEdgeCasesTest {

    @Test
    fun shortFrameIsIgnoredAndLogged() = runTest {
        val transport = fakeTransport()
        val logs = mutableListOf<CapturedLog>()
        val client = buildClient(transport = transport, logger = recordingLogger(logs))
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val job = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }

        client.connect()
        transport.injectFrame(Frame(KByteString(byteArrayOf(WireFraming.MAGIC_0, WireFraming.MAGIC_1, 0x00))))
        runCurrent()

        assertEquals(ConnectionState.Connected, client.connection.value)
        assertTrue(logs.any { it.level == LogLevel.WARN && it.message.contains("shorter than wire header") })
        assertTrue(warnings.any { it.message.contains("shorter than wire header") })

        job.cancel()
        client.disconnect()
    }

    @Test
    fun invalidWireHeaderIsDroppedGracefully() = runTest {
        val transport = fakeTransport()
        val logs = mutableListOf<CapturedLog>()
        val client = buildClient(transport = transport, logger = recordingLogger(logs))
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val job = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }

        client.connect()
        transport.injectFrame(
            rawFrame(
                encodedFromRadio(FromRadio(node_info = org.meshtastic.proto.NodeInfo(num = 7))),
                header0 = 0x00,
                header1 = 0x00,
            ),
        )
        runCurrent()

        assertEquals(ConnectionState.Connected, client.connection.value)
        assertTrue(logs.any { it.level == LogLevel.WARN && it.message.contains("invalid wire header") })
        assertTrue(warnings.any { it.message.contains("invalid wire header") })

        job.cancel()
        client.disconnect()
    }

    @Test
    fun emptyPayloadFrameEmitsProtocolWarning() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val job = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }

        client.connect()
        transport.injectFrame(rawFrame(ByteArray(0), declaredLength = 0))
        runCurrent()

        assertEquals(ConnectionState.Connected, client.connection.value)
        assertTrue(warnings.any { it.message.contains("empty payload") })

        job.cancel()
        client.disconnect()
    }

    @Test
    fun truncatedPayloadFrameEmitsLengthMismatchWarning() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val job = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }

        val fullPayload = encodedFromRadio(FromRadio(packet = inboundTextPacket(id = 1, from = 0x1001)))
        client.connect()
        transport.injectFrame(rawFrame(fullPayload.copyOf(fullPayload.size - 1), declaredLength = fullPayload.size))
        runCurrent()

        val warning = warnings.last()
        assertEquals(ConnectionState.Connected, client.connection.value)
        assertTrue(warning.message.contains("length mismatch"))
        assertEquals(fullPayload.size, warning.details["declared_payload_bytes"])
        assertEquals(fullPayload.size - 1, warning.details["actual_payload_bytes"])

        job.cancel()
        client.disconnect()
    }

    @Test
    fun oversizedInboundFrameIsRejected() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val job = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }

        client.connect()
        transport.injectFrame(rawFrame(byteArrayOf(0x08), declaredLength = WireFraming.MAX_PAYLOAD_SIZE + 1))
        runCurrent()

        val warning = warnings.last()
        assertEquals(ConnectionState.Connected, client.connection.value)
        assertTrue(warning.message.contains("exceeds max payload size"))
        assertEquals(WireFraming.MAX_PAYLOAD_SIZE + 1, warning.details["declared_payload_bytes"])

        job.cancel()
        client.disconnect()
    }

    @Test
    fun decodeFailureSurfacesStructuredProtocolWarning() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val job = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }

        client.connect()
        transport.injectFrame(rawFrame(byteArrayOf(0x08), declaredLength = 1))
        runCurrent()

        val warning = warnings.last()
        assertTrue(warning.message.contains("decode failed"))
        assertTrue((warning.details["exception"] as String).isNotEmpty())
        assertEquals(1, warning.details["payload_bytes"])

        job.cancel()
        client.disconnect()
    }

    @Test
    fun malformedFrameDoesNotPreventSubsequentValidPacketProcessing() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)
        val packets = mutableListOf<MeshPacket>()
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val packetJob = backgroundScope.launch { client.packets.collect { packets += it } }
        val warningJob = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }

        client.connect()
        transport.injectFrame(rawFrame(byteArrayOf(0x08), declaredLength = 1))
        transport.injectPacket(inboundTextPacket(id = 10, from = 0x1002, text = "after-malformed"))
        runCurrent()

        assertEquals(1, warnings.size)
        assertEquals(listOf(10), packets.map { it.id })

        packetJob.cancel()
        warningJob.cancel()
        client.disconnect()
    }

    @Test
    fun unknownPortPacketIsDroppedFromPacketsFlow() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)
        val packets = mutableListOf<MeshPacket>()
        val job = backgroundScope.launch { client.packets.collect { packets += it } }

        client.connect()
        transport.injectPacket(
            MeshPacket(
                from = 0x2001,
                to = 0,
                id = 20,
                decoded = Data(
                    portnum = PortNum.UNKNOWN_APP,
                    payload = ByteString.of(*byteArrayOf(0x01, 0x02)),
                ),
            ),
        )
        runCurrent()

        assertTrue(packets.isEmpty())

        job.cancel()
        client.disconnect()
    }

    @Test
    fun unknownPortPacketEmitsProtocolWarningAndLog() = runTest {
        val transport = fakeTransport()
        val logs = mutableListOf<CapturedLog>()
        val client = buildClient(transport = transport, logger = recordingLogger(logs))
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val job = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }

        client.connect()
        transport.injectPacket(
            MeshPacket(
                from = 0x2002,
                to = 0,
                id = 21,
                decoded = Data(portnum = PortNum.UNKNOWN_APP, payload = ByteString.of(*byteArrayOf(0x05))),
            ),
        )
        runCurrent()

        assertTrue(logs.any { it.level == LogLevel.WARN && it.message.contains("unknown port") })
        assertTrue(warnings.any { it.message.contains("unknown port") })

        job.cancel()
        client.disconnect()
    }

    @Test
    fun unknownPortPacketKeepsConnectionAlive() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)

        client.connect()
        transport.injectPacket(
            MeshPacket(
                from = 0x2003,
                to = 0,
                id = 22,
                decoded = Data(portnum = PortNum.UNKNOWN_APP, payload = ByteString.of(*byteArrayOf(0x07))),
            ),
        )
        runCurrent()

        assertEquals(ConnectionState.Connected, client.connection.value)
        client.disconnect()
    }

    @Test
    fun oversizedRawPacketSendIsRejected() = runTest {
        val client = buildClient()
        client.connect()

        assertFailsWith<MeshtasticException.PayloadTooLarge> {
            client.send(
                MeshPacket(
                    to = NodeId.BROADCAST.raw,
                    channel = 0,
                    decoded = Data(
                        portnum = PortNum.TEXT_MESSAGE_APP,
                        payload = ByteString.of(*ByteArray(DATA_PAYLOAD_LEN + 1)),
                    ),
                ),
            )
        }

        client.disconnect()
    }

    @Test
    fun oversizedPortnumPayloadSendIsRejected() = runTest {
        val client = buildClient()
        client.connect()

        assertFailsWith<MeshtasticException.PayloadTooLarge> {
            client.send(PortNum.TEXT_MESSAGE_APP, ByteArray(DATA_PAYLOAD_LEN + 1))
        }

        client.disconnect()
    }

    @Test
    fun oversizedTextSendIsRejected() = runTest {
        val client = buildClient()
        client.connect()

        assertFailsWith<MeshtasticException.PayloadTooLarge> {
            client.sendText("x".repeat(DATA_PAYLOAD_LEN + 1))
        }

        client.disconnect()
    }

    @Test
    fun encryptedPacketDoesNotReachPacketsFlow() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)
        val packets = mutableListOf<MeshPacket>()
        val job = backgroundScope.launch { client.packets.collect { packets += it } }

        client.connect()
        transport.injectPacket(MeshPacket(from = 0x3001, to = 0, id = 30))
        runCurrent()

        assertTrue(packets.isEmpty())

        job.cancel()
        client.disconnect()
    }

    @Test
    fun encryptedPacketEmitsProtocolWarningAndLog() = runTest {
        val transport = fakeTransport()
        val logs = mutableListOf<CapturedLog>()
        val client = buildClient(transport = transport, logger = recordingLogger(logs))
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val job = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }

        client.connect()
        transport.injectPacket(MeshPacket(from = 0x3002, to = 0, id = 31))
        runCurrent()

        assertTrue(logs.any { it.level == LogLevel.WARN && it.message.contains("encrypted packet") })
        assertTrue(warnings.any { it.message.contains("encrypted packet") })

        job.cancel()
        client.disconnect()
    }

    @Test
    fun encryptedPacketWarningIsRateLimitedWithinInterval() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val job = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }

        client.connect()
        transport.injectPacket(MeshPacket(from = 0x3003, to = 0, id = 32))
        transport.injectPacket(MeshPacket(from = 0x3003, to = 0, id = 33))
        runCurrent()

        assertEquals(1, warnings.count { it.message.contains("encrypted packet") })

        job.cancel()
        client.disconnect()
    }

    @Test
    fun encryptedPacketWarningCarriesRateLimitedDetail() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val job = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }

        client.connect()
        transport.injectPacket(MeshPacket(from = 0x3004, to = 0, id = 34))
        runCurrent()

        val warning = warnings.last()
        assertTrue(warning.message.contains("encrypted packet"))
        assertEquals(true, warning.details["rate_limited"])

        job.cancel()
        client.disconnect()
    }

    @Test
    fun encryptedPacketDoesNotAckPendingSend() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport, sendTimeout = 5.seconds)

        client.connect()
        val handle = client.send(unicastWantAckPacket(toNodeNum = 0x3005))
        runCurrent()
        assertEquals(SendState.Sent, handle.state.value)

        transport.injectPacket(MeshPacket(from = 0x3005, to = 0, id = 37))
        runCurrent()
        assertEquals(SendState.Sent, handle.state.value)

        advanceTimeBy(6_000L)
        runCurrent()
        val state = handle.state.value
        assertIs<SendState.Failed>(state)
        assertEquals(SendFailure.AckTimeout, state.reason)

        client.disconnect()
    }

    @Test
    fun duplicateInboundTextPacketIsDeliveredOnce() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)
        val packets = mutableListOf<MeshPacket>()
        val job = backgroundScope.launch { client.packets.collect { packets += it } }

        val packet = inboundTextPacket(id = 40, from = 0x4001)
        client.connect()
        transport.injectPacket(packet)
        transport.injectPacket(packet)
        runCurrent()

        assertEquals(listOf(40), packets.map { it.id })

        job.cancel()
        client.disconnect()
    }

    @Test
    fun duplicatePacketDoesNotBlockDistinctPackets() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)
        val packets = mutableListOf<MeshPacket>()
        val job = backgroundScope.launch { client.packets.collect { packets += it } }

        val first = inboundTextPacket(id = 41, from = 0x4002, text = "first")
        val second = inboundTextPacket(id = 42, from = 0x4002, text = "second")
        client.connect()
        transport.injectPacket(first)
        transport.injectPacket(first)
        transport.injectPacket(second)
        runCurrent()

        assertEquals(listOf(41, 42), packets.map { it.id })

        job.cancel()
        client.disconnect()
    }

    @Test
    fun samePacketIdFromDifferentNodesIsNotDeduped() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)
        val packets = mutableListOf<MeshPacket>()
        val job = backgroundScope.launch { client.packets.collect { packets += it } }

        client.connect()
        transport.injectPacket(inboundTextPacket(id = 43, from = 0x4003))
        transport.injectPacket(inboundTextPacket(id = 43, from = 0x4004))
        runCurrent()

        assertEquals(listOf(0x4003, 0x4004), packets.map { it.from })

        job.cancel()
        client.disconnect()
    }

    @Test
    fun duplicateRoutingAckRemainsIdempotent() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport, sendTimeout = 60.seconds)

        client.connect()
        val handle = client.send(unicastWantAckPacket(toNodeNum = 0x4005))
        runCurrent()

        transport.injectFrame(routingAckFrame(requestId = handle.id.raw, fromNodeNum = 0x4005))
        runCurrent()
        assertEquals(SendState.Acked, handle.state.value)

        transport.injectFrame(routingAckFrame(requestId = handle.id.raw, fromNodeNum = 0x4005))
        runCurrent()
        assertEquals(SendState.Acked, handle.state.value)

        client.disconnect()
    }

    @Test
    fun disconnectSequenceAllowsFreshSessionReconnect() = runTest {
        val transport = fakeTransport()
        val firstClient = buildClient(transport = transport)

        firstClient.connect()
        firstClient.disconnect()
        assertEquals(ConnectionState.Disconnected, firstClient.connection.value)

        val secondClient = buildClient(transport = transport)
        secondClient.connect()

        assertEquals(ConnectionState.Connected, secondClient.connection.value)
        secondClient.disconnect()
    }

    @Test
    fun recoverableTransportErrorTransitionsThroughReconnectBackToConnected() = runTest {
        val transport = fakeTransport()
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(
                enabled = true,
                initialBackoff = 1.seconds,
                maxBackoff = 1.seconds,
                maxAttempts = 1,
                jitter = 0.0,
            ),
        )
        val states = mutableListOf<ConnectionState>()
        val job = backgroundScope.launch { client.connection.collect { states += it } }

        client.connect()
        transport.simulateError(IllegalStateException("link lost"), recoverable = true)
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(client.connection.value)

        repeat(6) {
            if (client.connection.value == ConnectionState.Connected) return@repeat
            advanceTimeBy(500L)
            runCurrent()
        }

        assertTrue(states.any { it is ConnectionState.Reconnecting })
        assertEquals(TransportState.Connected, transport.state.value)
        assertTrue(client.connection.value != ConnectionState.Disconnected)

        job.cancel()
        client.disconnect()
    }

    @Test
    fun nonRecoverableTransportErrorEndsDisconnected() = runTest {
        val transport = fakeTransport()
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(
                enabled = true,
                initialBackoff = 1.seconds,
                maxBackoff = 1.seconds,
                maxAttempts = 1,
                jitter = 0.0,
            ),
        )

        client.connect()
        transport.simulateError(IllegalStateException("fatal"), recoverable = false)
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    @Test
    fun concurrentSendTextCallsProduceUniqueIds() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)

        client.connect()
        val handles = (1..10).map { index ->
            backgroundScope.async { client.sendText("message-$index") }
        }.awaitAll()
        runCurrent()

        assertEquals(10, handles.map { it.id.raw }.distinct().size)
        // sendText sets want_ack=true; broadcasts await firmware implicit ACK so they stay in Sent.
        assertTrue(handles.all { it.state.value == SendState.Sent })

        // Inject implicit ACKs for all handles.
        handles.forEach { transport.injectRoutingAck(requestId = it.id.raw) }
        runCurrent()
        assertTrue(handles.all { it.state.value == SendState.Acked })

        client.disconnect()
    }

    @Test
    fun concurrentRawSendsAllProduceOutboundFrames() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport)

        client.connect()
        (1..8).map { index ->
            backgroundScope.async {
                client.send(
                    MeshPacket(
                        to = NodeId.BROADCAST.raw,
                        channel = 0,
                        decoded = Data(
                            portnum = PortNum.TEXT_MESSAGE_APP,
                            payload = ByteString.of(*"payload-$index".encodeToByteArray()),
                        ),
                    ),
                )
            }
        }.awaitAll()
        runCurrent()

        val outboundIds = textOutboundPackets(transport).map { it.id }
        assertEquals(8, outboundIds.size)
        assertEquals(8, outboundIds.distinct().size)

        client.disconnect()
    }

    @Test
    fun routingAckForOneConcurrentSendDoesNotAffectOthers() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport = transport, sendTimeout = 60.seconds)

        client.connect()
        val handles = (1..3).map { index ->
            client.send(unicastWantAckPacket(toNodeNum = 0x5000 + index))
        }
        runCurrent()

        val target = handles[1]
        transport.injectFrame(routingAckFrame(requestId = target.id.raw, fromNodeNum = 0x5002))
        runCurrent()

        assertEquals(SendState.Acked, target.state.value)
        assertEquals(SendState.Sent, handles[0].state.value)
        assertEquals(SendState.Sent, handles[2].state.value)

        client.disconnect()
    }

    private fun TestScope.buildClient(
        transport: RadioTransport = fakeTransport(),
        logger: LogSink = LogSink.Silent,
        autoReconnect: AutoReconnectConfig = AutoReconnectConfig.Disabled,
        sendTimeout: Duration = 30.seconds,
    ): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .logger(logger)
        .autoReconnect(autoReconnect)
        .sendTimeout(sendTimeout)
        .coroutineContext(backgroundScope.coroutineContext)
        .build()

    private fun fakeTransport() = FakeRadioTransport(
        identity = TransportIdentity("fake:edge-cases"),
        autoHandshake = true,
    )

    private fun recordingLogger(logs: MutableList<CapturedLog>): LogSink = LogSink { level, tag, message, cause ->
        logs += CapturedLog(level, tag, message, cause)
    }

    private fun inboundTextPacket(id: Int, from: Int, text: String = "hello") = MeshPacket(
        from = from,
        to = 0,
        id = id,
        channel = 0,
        decoded = Data(
            portnum = PortNum.TEXT_MESSAGE_APP,
            payload = ByteString.of(*text.encodeToByteArray()),
        ),
    )

    private fun unicastWantAckPacket(toNodeNum: Int) = MeshPacket(
        to = toNodeNum,
        channel = 0,
        want_ack = true,
        decoded = Data(
            portnum = PortNum.TEXT_MESSAGE_APP,
            payload = ByteString.of(*"hi".encodeToByteArray()),
        ),
    )

    private fun routingAckFrame(requestId: Int, fromNodeNum: Int, error: Routing.Error = Routing.Error.NONE): Frame {
        val routing = Routing(error_reason = error)
        val payload = ByteString.of(*Routing.ADAPTER.encode(routing))
        val packet = MeshPacket(
            from = fromNodeNum,
            to = 0,
            decoded = Data(
                portnum = PortNum.ROUTING_APP,
                payload = payload,
                request_id = requestId,
            ),
        )
        return rawFrame(encodedFromRadio(FromRadio(packet = packet)))
    }

    private fun rawFrame(
        payload: ByteArray,
        header0: Byte = WireFraming.MAGIC_0,
        header1: Byte = WireFraming.MAGIC_1,
        declaredLength: Int = payload.size,
    ): Frame {
        val bytes = ByteArray(WireFraming.HEADER_SIZE + payload.size)
        bytes[0] = header0
        bytes[1] = header1
        bytes[2] = (declaredLength shr 8).toByte()
        bytes[3] = (declaredLength and 0xFF).toByte()
        payload.copyInto(bytes, destinationOffset = WireFraming.HEADER_SIZE)
        return Frame(KByteString(bytes))
    }

    private fun encodedFromRadio(fromRadio: FromRadio): ByteArray = FromRadio.ADAPTER.encode(fromRadio)

    private fun textOutboundPackets(transport: FakeRadioTransport): List<MeshPacket> = transport.outboundFrames()
        .mapNotNull { decodeToRadioOrNull(it)?.packet }
        .filter { it.decoded?.portnum == PortNum.TEXT_MESSAGE_APP }

    private fun decodeToRadioOrNull(frame: Frame): ToRadio? {
        val bytes = frame.bytes.toByteArray()
        if (bytes.size < WireFraming.HEADER_SIZE) return null
        return runCatching {
            ToRadio.ADAPTER.decode(bytes.copyOfRange(WireFraming.HEADER_SIZE, bytes.size))
        }.getOrNull()
    }

    private data class CapturedLog(val level: LogLevel, val tag: String, val message: String, val cause: Throwable?)
}
