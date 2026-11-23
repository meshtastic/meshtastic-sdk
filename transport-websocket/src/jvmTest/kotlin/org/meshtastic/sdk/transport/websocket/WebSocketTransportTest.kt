/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.TransportState
import org.meshtastic.sdk.WireFraming
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.websocket.Frame as WsFrame

class WebSocketTransportTest {

    private class TestServer(handler: suspend DefaultWebSocketServerSession.() -> Unit) {
        val subprotocol = CompletableDeferred<String?>()
        private val engine: EmbeddedServer<*, *> = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/api/v1/ws") {
                    subprotocol.complete(call.request.headers["Sec-WebSocket-Protocol"])
                    handler()
                }
            }
        }.start(wait = false)
        val port: Int = runBlocking { engine.engine.resolvedConnectors().first().port }
        fun stop() = engine.stop(0, 0)
    }

    private class NoRouteServer {
        private val engine: EmbeddedServer<*, *> = embeddedServer(ServerCIO, port = 0) {}.start(wait = false)
        val port: Int = runBlocking { engine.engine.resolvedConnectors().first().port }
        fun stop() = engine.stop(0, 0)
    }

    /** Completes the WebSocket upgrade by hand so a test can then drop the socket or stall. */
    private class RawServer(private val afterUpgrade: ((java.net.Socket) -> Unit)?) {
        private val socket = ServerSocket(0)
        val port: Int = socket.localPort
        private val worker = Thread {
            try {
                val client = socket.accept()
                if (afterUpgrade == null) {
                    Thread.sleep(30_000)
                    return@Thread
                }
                val input = client.getInputStream().bufferedReader()
                var key = ""
                while (true) {
                    val line = input.readLine() ?: return@Thread
                    if (line.isEmpty()) break
                    if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(':').trim()
                }
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance(
                        "SHA-1",
                    ).digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                )
                client.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: $accept\r\n\r\n"
                            ).toByteArray(),
                    )
                    flush()
                }
                afterUpgrade.invoke(client)
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        fun stop() = socket.close()
    }

    /** Reads the upgrade request, holds the 101 until [gate] completes, and reports when the client socket closes. */
    private class GatedServer {
        val gate = CompletableDeferred<Unit>()
        val requestSeen = CompletableDeferred<Unit>()
        val clientClosed = CompletableDeferred<Unit>()
        private val socket = ServerSocket(0)
        val port: Int = socket.localPort
        private val worker = Thread {
            try {
                val client = socket.accept()
                val input = client.getInputStream().bufferedReader()
                var key = ""
                while (true) {
                    val line = input.readLine() ?: return@Thread
                    if (line.isEmpty()) break
                    if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(':').trim()
                }
                requestSeen.complete(Unit)
                Thread {
                    try {
                        while (input.read() != -1) {
                        }
                    } catch (_: Exception) {
                    }
                    clientClosed.complete(Unit)
                }.apply {
                    isDaemon = true
                    start()
                }
                runBlocking { gate.await() }
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance(
                        "SHA-1",
                    ).digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                )
                client.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: $accept\r\n\r\n"
                            ).toByteArray(),
                    )
                    flush()
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        fun stop() = socket.close()
    }

    private val servers = mutableListOf<() -> Unit>()
    private val clients = mutableListOf<HttpClient>()

    @AfterTest
    fun tearDown() {
        servers.forEach { runCatching { it() } }
        clients.forEach { runCatching { it.close() } }
    }

    private fun server(handler: suspend DefaultWebSocketServerSession.() -> Unit): TestServer =
        TestServer(handler).also { servers += it::stop }

    private fun transportFor(port: Int): WebSocketTransport = WebSocketTransport("ws://127.0.0.1:$port")

    private fun framed(payload: ByteArray): Frame = Frame(WebSocketTransport.withHeader(payload))

    private suspend fun WebSocketTransport.awaitError(): TransportState.Error =
        withTimeout(10_000) { state.first { it is TransportState.Error } } as TransportState.Error

    private fun run(block: suspend CoroutineScope.() -> Any?) {
        runBlocking(Dispatchers.Default) { withTimeout(30_000) { block() } }
    }

    @Test
    fun sendStripsHeaderAndReceivePrependsHeader() = run {
        val received = Channel<ByteArray>(Channel.UNLIMITED)
        val s = server {
            received.send((incoming.receive() as WsFrame.Binary).readBytes())
            send(WsFrame.Binary(true, byteArrayOf(9, 8, 7)))
            incoming.receiveCatching()
        }
        val t = transportFor(s.port)
        t.connect()
        assertEquals(TransportState.Connected, t.state.value)
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        t.send(framed(payload))
        assertContentEquals(payload, withTimeout(5_000) { received.receive() })
        val frame = withTimeout(5_000) { t.frames().first() }
        assertEquals(byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0, 3, 9, 8, 7).toByteString(), frame.bytes)
        t.disconnect()
    }

    @Test
    fun textMessagesAreIgnored() = run {
        val s = server {
            send(WsFrame.Text("hello"))
            send(WsFrame.Binary(true, byteArrayOf(42)))
            incoming.receiveCatching()
        }
        val t = transportFor(s.port)
        t.connect()
        val frame = withTimeout(5_000) { t.frames().first() }
        assertEquals(byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0, 1, 42).toByteString(), frame.bytes)
        t.disconnect()
    }

    @Test
    fun oversizeSendIsRejectedWithoutReachingTheWire() = run {
        val received = Channel<ByteArray>(Channel.UNLIMITED)
        val s = server {
            for (m in incoming) received.send((m as WsFrame.Binary).readBytes())
        }
        val t = transportFor(s.port)
        t.connect()
        val ex = assertFailsWith<MeshtasticException.Transport> {
            t.send(framed(ByteArray(WireFraming.MAX_PAYLOAD_SIZE + 1)))
        }
        assertTrue(ex.message!!.contains("512"))
        delay(300)
        assertTrue(received.tryReceive().isFailure)
        t.send(framed(ByteArray(WireFraming.MAX_PAYLOAD_SIZE)))
        assertEquals(WireFraming.MAX_PAYLOAD_SIZE, withTimeout(5_000) { received.receive() }.size)
        t.disconnect()
    }

    private suspend fun closeCodeCase(code: Short): TransportState.Error {
        val s = server {
            delay(100)
            close(CloseReason(code, "bye"))
        }
        val t = transportFor(s.port)
        t.connect()
        val error = t.awaitError()
        t.disconnect()
        return error
    }

    private val TransportState.Error.typed: WebSocketTransportException
        get() = assertIs<WebSocketTransportException>(cause)

    @Test
    fun close4001IsNotRecoverable() = run {
        val e = closeCodeCase(4001)
        assertFalse(e.recoverable)
        assertTrue(e.cause.message!!.contains("another client took the node's only WebSocket slot"))
        assertEquals(4001, e.typed.closeCode)
        assertEquals("bye", e.typed.closeReason)
        assertFalse(e.typed.slotContested)
    }

    @Test
    fun close1009IsNotRecoverable() = run {
        val e = closeCodeCase(1009)
        assertFalse(e.recoverable)
        assertTrue(e.cause.message!!.contains("512"))
        assertEquals(1009, e.typed.closeCode)
    }

    @Test
    fun close1000IsRecoverable() = run {
        val e = closeCodeCase(1000)
        assertTrue(e.recoverable)
        assertEquals(1000, e.typed.closeCode)
    }

    @Test
    fun close1013IsRecoverable() = run {
        val e = closeCodeCase(1013)
        assertTrue(e.recoverable)
        assertEquals(1013, e.typed.closeCode)
        assertEquals("bye", e.typed.closeReason)
    }

    @Test
    fun abruptSocketDropIsRecoverable() = run {
        val raw = RawServer { it.close() }.also { servers += it::stop }
        val t = transportFor(raw.port)
        t.connect()
        val e = t.awaitError()
        assertTrue(e.recoverable)
        assertTrue(e.cause.message!!.contains("without a close frame") || e.cause.message!!.contains("closed"))
        assertTrue(e.typed.closeCode == null || e.typed.closeCode == 1006, "closeCode=${e.typed.closeCode}")
        assertNull(e.typed.httpStatus)
        t.disconnect()
    }

    @Test
    fun upgradeRefusedWith404NamesTheStatus() = run {
        val s = NoRouteServer().also { servers += it::stop }
        val t = transportFor(s.port)
        val ex = assertFailsWith<MeshtasticException.Transport> { t.connect() }
        assertTrue(ex.message!!.contains("404"), ex.message)
        assertEquals(404, assertIs<WebSocketTransportException>(ex).httpStatus)
        assertNull(ex.closeCode)
        assertEquals(ex, assertIs<TransportState.Error>(t.state.value).cause)
    }

    @Test
    fun unreachableHostThrowsTransportException() = run {
        val port = ServerSocket(0).use { it.localPort }
        val t = transportFor(port)
        val ex = assertFailsWith<MeshtasticException.Transport> { t.connect() }
        assertTrue(ex.message!!.contains("could not reach"), ex.message)
        assertNull(assertIs<WebSocketTransportException>(ex).httpStatus)
    }

    @Test
    fun noSubprotocolHeaderIsSent() = run {
        val s = server { incoming.receiveCatching() }
        val t = transportFor(s.port)
        t.connect()
        assertNull(withTimeout(5_000) { s.subprotocol.await() })
        t.disconnect()
    }

    @Test
    fun disconnectDuringOpenSessionClosesNormallyAndIsIdempotent() = run {
        val closed = CompletableDeferred<CloseReason?>()
        val s = server {
            incoming.consumeEach { }
            closed.complete(closeReason.await())
        }
        val t = transportFor(s.port)
        t.connect()
        t.disconnect()
        assertEquals(TransportState.Disconnected, t.state.value)
        assertEquals(CloseReason.Codes.NORMAL.code, withTimeout(5_000) { closed.await() }!!.code)
        t.disconnect()
        assertEquals(TransportState.Disconnected, t.state.value)
    }

    @Test
    fun sendBeforeConnectAndAfterDisconnectThrowTransportException() = run {
        val s = server { incoming.receiveCatching() }
        val t = transportFor(s.port)
        assertFailsWith<MeshtasticException.Transport> { t.send(framed(byteArrayOf(1))) }
        t.connect()
        t.disconnect()
        assertFailsWith<MeshtasticException.Transport> { t.send(framed(byteArrayOf(1))) }
    }

    @Test
    fun cancellingConnectMidHandshakeLeavesNoConnectingState() = run {
        val raw = RawServer(null).also { servers += it::stop }
        val t = transportFor(raw.port)
        val job = launch(Dispatchers.Default) { t.connect() }
        withTimeout(5_000) { t.state.first { it == TransportState.Connecting } }
        delay(300)
        job.cancelAndJoin()
        assertEquals(TransportState.Disconnected, t.state.value)
    }

    @Test
    fun suppliedClientIsNotClosedByDisconnect() = run {
        val s = server { incoming.receiveCatching() }
        val client = HttpClient(CIO) { install(WebSockets) }.also { clients += it }
        val t = WebSocketTransport("ws://127.0.0.1:${s.port}", client)
        t.connect()
        t.disconnect()
        assertTrue(client.coroutineContext[Job]!!.isActive)
        t.connect()
        assertEquals(TransportState.Connected, t.state.value)
        t.disconnect()
    }

    @Test
    fun reconnectAfterErrorDeliversFramesOnTheSameFlow() = run {
        val sessions = Channel<Unit>(Channel.UNLIMITED)
        val s = server {
            sessions.send(Unit)
            send(WsFrame.Binary(true, byteArrayOf(7)))
            incoming.receiveCatching()
        }
        val t = transportFor(s.port)
        val collected = Channel<Frame>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { t.frames().collect { collected.send(it) } }
        t.connect()
        withTimeout(5_000) { collected.receive() }
        t.disconnect()
        t.connect()
        withTimeout(5_000) { collected.receive() }
        t.disconnect()
        collector.cancelAndJoin()
    }

    @Test
    fun cancellingConnectClosesTheUpgradeOnASuppliedClient() = run {
        val gated = GatedServer()
        servers += gated::stop
        val client = HttpClient(CIO) { install(WebSockets) }.also { clients += it }
        val t = WebSocketTransport("ws://127.0.0.1:${gated.port}", client)
        val job = launch(Dispatchers.Default) { t.connect() }
        withTimeout(5_000) { gated.requestSeen.await() }
        job.cancelAndJoin()
        withTimeout(5_000) { gated.clientClosed.await() }
        assertEquals(TransportState.Disconnected, t.state.value)
        assertTrue(client.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun disconnectDuringUpgradeFailsConnectAndStaysDisconnected() = run {
        val gated = GatedServer()
        servers += gated::stop
        val client = HttpClient(CIO) { install(WebSockets) }.also { clients += it }
        val t = WebSocketTransport("ws://127.0.0.1:${gated.port}", client)
        val connecting = async(Dispatchers.Default) { runCatching { t.connect() } }
        withTimeout(5_000) { gated.requestSeen.await() }
        t.disconnect()
        gated.gate.complete(Unit)
        val outcome = withTimeout(10_000) { connecting.await() }
        assertIs<MeshtasticException.Transport>(outcome.exceptionOrNull())
        assertEquals(TransportState.Disconnected, t.state.value)
        withTimeout(5_000) { gated.clientClosed.await() }
        assertTrue(client.coroutineContext[Job]!!.isActive)
    }

    @Test
    fun stalePumpEndingAfterReconnectLeavesTheNewSession() = run {
        val sessions = java.util.concurrent.atomic.AtomicInteger()
        val s = server {
            if (sessions.incrementAndGet() == 1) {
                close(CloseReason(1013, "oom"))
            } else {
                incoming.receiveCatching()
            }
        }
        val t = transportFor(s.port)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        t.pumpProbe = { phase ->
            if (phase == "ending" && entered.complete(Unit)) {
                withContext(NonCancellable) { release.await() }
            }
            if (phase == "ended") ended.complete(Unit)
        }
        t.connect()
        withTimeout(10_000) { entered.await() }
        t.disconnect()
        t.connect()
        release.complete(Unit)
        withTimeout(10_000) { ended.await() }
        assertEquals(TransportState.Connected, t.state.value)
        t.disconnect()
    }

    @Test
    fun messageOverFirmwareLimitFailsTheSessionWithoutDelivery() = run {
        val s = server {
            send(WsFrame.Binary(true, ByteArray(WireFraming.MAX_PAYLOAD_SIZE + 1)))
            incoming.receiveCatching()
        }
        val t = transportFor(s.port)
        t.connect()
        val e = t.awaitError()
        assertFalse(e.recoverable)
        assertTrue(e.cause.message!!.contains("512"))
        assertEquals(1009, e.typed.closeCode)
        assertTrue(t.frames().let { withTimeoutOrNull(300) { it.first() } } == null)
        t.disconnect()
    }

    @Test
    fun messageOverTheClientFrameBoundIsNotDelivered() = run {
        val s = server {
            send(WsFrame.Binary(true, ByteArray(MAX_INBOUND_FRAME_BYTES.toInt() * 2)))
            incoming.receiveCatching()
        }
        val t = transportFor(s.port)
        t.connect()
        val e = t.awaitError()
        assertFalse(e.recoverable)
        assertTrue(e.cause.message!!.contains("512"))
        assertTrue(t.frames().let { withTimeoutOrNull(300) { it.first() } } == null)
        t.disconnect()
    }

    @Test
    fun overBoundMessageAlwaysEndsTheSessionWithTheOversizeError() = run {
        val s = server {
            send(WsFrame.Binary(true, ByteArray(MAX_INBOUND_FRAME_BYTES.toInt() * 2)))
            incoming.receiveCatching()
        }
        repeat(300) {
            val t = transportFor(s.port)
            t.connect()
            val e = t.awaitError()
            assertFalse(e.recoverable)
            assertTrue(e.cause.message!!.contains("512"))
            t.disconnect()
        }
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val dispatched = CompletableDeferred<Unit>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
            dispatched.complete(Unit)
        }
    }

    @Test
    fun connectCancelledBeforeItsAttemptStartsFailsInsteadOfHanging() = run {
        val dispatcher = ManualDispatcher()
        val t = transportFor(1)
        t.attemptContext = dispatcher
        val connecting = async(Dispatchers.Default) { runCatching { t.connect() } }
        withTimeout(5_000) { dispatcher.dispatched.await() }
        t.disconnect()
        while (true) (dispatcher.queue.poll() ?: break).run()
        val outcome = withTimeout(10_000) { connecting.await() }
        assertIs<MeshtasticException.Transport>(outcome.exceptionOrNull())
        assertEquals(TransportState.Disconnected, t.state.value)
    }

    @Test
    fun concurrentConnectCancelsTheEarlierAttempt() = run {
        val gated = GatedServer()
        servers += gated::stop
        val client = HttpClient(CIO) { install(WebSockets) }.also { clients += it }
        val t = WebSocketTransport("ws://127.0.0.1:${gated.port}", client)
        val first = CompletableDeferred<Unit>()
        val resumeFirst = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        t.connectProbe = { phase ->
            if (phase == "entering" && calls.incrementAndGet() == 1) {
                first.complete(Unit)
                resumeFirst.await()
            }
        }
        val a = async(Dispatchers.Default) { runCatching { t.connect() } }
        withTimeout(5_000) { first.await() }
        val b = async(Dispatchers.Default) { runCatching { t.connect() } }
        withTimeout(5_000) { gated.requestSeen.await() }
        resumeFirst.complete(Unit)
        val outcome = withTimeout(10_000) { b.await() }
        assertIs<MeshtasticException.Transport>(outcome.exceptionOrNull())
        t.disconnect()
        withTimeout(10_000) { a.await() }
    }

    @Test
    fun cancellationAfterUpgradeClosesTheSessionAndLeavesDisconnected() = run {
        val closed = CompletableDeferred<Unit>()
        val s = server {
            incoming.consumeEach { }
            closed.complete(Unit)
        }
        val t = transportFor(s.port)
        val entered = CompletableDeferred<Unit>()
        t.connectProbe = { phase ->
            if (phase == "upgraded") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch(Dispatchers.Default) { t.connect() }
        withTimeout(5_000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(TransportState.Disconnected, t.state.value)
        withTimeout(5_000) { closed.await() }
    }

    /** Serves one scripted connection per mode: drop or close after a permit, or refuse the upgrade. */
    private class ScriptedServer(private val modes: java.util.concurrent.ConcurrentLinkedQueue<String>) {
        val permits = java.util.concurrent.Semaphore(0)
        private val socket = ServerSocket(0)
        val port: Int = socket.localPort
        private val worker = Thread {
            try {
                while (true) {
                    val client = socket.accept()
                    val input = client.getInputStream().bufferedReader()
                    var key = ""
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                            key = line.substringAfter(':').trim()
                        }
                    }
                    val mode = modes.poll() ?: "drop"
                    val out = client.getOutputStream()
                    if (mode == "refuse") {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        out.flush()
                        client.close()
                        continue
                    }
                    val accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance(
                            "SHA-1",
                        ).digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                    )
                    out.write(
                        (
                            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: $accept\r\n\r\n"
                            ).toByteArray(),
                    )
                    out.flush()
                    permits.acquire()
                    if (mode == "close") {
                        out.write(byteArrayOf(0x88.toByte(), 2, 0x03, 0xE8.toByte()))
                        out.flush()
                    }
                    client.close()
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        fun stop() = socket.close()
    }

    private class Rig(modes: List<String>) {
        val clock = java.util.concurrent.atomic.AtomicLong(1_000)
        val queue = java.util.concurrent.ConcurrentLinkedQueue(modes)
        val server = ScriptedServer(queue)
        val transport = WebSocketTransport("ws://127.0.0.1:${server.port}").also { it.clockMs = { clock.get() } }

        /** Opens a session, lets [lastsMs] pass on the test clock, then ends it as the next mode says. */
        suspend fun session(lastsMs: Long = 100): TransportState.Error {
            transport.connect()
            clock.addAndGet(lastsMs)
            server.permits.release()
            return withTimeout(10_000) { transport.state.first { it is TransportState.Error } } as TransportState.Error
        }
    }

    private fun rig(vararg modes: String): Rig = Rig(modes.toList()).also { servers += it.server::stop }

    @Test
    fun fiveQuickDropsInARowAreNotRecoverableAndFourAre() = run {
        val r = rig("drop", "drop", "drop", "drop", "drop")
        repeat(4) {
            val e = r.session()
            assertTrue(e.recoverable)
            assertFalse(e.typed.slotContested)
        }
        val fifth = r.session()
        assertFalse(fifth.recoverable)
        assertTrue(fifth.cause.message!!.contains("only WebSocket slot"))
        assertTrue(fifth.typed.slotContested)
        r.transport.disconnect()
    }

    @Test
    fun aSessionOfThirtySecondsResetsTheDropCount() = run {
        val r = rig("drop", "drop", "drop", "drop", "drop", "drop", "drop", "drop", "drop", "drop")
        repeat(4) { assertTrue(r.session().recoverable) }
        assertTrue(r.session(lastsMs = 30_000).recoverable)
        repeat(4) { assertTrue(r.session().recoverable) }
        assertFalse(r.session().recoverable)
        r.transport.disconnect()
    }

    @Test
    fun aSessionEndedByACloseFrameResetsTheDropCount() = run {
        val r = rig("drop", "drop", "drop", "drop", "close", "drop", "drop", "drop", "drop", "drop")
        repeat(4) { assertTrue(r.session().recoverable) }
        assertTrue(r.session().recoverable)
        repeat(4) { assertTrue(r.session().recoverable) }
        assertFalse(r.session().recoverable)
        r.transport.disconnect()
    }

    @Test
    fun aConnectThatNeverOpenedIsNeitherCountedNorResets() = run {
        val r = rig("drop", "drop", "drop", "refuse", "refuse", "drop", "drop")
        repeat(3) { assertTrue(r.session().recoverable) }
        repeat(2) { assertFailsWith<MeshtasticException.Transport> { r.transport.connect() } }
        assertTrue(r.session().recoverable)
        assertFalse(r.session().recoverable)
        r.transport.disconnect()
    }

    @Test
    fun hostDisconnectOfALiveSessionResetsTheDropCount() = run {
        val r = rig("drop", "drop", "drop", "drop", "drop", "drop", "drop", "drop", "drop", "drop")
        repeat(4) { assertTrue(r.session().recoverable) }
        r.transport.connect()
        r.transport.disconnect()
        r.server.permits.release()
        repeat(4) { assertTrue(r.session().recoverable) }
        assertFalse(r.session().recoverable)
        r.transport.disconnect()
    }

    @Test
    fun disconnectAfterAnEndedSessionKeepsTheDropCountAsTheEngineReconnects() = run {
        val r = rig("drop", "drop", "drop", "drop", "drop")
        repeat(4) {
            assertTrue(r.session().recoverable)
            r.transport.disconnect()
        }
        val fifth = r.session()
        assertFalse(fifth.recoverable)
        assertTrue(fifth.typed.slotContested)
        r.transport.disconnect()
    }

    private class FloorProbe {
        val requested = java.util.concurrent.atomic.AtomicLong(-1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val hook: suspend (Long) -> Unit = { ms ->
            requested.set(ms)
            started.complete(Unit)
            try {
                release.await()
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
        }
    }

    private data class FloorRig(
        val transport: WebSocketTransport,
        val clock: java.util.concurrent.atomic.AtomicLong,
        val probe: FloorProbe,
    )

    private fun closeCodeRig(code: Short): FloorRig {
        val s = server {
            delay(50)
            close(CloseReason(code, "bye"))
        }
        val clock = java.util.concurrent.atomic.AtomicLong(5_000)
        val probe = FloorProbe()
        val t = transportFor(s.port).also {
            it.clockMs = { clock.get() }
            it.floorDelay = probe.hook
        }
        return FloorRig(t, clock, probe)
    }

    @Test
    fun connectAfter1013WaitsUntilThirtySecondsPastTheEnd() = run {
        val (t, clock, probe) = closeCodeRig(1013)
        t.connect()
        t.awaitError()
        clock.addAndGet(10_000)
        val next = async(Dispatchers.Default) { t.connect() }
        withTimeout(5_000) { probe.started.await() }
        assertEquals(20_000L, probe.requested.get())
        assertEquals(TransportState.Connecting, t.state.value)
        probe.release.complete(Unit)
        withTimeout(10_000) { next.await() }
        assertEquals(TransportState.Connected, t.state.value)
        t.disconnect()
    }

    @Test
    fun disconnectDuringThe1013WaitEndsItCleanly() = run {
        val (t, _, probe) = closeCodeRig(1013)
        t.connect()
        t.awaitError()
        val next = async(Dispatchers.Default) { runCatching { t.connect() } }
        withTimeout(5_000) { probe.started.await() }
        t.disconnect()
        val outcome = withTimeout(10_000) { next.await() }
        assertIs<MeshtasticException.Transport>(outcome.exceptionOrNull())
        withTimeout(5_000) { probe.cancelled.await() }
        assertEquals(TransportState.Disconnected, t.state.value)
    }

    @Test
    fun noWaitAfterOtherCloseCodes() = run {
        for (code in listOf<Short>(1000, 1001, 1011)) {
            val (t, _, probe) = closeCodeRig(code)
            t.connect()
            t.awaitError()
            t.connect()
            assertEquals(-1L, probe.requested.get())
            t.disconnect()
        }
    }
}
