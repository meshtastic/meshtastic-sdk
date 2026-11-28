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
 * One fully reassembled frame of the device display, delivered on [RadioClient.displayFrames].
 *
 * The SDK joins the firmware's `FromRadio.display_frame` chunks and hands over the bytes as sent;
 * it does not decode or decompress them, and [RadioClient.displayImage] composites partial rectangles
 * for a consumer that wants a whole image. An incomplete frame is never delivered.
 *
 * [data] is laid out per [format]: `MONO_VLSB` is 1 bit per pixel in vertical LSB-first pages
 * (`byte index = x + (y / 8) * width`, `bit index = y % 8`); `RGB565` is little-endian, tightly
 * packed rows. When [rectWidth] and [rectHeight] are both 0 the frame covers the full panel;
 * otherwise [data] holds only the rectangle at ([rectX], [rectY]). A zero rect dimension on a
 * single axis means the full panel on that axis.
 *
 * @property width full display width in pixels
 * @property height full display height in pixels
 * @property format pixel encoding of [data]
 * @property frameId the device's frame counter; any change marks a new frame, not an ordering
 * @property data the reassembled framebuffer bytes
 * @property rectX left edge of the partial-update rectangle
 * @property rectY top edge of the partial-update rectangle
 * @property rectWidth width of the partial-update rectangle, 0 for the full panel
 * @property rectHeight height of the partial-update rectangle, 0 for the full panel
 * @property paletteSignature identity of the palette that colorizes this frame, matching
 *   [org.meshtastic.proto.DisplayPalette.signature] of [RadioClient.displayPalette]; 0 means
 *   monochrome
 * @since 0.3.0
 */
public data class DisplayMirrorFrame(
    val width: Int,
    val height: Int,
    val format: DisplayFrame.Format,
    val frameId: Int,
    val data: ByteString,
    val rectX: Int = 0,
    val rectY: Int = 0,
    val rectWidth: Int = 0,
    val rectHeight: Int = 0,
    val paletteSignature: Int = 0,
)
