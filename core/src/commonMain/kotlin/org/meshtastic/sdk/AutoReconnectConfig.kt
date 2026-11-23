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
 * With the supervisor on, a transport drop, a failed first connect, a handshake timeout, the
 * liveness watchdog and a `FromRadio.rebooted` all lead to [ConnectionState.Reconnecting] and a new
 * attempt through the client's [RadioTransportProvider]. A non-recoverable transport error,
 * [MeshtasticException.FirmwareTooOld] and an exhausted cap lead to [ConnectionState.Failed].
 *
 * **Backoff formula:** `delay(n) = min(initialBackoff * backoffMultiplier^(n-1), maxBackoff)`
 * then multiplied by `(1 ± jitter * random())`, where `n` counts attempts since the last link that
 * stayed up for [stableAfter]. The supervisor uses [kotlinx.coroutines.delay] so virtual-time test
 * runners (`runTest` / `advanceTimeBy`) drive the schedule deterministically.
 *
 * @property enabled toggle the supervisor entirely. When `false` the engine surfaces a
 *   single [ConnectionState.Disconnected] after a transport drop.
 * @property initialBackoff delay before the first reconnect attempt (default: 1 s).
 * @property maxBackoff hard cap on backoff between successive attempts (default: 60 s).
 * @property maxAttempts upper bound on reconnect attempts since the last stable link; `null` means
 *   retry indefinitely. When the cap is reached the engine reports [ConnectionState.Failed] with the
 *   last cause and stops.
 * @property backoffMultiplier exponential growth factor between attempts (default: `2.0`).
 *   Coerced to `>= 1.0` by the `init` block.
 * @property jitter symmetric multiplicative randomization, in `0.0..1.0`. `0.2` means
 *   ±20% on each computed delay. `0.0` disables jitter entirely.
 * @property stableAfter how long a session must stay [ConnectionState.Connected] before the attempt
 *   count and backoff start over. A radio that drops sooner keeps backing off. [Duration.ZERO] (the
 *   default) starts over as soon as a session connects.
 * @property maxHandshakeRecoveries how many sessions in a row the engine restarts after a handshake
 *   timeout, a liveness timeout, a handshake protocol error or a [RadioClient.sessionEvents] overflow
 *   before it reports [ConnectionState.Failed] with that error. A session that reaches
 *   [ConnectionState.Connected] and then ends any other way resets the count; one that ends in an
 *   overflow does not, since an overflow usually follows the handshake. `null` (the default)
 *   restarts without limit, except three overflow restarts in a row.
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
    val stableAfter: Duration = Duration.ZERO,
    val maxHandshakeRecoveries: Int? = null,
) {
    init {
        require(initialBackoff > Duration.ZERO) { "initialBackoff must be > 0" }
        require(maxBackoff >= initialBackoff) { "maxBackoff must be >= initialBackoff" }
        require(maxAttempts == null || maxAttempts > 0) { "maxAttempts must be > 0 when set" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0" }
        require(jitter in 0.0..1.0) { "jitter must be in 0.0..1.0" }
        require(stableAfter >= Duration.ZERO && stableAfter.isFinite()) { "stableAfter must be finite and >= 0" }
        require(maxHandshakeRecoveries == null || maxHandshakeRecoveries >= 0) {
            "maxHandshakeRecoveries must be >= 0 when set"
        }
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
