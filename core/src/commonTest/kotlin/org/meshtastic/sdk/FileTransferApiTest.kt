/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.XModem
import org.meshtastic.sdk.internal.XModemProtocol
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Mirrors the transfer logic in firmware's `xmodem.cpp` closely enough to drive the SDK end to end. */
private class FirmwareXModem(val files: MutableMap<String, ByteString> = mutableMapOf()) {
    var transmitting: ByteString? = null
    var path = ""
    var receiving: okio.Buffer? = null
    var packetNo = 1
    var sendingEot = false
    var removedByCan = mutableListOf<String>()

    /** Number of upcoming sent blocks to corrupt (download) and received blocks to refuse (upload). */
    var corrupt = 0
    var nakNext = 0

    private fun block(): XModem {
        val data = transmitting!!
        val from = (packetNo - 1) * 128
        val chunk = if (from >= data.size) ByteString.EMPTY else data.substring(from, minOf(from + 128, data.size))
        if (chunk.size < 128) sendingEot = true
        val damage = if (corrupt > 0) 1 else 0
        if (corrupt > 0) corrupt--
        return XModem.Builder().also { wb ->
            wb.control = XModem.Control.SOH
            wb.seq = packetNo
            wb.crc16 = XModemProtocol.crc16(chunk) + damage
            wb.buffer = chunk
        }.build()
    }

    private fun ctl(c: XModem.Control) = XModemProtocol.control(c)

