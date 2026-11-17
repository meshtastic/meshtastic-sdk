/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.XModem
import org.meshtastic.sdk.FileTransferFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XModemMachineTest {

    private fun bytesOf(size: Int): ByteString = ByteArray(size) { ((it * 7 + 3) and 0xFF).toByte() }.toByteString()

    private fun deviceBlock(seq: Int, chunk: ByteString, crc: Int = XModemProtocol.crc16(chunk)): XModem =
        XModem.Builder().also { wb ->
            wb.control = XModem.Control.SOH
            wb.seq = seq
            wb.crc16 = crc
            wb.buffer = chunk
        }.build()

    private fun ctl(control: XModem.Control): XModem = XModemProtocol.control(control)

    private fun controls(step: XModemStep<*>): List<XModem.Control> = step.send.map { it.control }

    private fun failure(step: XModemStep<*>): FileTransferFailure? = (step.outcome as? XModemOutcome.Failed)?.failure

    // CRC

    @Test
    fun crcMatchesFirmwareVectors() {
        assertEquals(0x31C3, XModemProtocol.crc16("123456789".encodeUtf8()))
        assertEquals(0x0000, XModemProtocol.crc16(ByteString.EMPTY))
        assertEquals(0x0000, XModemProtocol.crc16(ByteString.of(0)))
        assertEquals(0x2EE4, XModemProtocol.crc16(bytesOf(128)))
        assertEquals(0x99CF, XModemProtocol.crc16(ByteString.of(-1, -1, -1, -1)))
    }

    @Test
    fun pathFrameIsSeqZeroWithNulTerminator() {
        val frame = XModemProtocol.pathFrame(XModem.Control.STX, "/prefs/a.txt")
        assertEquals(XModem.Control.STX, frame.control)
        assertEquals(0, frame.seq)
        assertEquals("/prefs/a.txt".encodeUtf8().size + 1, frame.buffer.size)
        assertEquals(0, frame.buffer[frame.buffer.size - 1].toInt())
    }

    // Download

    @Test
    fun downloadStartsWithStxRequestAndCompletesMultiBlock() {
        val file = bytesOf(300)
        val machine = XModemDownload("/a")
        val start = machine.start(0)
        assertEquals(XModem.Control.STX, start.send.single().control)
        assertEquals(0, start.send.single().seq)

        var step = machine.onFrame(deviceBlock(1, file.substring(0, 128)), 1)
        assertEquals(listOf(XModem.Control.ACK), controls(step))
        assertEquals(128, step.bytes)
        step = machine.onFrame(deviceBlock(2, file.substring(128, 256)), 2)
        assertEquals(listOf(XModem.Control.ACK), controls(step))
        step = machine.onFrame(deviceBlock(3, file.substring(256)), 3)
        assertEquals(listOf(XModem.Control.ACK), controls(step))
        step = machine.onFrame(ctl(XModem.Control.EOT), 4)
        assertTrue(step.send.isEmpty())
        assertEquals(XModemOutcome.Done(file), step.outcome)
    }

    @Test
    fun downloadExactMultipleOfBlockSizeEndsWithEmptyBlock() {
        val file = bytesOf(256)
        val machine = XModemDownload("/a")
        machine.start(0)
        machine.onFrame(deviceBlock(1, file.substring(0, 128)), 1)
        machine.onFrame(deviceBlock(2, file.substring(128)), 2)
        val empty = machine.onFrame(deviceBlock(3, ByteString.EMPTY), 3)
        assertEquals(listOf(XModem.Control.ACK), controls(empty))
        assertEquals(XModemOutcome.Done(file), machine.onFrame(ctl(XModem.Control.EOT), 4).outcome)
    }

    @Test
    fun downloadZeroLengthFile() {
        val machine = XModemDownload("/a")
        machine.start(0)
        assertEquals(listOf(XModem.Control.ACK), controls(machine.onFrame(deviceBlock(1, ByteString.EMPTY), 1)))
        assertEquals(XModemOutcome.Done(ByteString.EMPTY), machine.onFrame(ctl(XModem.Control.EOT), 2).outcome)
    }

    @Test
    fun downloadCorruptBlockIsNakedThenGoodRetryAccepted() {
        val file = bytesOf(100)
        val machine = XModemDownload("/a")
        machine.start(0)
        val bad = machine.onFrame(deviceBlock(1, file, crc = 0x1234), 1)
        assertEquals(listOf(XModem.Control.NAK), controls(bad))
        assertEquals(null, bad.outcome)
        assertEquals(listOf(XModem.Control.ACK), controls(machine.onFrame(deviceBlock(1, file), 2)))
        assertEquals(XModemOutcome.Done(file), machine.onFrame(ctl(XModem.Control.EOT), 3).outcome)
    }

    @Test
    fun downloadWrongSequenceIsNaked() {
        val machine = XModemDownload("/a")
        machine.start(0)
        val chunk = bytesOf(128)
        assertEquals(listOf(XModem.Control.NAK), controls(machine.onFrame(deviceBlock(2, chunk), 1)))
    }

    @Test
    fun downloadDuplicateBlockIsIgnoredNotDoubleCounted() {
        val file = bytesOf(200)
        val machine = XModemDownload("/a")
        machine.start(0)
        machine.onFrame(deviceBlock(1, file.substring(0, 128)), 1)
        val dup = machine.onFrame(deviceBlock(1, file.substring(0, 128)), 2)
        assertTrue(dup.send.isEmpty())
        assertEquals(null, dup.outcome)
        machine.onFrame(deviceBlock(2, file.substring(128)), 3)
        assertEquals(XModemOutcome.Done(file), machine.onFrame(ctl(XModem.Control.EOT), 4).outcome)
    }

    @Test
    fun downloadSequenceContinuesPastEightBits() {
        val machine = XModemDownload("/a", maxBytes = 1 shl 24)
        machine.start(0)
        val chunk = bytesOf(128)
        for (seq in 1..300) {
            assertEquals(
                listOf(XModem.Control.ACK),
                controls(machine.onFrame(deviceBlock(seq, chunk), seq.toLong())),
                "seq $seq",
            )
        }
        val done = machine.onFrame(ctl(XModem.Control.EOT), 400)
        assertEquals(null, done.outcome)
    }

    @Test
    fun downloadSequenceWrapsAtSixteenBits() {
        val machine = XModemDownload("/a", maxBytes = 1 shl 24)
        machine.start(0)
        val chunk = bytesOf(128)
        for (seq in 1..65535) machine.onFrame(deviceBlock(seq, chunk), 1)
        assertEquals(listOf(XModem.Control.ACK), controls(machine.onFrame(deviceBlock(0, chunk), 2)))
        assertEquals(listOf(XModem.Control.ACK), controls(machine.onFrame(deviceBlock(1, chunk), 3)))
    }

    @Test
    fun downloadRetriesExhaustedAbortsWithoutCan() {
        val machine = XModemDownload("/a", maxRetries = 3)
        machine.start(0)
        val chunk = bytesOf(10)
        repeat(3) {
            assertEquals(
                listOf(XModem.Control.NAK),
                controls(machine.onFrame(deviceBlock(1, chunk, crc = 1), it.toLong())),
            )
        }
        val last = machine.onFrame(deviceBlock(1, chunk, crc = 1), 9)
        assertEquals(FileTransferFailure.RetriesExhausted, failure(last))
        assertEquals(listOf(XModemProtocol.downloadAbort()), last.send)
        assertTrue(last.send.none { it.control == XModem.Control.CAN })
    }

    @Test
    fun downloadCanBeforeFirstBlock() {
        val machine = XModemDownload("/a")
        machine.start(0)
        val step = machine.onFrame(ctl(XModem.Control.CAN), 1)
        assertEquals(FileTransferFailure.DeviceCancelled, failure(step))
        assertTrue(step.send.isEmpty())
    }

    @Test
    fun downloadCanMidTransfer() {
        val machine = XModemDownload("/a")
        machine.start(0)
        machine.onFrame(deviceBlock(1, bytesOf(128)), 1)
        assertEquals(FileTransferFailure.DeviceCancelled, failure(machine.onFrame(ctl(XModem.Control.CAN), 2)))
    }

    @Test
    fun downloadCanAfterLastBlockBeforeEot() {
        val machine = XModemDownload("/a")
        machine.start(0)
        machine.onFrame(deviceBlock(1, bytesOf(5)), 1)
        assertEquals(FileTransferFailure.DeviceCancelled, failure(machine.onFrame(ctl(XModem.Control.CAN), 2)))
    }

    @Test
    fun downloadNakToRequestIsRefused() {
        val machine = XModemDownload("/missing")
        machine.start(0)
        val step = machine.onFrame(ctl(XModem.Control.NAK), 1)
        assertEquals(FileTransferFailure.Refused, failure(step))
        assertTrue(step.send.isEmpty())
    }

    @Test
    fun downloadSilentDeviceTimesOutBeforeFirstBlock() {
        val machine = XModemDownload("/a", blockTimeoutMs = 1000)
        machine.start(0)
        assertTrue(machine.onTick(999).outcome == null)
        assertEquals(FileTransferFailure.Timeout, failure(machine.onTick(1000)))
    }

    @Test
    fun downloadMidTransferTimeoutNaksThenGivesUp() {
        val machine = XModemDownload("/a", maxRetries = 2, blockTimeoutMs = 1000)
        machine.start(0)
        machine.onFrame(deviceBlock(1, bytesOf(128)), 10)
        val first = machine.onTick(1010)
        assertEquals(listOf(XModem.Control.NAK), controls(first))
        assertEquals(2010, machine.deadlineMs)
        assertEquals(listOf(XModem.Control.NAK), controls(machine.onTick(2010)))
        val last = machine.onTick(3010)
        assertEquals(FileTransferFailure.Timeout, failure(last))
        assertEquals(listOf(XModemProtocol.downloadAbort()), last.send)
    }

    @Test
    fun downloadTooLargeAborts() {
        val machine = XModemDownload("/a", maxBytes = 200)
        machine.start(0)
        machine.onFrame(deviceBlock(1, bytesOf(128)), 1)
        val step = machine.onFrame(deviceBlock(2, bytesOf(128)), 2)
        assertEquals(FileTransferFailure.TooLarge, failure(step))
        assertEquals(listOf(XModemProtocol.downloadAbort()), step.send)
    }

    @Test
    fun downloadExactlyAtCapIsAccepted() {
        val machine = XModemDownload("/a", maxBytes = 256)
        machine.start(0)
        machine.onFrame(deviceBlock(1, bytesOf(128)), 1)
        assertEquals(listOf(XModem.Control.ACK), controls(machine.onFrame(deviceBlock(2, bytesOf(128)), 2)))
    }

    @Test
    fun downloadCancelReturnsAbortFrameNotCan() {
        val machine = XModemDownload("/a")
        machine.start(0)
        assertEquals(listOf(XModemProtocol.downloadAbort()), machine.cancel())
        assertTrue(machine.cancel().isEmpty())
    }

    // Upload

    @Test
    fun uploadStartsWithSohRequestAndCompletesMultiBlock() {
        val file = bytesOf(300)
        val machine = XModemUpload("/a", file)
        val start = machine.start(0)
        assertEquals(XModem.Control.SOH, start.send.single().control)
        assertEquals(0, start.send.single().seq)
        assertEquals(300, machine.totalBytes)

        var step = machine.onFrame(ctl(XModem.Control.ACK), 1)
        assertEquals(1, step.send.single().seq)
        assertEquals(file.substring(0, 128), step.send.single().buffer)
        assertEquals(XModemProtocol.crc16(file.substring(0, 128)), step.send.single().crc16)
        step = machine.onFrame(ctl(XModem.Control.ACK), 2)
        assertEquals(2, step.send.single().seq)
        assertEquals(128, step.bytes)
        step = machine.onFrame(ctl(XModem.Control.ACK), 3)
        assertEquals(3, step.send.single().seq)
        assertEquals(file.substring(256), step.send.single().buffer)
        step = machine.onFrame(ctl(XModem.Control.ACK), 4)
        assertEquals(listOf(XModem.Control.EOT), controls(step))
        assertEquals(300, step.bytes)
        step = machine.onFrame(ctl(XModem.Control.ACK), 5)
        assertEquals(XModemOutcome.Done(Unit), step.outcome)
    }

    @Test
    fun uploadExactMultipleOfBlockSizeSendsNoEmptyBlock() {
        val machine = XModemUpload("/a", bytesOf(256))
        machine.start(0)
        machine.onFrame(ctl(XModem.Control.ACK), 1)
        machine.onFrame(ctl(XModem.Control.ACK), 2)
        assertEquals(listOf(XModem.Control.EOT), controls(machine.onFrame(ctl(XModem.Control.ACK), 3)))
    }

    @Test
    fun uploadZeroLengthSendsEotRightAfterStartAck() {
        val machine = XModemUpload("/a", ByteString.EMPTY)
        machine.start(0)
        assertEquals(listOf(XModem.Control.EOT), controls(machine.onFrame(ctl(XModem.Control.ACK), 1)))
        assertEquals(XModemOutcome.Done(Unit), machine.onFrame(ctl(XModem.Control.ACK), 2).outcome)
    }

    @Test
    fun uploadNakResendsSameBlockThenContinues() {
        val file = bytesOf(200)
        val machine = XModemUpload("/a", file)
        machine.start(0)
        val first = machine.onFrame(ctl(XModem.Control.ACK), 1)
        val again = machine.onFrame(ctl(XModem.Control.NAK), 2)
        assertEquals(first.send, again.send)
        assertEquals(2, machine.onFrame(ctl(XModem.Control.ACK), 3).send.single().seq)
    }

    @Test
    fun uploadRetriesExhaustedSendsCan() {
        val machine = XModemUpload("/a", bytesOf(10), maxRetries = 2)
        machine.start(0)
        machine.onFrame(ctl(XModem.Control.ACK), 1)
        machine.onFrame(ctl(XModem.Control.NAK), 2)
        machine.onFrame(ctl(XModem.Control.NAK), 3)
        val last = machine.onFrame(ctl(XModem.Control.NAK), 4)
        assertEquals(FileTransferFailure.RetriesExhausted, failure(last))
        assertEquals(listOf(XModem.Control.CAN), controls(last))
    }

    @Test
    fun uploadSequenceContinuesPastEightBitsAndWrapsAtSixteen() {
        val machine = XModemUpload("/a", bytesOf(128 * 70_000), maxBytes = Int.MAX_VALUE)
        machine.start(0)
        var step = machine.onFrame(ctl(XModem.Control.ACK), 1)
        val seqs = mutableListOf(step.send.single().seq)
        repeat(65_536) {
            step = machine.onFrame(ctl(XModem.Control.ACK), 1)
            seqs.add(step.send.single().seq)
        }
        assertEquals(256, seqs[255])
        assertEquals(65_535, seqs[65_534])
        assertEquals(0, seqs[65_535])
        assertEquals(1, seqs[65_536])
    }

    @Test
    fun uploadNakToStartIsRefusedWithoutCan() {
        val machine = XModemUpload("/a", bytesOf(10))
        machine.start(0)
        val step = machine.onFrame(ctl(XModem.Control.NAK), 1)
        assertEquals(FileTransferFailure.Refused, failure(step))
        assertTrue(step.send.isEmpty())
    }

    @Test
    fun uploadCanFromDeviceAtEachStage() {
        val stages = listOf(0, 1, 2)
        for (acks in stages) {
            val machine = XModemUpload("/a", bytesOf(200))
            machine.start(0)
            repeat(acks) { machine.onFrame(ctl(XModem.Control.ACK), 1) }
            val step = machine.onFrame(ctl(XModem.Control.CAN), 2)
            assertEquals(FileTransferFailure.DeviceCancelled, failure(step), "after $acks acks")
            assertTrue(step.send.isEmpty())
        }
        val machine = XModemUpload("/a", bytesOf(200))
        machine.start(0)
        repeat(3) { machine.onFrame(ctl(XModem.Control.ACK), 1) }
        assertEquals(FileTransferFailure.DeviceCancelled, failure(machine.onFrame(ctl(XModem.Control.CAN), 2)))
    }

    @Test
    fun uploadSilentStartTimesOutWithoutCan() {
        val machine = XModemUpload("/a", bytesOf(10), blockTimeoutMs = 1000)
        machine.start(0)
        val step = machine.onTick(1000)
        assertEquals(FileTransferFailure.Timeout, failure(step))
        assertTrue(step.send.isEmpty())
    }

    @Test
    fun uploadBlockTimeoutResendsThenFailsWithCan() {
        val machine = XModemUpload("/a", bytesOf(10), maxRetries = 1, blockTimeoutMs = 1000)
        machine.start(0)
        val block = machine.onFrame(ctl(XModem.Control.ACK), 10)
        val resend = machine.onTick(1010)
        assertEquals(block.send, resend.send)
        val last = machine.onTick(2010)
        assertEquals(FileTransferFailure.Timeout, failure(last))
        assertEquals(listOf(XModem.Control.CAN), controls(last))
    }

    @Test
    fun uploadTooLargeFailsBeforeAnyFrame() {
        val machine = XModemUpload("/a", bytesOf(129), maxBytes = 128)
        val step = machine.start(0)
        assertEquals(FileTransferFailure.TooLarge, failure(step))
        assertTrue(step.send.isEmpty())
    }

    @Test
    fun uploadCancelSendsCan() {
        val machine = XModemUpload("/a", bytesOf(10))
        machine.start(0)
        machine.onFrame(ctl(XModem.Control.ACK), 1)
        assertEquals(listOf(XModem.Control.CAN), machine.cancel().map { it.control })
        assertTrue(machine.cancel().isEmpty())
    }
}
