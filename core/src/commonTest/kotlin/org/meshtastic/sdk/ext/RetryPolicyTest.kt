/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {
    @Test fun noneReturnsNullImmediately() {
        assertNull(RetryPolicy.None.delayForAttempt(0))
    }

    @Test fun fixedReturnsConfiguredDelay() {
        val policy = RetryPolicy.Fixed(maxAttempts = 3, delay = 7.seconds)
        assertEquals(7.seconds, policy.delayForAttempt(0))
    }

    @Test fun fixedReturnsNullAtMaxAttempts() {
        val policy = RetryPolicy.Fixed(maxAttempts = 3, delay = 7.seconds)
        assertNull(policy.delayForAttempt(policy.maxAttempts))
    }

    @Test fun exponentialBackoffGrowsGeometrically() {
        val policy = RetryPolicy.ExponentialBackoff(
            maxAttempts = 4,
            initialDelay = 1.seconds,
            maxDelay = 20.seconds,
            multiplier = 2.0,
            jitterFactor = 0.0,
        )

        assertEquals(1.seconds, policy.delayForAttempt(0))
        assertEquals(2.seconds, policy.delayForAttempt(1))
        assertEquals(4.seconds, policy.delayForAttempt(2))
        assertEquals(8.seconds, policy.delayForAttempt(3))
    }

    @Test fun exponentialBackoffReturnsNullAtMaxAttempts() {
        val policy = RetryPolicy.ExponentialBackoff(maxAttempts = 4, jitterFactor = 0.0)
        assertNull(policy.delayForAttempt(policy.maxAttempts))
    }

    @Test fun exponentialBackoffIsCappedAtMaxDelay() {
        val policy = RetryPolicy.ExponentialBackoff(
            maxAttempts = 4,
            initialDelay = 10.seconds,
            maxDelay = 20.seconds,
            multiplier = 3.0,
            jitterFactor = 0.0,
        )

        assertEquals(10.seconds, policy.delayForAttempt(0))
        assertEquals(20.seconds, policy.delayForAttempt(1))
        assertEquals(20.seconds, policy.delayForAttempt(2))
    }
}
