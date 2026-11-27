/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal.sfpp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Spec §4 rules, against firmware `sfppframe::Reassembler` behaviour. */
class SfppFrameAssemblerTest {
    private val ct = SfppVector.ctrDefault
    private val hash = SfppVector.ctrDefaultHash
    private val archiveA = 0x0A0A0A0A
    private val archiveB = 0x0B0B0B0B

    private fun slices(vararg cuts: Int): List<ByteArray> {
        val bounds = listOf(0) + cuts.toList() + ct.size
        return bounds.zipWithNext { a, b -> ct.copyOfRange(a, b) }
    }

    @Test
    fun singleFrameWithBroadcastAsZeroCompletesAndVerifies() {
        val replay = assertNotNull(
            SfppFrameAssembler().offer(itemFrame(hash, ct, heardAgoS = 600), archiveA, attested = false, nowMs = 0),
        )
        assertEquals(-1, replay.to)
        assertEquals(SfppVector.FROM, replay.from)
        assertEquals(SfppVector.ID, replay.id)
        assertEquals(600L, replay.heardAgoS)
        assertContentEquals(ct, replay.ciphertext)
    }

    @Test
    fun shuffledFramesFromTwoArchivesCompleteOneSet() {
        val parts = slices(9, 17)
        val assembler = SfppFrameAssembler()
        assertNull(assembler.offer(itemFrame(hash, parts[2], packFrame(2, 3), envelope = false), archiveB, false, 0))
        assertNull(assembler.offer(itemFrame(hash, parts[0], packFrame(0, 3)), archiveA, true, 1))
        val replay =
            assertNotNull(
                assembler.offer(itemFrame(hash, parts[1], packFrame(1, 3), envelope = false), archiveB, false, 2),
            )
        assertContentEquals(ct, replay.ciphertext)
        assertEquals(archiveA, replay.archive)
        assertEquals(true, replay.envelopeAttested)
    }

    @Test
    fun invalidFramePositionsAreRejected() {
        val parts = slices(12)
        val assembler = SfppFrameAssembler()
        assertNull(assembler.offer(itemFrame(hash, ct, packFrame(0, 5)), archiveA, false, 0))
        assertNull(assembler.offer(itemFrame(hash, ct, packFrame(2, 2)), archiveA, false, 0))
        assertEquals(0, assembler.openSlots)
        // A frame whose total disagrees with the open slot's is dropped, and the set still completes.
        assertNull(assembler.offer(itemFrame(hash, parts[0], packFrame(0, 2)), archiveA, false, 0))
        assertNull(assembler.offer(itemFrame(hash, parts[1], packFrame(1, 3), envelope = false), archiveB, false, 0))
        assertNotNull(assembler.offer(itemFrame(hash, parts[1], packFrame(1, 2), envelope = false), archiveB, false, 0))
    }

    @Test
    fun corruptedSliceDiscardsTheWholeSet() {
        val parts = slices(12)
        val bad = parts[1].copyOf().also { it[0] = (it[0].toInt() xor 0x55).toByte() }
        val assembler = SfppFrameAssembler()
        assertNull(assembler.offer(itemFrame(hash, parts[0], packFrame(0, 2)), archiveA, false, 0))
        assertNull(assembler.offer(itemFrame(hash, bad, packFrame(1, 2), envelope = false), archiveA, false, 1))
        assertEquals(0, assembler.openSlots)
    }

    // Firmware Throttle::deadlinePassedAt: the deadline has passed once now reaches it.
    @Test
    fun slotExpiresWhenItsDeadlineIsReached() {
        val parts = slices(12)
        val late = SfppFrameAssembler(deadlineMs = 120_000)
        assertNull(late.offer(itemFrame(hash, parts[1], packFrame(1, 2), envelope = false), archiveA, false, 0))
        assertNull(late.offer(itemFrame(hash, parts[0], packFrame(0, 2)), archiveA, false, 120_000))
        val inTime = SfppFrameAssembler(deadlineMs = 120_000)
        assertNull(inTime.offer(itemFrame(hash, parts[1], packFrame(1, 2), envelope = false), archiveA, false, 0))
        assertNotNull(inTime.offer(itemFrame(hash, parts[0], packFrame(0, 2)), archiveA, false, 119_999))
    }

    @Test
    fun leastRecentlyUsedSlotIsEvicted() {
        val parts = slices(12)
        val assembler = SfppFrameAssembler(slots = 2)
        assertNull(assembler.offer(itemFrame(hash, parts[0], packFrame(0, 2)), archiveA, false, 0))
        assertNull(assembler.offer(itemFrame(hex("1111111111111111"), parts[0], packFrame(0, 2)), archiveA, false, 1))
        // Touching the first slot makes the second the eviction victim.
        assertNull(assembler.offer(itemFrame(hash, parts[0], packFrame(0, 2)), archiveA, false, 2))
        assertNull(assembler.offer(itemFrame(hex("2222222222222222"), parts[0], packFrame(0, 2)), archiveA, false, 3))
        assertNotNull(assembler.offer(itemFrame(hash, parts[1], packFrame(1, 2), envelope = false), archiveA, false, 4))
    }

    // Firmware Reassembler::offer rejects a total mismatch before it updates the slot's lastUsed.
    @Test
    fun rejectedFrameDoesNotRefreshItsSlot() {
        val parts = slices(12)
        val assembler = SfppFrameAssembler(slots = 2)
        assertNull(assembler.offer(itemFrame(hash, parts[0], packFrame(0, 2)), archiveA, false, 0))
        assertNull(assembler.offer(itemFrame(hex("1111111111111111"), parts[0], packFrame(0, 2)), archiveA, false, 1))
        assertNull(assembler.offer(itemFrame(hash, parts[1], packFrame(1, 3), envelope = false), archiveA, false, 2))
        // Still least recently used, so the third key evicts the first slot and its set never completes.
        assertNull(assembler.offer(itemFrame(hex("2222222222222222"), parts[0], packFrame(0, 2)), archiveA, false, 3))
        assertNull(assembler.offer(itemFrame(hash, parts[1], packFrame(1, 2), envelope = false), archiveA, false, 4))
    }
}
