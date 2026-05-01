/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString.Companion.toByteString
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
