/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.LogLevel
import org.meshtastic.sdk.LogSink
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportState
import org.meshtastic.sdk.WireFraming
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * jSerialComm-backed [RadioTransport] for JVM and Android. Configures 115200 8N1
 * blocking reads and wires the InputStream into [SerialFrameAssembler].
 *
 * [frames] is buffered to [FRAME_CHANNEL_CAPACITY]; overflow increments [droppedFrameCount].
 * [disconnect] is idempotent and joins the reader before closing the port.
 */
internal class JSerialCommTransport(
    private val port: SerialPort,
    override val identity: TransportIdentity,
    private val baudRate: Int,
    private val logger: LogSink = LogSink.Silent,
    private val parentContext: CoroutineContext = EmptyCoroutineContext,
) : RadioTransport {

    private val _state: MutableStateFlow<TransportState> =
        MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val frameChannel = Channel<Frame>(capacity = FRAME_CHANNEL_CAPACITY)
    private var readerScope: CoroutineScope? = null
    private var readerJob: Job? = null

    /** Idempotent cleanup guard. */
    private val cleanedUp = AtomicBoolean(false)

    private val publisher = FrameChannelPublisher(
        target = frameChannel,
        onDrop = { total ->
            if (total == 1L || total % DROP_LOG_EVERY == 0L) {
                logger.log(
                    LogLevel.WARN,
                    TAG,
                    "Frame channel full; dropped frame " +
                        "(total dropped=$total, capacity=$FRAME_CHANNEL_CAPACITY). " +
                        "Slow consumer on frames()?",
                    null,
                )
            }
        },
    )

    /**
     * Total frames dropped because [frameChannel] was full.
     */
    internal val droppedFrameCount: AtomicLong get() = publisher.dropCount

    override suspend fun connect() {
        check(!cleanedUp.get()) { "JSerialCommTransport has been disconnected; construct a new instance" }
        _state.value = TransportState.Connecting
        logger.log(LogLevel.DEBUG, TAG, "Connecting to ${port.systemPortName} at $baudRate baud", null)
        try {
            port.baudRate = baudRate
            port.numDataBits = DATA_BITS_8
            port.parity = SerialPort.NO_PARITY
            port.numStopBits = SerialPort.ONE_STOP_BIT
            port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
            port.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                READ_TIMEOUT_MS,
                /* writeTimeout = */
                0,
            )

            if (!port.openPort()) {
                throw MeshtasticException.Transport(
                    "Serial open failed for ${port.systemPortName}",
                )
            }

            // Purge stale bytes from a previous session so the framer starts clean.
            // Do NOT toggle DTR/RTS — on native USB-CDC boards that triggers a
            // SET_CONTROL_LINE_STATE reset. jSerialComm leaves them asserted by default.
            port.flushIOBuffers()

            input = port.inputStream
            output = port.outputStream

            // 4× START1 wake bytes per protocol.md §2 resync FSM.
            output?.write(WAKE_BYTES)
            output?.flush()

            val scope = CoroutineScope(parentContext + SupervisorJob() + Dispatchers.IO)
            readerScope = scope
            readerJob = scope.launch { runReader() }

            _state.value = TransportState.Connected
            logger.log(LogLevel.INFO, TAG, "Connected to ${port.systemPortName}", null)
        } catch (e: MeshtasticException) {
            _state.value = TransportState.Error(e, recoverable = true)
            cleanup()
            throw e
        } catch (e: Exception) {
            _state.value = TransportState.Error(e, recoverable = true)
            cleanup()
            throw MeshtasticException.Transport("Serial connect failed: ${e.message}", e)
        }
    }

    override suspend fun disconnect() {
        logger.log(LogLevel.DEBUG, TAG, "Disconnecting from ${port.systemPortName}", null)
        _state.value = TransportState.Disconnected
        cleanup()
    }

    override suspend fun send(frame: Frame) {
        require(frame.bytes.size <= WireFraming.MAX_FRAME_ON_WIRE) {
            "Serial frame ${frame.bytes.size} B exceeds MAX_FRAME_ON_WIRE=${WireFraming.MAX_FRAME_ON_WIRE} B"
        }
        val out = output ?: throw MeshtasticException.Transport("Serial not connected")
        try {
            withContext(Dispatchers.IO) {
                out.write(frame.bytes.toByteArray())
                out.flush()
            }
        } catch (e: Exception) {
            throw MeshtasticException.Transport("Serial send failed: ${e.message}", e)
        }
    }

    override fun frames(): Flow<Frame> = frameChannel.consumeAsFlow()

    /**
     * Blocking reader loop on [Dispatchers.IO]. Reads chunks and pushes bytes through
     * the framing FSM. Read timeouts return 0; cancellation is observed promptly.
     */
    private fun runReader() {
        val assembler = SerialFrameAssembler { frame ->
            publisher.publish(frame)
        }
        val buf = ByteArray(READ_CHUNK_BYTES)
        try {
            while (!cleanedUp.get()) {
                // Use port.readBytes() not InputStream: in SEMI_BLOCKING mode the
                // InputStream wrapper throws on timeout; readBytes() returns 0.
                val read = port.readBytes(buf, buf.size.toLong().toInt())
                when {
                    read < 0 -> break
                    read == 0 -> continue
                    else -> assembler.feed(buf, read)
                }
            }
        } catch (e: Exception) {
            // Close channel with cause so engine's frames() collector sees the real error.
            runCatching { frameChannel.close(e) }
        }
    }

    private suspend fun cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return

        // Close port AFTER reader stops to avoid racing closePort with an in-flight
        // blocking read (OS-undefined, can corrupt the fd for the next openPort).
        val job = readerJob
        readerJob = null
        val scope = readerScope
        readerScope = null
        try {
            if (job != null) {
                val joined = withTimeoutOrNull(READER_JOIN_TIMEOUT_MS) { job.join() }
                if (joined == null) job.cancel()
            }
        } finally {
            scope?.cancel()
            runCatching { input?.close() }
            runCatching { output?.close() }
            runCatching { if (port.isOpen) port.closePort() }
            input = null
            output = null
            frameChannel.close()
        }
    }

    private companion object {
        const val TAG = "SerialTransport"
        const val DATA_BITS_8 = 8
        const val READ_TIMEOUT_MS = 100
        const val READ_CHUNK_BYTES = 256

        /** Max wait for reader job to exit before forcible cancel. */
        const val READER_JOIN_TIMEOUT_MS = 200L

        const val FRAME_CHANNEL_CAPACITY = Channel.BUFFERED

        /** Log every Nth drop to avoid flooding. First drop always logged. */
        const val DROP_LOG_EVERY = 100L

        val WAKE_BYTES = byteArrayOf(
            WireFraming.MAGIC_0,
            WireFraming.MAGIC_0,
            WireFraming.MAGIC_0,
            WireFraming.MAGIC_0,
        )
    }
}
