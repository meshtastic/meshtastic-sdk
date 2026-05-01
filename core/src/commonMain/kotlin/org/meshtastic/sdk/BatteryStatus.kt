/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.Telemetry
/**
 * Curated battery health and state information.
 *
 * @property percent charge level (0..100).
 * @property voltageVolts raw battery voltage, if reported.
 * @property pluggedIn `true` if the device is currently drawing external power.
 */
public data class BatteryStatus(
    public val percent: Int?,
    public val voltageVolts: Float?,
    public val pluggedIn: Boolean,
)

/**
 * Converts protobuf [DeviceMetrics] to [BatteryStatus].
 *
 * Maps the firmware's `>= 101` level sentinel to [BatteryStatus.pluggedIn].
 */
public fun DeviceMetrics.toBatteryStatus(): BatteryStatus? {
    if (battery_level == null && voltage == null) return null
    val raw = battery_level
    val plugged = raw != null && raw >= 101
    val pct = when {
        raw == null -> null
        plugged -> 100
        else -> raw.coerceIn(0, 100)
    }
    return BatteryStatus(percent = pct, voltageVolts = voltage, pluggedIn = plugged)
}

/** Converts protobuf [Telemetry] to [BatteryStatus] by inspecting its [DeviceMetrics]. */
public fun Telemetry.toBatteryStatus(): BatteryStatus? = device_metrics?.toBatteryStatus()
