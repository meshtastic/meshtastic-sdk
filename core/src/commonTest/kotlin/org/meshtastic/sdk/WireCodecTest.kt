/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ToRadio
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [WireCodec] framing, round-trip encode/decode, and fuzz resilience.
 */
class WireCodecTest {

    private companion object {
        private const val MAX_FRAME_SIZE = 512
        private val START1: Byte = 0x94.toByte()
        private val START2: Byte = 0xC3.toByte()
    }

    private fun encodeFromRadio(message: FromRadio): ByteArray {
        val payload = FromRadio.ADAPTER.encode(message)
        return ByteArray(4 + payload.size).apply {
            this[0] = START1
            this[1] = START2
            this[2] = (payload.size shr 8).toByte()
            this[3] = (payload.size and 0xFF).toByte()
            payload.copyInto(this, destinationOffset = 4)
        }
    }

    private fun withRepeatedStart1s(frame: ByteArray, count: Int): ByteArray =
        ByteArray(count) { START1 } + frame.copyOfRange(1, frame.size)

    private fun serializedSize(message: ToRadio): Int = ToRadio.ADAPTER.encode(message).size

    private fun toRadioWithSerializedSize(targetSize: Int): ToRadio {
        val builders = listOf<(ByteArray) -> ToRadio>(
            { payload -> ToRadio(packet = MeshPacket(decoded = Data(payload = payload.toByteString()))) },
            { payload -> ToRadio(packet = MeshPacket(channel = 1, decoded = Data(payload = payload.toByteString()))) },
            { payload -> ToRadio(packet = MeshPacket(to = 1, decoded = Data(payload = payload.toByteString()))) },
            { payload ->
                ToRadio(
                    packet = MeshPacket(
                        from = 1,
                        to = 1,
                        channel = 1,
                        decoded = Data(payload = payload.toByteString()),
                    ),
                )
            },
        )

        for (payloadSize in 0..1024) {
            val payload = ByteArray(payloadSize) { ((it * 31) and 0xFF).toByte() }
            for (builder in builders) {
                val message = builder(payload)
                if (serializedSize(message) == targetSize) return message
            }
        }

        error("Could not construct ToRadio with serialized size $targetSize")
    }

    // ── Basic header / framing ────────────────────────────────────────────────

    @Test
    fun testEncodeToRadio() {
        val msg = ToRadio()
        val frame = WireCodec.encodeToRadio(msg)
        assertEquals(0x94.toByte(), frame[0])
        assertEquals(0xC3.toByte(), frame[1])
        assertTrue(frame.size >= 4)
    }

    @Test
    fun testFrameSizeDoesNotExceedMax() {
        val frame = WireCodec.encodeToRadio(ToRadio())
        assertTrue(frame.size <= 4 + 512)
    }

    @Test
    fun testEnvelopeTooLargeThrowsProtocol() {
        // P2-5 (audit §1.4): envelope-overflow is a wire-layer protocol error, not an
        // application-payload error. `MeshtasticException.PayloadTooLarge` is reserved for
        // the `RadioClient.send` path which enforces `DATA_PAYLOAD_LEN` (233 bytes).
        val bigPayload = ByteArray(513)
        val packet = MeshPacket(
            decoded = org.meshtastic.proto.Data(payload = bigPayload.toByteString()),
        )
        assertFailsWith<MeshtasticException.Protocol> {
            WireCodec.encodeToRadio(ToRadio(packet = packet))
        }
    }

    // ── Round-trip encode → decode ────────────────────────────────────────────

    private fun roundTrip(msg: ToRadio) = WireCodec.FrameDecoder().feedBytes(WireCodec.encodeToRadio(msg)).firstOrNull()

    @Test
    fun testRoundTripEmptyToRadio() {
        assertNotNull(roundTrip(ToRadio()))
    }

    @Test
    fun testRoundTripHeartbeat() {
        // A ToRadio with heartbeat may not decode as FromRadio (different proto shapes);
        // verify only that the frame is well-formed (correct header + length encoding).
        val msg = ToRadio(heartbeat = Heartbeat())
        val frame = WireCodec.encodeToRadio(msg)
        val payload = msg.adapter.encode(msg)
        assertEquals(0x94.toByte(), frame[0])
        assertEquals(0xC3.toByte(), frame[1])
        assertEquals((payload.size shr 8).toByte(), frame[2])
        assertEquals((payload.size and 0xFF).toByte(), frame[3])
        assertEquals(4 + payload.size, frame.size)
    }

    @Test
    fun testRoundTripMeshPacket() {
        // Verifies framing is well-formed; ToRadio/FromRadio may have different schemas.
        val packet = MeshPacket(to = 0xFFFFFFFF.toInt(), channel = 3)
        val msg = ToRadio(packet = packet)
        val frame = WireCodec.encodeToRadio(msg)
        val payload = msg.adapter.encode(msg)
        assertEquals(4 + payload.size, frame.size)
        assertEquals(0x94.toByte(), frame[0])
        assertEquals(0xC3.toByte(), frame[1])
    }

