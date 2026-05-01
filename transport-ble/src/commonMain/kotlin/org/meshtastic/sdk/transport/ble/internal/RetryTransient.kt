/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Retry a suspending block on transient errors with bounded attempts and an
 * explicit backoff schedule. Cancellation is honoured: a [CancellationException]
 * propagates immediately, and [delay] between attempts is cancellable.
 *
 * The block is invoked at most [attempts] times. Between failed attempts the
 * coroutine sleeps for `backoffMs[failedAttemptIndex]`. After the final
 * failure the last captured exception is rethrown.
 *
 * @param attempts total invocations (1 initial + N-1 retries). Must be >= 1.
 * @param backoffMs sleeps between attempts. Length must be >= [attempts] - 1.
 * @param onAttemptFailure invoked with the exception from each failed attempt
 *   that will be retried; defaults to a no-op. Useful for cleanup between
 *   attempts (e.g. dropping a half-open GATT client on Android before the next
 *   connect retry).
 * @param block the operation to retry.
 */
internal suspend fun <T> retryTransient(
    attempts: Int,
    backoffMs: LongArray,
    onAttemptFailure: suspend (Throwable) -> Unit = {},
    block: suspend () -> T,
): T = retryTransient(
    attempts = attempts,
    backoffSelector = { i, _ -> backoffMs[i] },
    onAttemptFailure = onAttemptFailure,
    backoffSlots = backoffMs.size,
    block = block,
)

/**
 * Variant of [retryTransient] that picks the next-attempt delay dynamically
 * based on the failed attempt index *and* the captured throwable. Used by the
 * BLE connect path to vary backoff per [GattErrorCategory] (TX-14 in the
 * audit). Cancellation handling is identical to the LongArray overload.
 *
 * @param backoffSlots how many distinct slot indices [backoffSelector] will be
 *   called with — defaults to `attempts - 1` (the legacy contract). Used only
 *   for the precondition check; pass a larger value if you intend to reuse the
 *   selector across multiple retry sessions.
 */
internal suspend fun <T> retryTransient(
    attempts: Int,
    backoffSelector: (attemptIndex: Int, error: Throwable) -> Long,
    onAttemptFailure: suspend (Throwable) -> Unit = {},
    backoffSlots: Int = attempts - 1,
    block: suspend () -> T,
): T {
    require(attempts >= 1) { "attempts must be >= 1" }
    require(backoffSlots >= attempts - 1) {
        "backoffSelector must cover at least attempts-1 slots"
    }
    var lastError: Throwable? = null
    repeat(attempts) { i ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
            if (i < attempts - 1) {
                onAttemptFailure(e)
                delay(backoffSelector(i, e))
            }
        }
    }
    throw lastError ?: IllegalStateException("retryTransient: no error captured")
}
