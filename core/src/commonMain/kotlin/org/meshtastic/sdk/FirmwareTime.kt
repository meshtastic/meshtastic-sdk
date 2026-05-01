/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Converts a 32-bit unsigned firmware timestamp (seconds since epoch) to an SDK [Instant].
 * Returns `null` if the timestamp is 0 (the firmware's "never/unset" sentinel).
 */
public fun Int.firmwareSecondsToInstant(): Instant? {
    if (this == 0) return null
    val unsigned = this.toLong() and 0xFFFFFFFFL
    return Instant.fromEpochSeconds(unsigned)
}

/** Converts an SDK [Instant] to a 32-bit unsigned firmware timestamp. */
public fun Instant.toFirmwareSeconds(): Int {
    val secs = epochSeconds
    val clamped = secs.coerceIn(0L, 0xFFFFFFFFL)
    return clamped.toInt()
}

/** Returns a human-friendly relative time string (e.g., `"5m ago"`, `"2h ago"`). */
public fun Instant.relativeTo(now: Instant = Clock.System.now()): String {
    val delta = (now - this).inWholeSeconds
    return when {
        delta < 60L -> "just now"
        delta < 3_600L -> "${delta / 60L}m ago"
        delta < 86_400L -> "${delta / 3_600L}h ago"
        else -> "${delta / 86_400L}d ago"
    }
}

/** Returns a relative time string, or `"never"` if the instant is `null`. */
public fun Instant?.relativeToOrNever(now: Instant = Clock.System.now()): String = this?.relativeTo(now) ?: "never"
