/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString
import org.meshtastic.proto.DisplayFrame

/**
 * The device display as one validated image, from [RadioClient.displayImage].
 *
 * [pixels] uses the layout of [format]. `MONO_VLSB`: the frame exactly as [DisplayMirrorFrame.data] delivered it
 * (1 bit per pixel in vertical LSB-first pages, `byte index = x + (y / 8) * width`, `bit index = y % 8`).
 * `RGB565`: the whole panel, `width * height * 2` bytes, little-endian, tightly packed rows, with every completed
 * partial-update rectangle painted onto it and zero where none has.
 *
 * @property width full display width in pixels
 * @property height full display height in pixels
 * @property format pixel encoding of [pixels]
 * @property frameId the [DisplayMirrorFrame.frameId] of the frame that produced this image
 * @property paletteSignature identity of the palette that colorizes a `MONO_VLSB` image, matching
 *   [org.meshtastic.proto.DisplayPalette.signature] of [RadioClient.displayPalette]; 0 for `RGB565`, which carries
 *   its color
 * @property pixels the image bytes
 * @since 0.3.2
 */
public data class DisplayImage(
    val width: Int,
    val height: Int,
    val format: DisplayFrame.Format,
    val frameId: Int,
    val paletteSignature: Int,
    val pixels: ByteString,
)
