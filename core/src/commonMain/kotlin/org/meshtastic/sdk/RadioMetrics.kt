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
 * @property rssiDbm Received Signal Strength Indicator in dBm. 0 where the packet reported none.
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

/**
 * Extracts [RadioMetrics] from a [MeshPacket]. Returns `null` only when the packet carries no
 * radio reading at all: `rx_rssi` absent **and** `rx_snr` zero, as in one replayed from history.
 *
 * Absent is not zero. Since protobufs 2.8.0 `rx_rssi` is `optional` precisely because 0 dBm is a
 * legitimate reading - an SX126x can report exactly 0, and the SX127x formula can go positive -
 * so a packet carrying a real 0 dBm still has metrics. An absent `rx_rssi` next to a nonzero
 * `rx_snr` still has metrics too, with [RadioMetrics.rssiDbm] falling back to 0; that is the
 * shape a packet from pre-2.8.0 firmware takes, where proto3 elided a genuine 0 from the wire.
 */
public fun MeshPacket.toRadioMetrics(): RadioMetrics? {
    if (rx_rssi == null && rx_snr == 0f) return null
    val hops = if (hop_start > 0) (hop_start - hop_limit).coerceAtLeast(0) else null
    return RadioMetrics(rssiDbm = rx_rssi ?: 0, snrDb = rx_snr, hopsAway = hops, viaMqtt = via_mqtt)
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
