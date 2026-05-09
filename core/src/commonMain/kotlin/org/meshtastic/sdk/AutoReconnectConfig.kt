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
 * Configuration for the engine's built-in auto-reconnect supervisor.
 *
 * Configure on the [RadioClient.Builder] via
 * [autoReconnect(...)][RadioClient.Builder.autoReconnect].
 *
 * **Default: [Disabled].** The **1.0 release will flip the default to `enabled = true`**; pin
 * a value explicitly here if you want today's "host owns reconnect policy" behaviour to survive
 * that bump.
 *
 * **Backoff formula:** `delay(n) = min(initialBackoff * backoffMultiplier^(n-1), maxBackoff)`
 * then multiplied by `(1 ± jitter * random())`. The supervisor uses
 * [kotlinx.coroutines.delay] so virtual-time test runners (`runTest` / `advanceTimeBy`)
 * drive the schedule deterministically.
 *
 * @property enabled toggle the supervisor entirely. When `false` the engine surfaces a
 *   single [ConnectionState.Disconnected] after a transport drop.
 * @property initialBackoff delay before the first reconnect attempt (default: 1 s).
 * @property maxBackoff hard cap on backoff between successive attempts (default: 60 s).
 * @property maxAttempts upper bound on reconnect attempts; `null` means retry indefinitely.
 *   When the cap is reached the engine emits [ConnectionState.Disconnected] with the
 *   original cause and stops.
 * @property backoffMultiplier exponential growth factor between attempts (default: `2.0`).
 *   Coerced to `>= 1.0` by the `init` block.
 * @property jitter symmetric multiplicative randomization, in `0.0..1.0`. `0.2` means
 *   ±20% on each computed delay. `0.0` disables jitter entirely.
 *
 * @since 0.1.0
 */
public data class AutoReconnectConfig(
    val enabled: Boolean = true,
    val initialBackoff: Duration = 1.seconds,
    val maxBackoff: Duration = 60.seconds,
    val maxAttempts: Int? = null,
    val backoffMultiplier: Double = 2.0,
    val jitter: Double = 0.2,
) {
    init {
        require(initialBackoff > Duration.ZERO) { "initialBackoff must be > 0" }
        require(maxBackoff >= initialBackoff) { "maxBackoff must be >= initialBackoff" }
        require(maxAttempts == null || maxAttempts > 0) { "maxAttempts must be > 0 when set" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0" }
        require(jitter in 0.0..1.0) { "jitter must be in 0.0..1.0" }
    }

    public companion object {
        /**
         * Default: auto-reconnect off; the host application owns reconnect policy.
         *
         * **Note:** the 1.0 release will flip the default to an enabled config. Pin
         * [Disabled] here explicitly if you want today's behaviour to survive the bump.
         */
        public val Disabled: AutoReconnectConfig = AutoReconnectConfig(enabled = false)
    }
}
