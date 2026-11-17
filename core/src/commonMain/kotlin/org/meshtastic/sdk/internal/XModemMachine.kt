/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.XModem
import org.meshtastic.sdk.FileTransferApi
import org.meshtastic.sdk.FileTransferFailure

/** Constants and helpers for firmware's XModem exchange (`firmware/src/xmodem.cpp`). */
internal object XModemProtocol {
    const val BLOCK_SIZE: Int = 128
    const val SEQ_MASK: Int = 0xFFFF
    const val DEFAULT_MAX_RETRIES: Int = 10
    const val DEFAULT_BLOCK_TIMEOUT_MS: Long = 10_000L
    private const val CRC_POLY: Int = 0x1021

    /** CRC-16/XMODEM (polynomial 0x1021, initial value 0), the value of firmware's `crc16_ccitt`. */
    fun crc16(data: ByteString): Int {
        var crc = 0
        for (i in 0 until data.size) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor CRC_POLY else crc shl 1 }
        }
        return crc and 0xFFFF
    }

    fun control(control: XModem.Control): XModem = XModem.Builder().also { wb -> wb.control = control }.build()

    /** The `seq = 0` frame that names the file; the NUL ends firmware's `strncpy` of the name. */
    fun pathFrame(control: XModem.Control, path: String): XModem = XModem.Builder().also { wb ->
        wb.control = control
        wb.seq = 0
        wb.buffer = Buffer().write(path.encodeUtf8()).writeByte(0).readByteString()
    }.build()

    fun dataFrame(seq: Int, chunk: ByteString): XModem = XModem.Builder().also { wb ->
        wb.control = XModem.Control.SOH
        wb.seq = seq and SEQ_MASK
        wb.crc16 = crc16(chunk)
        wb.buffer = chunk
    }.build()

    /**
     * Frame that ends a download without deleting the source file. Firmware answers `CAN` by
     * removing the file named in the last request; a data frame while it is transmitting makes it
     * close the file and reply `CAN` instead (`xmodem.cpp:220-228`). `seq` is nonzero so an idle
     * device ignores it rather than reading it as a new request.
     */
    fun downloadAbort(): XModem = XModem.Builder().also { wb ->
        wb.control = XModem.Control.SOH
        wb.seq = 1
    }.build()

    fun validPath(path: String): Boolean {
        val size = path.encodeUtf8().size
        return size in 1..FileTransferApi.MAX_PATH_BYTES && '\u0000' !in path
    }
}

internal sealed interface XModemOutcome<out T> {
    data class Done<T>(val value: T) : XModemOutcome<T>

    data class Failed(val failure: FileTransferFailure) : XModemOutcome<Nothing>
}

/**
 * What a machine wants done after an input: frames to send, the bytes acknowledged so far when
 * that changed, and the terminal outcome once the transfer is over.
 */
internal class XModemStep<out T>(
    val send: List<XModem> = emptyList(),
    val bytes: Int? = null,
    val outcome: XModemOutcome<T>? = null,
)

/**
 * Pure transfer state machine. No clock, no I/O: callers pass the current time in milliseconds
 * and act on the returned [XModemStep]. [deadlineMs] is when [onTick] next has work to do.
 */
internal interface XModemMachine<T> {
    val deadlineMs: Long
    val totalBytes: Int?

    fun start(nowMs: Long): XModemStep<T>

    fun onFrame(frame: XModem, nowMs: Long): XModemStep<T>

    fun onTick(nowMs: Long): XModemStep<T>

    /** Frames that leave the device idle when the caller gives up; empty once the transfer ended. */
    fun cancel(): List<XModem>
}

