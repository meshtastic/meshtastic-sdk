/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import com.fazecast.jSerialComm.SerialPort
import java.io.OutputStream

/**
 * The blocking port operations [JSerialCommTransport] needs. Every call may block the calling
 * thread, so the transport only invokes them on [kotlinx.coroutines.Dispatchers.IO] (ADR-012).
 *
 * Exists so the transport's connect/reconnect/link-loss logic can be unit-tested without
 * serial hardware; production code always uses [JSerialCommPortIo].
 */
internal interface SerialPortIo {
    /** OS name of the port, for log and error messages. */
    val name: String

    /** Whether the port is currently open. */
    val isOpen: Boolean

    /**
     * Configure 8N1 at [baudRate] with no flow control and semi-blocking reads that return after
     * at most [readTimeoutMs], then open the port. Returns `false` when the OS refuses the open.
     */
    fun open(baudRate: Int, readTimeoutMs: Int): Boolean

    /** Discard bytes buffered by the OS in either direction. */
    fun flushBuffers()

    /** Write all of [bytes] and flush. Throws on I/O failure. */
    fun write(bytes: ByteArray)

    /**
     * Read up to `buffer.size` bytes. Returns the number read, `0` when the read timeout elapsed
     * with nothing available, or a negative value when the port is gone (e.g. unplugged).
     */
    fun read(buffer: ByteArray): Int

    /** Close the port. Safe to call when it is not open. */
    fun close()
}

/** [SerialPortIo] backed by a jSerialComm [SerialPort]. */
internal class JSerialCommPortIo(private val port: SerialPort) : SerialPortIo {

    // ADR-012: native-handle ownership — the port's output stream for the current open, written
    // on the IO dispatcher by connect()/send() and cleared by close().
    @Volatile
    private var output: OutputStream? = null

    override val name: String get() = port.systemPortName

    override val isOpen: Boolean get() = port.isOpen

    override fun open(baudRate: Int, readTimeoutMs: Int): Boolean {
        port.baudRate = baudRate
        port.numDataBits = DATA_BITS_8
        port.parity = SerialPort.NO_PARITY
        port.numStopBits = SerialPort.ONE_STOP_BIT
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        port.setComPortTimeouts(
            SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
            readTimeoutMs,
            /* writeTimeout = */
            0,
        )
        if (!port.openPort()) return false
        output = port.outputStream
        return true
    }

    override fun flushBuffers() {
        // Do NOT toggle DTR/RTS here: on native USB-CDC boards that triggers a
        // SET_CONTROL_LINE_STATE reset. jSerialComm leaves them asserted by default.
        port.flushIOBuffers()
    }

    override fun write(bytes: ByteArray) {
        val out = output ?: error("Serial port ${port.systemPortName} is not open")
        out.write(bytes)
        out.flush()
    }

    // Use port.readBytes() not the InputStream: in SEMI_BLOCKING mode the InputStream wrapper
    // throws on timeout, while readBytes() returns 0.
    override fun read(buffer: ByteArray): Int = port.readBytes(buffer, buffer.size)

    override fun close() {
        runCatching { output?.close() }
        output = null
        if (port.isOpen) port.closePort()
    }

    private companion object {
        const val DATA_BITS_8 = 8
    }
}
