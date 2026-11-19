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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.TransportState
import org.meshtastic.sdk.WireFraming
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
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

    @Test
    fun close4001IsNotRecoverable() = run {
        val e = closeCodeCase(4001)
        assertFalse(e.recoverable)
        assertTrue(e.cause.message!!.contains("another client took the node's only WebSocket slot"))
    }

    @Test
    fun close1009IsNotRecoverable() = run {
        val e = closeCodeCase(1009)
        assertFalse(e.recoverable)
        assertTrue(e.cause.message!!.contains("512"))
    }

    @Test
    fun close1000IsRecoverable() = run { assertTrue(closeCodeCase(1000).recoverable) }

    @Test
    fun close1013IsRecoverable() = run { assertTrue(closeCodeCase(1013).recoverable) }

    @Test
    fun abruptSocketDropIsRecoverable() = run {
        val raw = RawServer { it.close() }.also { servers += it::stop }
        val t = transportFor(raw.port)
        t.connect()
        val e = t.awaitError()
        assertTrue(e.recoverable)
        assertTrue(e.cause.message!!.contains("without a close frame") || e.cause.message!!.contains("closed"))
        t.disconnect()
    }

    @Test
    fun upgradeRefusedWith404NamesTheStatus() = run {
        val s = NoRouteServer().also { servers += it::stop }
        val t = transportFor(s.port)
        val ex = assertFailsWith<MeshtasticException.Transport> { t.connect() }
        assertTrue(ex.message!!.contains("404"), ex.message)
        assertIs<TransportState.Error>(t.state.value)
    }

    @Test
    fun unreachableHostThrowsTransportException() = run {
        val port = ServerSocket(0).use { it.localPort }
        val t = transportFor(port)
        val ex = assertFailsWith<MeshtasticException.Transport> { t.connect() }
        assertTrue(ex.message!!.contains("could not reach"), ex.message)
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
}