/** Device to host. The host sends the request (`STX`, `seq = 0`, path) and acknowledges each block. */
internal class XModemDownload(
    private val path: String,
    private val maxBytes: Int = FileTransferApi.MAX_FILE_BYTES,
    private val maxRetries: Int = XModemProtocol.DEFAULT_MAX_RETRIES,
    private val blockTimeoutMs: Long = XModemProtocol.DEFAULT_BLOCK_TIMEOUT_MS,
) : XModemMachine<ByteString> {
    private enum class Phase { Idle, AwaitFirst, AwaitBlock, AwaitEot, Finished }

    private var phase = Phase.Idle
    private val data = Buffer()
    private var received = 0
    private var accepted = 0
    private var nextSeq = 1
    private var retries = 0

    override var deadlineMs: Long = Long.MAX_VALUE
        private set
    override val totalBytes: Int? = null

    override fun start(nowMs: Long): XModemStep<ByteString> {
        phase = Phase.AwaitFirst
        deadlineMs = nowMs + blockTimeoutMs
        return XModemStep(send = listOf(XModemProtocol.pathFrame(XModem.Control.STX, path)))
    }

    override fun onFrame(frame: XModem, nowMs: Long): XModemStep<ByteString> {
        if (phase == Phase.Idle || phase == Phase.Finished) return XModemStep()
        return when (frame.control) {
            XModem.Control.CAN -> fail(FileTransferFailure.DeviceCancelled, abort = false)

            XModem.Control.NAK ->
                if (phase == Phase.AwaitFirst) fail(FileTransferFailure.Refused, abort = false) else XModemStep()

            XModem.Control.SOH, XModem.Control.STX ->
                if (phase == Phase.AwaitEot) XModemStep() else block(frame, nowMs)

            // No ACK for EOT: an idle firmware answers a stray ACK with CAN (xmodem.cpp:279-282).
            XModem.Control.EOT ->
                if (phase == Phase.AwaitEot) finish() else XModemStep()

            else -> XModemStep()
        }
    }

    override fun onTick(nowMs: Long): XModemStep<ByteString> {
        if (phase == Phase.Idle || phase == Phase.Finished || nowMs < deadlineMs) return XModemStep()
        return when (phase) {
            Phase.AwaitBlock -> retry(nowMs, FileTransferFailure.Timeout)
            else -> fail(FileTransferFailure.Timeout, abort = true)
        }
    }

    override fun cancel(): List<XModem> {
        if (phase == Phase.Idle || phase == Phase.Finished) return emptyList()
        phase = Phase.Finished
        return listOf(XModemProtocol.downloadAbort())
    }

    private fun block(frame: XModem, nowMs: Long): XModemStep<ByteString> {
        val seq = frame.seq and XModemProtocol.SEQ_MASK
        val chunk = frame.buffer
        val crcOk = chunk.size <= XModemProtocol.BLOCK_SIZE && XModemProtocol.crc16(chunk) == frame.crc16
        if (crcOk && accepted > 0 && seq == ((nextSeq - 1) and XModemProtocol.SEQ_MASK)) return XModemStep()
        if (!crcOk || seq != nextSeq) return retry(nowMs, FileTransferFailure.RetriesExhausted)
        if (received + chunk.size > maxBytes) return fail(FileTransferFailure.TooLarge, abort = true)
        data.write(chunk)
        received += chunk.size
        accepted++
        nextSeq = (nextSeq + 1) and XModemProtocol.SEQ_MASK
        retries = 0
        deadlineMs = nowMs + blockTimeoutMs
        phase = if (chunk.size < XModemProtocol.BLOCK_SIZE) Phase.AwaitEot else Phase.AwaitBlock
        return XModemStep(send = listOf(XModemProtocol.control(XModem.Control.ACK)), bytes = received)
    }

    private fun retry(nowMs: Long, exhausted: FileTransferFailure): XModemStep<ByteString> {
        retries++
        if (retries > maxRetries) return fail(exhausted, abort = true)
        deadlineMs = nowMs + blockTimeoutMs
        return XModemStep(send = listOf(XModemProtocol.control(XModem.Control.NAK)))
    }

    private fun finish(): XModemStep<ByteString> {
        phase = Phase.Finished
        return XModemStep(outcome = XModemOutcome.Done(data.readByteString()))
    }

    private fun fail(failure: FileTransferFailure, abort: Boolean): XModemStep<ByteString> {
        phase = Phase.Finished
        return XModemStep(
            send = if (abort) listOf(XModemProtocol.downloadAbort()) else emptyList(),
            outcome = XModemOutcome.Failed(failure),
        )
    }
}

