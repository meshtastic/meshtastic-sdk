/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Represents the current mesh congestion level based on channel utilization metrics.
 *
 * @property airUtilTx current transmit air utilization percentage (0-100)
 * @property channelUtil current channel utilization percentage (0-100)
 */
public data class CongestionMetrics(val airUtilTx: Float, val channelUtil: Float) {
    /** Computed congestion level based on thresholds. */
    public val level: CongestionLevel get() = when {
        airUtilTx >= CRITICAL_THRESHOLD || channelUtil >= CRITICAL_THRESHOLD -> CongestionLevel.CRITICAL
        airUtilTx >= HIGH_THRESHOLD || channelUtil >= HIGH_THRESHOLD -> CongestionLevel.HIGH
        airUtilTx >= MEDIUM_THRESHOLD || channelUtil >= MEDIUM_THRESHOLD -> CongestionLevel.MEDIUM
        else -> CongestionLevel.LOW
    }

    /** Suggested backoff duration based on current congestion. */
    public val suggestedBackoff: Duration get() = when (level) {
        CongestionLevel.LOW -> Duration.ZERO
        CongestionLevel.MEDIUM -> 5.seconds
        CongestionLevel.HIGH -> 15.seconds
        CongestionLevel.CRITICAL -> 30.seconds
    }

    /** Whether it's safe to send non-urgent messages. */
    public val canSendNonUrgent: Boolean get() = level <= CongestionLevel.MEDIUM

    public companion object {
        public const val MEDIUM_THRESHOLD: Float = 25f
        public const val HIGH_THRESHOLD: Float = 50f
        public const val CRITICAL_THRESHOLD: Float = 75f
    }
}

/**
 * Discrete congestion buckets derived from channel-utilization telemetry.
 *
 * @since 0.1.0
 */
public enum class CongestionLevel {
    /** Channel is clear — send freely. */
    LOW,

    /** Moderate activity — consider batching or delaying non-urgent messages. */
    MEDIUM,

    /** Heavy traffic — back off non-essential sends. */
    HIGH,

    /** Near capacity — only send critical messages. */
    CRITICAL,
}