    @Test
    fun testRoundTripMultipleFrames() {
        // Use empty ToRadio so each frame decodes cleanly as empty FromRadio.
        val combined = WireCodec.encodeToRadio(ToRadio()) + WireCodec.encodeToRadio(ToRadio())
        val results = WireCodec.FrameDecoder().feedBytes(combined)
        assertEquals(2, results.size, "Two concatenated frames must decode to exactly 2 messages")
    }

    @Test
    fun testRoundTripWithGarbagePrefix() {
        val garbage = byteArrayOf(0x00, 0xFF.toByte(), 0x12, 0x34)
        val results = WireCodec.FrameDecoder().feedBytes(garbage + WireCodec.encodeToRadio(ToRadio()))
        assertEquals(1, results.size, "Decoder must recover from leading garbage")
    }

    @Test
    fun testRoundTripWithGarbageBetweenFrames() {
        val frame1 = WireCodec.encodeToRadio(ToRadio())
        val garbage = byteArrayOf(0x00, 0x11, 0x22)
        val frame2 = WireCodec.encodeToRadio(ToRadio())
        val results = WireCodec.FrameDecoder().feedBytes(frame1 + garbage + frame2)
        assertEquals(2, results.size, "Decoder must recover from garbage between frames")
    }

    // ── FrameDecoder resync ───────────────────────────────────────────────────

    @Test
    fun testFrameDecoderSync() {
        val decoder = WireCodec.FrameDecoder()
        assertNull(decoder.feed(0xFF.toByte()))
        assertNull(decoder.feed(0xFF.toByte()))
        assertNull(decoder.feed(0x94.toByte()))
        assertNull(decoder.feed(0xC3.toByte()))
    }

    @Test
    fun testFrameDecoderRejectsBogusLength() {
        val decoder = WireCodec.FrameDecoder()
        decoder.feed(0x94.toByte())
        decoder.feed(0xC3.toByte())
        decoder.feed(0xFF.toByte())
        assertNull(decoder.feed(0xFF.toByte())) // 0xFFFF > 512 → resync
    }

    @Test
    fun testFrameDecoderResetsAfterBogusLength() {
        val decoder = WireCodec.FrameDecoder()
        decoder.feed(0x94.toByte())
        decoder.feed(0xC3.toByte())
        decoder.feed(0xFF.toByte())
        decoder.feed(0xFF.toByte())
        val results = decoder.feedBytes(WireCodec.encodeToRadio(ToRadio()))
        assertEquals(1, results.size, "Decoder must resync and parse a valid frame after bogus length")
    }

