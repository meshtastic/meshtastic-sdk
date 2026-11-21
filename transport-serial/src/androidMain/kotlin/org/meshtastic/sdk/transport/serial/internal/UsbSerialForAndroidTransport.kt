/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * usb-serial-for-android backed [RadioTransport]. [openPort] does the `UsbManager.openDevice`
 * and `UsbSerialPort.open` work so the data plane here is testable with a fake port.
 *
 * Reads block on [ioDispatcher] in [READ_TIMEOUT_MS] slices; a read failure after connect
 * publishes [TransportState.Error] (recoverable) and ends [frames] normally, so the engine's
 * auto-reconnect supervisor, not a frame-reader exception, decides what happens next.
 */
internal class UsbSerialForAndroidTransport(
    private val openPort: () -> UsbSerialPort,
    override val identity: TransportIdentity,
    private val baudRate: Int,
    private val logger: LogSink = LogSink.Silent,
    private val parentContext: CoroutineContext = EmptyCoroutineContext,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onTextLine: ((String) -> Unit)? = null,
) : RadioTransport {

    private val _state: MutableStateFlow<TransportState> = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private var port: UsbSerialPort? = null
    private val frameChannel = Channel<Frame>(capacity = Channel.BUFFERED)
    private var readerScope: CoroutineScope? = null
    private var readerJob: Job? = null

    // ADR-012: lifecycle idempotency
    private val cleanedUp = AtomicBoolean(false)

    override suspend fun connect() {
        check(!cleanedUp.get()) { "UsbSerialForAndroidTransport has been disconnected; construct a new instance" }
        _state.value = TransportState.Connecting
        try {
            val opened = withContext(ioDispatcher) {
                openPort().also { p ->
                    // Record ownership before the dispatcher returns a result to the possibly cancelled caller.
                    port = p
                    p.setParameters(
                        baudRate,
                        UsbSerialPort.DATABITS_8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE,
                    )
                    // Native USB-CDC boards (RAK4631, nRF52840) wait for DTR/RTS before answering wake bytes.
                    p.dtr = true
                    p.rts = true
                    p.write(WAKE_BYTES, WRITE_TIMEOUT_MS)
                }
            }
            val scope = CoroutineScope(parentContext + SupervisorJob() + ioDispatcher)
            readerScope = scope
            _state.value = TransportState.Connected
            readerJob = scope.launch { runReader(opened) }
            logger.log(LogLevel.INFO, TAG, "Connected to ${identity.raw} at $baudRate baud", null)
        } catch (e: CancellationException) {
            _state.value = TransportState.Disconnected
            withContext(NonCancellable) { cleanup() }
            throw e
        } catch (e: MeshtasticException) {
            _state.value = TransportState.Error(e, recoverable = true)
            cleanup()
            throw e
        } catch (e: Exception) {
            _state.value = TransportState.Error(e, recoverable = true)
            cleanup()
            throw MeshtasticException.Transport("USB serial connect failed: ${e.message}", e)
        }
    }

    override suspend fun disconnect() {
        _state.value = TransportState.Disconnected
        cleanup()
    }

    override suspend fun send(frame: Frame) {
        require(frame.bytes.size <= WireFraming.MAX_FRAME_ON_WIRE) {
            "Serial frame ${frame.bytes.size} B exceeds MAX_FRAME_ON_WIRE=${WireFraming.MAX_FRAME_ON_WIRE} B"
        }
        val out = port ?: throw MeshtasticException.Transport("USB serial not connected")
        try {
            withContext(ioDispatcher) { out.write(frame.bytes.toByteArray(), WRITE_TIMEOUT_MS) }
        } catch (e: IOException) {
            throw MeshtasticException.Transport("USB serial send failed: ${e.message}", e)
        }
    }

    override fun frames(): Flow<Frame> = frameChannel.consumeAsFlow()

    private suspend fun runReader(port: UsbSerialPort) {
        val reader = SerialFrameReader(frameChannel, onTextLine)
        val buf = ByteArray(READ_CHUNK_BYTES)
        try {
            while (!cleanedUp.get()) {
                val read = port.read(buf, READ_TIMEOUT_MS)
                if (read > 0) reader.publish(buf, read)
            }
        } catch (e: IOException) {
            if (cleanedUp.get()) return
            logger.log(LogLevel.WARN, TAG, "USB serial read failed: ${e.message}", e)
            // A disconnect that raced the read has already published Disconnected; keep it.
            _state.compareAndSet(TransportState.Connected, TransportState.Error(e, recoverable = true))
            frameChannel.close()
        }
    }

    private suspend fun cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return
        val job = readerJob
        readerJob = null
        val scope = readerScope
        readerScope = null
        try {
            job?.cancel()
            if (job != null) withTimeoutOrNull(READER_JOIN_TIMEOUT_MS) { job.join() }
        } finally {
            scope?.cancel()
            runCatching { port?.close() }
            port = null
            frameChannel.close()
        }
    }

    private companion object {
        const val TAG = "UsbSerialTransport"
        const val READ_TIMEOUT_MS = 200
        const val WRITE_TIMEOUT_MS = 2_000
        const val READ_CHUNK_BYTES = 256
        const val READER_JOIN_TIMEOUT_MS = 1_000L

        /** 4× START1 per protocol.md §2 resync FSM. */
        val WAKE_BYTES = byteArrayOf(WireFraming.MAGIC_0, WireFraming.MAGIC_0, WireFraming.MAGIC_0, WireFraming.MAGIC_0)
    }
}
