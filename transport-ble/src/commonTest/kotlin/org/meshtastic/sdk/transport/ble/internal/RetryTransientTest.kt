/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RetryTransientTest {

    @Test
    fun succeedsWithoutRetryOnFirstAttempt() = runTest {
        var calls = 0
        val result = retryTransient(attempts = 3, backoffMs = longArrayOf(100, 100)) {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun retriesUpToBoundThenSucceeds() = runTest {
        var calls = 0
        val result = retryTransient(attempts = 3, backoffMs = longArrayOf(250, 750)) {
            calls++
            if (calls < 3) error("transient $calls")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
        // Backoff is honoured via runTest's virtual time scheduler:
        assertEquals(250L + 750L, testScheduler.currentTime)
    }

    @Test
    fun rethrowsLastErrorAfterAllAttempts() = runTest {
        var calls = 0
        val ex = assertFailsWith<RuntimeException> {
            retryTransient(attempts = 3, backoffMs = longArrayOf(10, 10)) {
                calls++
                error("boom-$calls")
            }
        }
        assertEquals(3, calls)
        assertTrue(ex.message!!.endsWith("boom-3"))
    }

    @Test
    fun cancellationPropagatesImmediately() = runTest {
        val deferred = async {
            retryTransient(attempts = 5, backoffMs = longArrayOf(1_000, 1_000, 1_000, 1_000)) {
                throw CancellationException("cancelled by caller")
            }
        }
        assertFailsWith<CancellationException> { deferred.await() }
    }

    @Test
    fun onAttemptFailureRunsBetweenAttemptsOnly() = runTest {
        val cleanups = mutableListOf<String>()
        var calls = 0
        retryTransient(
            attempts = 3,
            backoffMs = longArrayOf(50, 50),
            onAttemptFailure = { cleanups += it.message ?: "?" },
        ) {
            calls++
            if (calls < 3) error("fail-$calls")
        }
        // Only between attempts, not after the final success.
        assertEquals(listOf("fail-1", "fail-2"), cleanups)
    }

    /**
     * TX-14: backoffSelector receives both the failed-attempt index and the
     * captured throwable, so callers can vary the delay per error category
     * (e.g. shorter for clean peer-disconnects, longer for bond/MTU faults).
     */
    @Test
    fun backoffSelectorReceivesAttemptAndError() = runTest {
        val seen = mutableListOf<Pair<Int, String?>>()
        var calls = 0
        retryTransient(
            attempts = 3,
            backoffSelector = { i, e ->
                seen += i to e.message
                100L
            },
        ) {
            calls++
            if (calls < 3) error("err-$calls")
            "ok"
        }
        assertEquals(2, seen.size)
        assertEquals(0 to "err-1", seen[0])
        assertEquals(1 to "err-2", seen[1])
        // 100ms + 100ms honoured via virtual time:
        assertEquals(200L, testScheduler.currentTime)
    }
}
