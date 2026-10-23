/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.tcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.TransportState
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Link-loss contract of [TcpTransport] against a loopback server: a peer close (the firmware's
 * idle timeout, a radio reboot) becomes a recoverable [TransportState.Error] and completes
 * `frames()` (protocol.md §1A), our own disconnect is not an error, and the instance reconnects.
 */
class TcpTransportLinkLossTest {

    private lateinit var server: ServerSocket
    private val accepted = LinkedBlockingQueue<Socket>()

    @BeforeTest
    fun startServer() {
        server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(name = "tcp-test-server", isDaemon = true) {
            while (!server.isClosed) {
                runCatching { accepted.put(server.accept()) }
            }
        }
    }

    @AfterTest
    fun stopServer() {
        server.close()
        accepted.forEach { runCatching { it.close() } }
    }

    private fun transport() = TcpTransport(LOOPBACK, server.localPort)

    /**
     * Next accepted connection, with the transport's 4 wake bytes already read: closing a socket
     * with unread input sends RST instead of FIN, which is not the clean close under test.
     */
    private fun nextPeer(): Socket {
        val peer = checkNotNull(accepted.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "server did not accept a connection"
        }
        peer.soTimeout = TIMEOUT_MS.toInt()
        val wake = ByteArray(WAKE_BYTE_COUNT)
        var read = 0
        while (read < wake.size) {
            val n = peer.getInputStream().read(wake, read, wake.size - read)
            check(n > 0) { "peer closed before the wake bytes arrived" }
            read += n
        }
        return peer
    }

    @Test
    fun peerClosePublishesRecoverableErrorAndCompletesFrames() = runBlocking(Dispatchers.Default) {
        val transport = transport()
        transport.connect()
        val peer = nextPeer()
        val collected = async { transport.frames().toList() }

        peer.getOutputStream().apply {
            write(FRAME_BYTES)
            flush()
        }
        peer.close()

        assertEquals(listOf(Frame(FRAME_BYTES.toByteString())), withTimeout(TIMEOUT_MS) { collected.await() })
        val state = transport.state.value
        assertIs<TransportState.Error>(state)
        assertTrue(state.recoverable)
        assertIs<MeshtasticException.Transport>(state.cause)
        transport.disconnect()
    }

    @Test
    fun disconnectIsNotReportedAsError() = runBlocking(Dispatchers.Default) {
        val transport = transport()
        transport.connect()
        nextPeer()
        val collected = async { transport.frames().toList() }
        delay(SETTLE_MS) // let the collector park in a read

        transport.disconnect()
        withTimeout(TIMEOUT_MS) { collected.await() }
        delay(SETTLE_MS)

        assertEquals(TransportState.Disconnected, transport.state.value)
    }

    @Test
    fun transportReconnectsAfterPeerClose() = runBlocking(Dispatchers.Default) {
        val transport = transport()
        transport.connect()
        nextPeer().close()
        withTimeout(TIMEOUT_MS) { transport.frames().toList() }
        assertIs<TransportState.Error>(transport.state.value)

        // The engine's auto-reconnect path on the same instance.
        transport.disconnect()
        transport.connect()
        assertEquals(TransportState.Connected, transport.state.value)
        val peer = nextPeer()
        peer.getOutputStream().apply {
            write(FRAME_BYTES)
            flush()
        }
        assertEquals(Frame(FRAME_BYTES.toByteString()), withTimeout(TIMEOUT_MS) { transport.frames().first() })
        transport.disconnect()
    }

    @Test
    fun cancelledConnectRethrowsCancellation() = runBlocking(Dispatchers.Default) {
        val transport = transport()
        var thrown: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                transport.connect()
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        job.cancel()
        job.join()

        // If connect() had not finished when it was cancelled, the cancellation must come back
        // as-is rather than wrapped in a transport error, and the state must not stay Connecting.
        thrown?.let { assertIs<CancellationException>(it) }
        assertNotEquals<TransportState>(TransportState.Connecting, transport.state.value)
        transport.disconnect()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 200L
        const val WAKE_BYTE_COUNT = 4

        val FRAME_BYTES = byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0, 2, 0x08, 0x01)
    }
}
