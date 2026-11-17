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
    fun downloadBehindSequenceIsNaked() {
        val machine = XModemDownload("/a")
        machine.start(0)
        val chunk = bytesOf(128)
        machine.onFrame(deviceBlock(1, chunk), 1)
        machine.onFrame(deviceBlock(2, chunk), 2)
        assertEquals(listOf(XModem.Control.NAK), controls(machine.onFrame(deviceBlock(0, chunk), 3)))
    }

    @Test
    fun downloadAheadSequenceResyncsAndAbortsWithoutWaitingOutTheTimeout() {
        val machine = XModemDownload("/a")
        machine.start(0)
        val step = machine.onFrame(deviceBlock(2, bytesOf(128)), 1)
        assertEquals(listOf(XModemProtocol.downloadAbort()), step.send)
        assertEquals(null, step.outcome)
        assertEquals(XModemOutcome.Resync, machine.onFrame(ctl(XModem.Control.CAN), 2).outcome)
    }

    @Test
    fun downloadAheadShortBlockIsAckedSoFirmwareClearsIsEot() {
        val machine = XModemDownload("/a")
        machine.start(0)
        val step = machine.onFrame(deviceBlock(2, bytesOf(97)), 1)
        assertEquals(listOf(XModem.Control.ACK), controls(step))
        assertEquals(XModemOutcome.Resync, machine.onFrame(ctl(XModem.Control.EOT), 2).outcome)
    }

    @Test
    fun downloadEotAfterFullBlockResyncsWithNoFrames() {
        val machine = XModemDownload("/a")
        machine.start(0)
        machine.onFrame(deviceBlock(1, bytesOf(128)), 1)
        val step = machine.onFrame(ctl(XModem.Control.EOT), 2)
        assertTrue(step.send.isEmpty())
        assertEquals(XModemOutcome.Resync, step.outcome)
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
        machine.onFrame(deviceBlock(301, bytesOf(7)), 399)
        val done = machine.onFrame(ctl(XModem.Control.EOT), 400)
        assertTrue(done.outcome is XModemOutcome.Done)
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
        val chunk = bytesOf(128)
        repeat(3) {
            assertEquals(
                listOf(XModem.Control.NAK),
                controls(machine.onFrame(deviceBlock(1, chunk, crc = 1), it.toLong())),
            )
        }
        val last = machine.onFrame(deviceBlock(1, chunk, crc = 1), 9)
        assertEquals(listOf(XModemProtocol.downloadAbort()), last.send)
        assertTrue(last.send.none { it.control == XModem.Control.CAN })
        assertEquals(null, last.outcome)
        assertEquals(null, machine.onFrame(ctl(XModem.Control.ACK), 10).outcome)
        assertEquals(FileTransferFailure.RetriesExhausted, failure(machine.onFrame(ctl(XModem.Control.CAN), 11)))
    }

    @Test
    fun downloadRetriesExhaustedOnShortBlockAcksSoFirmwareSendsEot() {
        val machine = XModemDownload("/a", maxRetries = 1)
        machine.start(0)
        val chunk = bytesOf(10)
        machine.onFrame(deviceBlock(1, chunk, crc = 1), 1)
        val last = machine.onFrame(deviceBlock(1, chunk, crc = 1), 2)
        assertEquals(listOf(XModem.Control.ACK), controls(last))
        assertEquals(FileTransferFailure.RetriesExhausted, failure(machine.onFrame(ctl(XModem.Control.EOT), 3)))
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
    fun downloadCanAfterShortBlockStillSucceedsWithAllBytes() {
        val file = bytesOf(5)
        val machine = XModemDownload("/a")
        machine.start(0)
        machine.onFrame(deviceBlock(1, file), 1)
        assertEquals(XModemOutcome.Done(file), machine.onFrame(ctl(XModem.Control.CAN), 2).outcome)
    }

    @Test
    fun downloadCanAfterEmptyFinalBlockStillSucceeds() {
        val file = bytesOf(128)
        val machine = XModemDownload("/a")
        machine.start(0)
        machine.onFrame(deviceBlock(1, file), 1)
        machine.onFrame(deviceBlock(2, ByteString.EMPTY), 2)
        assertEquals(XModemOutcome.Done(file), machine.onFrame(ctl(XModem.Control.CAN), 3).outcome)
    }

    @Test
    fun downloadCanAfterFullBlockIsStillDeviceCancelled() {
        val machine = XModemDownload("/a")
        machine.start(0)
        machine.onFrame(deviceBlock(1, bytesOf(128)), 1)
        assertEquals(FileTransferFailure.DeviceCancelled, failure(machine.onFrame(ctl(XModem.Control.CAN), 2)))
    }

    @Test
    fun downloadSilenceAfterShortBlockAcksAgainAndSucceeds() {
        val file = bytesOf(97)
        val machine = XModemDownload("/a", blockTimeoutMs = 1000)
        machine.start(0)
        machine.onFrame(deviceBlock(1, file), 10)
        val confirm = machine.onTick(1010)
        assertEquals(listOf(XModem.Control.ACK), controls(confirm))
        assertEquals(null, confirm.outcome)
        assertTrue(confirm.send.single().control == XModem.Control.ACK)
        assertEquals(XModemOutcome.Done(file), machine.onFrame(ctl(XModem.Control.EOT), 1011).outcome)
    }

    @Test
    fun downloadSilenceAfterShortBlockStillSucceedsWhenNothingAnswersTheConfirm() {
        val file = bytesOf(97)
        val machine = XModemDownload("/a", blockTimeoutMs = 1000)
        machine.start(0)
        machine.onFrame(deviceBlock(1, file), 10)
        machine.onTick(1010)
        assertEquals(XModemOutcome.Done(file), machine.onTick(2010).outcome)
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
        val abort = machine.onTick(1000)
        assertEquals(listOf(XModemProtocol.downloadAbort()), abort.send)
        assertEquals(null, abort.outcome)
        assertEquals(FileTransferFailure.Timeout, failure(machine.onTick(2000)))
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
        assertEquals(listOf(XModemProtocol.downloadAbort()), last.send)
        assertEquals(FileTransferFailure.Timeout, failure(machine.onFrame(ctl(XModem.Control.CAN), 3011)))
    }

    @Test
    fun downloadTooLargeFullBlockAborts() {
        val machine = XModemDownload("/a", maxBytes = 200)
        machine.start(0)
        machine.onFrame(deviceBlock(1, bytesOf(128)), 1)
        val step = machine.onFrame(deviceBlock(2, bytesOf(128)), 2)
        assertEquals(listOf(XModemProtocol.downloadAbort()), step.send)
        assertEquals(FileTransferFailure.TooLarge, failure(machine.onFrame(ctl(XModem.Control.CAN), 3)))
    }

    @Test
    fun downloadTooLargeShortBlockIsAckedForEotAndKeepsNoBytes() {
        val machine = XModemDownload("/a", maxBytes = 200)
        machine.start(0)
        machine.onFrame(deviceBlock(1, bytesOf(128)), 1)
        val step = machine.onFrame(deviceBlock(2, bytesOf(100)), 2)
        assertEquals(listOf(XModem.Control.ACK), controls(step))
        assertEquals(null, step.bytes)
        assertEquals(null, step.outcome)
        assertEquals(FileTransferFailure.TooLarge, failure(machine.onFrame(ctl(XModem.Control.EOT), 3)))
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
        val step = machine.cancel(1)
        assertEquals(listOf(XModemProtocol.downloadAbort()), step.send)
        assertEquals(null, step.outcome)
        assertTrue(machine.cancel(2).send.isEmpty())
        assertTrue(machine.onFrame(ctl(XModem.Control.CAN), 3).outcome != null)
    }

    @Test
    fun downloadFollowingAShortOverCapBlockIsClean() {
        val first = XModemDownload("/a", maxBytes = 50)
        first.start(0)
        first.onFrame(deviceBlock(1, bytesOf(100)), 1)
        first.onFrame(ctl(XModem.Control.EOT), 2)
        val second = XModemDownload("/b")
        second.start(3)
        val full = second.onFrame(deviceBlock(1, bytesOf(128)), 4)
        assertEquals(listOf(XModem.Control.ACK), controls(full))
        assertEquals(null, full.outcome)
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
        assertEquals(listOf(XModem.Control.CAN), controls(last))
        assertEquals(null, last.outcome)
        assertEquals(FileTransferFailure.RetriesExhausted, failure(machine.onFrame(ctl(XModem.Control.ACK), 5)))
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
    fun uploadSilentStartProbesWithoutCanAndTimesOut() {
        val machine = XModemUpload("/a", bytesOf(10), blockTimeoutMs = 1000)
        val start = machine.start(0)
        val probe = machine.onTick(1000)
        assertEquals(start.send, probe.send)
        assertEquals(null, probe.outcome)
        val end = machine.onTick(2000)
        assertEquals(FileTransferFailure.Timeout, failure(end))
        assertTrue(end.send.isEmpty())
    }

    @Test
    fun uploadLostStartAckIsProbedThenNakCleansWithCanOnOwnPath() {
        val machine = XModemUpload("/a", bytesOf(10), blockTimeoutMs = 1000)
        machine.start(0)
        machine.onTick(1000)
        val nak = machine.onFrame(ctl(XModem.Control.NAK), 1001)
        assertEquals(listOf(XModem.Control.CAN), controls(nak))
        assertEquals(null, nak.outcome)
        assertEquals(null, machine.onFrame(ctl(XModem.Control.EOT), 1002).outcome)
        assertEquals(FileTransferFailure.Timeout, failure(machine.onFrame(ctl(XModem.Control.ACK), 1003)))
    }

    @Test
    fun uploadLostStartRequestIsRecoveredByProbeAck() {
        val machine = XModemUpload("/a", bytesOf(10), blockTimeoutMs = 1000)
        machine.start(0)
        machine.onTick(1000)
        val step = machine.onFrame(ctl(XModem.Control.ACK), 1001)
        assertEquals(1, step.send.single().seq)
        assertEquals(XModem.Control.SOH, step.send.single().control)
    }

    @Test
    fun uploadCancelBeforeStartAckSendsNoCan() {
        val machine = XModemUpload("/a", bytesOf(10))
        machine.start(0)
        val step = machine.cancel(1)
        assertTrue(step.send.none { it.control == XModem.Control.CAN })
        assertEquals(XModem.Control.SOH, step.send.single().control)
        assertEquals(0, step.send.single().seq)
        // device was idle and ACKs the probe: close it with EOT, never CAN
        val ack = machine.onFrame(ctl(XModem.Control.ACK), 2)
        assertEquals(listOf(XModem.Control.EOT), controls(ack))
        assertTrue(machine.onFrame(ctl(XModem.Control.ACK), 3).outcome != null)
    }

    @Test
    fun uploadCancelBeforeStartAckThenNakUsesCanOnOwnPath() {
        val machine = XModemUpload("/a", bytesOf(10))
        machine.start(0)
        machine.cancel(1)
        assertEquals(listOf(XModem.Control.CAN), controls(machine.onFrame(ctl(XModem.Control.NAK), 2)))
    }

    @Test
    fun uploadFinishedMachineIgnoresLateFrames() {
        val first = XModemUpload("/a", bytesOf(10), blockTimeoutMs = 1000)
        first.start(0)
        first.onTick(1000)
        first.onTick(2000)
        val late = first.onFrame(ctl(XModem.Control.ACK), 3001)
        assertTrue(late.send.isEmpty())
        assertEquals(null, late.outcome)
    }

    @Test
    fun uploadBlockTimeoutResendsThenFailsWithCan() {
        val machine = XModemUpload("/a", bytesOf(10), maxRetries = 1, blockTimeoutMs = 1000)
        machine.start(0)
        val block = machine.onFrame(ctl(XModem.Control.ACK), 10)
        val resend = machine.onTick(1010)
        assertEquals(block.send, resend.send)
        val last = machine.onTick(2010)
        assertEquals(listOf(XModem.Control.CAN), controls(last))
        assertEquals(FileTransferFailure.Timeout, failure(machine.onFrame(ctl(XModem.Control.ACK), 2011)))
    }

    @Test
    fun uploadEotAckTimeoutDoesNotResendEot() {
        val machine = XModemUpload("/a", ByteString.EMPTY, blockTimeoutMs = 1000)
        machine.start(0)
        assertEquals(listOf(XModem.Control.EOT), controls(machine.onFrame(ctl(XModem.Control.ACK), 1)))
        val end = machine.onTick(1001)
        assertTrue(end.send.isEmpty())
        assertEquals(FileTransferFailure.Timeout, failure(end))
    }

    // Nudges: a heartbeat written while a reply is overdue

    private fun nudges(machine: XModemMachine<*>, from: Long, to: Long): List<Long> {
        val times = mutableListOf<Long>()
        var t = from
        while (t <= to) {
            val step = machine.onTick(t)
            if (step.spacerNonce != null) {
                assertTrue(step.send.isEmpty(), "a nudge is never an XModem frame")
                assertEquals(null, step.outcome)
                times.add(t)
            }
            t = if (machine.wakeMs > t) machine.wakeMs else t + 1
        }
        return times
    }

    @Test
    fun downloadStartWaitNudgesEveryIntervalAndStopsAtTheDeadline() {
        val machine = XModemDownload("/a")
        machine.start(0)
        assertEquals(1_000, machine.wakeMs)
        assertTrue(machine.onTick(999).spacerNonce == null)
        val times = nudges(machine, 1_000, 9_999)
        assertEquals((1..9).map { it * 1_000L }, times)
        machine.onTick(10_000)
        assertEquals(FileTransferFailure.Timeout, failure(machine.onFrame(ctl(XModem.Control.CAN), 10_001)))
    }

    @Test
    fun healthyDeviceGetsNoNudge() {
        val machine = XModemDownload("/a")
        machine.start(0)
        assertTrue(machine.onTick(500).spacerNonce == null)
        machine.onFrame(deviceBlock(1, bytesOf(128)), 600)
        assertTrue(machine.onTick(1_500).spacerNonce == null)
        machine.onFrame(deviceBlock(2, bytesOf(5)), 1_600)
        assertTrue(machine.onTick(2_500).spacerNonce == null)
        assertTrue(machine.onFrame(ctl(XModem.Control.EOT), 2_600).outcome is XModemOutcome.Done)
    }

    @Test
    fun nudgeIsNotARetryAndDoesNotChangeTheExpectedBlock() {
        val machine = XModemDownload("/a", maxRetries = 1)
        machine.start(0)
        machine.onFrame(deviceBlock(1, bytesOf(128)), 10)
        assertEquals(listOf(1_010L, 2_010L, 3_010L), nudges(machine, 1_010, 3_999))
        val ack = machine.onFrame(deviceBlock(2, bytesOf(128)), 4_000)
        assertEquals(listOf(XModem.Control.ACK), controls(ack))
        assertEquals(128 * 2, ack.bytes)
    }

    @Test
    fun nudgesStopOnceTheTransferEnds() {
        val machine = XModemDownload("/a")
        machine.start(0)
        machine.onFrame(deviceBlock(1, bytesOf(5)), 10)
        machine.onFrame(ctl(XModem.Control.EOT), 20)
        assertTrue(machine.onTick(5_000).spacerNonce == null)
    }

    @Test
    fun cleanupWaitsNudgeToo() {
        val machine = XModemDownload("/a")
        machine.start(0)
        machine.onFrame(deviceBlock(1, bytesOf(128)), 5)
        machine.cancel(10)
        assertEquals(listOf(1_010L, 2_010L), nudges(machine, 1_010, 2_999))
        assertTrue(machine.onFrame(ctl(XModem.Control.CAN), 3_000).outcome != null)
        assertTrue(machine.onTick(4_000).spacerNonce == null)
    }

    @Test
    fun uploadWaitsNudgeAndKeepTheRequestPending() {
        val machine = XModemUpload("/a", bytesOf(10))
        val start = machine.start(0)
        assertEquals(listOf(1_000L, 2_000L), nudges(machine, 1_000, 2_999))
        val block = machine.onFrame(ctl(XModem.Control.ACK), 3_000)
        assertEquals(1, block.send.single().seq)
        assertEquals(XModem.Control.SOH, start.send.single().control)
    }

    @Test
    fun nudgeNoncesDifferFromEachOtherAndNeverTriggerNodeInfo() {
        val machine = XModemDownload("/a")
        machine.start(0)
        val nonces = (1..9).map { machine.onTick(it * 1_000L).spacerNonce!! }
        assertEquals(nonces.size, nonces.toSet().size)
        assertTrue(nonces.none { it <= 1L })
    }

    // Identical consecutive writes (BLE firmware drops them)

    @Test
    fun downloadAcksAndNaksCarryChangingSeqAndNeedNoSpacer() {
        val machine = XModemDownload("/a", maxRetries = 5)
        machine.start(0)
        val ack1 = machine.onFrame(deviceBlock(1, bytesOf(128)), 1)
        val ack2 = machine.onFrame(deviceBlock(2, bytesOf(128)), 2)
        val nak1 = machine.onFrame(deviceBlock(3, bytesOf(128), crc = 1), 3)
        val nak2 = machine.onFrame(deviceBlock(3, bytesOf(128), crc = 1), 4)
        val frames = listOf(ack1, ack2, nak1, nak2).map { it.send.single() }
        frames.zipWithNext().forEach { (a, b) -> assertTrue(a != b) }
        assertTrue(listOf(ack1, ack2, nak1, nak2).all { it.spacerNonce == null })
    }

    @Test
    fun uploadEotAndCanDifferFromThePreviousControlFrame() {
        val machine = XModemUpload("/a", ByteString.EMPTY)
        machine.start(0)
        val eot = machine.onFrame(ctl(XModem.Control.ACK), 1)
        assertEquals(XModem.Control.EOT, eot.send.single().control)
        assertTrue(eot.send.single().seq != 0)
    }

    @Test
    fun uploadRetransmitAfterNakGetsAHeartbeatSpacerWithChangingNonce() {
        val machine = XModemUpload("/a", bytesOf(10))
        machine.start(0)
        val first = machine.onFrame(ctl(XModem.Control.ACK), 1)
        assertEquals(null, first.spacerNonce)
        val again = machine.onFrame(ctl(XModem.Control.NAK), 2)
        val third = machine.onFrame(ctl(XModem.Control.NAK), 3)
        assertEquals(first.send, again.send)
        assertEquals(2L, again.spacerNonce)
        assertEquals(3L, third.spacerNonce)
    }

    @Test
    fun uploadProbeRepeatsTheRequestBehindASpacer() {
        val machine = XModemUpload("/a", bytesOf(10), blockTimeoutMs = 1000)
        val start = machine.start(0)
        assertEquals(null, start.spacerNonce)
        val probe = machine.onTick(1000)
        assertEquals(start.send, probe.send)
        assertEquals(2L, probe.spacerNonce)
    }

    @Test
    fun spacerNonceIsNeverTheNodeInfoTrigger() {
        val log = FrameLog()
        val frame = XModemProtocol.dataFrame(1, bytesOf(4))
        val nonces = (0 until 50).mapNotNull { log.spacerFor(frame) }
        assertTrue(nonces.none { it == 0L || it == 1L })
        assertEquals(nonces.size, nonces.toSet().size)
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
        // the device ACKed this upload's request, so firmware's filename is the upload target
        val step = machine.cancel(2)
        assertEquals(listOf(XModem.Control.CAN), controls(step))
        assertEquals(null, step.outcome)
        assertTrue(machine.cancel(3).send.isEmpty())
        assertTrue(machine.onFrame(ctl(XModem.Control.ACK), 4).outcome != null)
    }
}
