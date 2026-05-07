/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Defines a retry strategy for failed message sends.
 *
 * Use with [MessageHandle] to implement structured retry behavior:
 * ```kotlin
 * val policy = RetryPolicy.ExponentialBackoff()
 * val handle = client.sendText("hello")
 * handle.retryWith(policy) // suspends until success, non-retryable failure, or max attempts exhausted
 * ```
 */
public sealed class RetryPolicy {
    /** No retries — fail immediately on first failure. */
    public data object None : RetryPolicy()

    /**
     * Retry with fixed delay between attempts.
     *
     * @property maxAttempts maximum number of retry attempts (not counting the initial send)
     * @property delay fixed delay between retries
     */
    public data class Fixed(val maxAttempts: Int = 3, val delay: Duration = 5.seconds) : RetryPolicy()

    /**
     * Retry with exponential backoff and optional jitter.
     *
     * Delay formula: `min(initialDelay * multiplier^attempt, maxDelay) ± jitter`
     *
     * @property maxAttempts maximum number of retry attempts
     * @property initialDelay delay before first retry
     * @property maxDelay maximum delay cap
     * @property multiplier backoff multiplier per attempt
     * @property jitterFactor random jitter factor (0.0 = no jitter, 0.2 = ±20%)
     */
    public data class ExponentialBackoff(
        val maxAttempts: Int = 5,
        val initialDelay: Duration = 2.seconds,
        val maxDelay: Duration = 60.seconds,
        val multiplier: Double = 2.0,
        val jitterFactor: Double = 0.2,
    ) : RetryPolicy()

    /**
     * Computes the delay before the Nth retry attempt (0-indexed).
     * Returns null if the attempt exceeds maxAttempts.
     *
     * @return the delay before the next attempt, or null if max attempts exceeded
     */
    public fun delayForAttempt(attempt: Int): Duration? = when (this) {
        is None -> null

        is Fixed -> if (attempt < maxAttempts) delay else null

        is ExponentialBackoff -> {
            if (attempt >= maxAttempts) {
                null
            } else {
                val base = initialDelay * multiplier.pow(attempt.toDouble())
                val capped = if (base > maxDelay) maxDelay else base
                if (jitterFactor > 0.0) {
                    val jitter = 1.0 + (Random.nextDouble() * 2 - 1) * jitterFactor
                    capped * jitter
                } else {
                    capped
                }
            }
        }
    }

    /** Maximum number of attempts for this policy. */
    public val maxRetries: Int get() = when (this) {
        is None -> 0
        is Fixed -> maxAttempts
        is ExponentialBackoff -> maxAttempts
    }
}
