/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import okio.Buffer
import org.meshtastic.proto.DisplayFrame
import org.meshtastic.proto.DisplayPalette
import org.meshtastic.sdk.DisplayMirrorFrame

/** Largest frame (or partial-update rectangle) accepted, in bytes; 1 MiB covers any panel the firmware mirrors. */
internal const val MAX_DISPLAY_FRAME_BYTES: Int = 1024 * 1024

/**
 * Joins `FromRadio.display_frame` chunks into whole frames. Single-writer: called only from the
 * engine actor, so it holds plain state.
 *
 * Firmware sends the chunks of one frame contiguously and in offset order, but loss is possible, so
 * a chunk is accepted only when its offset equals the bytes already held for its `frame_id`; any
 * gap, `frame_id` change or metadata change discards the partial frame. A frame completes when
 * `offset + data.size == total_size`.
 */
internal class DisplayFrameReassembler(private val maxFrameBytes: Int = MAX_DISPLAY_FRAME_BYTES) {

    sealed interface Outcome {
        /** The chunk was accepted and the frame needs more chunks. */
        data object Incomplete : Outcome

        /** The chunk completed [frame]. */
        data class Complete(val frame: DisplayMirrorFrame) : Outcome

        /** The chunk was rejected and any partial frame discarded, for [reason]. */
        data class Dropped(val reason: String) : Outcome
    }

    private var current: DisplayFrame? = null
    private val buffer = Buffer()

    fun reset() {
        current = null
        buffer.clear()
    }

    fun accept(chunk: DisplayFrame): Outcome {
        val offset = chunk.offset
        val size = chunk.data_.size
        val total = chunk.total_size

        // offset 0 always starts a frame, abandoning whatever was in flight.
        if (offset == 0) reset()
        val head = current

        if (total <= 0 || total > maxFrameBytes) {
            reset()
            return Outcome.Dropped("total_size $total outside 1..$maxFrameBytes")
        }
        if (size == 0) {
            reset()
            return Outcome.Dropped("empty chunk for frame ${chunk.frame_id}")
        }
        if (head == null) {
            if (offset != 0) {
                return Outcome.Dropped("chunk at offset $offset of frame ${chunk.frame_id} without its start")
            }
        } else if (head.frame_id != chunk.frame_id) {
            reset()
            return Outcome.Dropped("chunk of frame ${chunk.frame_id} interrupted frame ${head.frame_id}")
        } else if (head.total_size != total || head.format != chunk.format) {
            reset()
            return Outcome.Dropped("frame ${chunk.frame_id} changed shape mid-frame")
        } else if (offset.toLong() != buffer.size) {
            val held = buffer.size
            reset()
            return Outcome.Dropped("gap in frame ${chunk.frame_id}: offset $offset, held $held")
        }
        if (offset.toLong() + size > total) {
            reset()
            return Outcome.Dropped("frame ${chunk.frame_id} overruns total_size $total")
        }

        if (head == null) current = chunk
        buffer.write(chunk.data_)
        if (buffer.size < total) return Outcome.Incomplete

        val first = checkNotNull(current)
        val data = buffer.readByteString()
        reset()
        return Outcome.Complete(
            DisplayMirrorFrame(
                width = first.width,
                height = first.height,
                format = first.format,
                frameId = first.frame_id,
                data = data,
                rectX = first.rect_x,
                rectY = first.rect_y,
                rectWidth = first.rect_width,
                rectHeight = first.rect_height,
                paletteSignature = first.palette_signature,
            ),
        )
    }
}

/**
 * Joins `FromRadio.display_palette` chunks into one [DisplayPalette]. A chunk whose `signature` or
 * `region_offset` does not continue the held partial discards it; a palette completes when
 * `region_offset + regions.size == region_total`.
 */
internal class DisplayPaletteReassembler {
    private var head: DisplayPalette? = null
    private val regions = ArrayList<DisplayPalette.ColorRegion>()

    fun reset() {
        head = null
        regions.clear()
    }

    /** Returns the whole palette once [chunk] completes it, else `null`. */
    fun accept(chunk: DisplayPalette): DisplayPalette? {
        if (chunk.region_offset == 0) {
            reset()
        } else if (head?.signature != chunk.signature || chunk.region_offset != regions.size) {
            reset()
            return null
        }
        val total = chunk.region_total
        if (chunk.region_offset + chunk.regions.size > total) {
            reset()
            return null
        }
        if (head == null) head = chunk
        regions.addAll(chunk.regions)
        if (regions.size < total) return null

        val first = checkNotNull(head)
        val whole = first.newBuilder().also { wb ->
            wb.region_offset = 0
            wb.regions = regions.toList()
        }.build()
        reset()
        return whole
    }
}