    @Test
    fun testFrameDecoderZeroLengthFrame() {
        val zeroFrame = byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x00, 0x00)
        val results = WireCodec.FrameDecoder().feedBytes(zeroFrame)
        assertEquals(1, results.size, "Zero-length frame should emit exactly one (empty) FromRadio")
    }

    @Test
    fun testDecoderReset() {
        val decoder = WireCodec.FrameDecoder()
        decoder.feed(0x94.toByte())
        decoder.feed(0xC3.toByte())
        decoder.feed(0x00.toByte())
        decoder.reset()
        val results = decoder.feedBytes(WireCodec.encodeToRadio(ToRadio()))
        assertEquals(1, results.size, "After reset, decoder must handle a fresh valid frame")
    }

    @Test
    fun testExactMaxFrameSize() {
        val exactMax = toRadioWithSerializedSize(MAX_FRAME_SIZE)
        val frame = WireCodec.encodeToRadio(exactMax)

        assertEquals(MAX_FRAME_SIZE, serializedSize(exactMax))
        assertEquals(4 + MAX_FRAME_SIZE, frame.size)
        assertEquals(0x02.toByte(), frame[2])
        assertEquals(0x00.toByte(), frame[3])

        assertFailsWith<MeshtasticException.Protocol> {
            WireCodec.encodeToRadio(toRadioWithSerializedSize(MAX_FRAME_SIZE + 1))
        }
    }

    @Test
    fun testResyncAfterCorruptionMidPayload() {
        val decoder = WireCodec.FrameDecoder()
        val partialFrame = encodeFromRadio(FromRadio(id = 1)).copyOfRange(0, 5)
        val recoveredFrame = encodeFromRadio(FromRadio(id = 42))

        assertTrue(decoder.feedBytes(partialFrame).isEmpty())
        assertTrue(decoder.feedBytes(byteArrayOf(0x80.toByte())).isEmpty())

        val results = decoder.feedBytes(recoveredFrame)
        assertEquals(listOf(FromRadio(id = 42)), results)
    }

    @Test
    fun testMultipleConsecutiveStart1Bytes() {
        val frame = withRepeatedStart1s(encodeFromRadio(FromRadio(id = 7)), count = 5)

        val results = WireCodec.FrameDecoder().feedBytes(frame)

        assertEquals(listOf(FromRadio(id = 7)), results)
    }

    @Test
    fun testBackToBackZeroLengthFrames() {
        val zeroFrame = byteArrayOf(START1, START2, 0x00, 0x00)

        val results = WireCodec.FrameDecoder().feedBytes(zeroFrame + zeroFrame + zeroFrame)

        assertEquals(3, results.size)
        assertTrue(results.all { it == FromRadio() })
    }

    @Test
    fun testFeedBytesReturnsCorrectCountForMixedValidInvalid() {
        val valid1 = encodeFromRadio(FromRadio(id = 1))
        val valid2 = encodeFromRadio(FromRadio(id = 2))
        val zeroFrame = byteArrayOf(START1, START2, 0x00, 0x00)
        val garbage = byteArrayOf(0x00, 0x7F, 0x01, 0x55)
        val malformedFrame = byteArrayOf(START1, START2, 0x00, 0x02, 0x08, 0x80.toByte())
        val truncatedFrame = encodeFromRadio(FromRadio(id = 9)).copyOfRange(0, 5)

        val results = WireCodec.FrameDecoder().feedBytes(
            valid1 + garbage + malformedFrame + valid2 + zeroFrame + truncatedFrame,
        )

        assertEquals(listOf(FromRadio(id = 1), FromRadio(id = 2), FromRadio()), results)
    }

    @Test
    fun testPartialFrameThenValidFrameNoReset() {
        val decoder = WireCodec.FrameDecoder()
        val partialFrame = encodeFromRadio(FromRadio(id = 1)).copyOfRange(0, 5)
        val recoveredFrame = withRepeatedStart1s(encodeFromRadio(FromRadio(id = 77)), count = 5)

        assertTrue(decoder.feedBytes(partialFrame).isEmpty())

        val results = decoder.feedBytes(recoveredFrame)

        assertEquals(listOf(FromRadio(id = 77)), results)
    }

    @Test
    fun testStart1AppearingAsPayloadByte() {
        val message = FromRadio(
            packet = MeshPacket(
                decoded = Data(payload = byteArrayOf(START1).toByteString()),
            ),
        )
        val payload = FromRadio.ADAPTER.encode(message)

        assertTrue(payload.contains(START1))
        assertEquals(listOf(message), WireCodec.FrameDecoder().feedBytes(encodeFromRadio(message)))
    }

    @Test
    fun testLargePayloadNearBoundary() {
        val frame511 = WireCodec.encodeToRadio(toRadioWithSerializedSize(MAX_FRAME_SIZE - 1))
        val frame512 = WireCodec.encodeToRadio(toRadioWithSerializedSize(MAX_FRAME_SIZE))

        assertEquals(4 + MAX_FRAME_SIZE - 1, frame511.size)
        assertEquals(4 + MAX_FRAME_SIZE, frame512.size)
        assertFailsWith<MeshtasticException.Protocol> {
            WireCodec.encodeToRadio(toRadioWithSerializedSize(MAX_FRAME_SIZE + 1))
        }
    }

    // ── Fuzz tests: random bytes must never crash the decoder ─────────────────

    @Test
    fun testFuzzRandomBytes() {
        val decoder = WireCodec.FrameDecoder()
        val rng = Random(seed = 0x4d657368L)
        repeat(10_000) { decoder.feed(rng.nextInt(256).toByte()) }
        // Success = no exception thrown
    }

    @Test
    fun testFuzzRandomBytesWithEmbeddedValidFrames() {
        val rng = Random(seed = 0xC3940000L)
        val validFrame = WireCodec.encodeToRadio(ToRadio())
        val decoder = WireCodec.FrameDecoder()
        var decoded = 0
        repeat(20) {
            repeat(rng.nextInt(50)) { decoder.feed(rng.nextInt(256).toByte()) }
            decoded += decoder.feedBytes(validFrame).size
        }
        assertTrue(decoded >= 0, "Fuzz+valid frame test must not throw")
    }

    @Test
    fun testFuzzSyncBytesOnlyNoCrash() {
        val decoder = WireCodec.FrameDecoder()
        repeat(1000) { decoder.feed(0x94.toByte()) }
    }

    @Test
    fun testFuzzPartialFrameThenReset() {
        val rng = Random(seed = 42L)
        val frame = WireCodec.encodeToRadio(ToRadio())
        repeat(100) {
            val decoder = WireCodec.FrameDecoder()
            val partial = rng.nextInt(frame.size)
            for (i in 0 until partial) decoder.feed(frame[i])
            decoder.reset()
        }
    }
}
