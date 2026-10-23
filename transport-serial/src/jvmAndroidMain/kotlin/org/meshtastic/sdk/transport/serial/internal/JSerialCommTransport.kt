/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * jSerialComm-backed [RadioTransport] for JVM and Android. Configures 115200 8N1
 * semi-blocking reads and wires the port into [SerialFrameAssembler].
 *
 * - **Reusable:** an instance survives any number of `connect()` / `disconnect()` cycles (the
 *   engine's auto-reconnect calls `disconnect(); connect()` on the same instance). Each cycle
 *   gets a fresh frame channel; [frames] resolves it when collection starts.
 * - **Back-pressure:** the reader blocks its own IO thread when [frames] is not keeping up;
 *   frames are never dropped.
 * - **Never blocks the caller:** every blocking port call (configure, open, flush, wake bytes,
 *   write, close) runs on [Dispatchers.IO]. Open and close are bounded by a timeout so a port
 *   that hangs in the OS fails the call instead of hanging it.
 * - **Link loss:** when the port goes away (e.g. the device is unplugged) [state] becomes
 *   [TransportState.Error] with `recoverable = true` and the current [frames] flow completes.
 *
 * [disconnect] is idempotent, stops the reader before closing the port, and never reports an
 * error for a teardown it initiated.
 */
internal class JSerialCommTransport(
    private val port: SerialPortIo,
    override val identity: TransportIdentity,
    private val baudRate: Int,
    private val logger: LogSink = LogSink.Silent,
    private val parentContext: CoroutineContext = EmptyCoroutineContext,
    private val openTimeoutMs: Long = OPEN_TIMEOUT_MS,
    private val closeTimeoutMs: Long = CLOSE_TIMEOUT_MS,
) : RadioTransport {

    /** Production constructor over a jSerialComm [SerialPort]. */
    constructor(
        port: SerialPort,
        identity: TransportIdentity,
        baudRate: Int,
        logger: LogSink = LogSink.Silent,
        parentContext: CoroutineContext = EmptyCoroutineContext,
    ) : this(JSerialCommPortIo(port), identity, baudRate, logger, parentContext)

    private val _state: MutableStateFlow<TransportState> =
        MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    /** Everything owned by one connect cycle. */
    private class Session(val channel: Channel<Frame>, val readerScope: CoroutineScope) {
        // ADR-012: lifecycle idempotency — set by disconnect() so the reader exits quietly and a
        // read failure caused by our own teardown is not reported as link loss.
        val stopped = AtomicBoolean(false)
        var readerJob: Job? = null
    }

    // ADR-012: native-handle ownership — the open port's current cycle; read by the reader and
    // send() off the engine dispatcher.
    @Volatile
    private var session: Session? = null

    // ADR-012: lifecycle idempotency — channel for the current (or next) cycle. Replaced when a
    // cycle that used it is torn down, so the instance stays reusable.
    @Volatile
    private var frameChannel: Channel<Frame> = newFrameChannel()

    // ADR-012: native-handle ownership — a port call that outlived its timeout and may still
    // hold the port. New connects fail fast until it returns.
    @Volatile
    private var strandedIo: Deferred<*>? = null

    /**
     * Runs blocking port calls. Deliberately not a child of the caller: a call that hangs in
     * the OS cannot be cancelled, so the caller gives up after a timeout and leaves it here.
     */
    private val blockingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // isClosedForSend is delicate because it can race a concurrent close; here only a collector
    // cancelled before this cycle can close the channel, and a stale `false` is harmless.
    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun connect() {
        // A connect() on a live cycle replaces it, like disconnect() + connect().
        session?.let { teardown(it) }
        _state.value = TransportState.Connecting
        logger.log(LogLevel.DEBUG, TAG, "Connecting to ${port.name} at $baudRate baud", null)
        // A collector cancelled before this cycle cancels its channel; never reuse a dead one.
        if (frameChannel.isClosedForSend) frameChannel = newFrameChannel()
        val channel = frameChannel
        try {
            onPort("open", openTimeoutMs) {
                if (!port.open(baudRate, READ_TIMEOUT_MS)) {
                    throw MeshtasticException.Transport("Serial open failed for ${port.name}")
                }
                // Purge stale bytes from a previous session so the framer starts clean.
                port.flushBuffers()
                // 4× START1 wake bytes per protocol.md §2 resync FSM.
                port.write(WAKE_BYTES)
            }

            val readerScope = CoroutineScope(parentContext + SupervisorJob() + Dispatchers.IO)
            val s = Session(channel, readerScope)
            session = s
            s.readerJob = readerScope.launch { runReader(s) }

            _state.value = TransportState.Connected
            logger.log(LogLevel.INFO, TAG, "Connected to ${port.name}", null)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { closePortQuietly() }
            _state.value = TransportState.Disconnected
            throw e
        } catch (e: MeshtasticException) {
            _state.value = TransportState.Error(e, recoverable = true)
            withContext(NonCancellable) { closePortQuietly() }
            throw e
        } catch (e: Exception) {
            _state.value = TransportState.Error(e, recoverable = true)
            withContext(NonCancellable) { closePortQuietly() }
            throw MeshtasticException.Transport("Serial connect failed: ${e.message}", e)
        }
    }

    override suspend fun disconnect() {
        logger.log(LogLevel.DEBUG, TAG, "Disconnecting from ${port.name}", null)
        val s = session
        // Mark the cycle stopped before publishing Disconnected so the reader cannot report the
        // read failure our own close causes.
        s?.stopped?.set(true)
        _state.value = TransportState.Disconnected
        if (s != null) teardown(s) else withContext(NonCancellable) { closePortQuietly() }
    }

    override suspend fun send(frame: Frame) {
        require(frame.bytes.size <= WireFraming.MAX_FRAME_ON_WIRE) {
            "Serial frame ${frame.bytes.size} B exceeds MAX_FRAME_ON_WIRE=${WireFraming.MAX_FRAME_ON_WIRE} B"
        }
        val s = session
        if (s == null || s.stopped.get()) throw MeshtasticException.Transport("Serial not connected")
        try {
            withContext(Dispatchers.IO) { port.write(frame.bytes.toByteArray()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MeshtasticException.Transport("Serial send failed: ${e.message}", e)
        }
    }

    /**
     * Frames of the current connect cycle. The channel is resolved when collection starts, so
     * collect once per cycle, after [connect]. The flow completes when the cycle ends
     * ([disconnect] or link loss).
     */
    override fun frames(): Flow<Frame> = flow { emitAll(frameChannel.consumeAsFlow()) }

    /**
     * Blocking reader loop on [Dispatchers.IO]. Read timeouts return 0, so [Session.stopped] is
     * checked at least every [READ_TIMEOUT_MS].
     */
    private fun runReader(s: Session) {
        val publisher = FrameChannelPublisher(s.channel)
        val assembler = SerialFrameAssembler { frame -> publisher.publish(frame) }
        val buf = ByteArray(READ_CHUNK_BYTES)
        val failure: Throwable = try {
            var lost: Throwable? = null
            while (lost == null && !s.stopped.get()) {
                val read = port.read(buf)
                when {
                    read < 0 -> lost = MeshtasticException.Transport(
                        "Serial port ${port.name} closed (device unplugged?)",
                    )

                    read > 0 -> assembler.feed(buf, read)
                }
            }
            lost ?: return
        } catch (e: Exception) {
            e
        }
        reportLinkLoss(s, failure)
    }

    /** Publish a recoverable [TransportState.Error] for an unexpected end of [s]. */
    private fun reportLinkLoss(s: Session, cause: Throwable) {
        if (s.stopped.get()) return
        val error = cause as? MeshtasticException
            ?: MeshtasticException.Transport("Serial read failed on ${port.name}: ${cause.message}", cause)
        if (_state.compareAndSet(TransportState.Connected, TransportState.Error(error, recoverable = true))) {
            logger.log(LogLevel.WARN, TAG, "Serial link to ${port.name} lost: ${error.message}", null)
        }
        // End this cycle's frames() normally; the engine reacts to the Error state.
        s.channel.close()
    }

    /** Stop the reader, end the cycle's flow, then close the port. Never cancelled midway. */
    private suspend fun teardown(s: Session): Unit = withContext(NonCancellable) {
        if (session === s) session = null
        s.stopped.set(true)
        // Closing the channel ends the collector once it has taken the buffered frames.
        s.channel.close()
        if (frameChannel === s.channel) frameChannel = newFrameChannel()
        // Close the port only after the reader stops: racing closePort with an in-flight
        // blocking read is OS-undefined and can corrupt the fd for the next openPort.
        s.readerJob?.let { job ->
            if (withTimeoutOrNull(READER_JOIN_TIMEOUT_MS) { job.join() } == null) {
                // Still parked on a full channel nobody drains: cancelling the channel releases it.
                s.channel.cancel()
                job.cancel()
                withTimeoutOrNull(READER_JOIN_TIMEOUT_MS) { job.join() }
            }
        }
        s.readerScope.cancel()
        closePortQuietly()
    }

    /** Close the port on [Dispatchers.IO], bounded by [closeTimeoutMs]. Never throws. */
    private suspend fun closePortQuietly() {
        // A stranded call still owns the port; its completion handler closes it.
        if (strandedIo?.isActive == true) return
        try {
            onPort("close", closeTimeoutMs) { port.close() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogLevel.WARN, TAG, "Serial close of ${port.name} failed: ${e.message}", null)
        }
    }

    /**
     * Run a blocking port call on [Dispatchers.IO] and wait at most [timeoutMs] for it. If it
     * does not return in time the call is left running (it cannot be interrupted), recorded in
     * [strandedIo], and this throws [MeshtasticException.Transport]; once it finally returns the
     * port is closed unless a cycle owns it.
     */
    private suspend fun <T> onPort(what: String, timeoutMs: Long, block: () -> T): T {
        if (strandedIo?.isActive == true) {
            throw MeshtasticException.Transport(
                "Serial port ${port.name} is still blocked in an earlier call; try again later",
            )
        }
        val call = blockingScope.async { block() }
        val outcome = try {
            withTimeoutOrNull(timeoutMs) { Done(call.await()) }
        } catch (e: CancellationException) {
            if (call.isActive) strand(call)
            throw e
        }
        if (outcome == null) {
            strand(call)
            throw MeshtasticException.Transport("Serial $what of ${port.name} timed out after $timeoutMs ms")
        }
        return outcome.value
    }

    private fun strand(call: Deferred<*>) {
        strandedIo = call
        call.invokeOnCompletion {
            if (session == null) runCatching { port.close() }
        }
    }

    /** Distinguishes a call that returned `null` from one that timed out. */
    private class Done<T>(val value: T)

    private companion object {
        const val TAG = "SerialTransport"
        const val READ_TIMEOUT_MS = 100
        const val READ_CHUNK_BYTES = 256

        /** Max wait for configure + open + flush + wake bytes. */
        const val OPEN_TIMEOUT_MS = 5_000L

        /** Max wait for the port close. */
        const val CLOSE_TIMEOUT_MS = 2_000L

        /** Max wait for the reader to exit before forcible cancel (a few read timeouts). */
        const val READER_JOIN_TIMEOUT_MS = 500L

        const val FRAME_CHANNEL_CAPACITY = Channel.BUFFERED

        fun newFrameChannel(): Channel<Frame> = Channel(capacity = FRAME_CHANNEL_CAPACITY)

        val WAKE_BYTES = byteArrayOf(
            WireFraming.MAGIC_0,
            WireFraming.MAGIC_0,
            WireFraming.MAGIC_0,
            WireFraming.MAGIC_0,
        )
    }
}
