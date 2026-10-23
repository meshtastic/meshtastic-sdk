/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportSpec
import org.meshtastic.sdk.TransportState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Connect-cycle behaviour of [JSerialCommTransport] against an in-memory port: reuse across
 * `disconnect(); connect()` (the engine's auto-reconnect), back-pressure without drops,
 * unplug reported as a recoverable error, and bounded blocking port calls.
 */
class JSerialCommTransportLifecycleTest {

    private fun transport(port: FakeSerialPort, openTimeoutMs: Long = 5_000L) = JSerialCommTransport(
        port = port,
        identity = TransportIdentity.of(TransportSpec.SerialJvm(port.name)),
        baudRate = 115_200,
        openTimeoutMs = openTimeoutMs,
    )

    @Test
    fun transportReconnectsAfterDisconnect() = runBlocking(Dispatchers.Default) {
        val port = FakeSerialPort()
        val transport = transport(port)

        transport.connect()
        port.push(frameBytes(1))
        assertEquals(frame(1), withTimeout(TIMEOUT_MS) { transport.frames().first() })
        transport.disconnect()
        assertEquals(TransportState.Disconnected, transport.state.value)

        transport.connect()
        assertEquals(TransportState.Connected, transport.state.value)
        port.push(frameBytes(2))
        assertEquals(frame(2), withTimeout(TIMEOUT_MS) { transport.frames().first() })
        transport.disconnect()

        assertEquals(2, port.opens.get())
        assertTrue(port.closes.get() >= 2)
        assertFalse(port.isOpen)
    }

    @Test
    fun framesFlowCompletesOnDisconnect() = runBlocking(Dispatchers.Default) {
        val port = FakeSerialPort()
        val transport = transport(port)
        transport.connect()
        val collected = async { transport.frames().toList() }
        port.push(frameBytes(1))
        withTimeout(TIMEOUT_MS) { while (port.pending() > 0) delay(10) }

        transport.disconnect()

        assertEquals(TransportState.Disconnected, transport.state.value)
        assertTrue(withTimeout(TIMEOUT_MS) { collected.await() }.size <= 1)
    }

    @Test
    fun slowCollectorReceivesEveryFrame() = runBlocking(Dispatchers.Default) {
        val port = FakeSerialPort()
        val transport = transport(port)
        transport.connect()
        val total = 300 // several times the frame channel capacity
        repeat(total) { port.push(frameBytes(it)) }
        // Let the reader fill the channel and park before anyone collects.
        delay(SETTLE_MS)
        assertTrue(port.pending() > 0, "reader should be parked on the full frame channel")

        val received = withTimeout(TIMEOUT_MS) { transport.frames().take(total).toList() }

        assertEquals(List(total) { frame(it) }, received)
        transport.disconnect()
    }

    @Test
    fun unplugPublishesRecoverableErrorAndCompletesFrames() = runBlocking(Dispatchers.Default) {
        val port = FakeSerialPort()
        val transport = transport(port)
        transport.connect()
        val collected = async { transport.frames().toList() }
        port.push(frameBytes(7))

        withTimeout(TIMEOUT_MS) { while (port.pending() > 0) delay(10) }
        port.unplug()

        val error = withTimeout(TIMEOUT_MS) { transport.state.first { it is TransportState.Error } }
        assertIs<TransportState.Error>(error)
        assertTrue(error.recoverable)
        assertIs<MeshtasticException.Transport>(error.cause)
        assertEquals(listOf(frame(7)), withTimeout(TIMEOUT_MS) { collected.await() })

        // The engine's auto-reconnect path on the same instance.
        port.replug()
        transport.disconnect()
        transport.connect()
        assertEquals(TransportState.Connected, transport.state.value)
        transport.disconnect()
    }

    @Test
    fun disconnectIsNotReportedAsError() = runBlocking(Dispatchers.Default) {
        val port = FakeSerialPort(readFailsWhenClosed = true)
        val transport = transport(port)
        transport.connect()

        transport.disconnect()
        delay(SETTLE_MS)

        assertEquals(TransportState.Disconnected, transport.state.value)
    }

    @Test
    fun hangingOpenTimesOutAndLaterRecovers() = runBlocking(Dispatchers.Default) {
        val release = CountDownLatch(1)
        val port = FakeSerialPort(openGate = release)
        val transport = transport(port, openTimeoutMs = 200L)

        val first = assertFailsWith<MeshtasticException.Transport> { withTimeout(TIMEOUT_MS) { transport.connect() } }
        assertTrue(first.message.orEmpty().contains("timed out"))
        assertIs<TransportState.Error>(transport.state.value)

        // The stranded open still owns the port: a retry fails fast instead of piling up.
        val second = assertFailsWith<MeshtasticException.Transport> { withTimeout(TIMEOUT_MS) { transport.connect() } }
        assertTrue(second.message.orEmpty().contains("still blocked"))

        release.countDown()
        // Once the stranded open returns, its completion handler closes the orphaned port.
        withTimeout(TIMEOUT_MS) { while (port.opens.get() == 0 || port.isOpen) delay(10) }
        port.openGate = null

        transport.disconnect()
        transport.connect()
        assertEquals(TransportState.Connected, transport.state.value)
        transport.disconnect()
    }

    @Test
    fun cancelledConnectRethrowsCancellation() = runBlocking(Dispatchers.Default) {
        val release = CountDownLatch(1)
        val port = FakeSerialPort(openGate = release)
        val transport = transport(port)
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
        release.countDown()

        assertIs<CancellationException>(thrown)
        assertEquals(TransportState.Disconnected, transport.state.value)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 300L

        fun frameBytes(i: Int): ByteArray = byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0, 1, i.toByte())

        fun frame(i: Int): Frame = Frame(frameBytes(i).toByteString())
    }
}

/**
 * In-memory [SerialPortIo]. [push] queues inbound bytes, [unplug] makes reads return -1, and
 * [openGate] (when set) blocks [open] until it is released, like a port hung in the OS.
 */
internal class FakeSerialPort(
    @Volatile var openGate: CountDownLatch? = null,
    private val readFailsWhenClosed: Boolean = false,
) : SerialPortIo {
    val opens = AtomicInteger(0)
    val closes = AtomicInteger(0)
    val reads = AtomicInteger(0)
    private val inbound = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var unplugged = false

    @Volatile
    override var isOpen: Boolean = false
        private set

    override val name: String = "fake0"

    fun push(bytes: ByteArray) {
        inbound.put(bytes)
    }

    fun pending(): Int = inbound.size

    fun unplug() {
        unplugged = true
    }

    fun replug() {
        unplugged = false
    }

    override fun open(baudRate: Int, readTimeoutMs: Int): Boolean {
        openGate?.await()
        opens.incrementAndGet()
        isOpen = true
        return true
    }

    override fun flushBuffers() = Unit

    override fun write(bytes: ByteArray) {
        check(isOpen) { "write on closed port" }
    }

    override fun read(buffer: ByteArray): Int {
        if (unplugged) return -1
        if (!isOpen) {
            if (readFailsWhenClosed) error("read on closed port")
            return -1
        }
        val chunk = inbound.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: return 0
        chunk.copyInto(buffer)
        reads.incrementAndGet()
        return chunk.size
    }

    override fun close() {
        closes.incrementAndGet()
        isOpen = false
    }

    private companion object {
        const val POLL_MS = 20L
    }
}
