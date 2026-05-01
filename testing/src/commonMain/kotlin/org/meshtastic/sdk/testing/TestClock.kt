/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.testing

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Deterministic [Clock] for time-sensitive tests.
 *
 * Tests that need to control the passage of time without relying on `runTest`'s virtual scheduler
 * — for instance, when verifying [InMemoryStorage] timestamps or asserting on time-stamped state
 * — can construct a `TestClock`, pass it to the system under test, and call [advance] or [set]
 * to move virtual wall-clock time forward.
 *
 * ```kotlin
 * val clock = TestClock(initial = Instant.fromEpochMilliseconds(1_700_000_000_000))
 * val storage = InMemoryStorage(clock = clock)
 * storage.saveNode(node)
 * clock.advance(5.seconds)
 * // storage entries written before advance() have timestamps 5s in the past
 * ```
 *
 * Not thread-safe; intended for single-threaded test usage.
 *
 * @since 0.1.0
 */
public class TestClock(initial: Instant = Instant.fromEpochMilliseconds(0)) : Clock {
    private var current: Instant = initial

    override fun now(): Instant = current

    /** Advance virtual time by [duration]. */
    public fun advance(duration: Duration) {
        current += duration
    }

    /** Set virtual time to an exact [time]. */
    public fun set(time: Instant) {
        current = time
    }
}
