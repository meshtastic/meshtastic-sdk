/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.MeshPacket
/**
 * Physical layer metrics for a received packet.
 *
 * @property rssiDbm Received Signal Strength Indicator in dBm.
 * @property snrDb Signal-to-Noise Ratio in dB.
 * @property hopsAway number of mesh hops the packet traversed. `null` if the packet was local or
 *   from a version of firmware that does not report hop_start.
 * @property viaMqtt `true` if the packet arrived via an MQTT bridge rather than LoRa.
 */
public data class RadioMetrics(
    public val rssiDbm: Int,
    public val snrDb: Float,
    public val hopsAway: Int?,
    public val viaMqtt: Boolean,
)

/** Extracts [RadioMetrics] from a [MeshPacket]. Returns `null` if metrics are missing. */
public fun MeshPacket.toRadioMetrics(): RadioMetrics? {
    if (rx_rssi == 0 && rx_snr == 0f) return null
    val hops = if (hop_start > 0) (hop_start - hop_limit).coerceAtLeast(0) else null
    return RadioMetrics(rssiDbm = rx_rssi, snrDb = rx_snr, hopsAway = hops, viaMqtt = via_mqtt)
}

/**
 * Returns a signal quality estimate from 1 (poor) to 5 (excellent) based on SNR.
 *
 * Returns `null` if the packet has no SNR data.
 */
public fun MeshPacket.signalQuality(): Int? {
    val metrics = toRadioMetrics() ?: return null
    val snr = metrics.snrDb
    return when {
        snr >= 5f -> 5
        snr >= 0f -> 4
        snr >= -5f -> 3
        snr >= -10f -> 2
        else -> 1
    }
}