    fun handle(x: XModem): XModem? {
        when (x.control) {
            XModem.Control.SOH, XModem.Control.STX -> {
                if (x.seq == 0 && receiving == null && transmitting == null) {
                    path = x.buffer.utf8().substringBefore('\u0000')
                    if (x.control == XModem.Control.SOH) {
                        receiving = okio.Buffer()
                        packetNo = 1
                        return ctl(XModem.Control.ACK)
                    }
                    val data = files[path] ?: return ctl(XModem.Control.NAK)
                    transmitting = data
                    packetNo = 1
                    return block()
                }
                val rx = receiving
                if (rx != null) {
                    if (nakNext > 0) {
                        nakNext--
                        return ctl(XModem.Control.NAK)
                    }
                    if (x.seq == packetNo && XModemProtocol.crc16(x.buffer) == x.crc16) {
                        rx.write(x.buffer)
                        packetNo++
                        return ctl(XModem.Control.ACK)
                    }
                    return ctl(XModem.Control.NAK)
                }
                if (transmitting != null) {
                    transmitting = null
                    return ctl(XModem.Control.CAN)
                }
            }

            XModem.Control.EOT -> {
                receiving?.let { files[path] = it.readByteString() }
                receiving = null
                return ctl(XModem.Control.ACK)
            }

            XModem.Control.CAN -> {
                files.remove(path)
                removedByCan.add(path)
                receiving = null
                return ctl(XModem.Control.ACK)
            }

            XModem.Control.ACK -> {
                if (transmitting == null) return ctl(XModem.Control.CAN)
                if (sendingEot) {
                    transmitting = null
                    sendingEot = false
                    return ctl(XModem.Control.EOT)
                }
                packetNo++
                return block()
            }

            XModem.Control.NAK -> {
                if (transmitting == null) return ctl(XModem.Control.CAN)
                return block()
            }

            else -> Unit
        }
        return null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferApiTest {

    private fun TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:xmodem"),
            autoHandshake = true,
            nodeNum = 0x11111111,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .build()
        return transport to client
    }

    private fun sentXModem(transport: FakeRadioTransport): List<XModem> = transport.outboundFrames().mapNotNull {
        val bytes = it.bytes.toByteArray()
        runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)).xmodemPacket }.getOrNull()
    }

    /** Feeds each frame the SDK sends to the fake firmware and injects its reply, until [op] completes. */
    private fun TestScope.pump(
        transport: FakeRadioTransport,
        firmware: FirmwareXModem,
        op: Deferred<*>,
        dropReplyTo: Set<Int> = emptySet(),
        from: Int = 0,
    ) {
        var seen = from
        var guard = 0
        runCurrent()
        while (!op.isCompleted && guard++ < 5000) {
            val sent = sentXModem(transport)
            if (sent.size == seen) break
            val fresh = sent.drop(seen)
            val base = seen
            seen = sent.size
            for ((i, frame) in fresh.withIndex()) {
                val reply = firmware.handle(frame)
                if (reply != null && (base + i) !in dropReplyTo) {
                    transport.injectFrame(FromRadio.Builder().also { wb -> wb.xmodemPacket = reply }.build().toFrame())
                }
                runCurrent()
            }
        }
    }

    private fun assertNoConsecutiveRepeats(transport: FakeRadioTransport, from: Int) {
        val raw = transport.outboundFrames().drop(from).map { it.bytes }
        raw.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue(a != b, "identical consecutive write at ${from + i}")
        }
    }

    /**
     * Like [pump], but the fake device drops a write byte-identical to the previous one, as BLE
     * firmware does (`NimbleBluetooth.cpp:495`). Indexes count every ToRadio write.
     */
    private fun TestScope.pumpBle(transport: FakeRadioTransport, firmware: FirmwareXModem, op: Deferred<*>, from: Int) {
        var seen = from
        var guard = 0
        runCurrent()
        while (!op.isCompleted && guard++ < 5000) {
            val raw = transport.outboundFrames()
            if (raw.size == seen) break
            for (i in seen until raw.size) {
                val identical = i > 0 && raw[i].bytes == raw[i - 1].bytes
                val bytes = raw[i].bytes.toByteArray()
                val xmodem = runCatching {
                    ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)).xmodemPacket
                }.getOrNull()
                if (!identical && xmodem != null) {
                    firmware.handle(xmodem)?.let { reply ->
                        transport.injectFrame(
                            FromRadio.Builder().also { wb ->
                                wb.xmodemPacket = reply
                            }.build().toFrame(),
                        )
                    }
                }
                runCurrent()
            }
            seen = raw.size
        }
    }

    private fun bytesOf(size: Int): ByteString = ByteArray(size) { ((it * 31 + 5) and 0xFF).toByte() }.toByteString()

    private suspend fun TestScope.connect(client: RadioClient) {
        client.connect()
        runCurrent()
    }

    @Test
    fun downloadEndToEnd() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val content = bytesOf(1000)
        val firmware = FirmwareXModem(mutableMapOf("/prefs/a.bin" to content))

        val op = async { client.files.download("/prefs/a.bin") }
        pump(transport, firmware, op)

        assertEquals(FileTransferResult.Success(content), op.await())
        val request = sentXModem(transport).first()
        assertEquals(XModem.Control.STX, request.control)
        assertEquals(0, request.seq)
        assertEquals("/prefs/a.bin", request.buffer.utf8().substringBefore('\u0000'))
        assertNull(client.files.progress.value)
        assertTrue(sentXModem(transport).none { it.control == XModem.Control.CAN })
        runCatching { client.disconnect() }
    }

    @Test
    fun downloadExactMultipleOfBlockSizeEndToEnd() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val content = bytesOf(256)
        val firmware = FirmwareXModem(mutableMapOf("/a" to content))
        val op = async { client.files.download("/a") }
        pump(transport, firmware, op)
        assertEquals(FileTransferResult.Success(content), op.await())
        runCatching { client.disconnect() }
    }

    @Test
    fun downloadMissingFileIsRefused() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val op = async { client.files.download("/nope") }
        pump(transport, FirmwareXModem(), op)
        assertEquals(FileTransferResult.Failure(FileTransferFailure.Refused), op.await())
        runCatching { client.disconnect() }
    }

    @Test
    fun uploadEndToEnd() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val content = bytesOf(500)
        val firmware = FirmwareXModem()

        val op = async { client.files.upload("/prefs/b.bin", content) }
        pump(transport, firmware, op)

        assertEquals(FileTransferResult.Success(Unit), op.await())
        assertEquals(content, firmware.files["/prefs/b.bin"])
        val frames = sentXModem(transport)
        assertEquals(XModem.Control.SOH, frames.first().control)
        assertEquals(XModem.Control.EOT, frames.last().control)
        assertEquals(
            listOf(1, 2, 3, 4),
            frames.filter {
                it.control == XModem.Control.SOH && it.seq != 0
            }.map { it.seq },
        )
        assertNull(client.files.progress.value)
        runCatching { client.disconnect() }
    }

    @Test
    fun uploadZeroLengthFile() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val firmware = FirmwareXModem()
        val op = async { client.files.upload("/empty", ByteString.EMPTY) }
        pump(transport, firmware, op)
        assertEquals(FileTransferResult.Success(Unit), op.await())
        assertEquals(ByteString.EMPTY, firmware.files["/empty"])
        runCatching { client.disconnect() }
    }

    @Test
    fun secondTransferWhileRunningFailsBusy() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val first = async { client.files.download("/a") }
        runCurrent()
        val second = client.files.upload("/b", bytesOf(10))
        assertEquals(FileTransferResult.Failure(FileTransferFailure.Busy), second)
        assertEquals(FileTransferResult.Failure(FileTransferFailure.Busy), client.files.download("/c"))
        pump(transport, FirmwareXModem(mutableMapOf("/a" to bytesOf(3))), first)
        assertEquals(FileTransferResult.Success(bytesOf(3)), first.await())
        runCatching { client.disconnect() }
    }

    @Test
    fun cancellingUploadSendsCan() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val firmware = FirmwareXModem()
        val op = async { client.files.upload("/a", bytesOf(1000)) }
        runCurrent()
        firmware.handle(sentXModem(transport).last())?.let {
            transport.injectFrame(FromRadio.Builder().also { wb -> wb.xmodemPacket = it }.build().toFrame())
        }
        runCurrent()
        op.cancel()
        runCurrent()
        val can = sentXModem(transport).last()
        assertEquals(XModem.Control.CAN, can.control)
        // the slot stays held until the device's ACK to the CAN arrives
        assertEquals(FileTransferResult.Failure(FileTransferFailure.Busy), client.files.upload("/b", bytesOf(1)))
        transport.injectFrame(
            FromRadio.Builder().also { wb -> wb.xmodemPacket = firmware.handle(can) }.build().toFrame(),
        )
        runCurrent()
        assertNull(client.files.progress.value)
        val next = async { client.files.upload("/b", bytesOf(1)) }
        pump(transport, firmware, next, from = sentXModem(transport).size)
        assertEquals(FileTransferResult.Success(Unit), next.await())
        runCatching { client.disconnect() }
    }

    @Test
    fun cancelBeforeStartAckSendsNoCanAndNextUploadWorks() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val firmware = FirmwareXModem(mutableMapOf("/a" to bytesOf(5)))
        val op = async { client.files.upload("/b", bytesOf(500)) }
        runCurrent()
        // the request is lost; the caller cancels while waiting for the start ACK
        op.cancel()
        runCurrent()
        assertTrue(sentXModem(transport).none { it.control == XModem.Control.CAN })
        // the probe reaches the idle device: it ACKs, the SDK closes it with EOT, never CAN
        val probe = sentXModem(transport).last()
        assertEquals(0, probe.seq)
        assertEquals(XModem.Control.SOH, probe.control)
        transport.injectFrame(
            FromRadio.Builder().also { wb -> wb.xmodemPacket = firmware.handle(probe) }.build().toFrame(),
        )
        runCurrent()
        val eot = sentXModem(transport).last()
        assertEquals(XModem.Control.EOT, eot.control)
        transport.injectFrame(
            FromRadio.Builder().also { wb -> wb.xmodemPacket = firmware.handle(eot) }.build().toFrame(),
        )
        runCurrent()
        assertTrue(sentXModem(transport).none { it.control == XModem.Control.CAN })
        assertEquals(bytesOf(5), firmware.files["/a"])
        assertTrue(firmware.removedByCan.isEmpty())
        val next = async { client.files.upload("/c", bytesOf(3)) }
        pump(transport, firmware, next, from = sentXModem(transport).size)
        assertEquals(FileTransferResult.Success(Unit), next.await())
        runCatching { client.disconnect() }
    }

    @Test
    fun lostStartAckIsProbedAndLeavesTheDeviceIdle() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val firmware = FirmwareXModem(mutableMapOf("/a" to bytesOf(5)))
        val base = transport.outboundFrames().size
        val op = async { client.files.upload("/b", bytesOf(500)) }
        // firmware accepts the request but its ACK is lost
        pump(transport, firmware, op, dropReplyTo = setOf(0))
        advanceTimeBy(10_001)
        pump(transport, firmware, op, dropReplyTo = setOf(0), from = 1)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(FileTransferResult.Failure(FileTransferFailure.Timeout), op.await())
        assertNoConsecutiveRepeats(transport, base)
        val frames = sentXModem(transport)
        assertEquals(XModem.Control.CAN, frames.last().control)
        assertEquals(listOf("/b"), firmware.removedByCan)
        assertEquals(bytesOf(5), firmware.files["/a"])
        // the ACK to that CAN was consumed before the slot was released: the next upload starts clean
        val next = async { client.files.upload("/c", bytesOf(3)) }
        pump(transport, firmware, next, from = frames.size)
        assertEquals(FileTransferResult.Success(Unit), next.await())
        assertEquals(bytesOf(3), firmware.files["/c"])
        runCatching { client.disconnect() }
    }

    @Test
    fun twoBlockDownloadSucceedsWhenDeviceDropsRepeatedWrites() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val content = bytesOf(180)
        val firmware = FirmwareXModem(mutableMapOf("/prefs/config.proto" to content))
        val base = transport.outboundFrames().size
        val op = async { client.files.download("/prefs/config.proto") }
        pumpBle(transport, firmware, op, base)
        assertEquals(FileTransferResult.Success(content), op.await())
        assertNoConsecutiveRepeats(transport, base)
        runCatching { client.disconnect() }
    }

    @Test
    fun multiBlockDownloadWritesNoRepeatedFrames() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val content = bytesOf(128 * 6 + 9)
        val firmware = FirmwareXModem(mutableMapOf("/a" to content))
        val base = transport.outboundFrames().size
        val op = async { client.files.download("/a") }
        pumpBle(transport, firmware, op, base)
        assertEquals(FileTransferResult.Success(content), op.await())
        assertNoConsecutiveRepeats(transport, base)
        runCatching { client.disconnect() }
    }

    @Test
    fun repeatedNaksForOneDownloadBlockAreNotIdentical() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val content = bytesOf(200)
        val firmware = FirmwareXModem(mutableMapOf("/a" to content))
        firmware.corrupt = 3
        val base = transport.outboundFrames().size
        val op = async { client.files.download("/a") }
        pumpBle(transport, firmware, op, base)
        assertEquals(FileTransferResult.Success(content), op.await())
        assertEquals(3, sentXModem(transport).count { it.control == XModem.Control.NAK })
        assertNoConsecutiveRepeats(transport, base)
        runCatching { client.disconnect() }
    }

    @Test
    fun uploadRetransmitAfterNakIsSeparatedBySpacer() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val content = bytesOf(300)
        val firmware = FirmwareXModem()
        firmware.nakNext = 2
        val base = transport.outboundFrames().size
        val op = async { client.files.upload("/a", content) }
        pumpBle(transport, firmware, op, base)
        assertEquals(FileTransferResult.Success(Unit), op.await())
        assertEquals(content, firmware.files["/a"])
        assertNoConsecutiveRepeats(transport, base)
        val beats = transport.outboundFrames().drop(base).mapNotNull {
            val bytes = it.bytes.toByteArray()
            ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)).heartbeat
        }
        assertEquals(2, beats.size)
        assertTrue(beats.all { it.nonce >= 2 })
        assertTrue(beats[0] != beats[1])
        runCatching { client.disconnect() }
    }

    @Test
    fun overCapShortBlockLeavesNextDownloadClean() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val api = org.meshtastic.sdk.internal.FileTransferApiImpl(client, maxBytes = 200)
        val firmware = FirmwareXModem(mutableMapOf("/big" to bytesOf(250), "/next" to bytesOf(150)))
        val first = async { api.download("/big") }
        pump(transport, firmware, first)
        assertEquals(FileTransferResult.Failure(FileTransferFailure.TooLarge), first.await())
        assertTrue(!firmware.sendingEot)
        val second = async { api.download("/next") }
        pump(transport, firmware, second, from = sentXModem(transport).size)
        assertEquals(FileTransferResult.Success(bytesOf(150)), second.await())
        runCatching { client.disconnect() }
    }

    @Test
    fun cancellingDownloadKeepsSourceFile() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        val firmware = FirmwareXModem(mutableMapOf("/a" to bytesOf(1000)))
        val op = async { client.files.download("/a") }
        runCurrent()
        firmware.handle(sentXModem(transport).last())?.let {
            transport.injectFrame(FromRadio.Builder().also { wb -> wb.xmodemPacket = it }.build().toFrame())
        }
        runCurrent()
        op.cancel()
        runCurrent()
        val last = sentXModem(transport).last()
        assertTrue(last.control != XModem.Control.CAN)
        val reply = firmware.handle(last)
        assertEquals(XModem.Control.CAN, reply?.control)
        assertTrue(firmware.removedByCan.isEmpty())
        assertEquals(bytesOf(1000), firmware.files["/a"])
        runCatching { client.disconnect() }
    }

    @Test
    fun silentDeviceTimesOut() = runTest {
        val (_, client) = connectedClient()
        connect(client)
        val op = async { client.files.download("/a") }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        // the SDK then waits one more block timeout for the device's reply to its abort frame
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(FileTransferResult.Failure(FileTransferFailure.Timeout), op.await())
        runCatching { client.disconnect() }
    }

    @Test
    fun notConnectedFailsFast() = runTest {
        val (_, client) = connectedClient()
        assertEquals(FileTransferResult.Failure(FileTransferFailure.NotConnected), client.files.download("/a"))
        assertEquals(
            FileTransferResult.Failure(FileTransferFailure.NotConnected),
            client.files.upload("/a", ByteString.EMPTY),
        )
    }

    @Test
    fun invalidPathAndTooLargeFailBeforeAnyFrame() = runTest {
        val (transport, client) = connectedClient()
        connect(client)
        assertEquals(FileTransferResult.Failure(FileTransferFailure.InvalidPath), client.files.download(""))
        assertEquals(
            FileTransferResult.Failure(FileTransferFailure.InvalidPath),
            client.files.download("x".repeat(128)),
        )
        assertEquals(
            FileTransferResult.Failure(FileTransferFailure.InvalidPath),
            client.files.upload("a\u0000b", ByteString.EMPTY),
        )
        val big = ByteArray(FileTransferApi.MAX_FILE_BYTES + 1).toByteString()
        assertEquals(FileTransferResult.Failure(FileTransferFailure.TooLarge), client.files.upload("/a", big))
        assertTrue(sentXModem(transport).isEmpty())
        runCatching { client.disconnect() }
    }
}
