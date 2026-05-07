/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.NodeInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * How the local node reached a remote node.
 *
 * Derived from [NodeInfo.hops_away] and [NodeInfo.via_mqtt].
 *
 * @since 0.1.0
 */
public enum class ConnectionQuality {
    /** Node was heard directly (0 hops, not via MQTT). */
    DIRECT,

    /** Node was heard via one or more mesh relays. */
    RELAYED,

    /** Node was heard via MQTT gateway. */
    MQTT,

    /** Insufficient data to determine connection path. */
    UNKNOWN,
}

/**
 * Signal quality tier derived from SNR thresholds.
 *
 * Thresholds are based on typical LoRa performance characteristics:
 * - Good: SNR ≥ 5 dB (strong signal, reliable decode)
 * - Fair: SNR ≥ 0 dB (acceptable, may have occasional errors)
 * - Poor: SNR < 0 dB (weak signal, expect packet loss)
 * - None: SNR == 0 and no hops data (no signal information available)
 *
 * @since 0.1.0
 */
public enum class SignalQuality {
    /** Strong signal (SNR ≥ 5 dB). */
    GOOD,

    /** Acceptable signal (0 ≤ SNR < 5 dB). */
    FAIR,

    /** Weak signal (SNR < 0 dB). */
    POOR,

    /** No signal data available. */
    NONE,
}

/**
 * Default threshold for determining whether a node is "online."
 *
 * Matches the 2-hour window used by the Meshtastic Android app.
 */
public val DEFAULT_ONLINE_THRESHOLD: Duration = 2.hours

/**
 * Returns `true` if the node was last heard within [threshold] of [nowEpochSeconds].
 *
 * @param nowEpochSeconds current epoch time in seconds (e.g., `Clock.System.now().epochSeconds.toInt()`)
 * @param threshold how recently the node must have been heard to be considered online
 * @return `true` if the node is considered online; `false` if stale or never heard
 *
 * @since 0.1.0
 */
public fun NodeInfo.isOnline(nowEpochSeconds: Int, threshold: Duration = DEFAULT_ONLINE_THRESHOLD): Boolean {
    if (last_heard == 0) return false
    val cutoff = nowEpochSeconds - threshold.inWholeSeconds.toInt()
    return last_heard >= cutoff
}

/**
 * Returns the [ConnectionQuality] for this node based on hop count and MQTT flag.
 *
 * @since 0.1.0
 */
public val NodeInfo.connectionQuality: ConnectionQuality
    get() = when {
        via_mqtt -> ConnectionQuality.MQTT
        hops_away == 0 -> ConnectionQuality.DIRECT
        hops_away != null -> ConnectionQuality.RELAYED
        else -> ConnectionQuality.UNKNOWN
    }

/**
 * Returns the [SignalQuality] tier for this node based on SNR.
 *
 * @since 0.1.0
 */
public val NodeInfo.signalQuality: SignalQuality
    get() = when {
        // snr == 0f with no hops_away data likely means "no reading" (proto default)
        snr == 0f && hops_away == null -> SignalQuality.NONE

        snr >= 5f -> SignalQuality.GOOD

        snr >= 0f -> SignalQuality.FAIR

        else -> SignalQuality.POOR
    }
