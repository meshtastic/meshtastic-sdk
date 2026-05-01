/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the BLE drain protocol per `docs/protocol.md §3`:
 *
 * - On wake, drains until the device returns an empty buffer.
 * - Multiple wakes during a drain coalesce into a single re-drain (no concurrent reads).
 * - Returning `null` from the read source terminates the loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrainCoordinatorTest {

    @Test
    fun drainsUntilEmptyOnInitialWake() = runTest {
        // Queue: 3 payloads, then empty (drained), then null (closed).
        val responses = ArrayDeque<ByteArray?>().apply {
            add(byteArrayOf(1))
            add(byteArrayOf(2))
            add(byteArrayOf(3))
            add(ByteArray(0)) // drained
            add(null) // closed (terminates loop)
        }
        val coord = DrainCoordinator(readPayload = { responses.removeFirstOrNull() })

        val emitted = mutableListOf<ByteArray>()
        val job = launch { coord.run { p -> emitted += p } }
        advanceUntilIdle()
        job.cancel()

        assertEquals(3, emitted.size)
        assertContentEquals(byteArrayOf(1), emitted[0])
        assertContentEquals(byteArrayOf(2), emitted[1])
        assertContentEquals(byteArrayOf(3), emitted[2])
    }

    @Test
    fun coalescesConcurrentWakesIntoSingleRedrain() = runTest {
        // Pass 1: 1 byte, then empty.
        // Pass 2 (after re-wake): 2 bytes, then empty.
        // Pass 3: nothing (will block forever -> we cancel).
        val responses = ArrayDeque<ByteArray?>().apply {
            add(byteArrayOf(0xA))
            add(ByteArray(0))
            add(byteArrayOf(0xB))
            add(byteArrayOf(0xC))
            add(ByteArray(0))
        }
        val coord = DrainCoordinator(readPayload = { responses.removeFirstOrNull() ?: ByteArray(0) })

        val emitted = mutableListOf<ByteArray>()
        val job = launch { coord.run { p -> emitted += p } }

        // Initial wake from run() triggers pass 1.
        advanceUntilIdle()
        assertEquals(1, emitted.size)

        // Two rapid wakes during/after pass 1 — must coalesce, never produce
        // 4 emissions (which would mean we ran pass 2 twice).
        coord.wake()
        coord.wake()
        advanceUntilIdle()

        assertEquals(3, emitted.size, "Expected 1 + 2 = 3 emissions; got concurrent drains")
        job.cancel()
    }

    @Test
    fun terminatesWhenReaderReturnsNull() = runTest {
        val coord = DrainCoordinator(readPayload = { null })

        var ran = false
        val job = launch {
            coord.run { _ -> error("should not emit") }
            ran = true
        }
        advanceUntilIdle()

        assertTrue(ran, "run() should return when readPayload returns null")
        assertTrue(job.isCompleted)
    }

    @Test
    fun emptyOnFirstReadDoesNotEmit() = runTest {
        val responses = ArrayDeque<ByteArray?>().apply {
            add(ByteArray(0)) // empty on first read after initial wake
            add(null)
        }
        val coord = DrainCoordinator(readPayload = { responses.removeFirstOrNull() })

        val emitted = mutableListOf<ByteArray>()
        val job = launch { coord.run { p -> emitted += p } }
        advanceUntilIdle()
        job.cancel()

        assertEquals(0, emitted.size)
    }

    /**
     * P3b-2 (audit §2.7): a hanging GATT read must not pin the drain loop — a single
     * read is bounded by [DrainCoordinator.DEFAULT_READ_TIMEOUT_MS] (overridden here for
     * speed), on expiry we break the inner loop and wait for the next wake signal.
     */
    @Test
    fun hangingReadTimesOutAndWaitsForNextWake() = runTest {
        val secondPassQueue = ArrayDeque<ByteArray>().apply {
            add(byteArrayOf(0x42))
            add(ByteArray(0)) // drained → break second pass
        }
        var firstPass = true
        val coord = DrainCoordinator(
            readTimeoutMs = 1_000L,
            readPayload = {
                if (firstPass) {
                    firstPass = false
                    kotlinx.coroutines.delay(10_000L) // exceeds the 1 s deadline
                    ByteArray(0)
                } else {
                    secondPassQueue.removeFirst()
                }
            },
        )

        val emitted = mutableListOf<ByteArray>()
        val job = launch { coord.run { p -> emitted += p } }

        // First pass: stuck read → timeout → break, no emission.
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(0, emitted.size, "timed-out read must not emit")

        // Next wake should drive a fresh pass that reads the pending byte.
        coord.wake()
        advanceUntilIdle()
        job.cancel()

        assertEquals(1, emitted.size, "post-timeout wake must re-enter drain loop")
        assertContentEquals(byteArrayOf(0x42), emitted[0])
    }

    /**
     * TX-11: the first read of the first drain pass uses [firstReadTimeoutMs]
     * (longer) so the post-connect bond/encryption warmup window doesn't
     * silently break. Subsequent reads fall back to [readTimeoutMs] (shorter)
     * so a steady-state stuck read still surfaces quickly.
     */
    @Test
    fun firstReadHonoursLongerTimeoutThanSubsequentReads() = runTest {
        var callIndex = 0
        val coord = DrainCoordinator(
            readTimeoutMs = 1_000L,
            firstReadTimeoutMs = 10_000L,
            readPayload = {
                callIndex++
                when (callIndex) {
                    1 -> {
                        // First read: take 5s — would fail the steady-state
                        // 1s deadline but fits inside the first-read 10s.
                        kotlinx.coroutines.delay(5_000L)
                        byteArrayOf(0x01)
                    }

                    2 -> ByteArray(0)

                    // drained
                    else -> null // closed
                }
            },
        )

        val emitted = mutableListOf<ByteArray>()
        val job = launch { coord.run { p -> emitted += p } }
        advanceUntilIdle()
        job.cancel()

        assertEquals(1, emitted.size, "5s read must succeed under first-read 10s deadline")
        assertContentEquals(byteArrayOf(0x01), emitted[0])
    }

    /**
     * TX-11: confirm the steady-state deadline applies once the first read
     * completes — a long read on the *second* pass must time out under
     * [readTimeoutMs] (1s here), not be granted the longer first-read budget.
     */
    @Test
    fun secondPassFallsBackToShorterTimeout() = runTest {
        var callIndex = 0
        val coord = DrainCoordinator(
            readTimeoutMs = 1_000L,
            firstReadTimeoutMs = 10_000L,
            readPayload = {
                callIndex++
                when (callIndex) {
                    1 -> ByteArray(0)

                    // first pass drains immediately
                    2 -> {
                        // Second pass first read: take 5s — exceeds the 1s
                        // steady-state deadline → timeout, no emission.
                        kotlinx.coroutines.delay(5_000L)
                        byteArrayOf(0x02)
                    }

                    else -> ByteArray(0)
                }
            },
        )

        val emitted = mutableListOf<ByteArray>()
        val job = launch { coord.run { p -> emitted += p } }
        // First pass: drains immediately. Then trigger a second pass.
        advanceTimeBy(100L)
        runCurrent()
        coord.wake()
        // Wait long enough for the 1s deadline but well short of 5s.
        advanceTimeBy(2_000L)
        runCurrent()
        job.cancel()

        assertEquals(0, emitted.size, "steady-state deadline must short-circuit the slow read")
    }
}
