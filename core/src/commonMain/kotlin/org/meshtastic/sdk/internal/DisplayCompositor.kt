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
import org.meshtastic.sdk.DisplayImage
import org.meshtastic.sdk.DisplayMirrorFrame

/** Folds complete display frames into the latest [DisplayImage], dropping any whose geometry contradicts its bytes. */
internal class DisplayCompositor {
    private var canvas: ByteArray? = null
    private var panelWidth = 0
    private var panelHeight = 0

    /** The image after [frame], or `null` when [frame] is malformed (the previous image stays current). */
    fun accept(frame: DisplayMirrorFrame): DisplayImage? = when (frame.format) {
        DisplayFrame.Format.RGB565 -> paintRect(frame)
        DisplayFrame.Format.MONO_VLSB -> monoImage(frame)
        else -> null
    }

    fun reset() {
        canvas = null
        panelWidth = 0
        panelHeight = 0
    }

    private fun monoImage(frame: DisplayMirrorFrame): DisplayImage? {
        val size = frame.data.size.toLong()
        val expected = frame.width.toLong() * ((frame.height.toLong() + PIXELS_PER_PAGE - 1) / PIXELS_PER_PAGE)
        val valid = frame.width > 0 && frame.height > 0 && expected == size && size <= MAX_MONO_BYTES
        return if (valid) {
            DisplayImage(frame.width, frame.height, frame.format, frame.frameId, frame.paletteSignature, frame.data)
        } else {
            null
        }
    }

    private fun paintRect(frame: DisplayMirrorFrame): DisplayImage? {
        val w = if (frame.rectWidth > 0) frame.rectWidth else frame.width
        val h = if (frame.rectHeight > 0) frame.rectHeight else frame.height
        if (!withinPanel(frame, w, h)) return null
        val expected = w * h * 2
        if (expected != frame.data.size || expected > MAX_RECT_BYTES) return null
        val target = canvasFor(frame)
        val rect = frame.data.toByteArray()
        val rowBytes = w * 2
        for (row in 0 until h) {
            val destination = ((frame.rectY + row) * panelWidth + frame.rectX) * 2
            rect.copyInto(target, destination, row * rowBytes, (row + 1) * rowBytes)
        }
        return DisplayImage(panelWidth, panelHeight, frame.format, frame.frameId, 0, target.toByteString())
    }

    private fun canvasFor(frame: DisplayMirrorFrame): ByteArray {
        val held = canvas
        if (held != null && panelWidth == frame.width && panelHeight == frame.height) return held
        panelWidth = frame.width
        panelHeight = frame.height
        return ByteArray(frame.width * frame.height * 2).also { canvas = it }
    }

    /** Subtraction form throughout: `rectX + w` overflows for hostile values. */
    private fun withinPanel(frame: DisplayMirrorFrame, w: Int, h: Int): Boolean =
        frame.width in 1..MAX_PANEL_EDGE && frame.height in 1..MAX_PANEL_EDGE &&
            w in 1..frame.width && h in 1..frame.height &&
            frame.rectX in 0..(frame.width - w) && frame.rectY in 0..(frame.height - h)
}

// Largest realistic RGB565 panel is 800x480; the bound keeps device-controlled geometry from driving an allocation.
private const val MAX_PANEL_EDGE = 1024
private const val MAX_RECT_BYTES = MAX_PANEL_EDGE * MAX_PANEL_EDGE * 2
private const val MAX_MONO_BYTES = 65_535 // ScreenMirror::onRendered refuses larger mono frames
private const val PIXELS_PER_PAGE = 8
