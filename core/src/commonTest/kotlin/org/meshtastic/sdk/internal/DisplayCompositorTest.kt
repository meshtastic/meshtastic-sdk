/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.DisplayFrame
import org.meshtastic.sdk.DisplayMirrorFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DisplayCompositorTest {

    private fun rect(w: Int, h: Int, rx: Int, ry: Int, rw: Int, rh: Int, fill: Int, frameId: Int = 1) =
        DisplayMirrorFrame(
            width = w,
            height = h,
            format = DisplayFrame.Format.RGB565,
            frameId = frameId,
            data = ByteArray(rw * rh * 2) { fill.toByte() }.toByteString(),
            rectX = rx,
            rectY = ry,
            rectWidth = rw,
            rectHeight = rh,
        )

    private fun ByteArray.allEqual(value: Int, range: IntRange) = range.all { this[it] == value.toByte() }

    // Regression: partial rects delivered raw, so each consumer hand-composites them.
    @Test
    fun `a rect is painted at its x and y and nothing else`() {
        val image = assertNotNull(DisplayCompositor().accept(rect(8, 4, 2, 1, 3, 2, 0xAB)))
        val bytes = image.pixels.toByteArray()
        assertEquals(64, bytes.size)
        val painted = ((1 * 8 + 2) * 2 until (1 * 8 + 2) * 2 + 6) + ((2 * 8 + 2) * 2 until (2 * 8 + 2) * 2 + 6)
        bytes.forEachIndexed { index, byte ->
            assertEquals(if (index in painted) 0xAB.toByte() else 0, byte, "byte $index")
        }
    }

    // Regression: canvas dropped between rects, so an earlier rect vanishes.
    @Test
    fun `a later rect keeps what an earlier rect painted`() {
        val compositor = DisplayCompositor()
        compositor.accept(rect(8, 4, 0, 0, 2, 1, 0x11))
        val bytes = assertNotNull(compositor.accept(rect(8, 4, 4, 2, 2, 1, 0x22, frameId = 2))).pixels.toByteArray()
        assertEquals(true, bytes.allEqual(0x11, 0 until 4))
        assertEquals(true, bytes.allEqual(0x22, (2 * 8 + 4) * 2 until (2 * 8 + 4) * 2 + 4))
    }

    // mesh.proto DisplayFrame: a zero rect_width or rect_height means the full panel on that axis.
    @Test
    fun `a zero rect width or height means the full panel on that axis`() {
        val bytes = assertNotNull(
            DisplayCompositor().accept(rect(8, 4, 0, 1, 8, 2, 0x5C).copy(rectWidth = 0)),
        ).pixels.toByteArray()
        assertEquals(true, bytes.allEqual(0x5C, 8 * 2 until 3 * 8 * 2))
        assertEquals(true, bytes.allEqual(0, 0 until 8 * 2))
        assertEquals(true, bytes.allEqual(0, 3 * 8 * 2 until 4 * 8 * 2))
    }

    // Regression: a rect whose bytes do not match its geometry is painted anyway.
    @Test
    fun `a rect whose byte count differs from its geometry is dropped`() {
        val frame = rect(8, 4, 0, 0, 2, 2, 0x01)
        val short = frame.copy(data = frame.data.substring(0, frame.data.size - 1))
        assertNull(DisplayCompositor().accept(short))
    }

    // Regression: rectX + w overflow and an unchecked copy past the canvas.
    @Test
    fun `a rect outside the panel is dropped`() {
        val compositor = DisplayCompositor()
        assertNull(compositor.accept(rect(8, 4, 7, 0, 3, 1, 0x01)))
        assertNull(compositor.accept(rect(8, 4, 0, Int.MAX_VALUE, 2, 1, 0x01)))
        assertNull(compositor.accept(rect(8, 4, -1, 0, 2, 1, 0x01)))
    }

    // Regression: device-controlled geometry driving an allocation (bytes match, so only the edge bound rejects).
    @Test
    fun `an oversized panel is dropped before it allocates`() {
        assertNull(DisplayCompositor().accept(rect(1025, 1, 0, 0, 1025, 1, 0x01)))
    }

    // Regression: a stale canvas reused after the panel size changes.
    @Test
    fun `a size change starts a fresh canvas`() {
        val compositor = DisplayCompositor()
        compositor.accept(rect(8, 4, 0, 0, 8, 4, 0x77))
        val image = assertNotNull(compositor.accept(rect(4, 4, 0, 0, 2, 1, 0x33, frameId = 2)))
        val bytes = image.pixels.toByteArray()
        assertEquals(4, image.width)
        assertEquals(4, image.height)
        assertEquals(32, bytes.size)
        assertEquals(true, bytes.allEqual(0x33, 0 until 4))
        assertEquals(true, bytes.allEqual(0, 4 until 32))
    }

    // Regression: a session end leaving the old canvas behind for the next session.
    @Test
    fun `reset forgets the canvas`() {
        val compositor = DisplayCompositor()
        compositor.accept(rect(8, 4, 0, 0, 2, 1, 0x11))
        compositor.reset()
        val bytes = assertNotNull(compositor.accept(rect(8, 4, 4, 2, 2, 1, 0x22, frameId = 2))).pixels.toByteArray()
        assertEquals(true, bytes.allEqual(0, 0 until 4))
    }

    // mesh.proto Format: MONO_VLSB is width * ceil(height / 8) bytes.
    @Test
    fun `a mono frame passes through whole and one that contradicts its geometry is dropped`() {
        val data = ByteArray(1024) { it.toByte() }.toByteString()
        val mono = DisplayMirrorFrame(128, 64, DisplayFrame.Format.MONO_VLSB, 5, data, paletteSignature = 7)
        val image = assertNotNull(DisplayCompositor().accept(mono))
        assertEquals(data, image.pixels)
        assertEquals(DisplayFrame.Format.MONO_VLSB, image.format)
        assertEquals(7, image.paletteSignature)
        assertNull(DisplayCompositor().accept(mono.copy(data = data.substring(0, 1023))))
        assertNull(DisplayCompositor().accept(mono.copy(width = 0)))
        assertNull(DisplayCompositor().accept(mono.copy(format = DisplayFrame.Format.RGB565)))
    }
}
