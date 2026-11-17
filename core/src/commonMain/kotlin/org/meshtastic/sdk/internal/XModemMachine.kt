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
 *
 * A machine that sends frames to put the device back to idle keeps running until the device's
 * reply to them arrives (or [deadlineMs] passes), so that reply cannot be read by the next transfer.
 */
internal interface XModemMachine<T> {
    val deadlineMs: Long
    val totalBytes: Int?

    fun start(nowMs: Long): XModemStep<T>

    fun onFrame(frame: XModem, nowMs: Long): XModemStep<T>

    fun onTick(nowMs: Long): XModemStep<T>

    /**
     * The caller gives up. The step carries the frames that leave the device idle; the caller keeps
     * feeding frames and ticks until a step has an [XModemStep.outcome] (its value is meaningless).
     */
    fun cancel(nowMs: Long): XModemStep<T>
}

private class Settling<T>(val expect: XModem.Control, val outcome: XModemOutcome<T>)

/** Device to host. The host sends the request (`STX`, `seq = 0`, path) and acknowledges each block. */
internal class XModemDownload(
    private val path: String,
    private val maxBytes: Int = FileTransferApi.MAX_FILE_BYTES,
    private val maxRetries: Int = XModemProtocol.DEFAULT_MAX_RETRIES,
    private val blockTimeoutMs: Long = XModemProtocol.DEFAULT_BLOCK_TIMEOUT_MS,
) : XModemMachine<ByteString> {
    private enum class Phase { Idle, AwaitFirst, AwaitBlock, AwaitEot, Settling, Finished }

    private var phase = Phase.Idle
    private val data = Buffer()
    private var received = 0
    private var accepted = 0
    private var nextSeq = 1
    private var retries = 0
    private var settling: Settling<ByteString>? = null

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
        if (phase == Phase.Settling) return if (frame.control == settling!!.expect) endSettling() else XModemStep()
        return when (frame.control) {
            XModem.Control.CAN -> fail(FileTransferFailure.DeviceCancelled)

            XModem.Control.NAK ->
                if (phase == Phase.AwaitFirst) fail(FileTransferFailure.Refused) else XModemStep()

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
            Phase.Settling -> endSettling()
            Phase.AwaitBlock -> retry(nowMs, FileTransferFailure.Timeout, shortFrame = false)
            Phase.AwaitEot -> fail(FileTransferFailure.Timeout)
            else -> abort(nowMs, FileTransferFailure.Timeout)
        }
    }

    override fun cancel(nowMs: Long): XModemStep<ByteString> = when (phase) {
        Phase.Settling -> XModemStep()

        Phase.AwaitFirst, Phase.AwaitBlock -> abort(nowMs, FileTransferFailure.DeviceCancelled)

        else -> {
            phase = Phase.Finished
            XModemStep(outcome = XModemOutcome.Failed(FileTransferFailure.DeviceCancelled))
        }
    }

    private fun block(frame: XModem, nowMs: Long): XModemStep<ByteString> {
        val seq = frame.seq and XModemProtocol.SEQ_MASK
        val chunk = frame.buffer
        val crcOk = chunk.size <= XModemProtocol.BLOCK_SIZE && XModemProtocol.crc16(chunk) == frame.crc16
        if (crcOk && accepted > 0 && seq == ((nextSeq - 1) and XModemProtocol.SEQ_MASK)) return XModemStep()
        val short = chunk.size < XModemProtocol.BLOCK_SIZE
        if (!crcOk || seq != nextSeq) return retry(nowMs, FileTransferFailure.RetriesExhausted, shortFrame = short)
        if (received + chunk.size > maxBytes) return abort(nowMs, FileTransferFailure.TooLarge, shortFrame = short)
        data.write(chunk)
        received += chunk.size
        accepted++
        nextSeq = (nextSeq + 1) and XModemProtocol.SEQ_MASK
        retries = 0
        deadlineMs = nowMs + blockTimeoutMs
        phase = if (short) Phase.AwaitEot else Phase.AwaitBlock
        return XModemStep(send = listOf(XModemProtocol.control(XModem.Control.ACK)), bytes = received)
    }

    private fun retry(nowMs: Long, exhausted: FileTransferFailure, shortFrame: Boolean): XModemStep<ByteString> {
        retries++
        if (retries > maxRetries) return abort(nowMs, exhausted, shortFrame)
        deadlineMs = nowMs + blockTimeoutMs
        return XModemStep(send = listOf(XModemProtocol.control(XModem.Control.NAK)))
    }

    /**
     * Ends the transfer and leaves firmware idle. Firmware sets `isEOT` when it prepares a short
     * block and clears it only when it sends `EOT` (`xmodem.cpp:254-261`); an abort frame does not.
     * When the device's pending block is known to be short, the way out is to `ACK` it and wait for
     * the `EOT`, keeping none of its bytes. Otherwise the abort frame is sent and its `CAN` reply awaited.
     */
    private fun abort(nowMs: Long, failure: FileTransferFailure, shortFrame: Boolean = false): XModemStep<ByteString> {
        val (frame, expect) = if (shortFrame) {
            XModemProtocol.control(XModem.Control.ACK) to XModem.Control.EOT
        } else {
            XModemProtocol.downloadAbort() to XModem.Control.CAN
        }
        phase = Phase.Settling
        settling = Settling(expect, XModemOutcome.Failed(failure))
        deadlineMs = nowMs + blockTimeoutMs
        return XModemStep(send = listOf(frame))
    }

    private fun endSettling(): XModemStep<ByteString> {
        phase = Phase.Finished
        return XModemStep(outcome = settling!!.outcome)
    }

    private fun finish(): XModemStep<ByteString> {
        phase = Phase.Finished
        return XModemStep(outcome = XModemOutcome.Done(data.readByteString()))
    }

    private fun fail(failure: FileTransferFailure): XModemStep<ByteString> {
        phase = Phase.Finished
        return XModemStep(outcome = XModemOutcome.Failed(failure))
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
    private enum class Phase { Idle, AwaitStartAck, Probing, AwaitBlockAck, AwaitEotAck, Settling, Finished }

    private var phase = Phase.Idle
    private val blockCount = (content.size + XModemProtocol.BLOCK_SIZE - 1) / XModemProtocol.BLOCK_SIZE
    private var current = 0
    private var retries = 0
    private var cancelling = false
    private var settling: Settling<Unit>? = null

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
        return XModemStep(send = listOf(startFrame()))
    }

    override fun onFrame(frame: XModem, nowMs: Long): XModemStep<Unit> {
        if (phase == Phase.Idle || phase == Phase.Finished) return XModemStep()
        if (phase == Phase.Settling) return if (frame.control == settling!!.expect) endSettling() else XModemStep()
        return when (frame.control) {
            XModem.Control.CAN -> fail(FileTransferFailure.DeviceCancelled)

            XModem.Control.NAK -> when (phase) {
                Phase.AwaitStartAck -> fail(FileTransferFailure.Refused)
                Phase.Probing -> settle(nowMs, XModem.Control.CAN, outcome(FileTransferFailure.Timeout))
                Phase.AwaitEotAck -> XModemStep()
                else -> retry(nowMs, FileTransferFailure.RetriesExhausted)
            }

            XModem.Control.ACK ->
                if (phase == Phase.Probing && cancelling) {
                    settle(nowMs, XModem.Control.EOT, outcome(FileTransferFailure.Timeout))
                } else {
                    ack(nowMs)
                }

            else -> XModemStep()
        }
    }

    override fun onTick(nowMs: Long): XModemStep<Unit> {
        if (phase == Phase.Idle || phase == Phase.Finished || nowMs < deadlineMs) return XModemStep()
        return when (phase) {
            Phase.Settling -> endSettling()

            Phase.AwaitStartAck -> probe(nowMs)

            Phase.Probing -> fail(FileTransferFailure.Timeout)

            // A second EOT would reach a closed file, and nRF52's File::flush does not guard that.
            Phase.AwaitEotAck -> fail(FileTransferFailure.Timeout)

            else -> retry(nowMs, FileTransferFailure.Timeout)
        }
    }

    /**
     * `CAN` makes firmware delete the file named by its last request (`xmodem.cpp:240-250`), so it is
     * sent only once firmware has ACKed this upload's request. Before that, [probe] finds out which
     * state the device is in.
     */
    override fun cancel(nowMs: Long): XModemStep<Unit> = when (phase) {
        Phase.Settling -> XModemStep()

        Phase.Probing -> {
            cancelling = true
            XModemStep()
        }

        Phase.AwaitStartAck -> {
            cancelling = true
            probe(nowMs)
        }

        Phase.AwaitBlockAck, Phase.AwaitEotAck -> settle(
            nowMs,
            XModem.Control.CAN,
            outcome(FileTransferFailure.Timeout),
        )

        else -> {
            phase = Phase.Finished
            XModemStep(outcome = XModemOutcome.Failed(FileTransferFailure.DeviceCancelled))
        }
    }

    /**
     * The start request got no reply: either it never arrived (device idle) or its ACK was lost
     * (device receiving, and refusing every later `seq = 0` request with `NAK`). Sending the request
     * again tells them apart without touching any file: an idle device ACKs it, a receiving one
     * NAKs it (`xmodem.cpp:142-172`, `203-219`), and then `filename` is this upload's own path.
     */
    private fun probe(nowMs: Long): XModemStep<Unit> {
        phase = Phase.Probing
        deadlineMs = nowMs + blockTimeoutMs
        return XModemStep(send = listOf(startFrame()))
    }

    private fun startFrame(): XModem = XModemProtocol.pathFrame(XModem.Control.SOH, path)

    private fun ack(nowMs: Long): XModemStep<Unit> {
        retries = 0
        deadlineMs = nowMs + blockTimeoutMs
        return when (phase) {
            Phase.AwaitStartAck, Phase.Probing, Phase.AwaitBlockAck -> {
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
        if (retries > maxRetries) return settle(nowMs, XModem.Control.CAN, outcome(exhausted))
        deadlineMs = nowMs + blockTimeoutMs
        return XModemStep(send = listOf(blockFrame()))
    }

    private fun outcome(failure: FileTransferFailure): XModemOutcome<Unit> = XModemOutcome.Failed(failure)

    /** Sends [control] to put the device back to idle and waits for its `ACK`. */
    private fun settle(nowMs: Long, control: XModem.Control, outcome: XModemOutcome<Unit>): XModemStep<Unit> {
        phase = Phase.Settling
        settling = Settling(XModem.Control.ACK, outcome)
        deadlineMs = nowMs + blockTimeoutMs
        return XModemStep(send = listOf(XModemProtocol.control(control)))
    }

    private fun endSettling(): XModemStep<Unit> {
        phase = Phase.Finished
        return XModemStep(outcome = settling!!.outcome)
    }

    private fun fail(failure: FileTransferFailure): XModemStep<Unit> {
        phase = Phase.Finished
        return XModemStep(outcome = XModemOutcome.Failed(failure))
    }
}