/** Host to device. The host sends the request (`SOH`, `seq = 0`, path), the blocks, then `EOT`. */
internal class XModemUpload(
    private val path: String,
    private val content: ByteString,
    private val maxBytes: Int = FileTransferApi.MAX_FILE_BYTES,
    private val maxRetries: Int = XModemProtocol.DEFAULT_MAX_RETRIES,
    private val blockTimeoutMs: Long = XModemProtocol.DEFAULT_BLOCK_TIMEOUT_MS,
) : XModemMachine<Unit> {
    private enum class Phase { Idle, AwaitStartAck, AwaitBlockAck, AwaitEotAck, Finished }

    private var phase = Phase.Idle
    private val blockCount = (content.size + XModemProtocol.BLOCK_SIZE - 1) / XModemProtocol.BLOCK_SIZE
    private var current = 0
    private var retries = 0

    override var deadlineMs: Long = Long.MAX_VALUE
        private set
    override val totalBytes: Int = content.size

    override fun start(nowMs: Long): XModemStep<Unit> {
        if (content.size > maxBytes) {
            phase = Phase.Finished
            return XModemStep(outcome = XModemOutcome.Failed(FileTransferFailure.TooLarge))
        }
        phase = Phase.AwaitStartAck
        deadlineMs = nowMs + blockTimeoutMs
        return XModemStep(send = listOf(XModemProtocol.pathFrame(XModem.Control.SOH, path)))
    }

    override fun onFrame(frame: XModem, nowMs: Long): XModemStep<Unit> {
        if (phase == Phase.Idle || phase == Phase.Finished) return XModemStep()
        return when (frame.control) {
            XModem.Control.CAN -> fail(FileTransferFailure.DeviceCancelled, cancel = false)

            XModem.Control.NAK -> when (phase) {
                Phase.AwaitStartAck -> fail(FileTransferFailure.Refused, cancel = false)
                else -> retry(nowMs, FileTransferFailure.RetriesExhausted)
            }

            XModem.Control.ACK -> ack(nowMs)

            else -> XModemStep()
        }
    }

    override fun onTick(nowMs: Long): XModemStep<Unit> {
        if (phase == Phase.Idle || phase == Phase.Finished || nowMs < deadlineMs) return XModemStep()
        return when (phase) {
            // Nothing was acknowledged, so a CAN could delete a stale file named by an earlier request.
            Phase.AwaitStartAck -> fail(FileTransferFailure.Timeout, cancel = false)

            else -> retry(nowMs, FileTransferFailure.Timeout)
        }
    }

    override fun cancel(): List<XModem> {
        if (phase == Phase.Idle || phase == Phase.Finished) return emptyList()
        phase = Phase.Finished
        return listOf(XModemProtocol.control(XModem.Control.CAN))
    }

    private fun ack(nowMs: Long): XModemStep<Unit> {
        retries = 0
        deadlineMs = nowMs + blockTimeoutMs
        return when (phase) {
            Phase.AwaitStartAck, Phase.AwaitBlockAck -> {
                val acked = minOf(current * XModemProtocol.BLOCK_SIZE, content.size)
                if (current < blockCount) {
                    current++
                    phase = Phase.AwaitBlockAck
                    XModemStep(send = listOf(blockFrame()), bytes = acked)
                } else {
                    phase = Phase.AwaitEotAck
                    XModemStep(send = listOf(XModemProtocol.control(XModem.Control.EOT)), bytes = acked)
                }
            }

            else -> {
                phase = Phase.Finished
                XModemStep(bytes = content.size, outcome = XModemOutcome.Done(Unit))
            }
        }
    }

    private fun blockFrame(): XModem {
        val from = (current - 1) * XModemProtocol.BLOCK_SIZE
        val to = minOf(from + XModemProtocol.BLOCK_SIZE, content.size)
        return XModemProtocol.dataFrame(current, content.substring(from, to))
    }

    private fun retry(nowMs: Long, exhausted: FileTransferFailure): XModemStep<Unit> {
        retries++
        if (retries > maxRetries) return fail(exhausted, cancel = true)
        deadlineMs = nowMs + blockTimeoutMs
        val frame = if (phase == Phase.AwaitEotAck) XModemProtocol.control(XModem.Control.EOT) else blockFrame()
        return XModemStep(send = listOf(frame))
    }

    private fun fail(failure: FileTransferFailure, cancel: Boolean): XModemStep<Unit> {
        phase = Phase.Finished
        return XModemStep(
            send = if (cancel) listOf(XModemProtocol.control(XModem.Control.CAN)) else emptyList(),
            outcome = XModemOutcome.Failed(failure),
        )
    }
}
