/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal.sfpp

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.SfppHash

/** One reassembled, hash-verified replay: the original ciphertext plus what the archive said about it. */
internal class SfppReplay(
    val from: Int,
    val to: Int,
    val id: Int,
    val ciphertext: ByteArray,
    val heardAgoS: Long,
    val originVerified: Boolean,
    /** The node that sent frame 0, which carried the envelope. */
    val archive: Int,
    /** Frame 0 arrived signed by the archive or PKI-encrypted to us. */
    val envelopeAttested: Boolean,
)

/**
 * Rebuilds replayed messages from `ITEM_FRAME`s, as firmware `sfppframe::Reassembler` does.
 *
 * Frames are keyed by the first eight bytes of the message hash, so frames of one message from
 * different archives complete each other. A set completes when every index and frame 0's envelope
 * have arrived; it is then verified against the key and discarded whole on a mismatch, never
 * decrypted. Slots are least-recently-used and expire [deadlineMs] after their first frame.
 */
internal class SfppFrameAssembler(
    private val slots: Int = DEFAULT_SLOTS,
    private val deadlineMs: Long = DEFAULT_DEADLINE_MS,
) {
    private class Slot(val total: Int, val deadline: Long) {
        val parts = arrayOfNulls<ByteArray>(total)
        var envelope: SfppFrame? = null
        var archive: Int = 0
        var attested: Boolean = false
    }

    private val open = LinkedHashMap<ByteString, Slot>()

    /** Open slots, for tests and diagnostics. */
    val openSlots: Int get() = open.size

    /**
     * Feeds one frame sent by [sender]. [attested] says whether its outer packet was signed or
     * PKI-encrypted. Returns the replay when this frame completed a verified set.
     */
    fun offer(frame: SfppFrame, sender: Int, attested: Boolean, nowMs: Long): SfppReplay? {
        expire(nowMs)
        val position = position(frame) ?: return null
        val index = position and 0xFF
        val total = position ushr 8
        val key = frame.messageHash.copyOf(KEY_BYTES).toByteString()
        // A total that disagrees with the open slot's is dropped before it counts as a use, as firmware does.
        if (open[key]?.let { it.total != total } == true) return null
        val slot = open.remove(key) ?: run {
            if (open.size >= slots) open.remove(open.keys.first())
            Slot(total, nowMs + deadlineMs)
        }
        // Reinserting keeps the map in least-recently-used order.
        open[key] = slot
        if (slot.parts[index] != null) return null
        slot.parts[index] = frame.message
        if (index == 0) {
            slot.envelope = frame
            slot.archive = sender
            slot.attested = attested
        }
        val envelope = slot.envelope ?: return null
        if (slot.parts.any { it == null }) return null

        open.remove(key)
        val ciphertext = slot.parts.fold(ByteArray(0)) { acc, part -> acc + part!! }
        val to = if (envelope.encapsulatedTo == 0) BROADCAST else envelope.encapsulatedTo
        val hash = SfppHash.compute(ciphertext, to, envelope.encapsulatedFrom, envelope.encapsulatedId)
        if (hash.copyOf(KEY_BYTES).toByteString() != key) return null
        return SfppReplay(
            from = envelope.encapsulatedFrom,
            to = to,
            id = envelope.encapsulatedId,
            ciphertext = ciphertext,
            heardAgoS = envelope.heardAgoS,
            originVerified = envelope.originVerified,
            archive = slot.archive,
            envelopeAttested = slot.attested,
        )
    }

    // The frame's index and total packed as `index | total << 8` (a single frame is 0 of 1), or null if invalid.
    private fun position(frame: SfppFrame): Int? {
        if (frame.type != SfppWire.ITEM_FRAME) return null
        if (frame.messageHash.size < KEY_BYTES || frame.message.isEmpty()) return null
        if (frame.frame == 0) return 1 shl 8
        val index = frame.frame and 0xFF
        val total = (frame.frame ushr 8) and 0xFF
        return if (total in 1..MAX_FRAMES && index < total) index or (total shl 8) else null
    }

    private fun expire(nowMs: Long) {
        val iterator = open.values.iterator()
        while (iterator.hasNext()) {
            // Firmware Throttle::deadlinePassedAt: a deadline has passed once now reaches it.
            if (nowMs >= iterator.next().deadline) iterator.remove()
        }
    }

    internal companion object {
        const val DEFAULT_SLOTS: Int = 16
        const val DEFAULT_DEADLINE_MS: Long = 120_000
        const val MAX_FRAMES: Int = 4
        const val KEY_BYTES: Int = 8
        const val BROADCAST: Int = -1
    }
}
