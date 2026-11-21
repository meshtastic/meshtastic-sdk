/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.DisplayFrame
import org.meshtastic.proto.DisplayPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DisplayReassemblerTest {

    private fun bytes(range: IntRange): ByteString = ByteArray(range.count()) {
        (range.first + it).toByte()
    }.toByteString()

    private fun chunk(
        frameId: Int,
        offset: Int,
        data: ByteString,
        total: Int,
        paletteSignature: Int = 0,
    ): DisplayFrame = DisplayFrame.Builder().also { wb ->
        wb.width = 128
        wb.height = 64
        wb.format = DisplayFrame.Format.MONO_VLSB
        wb.frame_id = frameId
        wb.offset = offset
        wb.total_size = total
        wb.data_ = data
        wb.palette_signature = paletteSignature
    }.build()

    @Test
    fun singleChunkFrameCompletesImmediately() {
        val r = DisplayFrameReassembler()
        val out = r.accept(chunk(frameId = 1, offset = 0, data = bytes(0..9), total = 10, paletteSignature = 7))
        val complete = assertIs<DisplayFrameReassembler.Outcome.Complete>(out)
        assertEquals(bytes(0..9), complete.frame.data)
        assertEquals(128, complete.frame.width)
        assertEquals(64, complete.frame.height)
        assertEquals(DisplayFrame.Format.MONO_VLSB, complete.frame.format)
        assertEquals(1, complete.frame.frameId)
        assertEquals(7, complete.frame.paletteSignature)
    }

    @Test
    fun multiChunkFrameCompletesOnFinalChunk() {
        val r = DisplayFrameReassembler()
        assertIs<DisplayFrameReassembler.Outcome.Incomplete>(r.accept(chunk(5, 0, bytes(0..3), 10)))
        assertIs<DisplayFrameReassembler.Outcome.Incomplete>(r.accept(chunk(5, 4, bytes(4..7), 10)))
        val out = r.accept(chunk(5, 8, bytes(8..9), 10))
        assertEquals(bytes(0..9), assertIs<DisplayFrameReassembler.Outcome.Complete>(out).frame.data)
    }

    @Test
    fun newFrameIdAbandonsIncompleteFrame() {
        val r = DisplayFrameReassembler()
        r.accept(chunk(5, 0, bytes(0..3), 10))
        val out = r.accept(chunk(6, 0, bytes(20..29), 10))
        val complete = assertIs<DisplayFrameReassembler.Outcome.Complete>(out)
        assertEquals(6, complete.frame.frameId)
        assertEquals(bytes(20..29), complete.frame.data)
    }

    @Test
    fun staleTailOfAbandonedFrameIsDropped() {
        val r = DisplayFrameReassembler()
        r.accept(chunk(5, 0, bytes(0..3), 10))
        r.accept(chunk(6, 0, bytes(20..24), 10))
        assertIs<DisplayFrameReassembler.Outcome.Dropped>(r.accept(chunk(5, 4, bytes(4..9), 10)))
    }

    @Test
    fun gapDropsFrame() {
        val r = DisplayFrameReassembler()
        r.accept(chunk(5, 0, bytes(0..3), 10))
        assertIs<DisplayFrameReassembler.Outcome.Dropped>(r.accept(chunk(5, 6, bytes(6..9), 10)))
        // the partial is gone: the missing middle never completes the frame
        assertIs<DisplayFrameReassembler.Outcome.Dropped>(r.accept(chunk(5, 4, bytes(4..9), 10)))
    }

    @Test
    fun midFrameChunkWithoutStartIsDropped() {
        val r = DisplayFrameReassembler()
        assertIs<DisplayFrameReassembler.Outcome.Dropped>(r.accept(chunk(9, 4, bytes(4..9), 10)))
    }

    @Test
    fun oversizeTotalIsDropped() {
        val r = DisplayFrameReassembler(maxFrameBytes = 16)
        assertIs<DisplayFrameReassembler.Outcome.Dropped>(r.accept(chunk(1, 0, bytes(0..3), 17)))
    }

    @Test
    fun chunkPastTotalIsDropped() {
        val r = DisplayFrameReassembler()
        assertIs<DisplayFrameReassembler.Outcome.Dropped>(r.accept(chunk(1, 0, bytes(0..11), 10)))
    }

    @Test
    fun totalSizeChangeWithinFrameIsDropped() {
        val r = DisplayFrameReassembler()
        r.accept(chunk(5, 0, bytes(0..3), 10))
        assertIs<DisplayFrameReassembler.Outcome.Dropped>(r.accept(chunk(5, 4, bytes(4..9), 12)))
    }

    @Test
    fun aPaletteSignatureChangeWithinAFrameIsDropped() {
        val r = DisplayFrameReassembler()
        r.accept(chunk(5, 0, bytes(0..3), 10, paletteSignature = 1))
        assertIs<DisplayFrameReassembler.Outcome.Dropped>(r.accept(chunk(5, 4, bytes(4..9), 10, paletteSignature = 2)))
    }

    @Test
    fun aRectangleChangeWithinAFrameIsDropped() {
        val r = DisplayFrameReassembler()
        r.accept(chunk(5, 0, bytes(0..3), 10).newBuilder().also { it.rect_x = 8 }.build())
        val moved = chunk(5, 4, bytes(4..9), 10).newBuilder().also { it.rect_x = 16 }.build()
        assertIs<DisplayFrameReassembler.Outcome.Dropped>(r.accept(moved))
    }

    @Test
    fun resetDiscardsPartialFrame() {
        val r = DisplayFrameReassembler()
        r.accept(chunk(5, 0, bytes(0..3), 10))
        r.reset()
        assertIs<DisplayFrameReassembler.Outcome.Dropped>(r.accept(chunk(5, 4, bytes(4..9), 10)))
    }

    @Test
    fun rectFieldsPassThrough() {
        val r = DisplayFrameReassembler()
        val c = chunk(1, 0, bytes(0..3), 4).newBuilder().also { wb ->
            wb.format = DisplayFrame.Format.RGB565
            wb.rect_x = 10
            wb.rect_y = 20
            wb.rect_width = 2
            wb.rect_height = 1
        }.build()
        val frame = assertIs<DisplayFrameReassembler.Outcome.Complete>(r.accept(c)).frame
        assertEquals(10, frame.rectX)
        assertEquals(20, frame.rectY)
        assertEquals(2, frame.rectWidth)
        assertEquals(1, frame.rectHeight)
    }

    // ---- palette ----

    private fun region(x: Int): DisplayPalette.ColorRegion = DisplayPalette.ColorRegion.Builder().also { wb ->
        wb.x = x
        wb.width = 8
        wb.height = 8
        wb.on_color = 0xFFFF
    }.build()

    private fun paletteChunk(signature: Int, offset: Int, total: Int, vararg xs: Int): DisplayPalette =
        DisplayPalette.Builder().also { wb ->
            wb.signature = signature
            wb.default_on_color = 0x1234
            wb.default_off_color = 0x4321
            wb.region_offset = offset
            wb.region_total = total
            wb.regions = xs.map(::region)
        }.build()

    @Test
    fun paletteChunksAssembleIntoOnePalette() {
        val r = DisplayPaletteReassembler()
        assertNull(r.accept(paletteChunk(3, 0, 3, 0, 1)))
        val whole = r.accept(paletteChunk(3, 2, 3, 2))
        assertEquals(listOf(0, 1, 2), whole!!.regions.map { it.x })
        assertEquals(3, whole.signature)
        assertEquals(3, whole.region_total)
        assertEquals(0x1234, whole.default_on_color)
    }

    @Test
    fun paletteSignatureChangeDiscardsPartial() {
        val r = DisplayPaletteReassembler()
        r.accept(paletteChunk(3, 0, 3, 0, 1))
        assertNull(r.accept(paletteChunk(4, 2, 3, 2)))
        val whole = r.accept(paletteChunk(4, 0, 1, 9))
        assertEquals(listOf(9), whole!!.regions.map { it.x })
    }

    @Test
    fun aRegionTotalChangeDiscardsThePartialPalette() {
        val r = DisplayPaletteReassembler()
        assertNull(r.accept(paletteChunk(3, 0, 3, 0)))
        assertNull(r.accept(paletteChunk(3, 1, 2, 1)), "a continuation declaring a different total is rejected")
    }

    @Test
    fun paletteGapDropsPartial() {
        val r = DisplayPaletteReassembler()
        r.accept(paletteChunk(3, 0, 4, 0, 1))
        assertNull(r.accept(paletteChunk(3, 3, 4, 3)))
        assertNull(r.accept(paletteChunk(3, 2, 4, 2, 3)))
    }
}
