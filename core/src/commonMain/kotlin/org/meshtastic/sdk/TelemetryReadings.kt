/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.AirQualityMetrics
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.PowerMetrics
import org.meshtastic.proto.Telemetry
/**
 * A type-safe wrapper for the different categories of telemetry data reported by the mesh.
 */
public sealed class TelemetryReading {
    /** Hardware-specific metrics (battery, voltage, memory usage). */
    public data class Device(public val metrics: DeviceMetrics) : TelemetryReading()

    /** Environmental sensors (temperature, humidity, pressure). */
    public data class Environment(public val metrics: EnvironmentMetrics) : TelemetryReading()

    /** Power and energy management metrics. */
    public data class Power(public val metrics: PowerMetrics) : TelemetryReading()

    /** Air quality and gas sensor readings. */
    public data class AirQuality(public val metrics: AirQualityMetrics) : TelemetryReading()
}

/**
 * Maps a protobuf [Telemetry] packet to its corresponding type-safe [TelemetryReading].
 *
 * Returns `null` if the packet does not contain any of the supported metrics oneof arms.
 */
public fun Telemetry.toReading(): TelemetryReading? {
    device_metrics?.let { return TelemetryReading.Device(it) }
    environment_metrics?.let { return TelemetryReading.Environment(it) }
    power_metrics?.let { return TelemetryReading.Power(it) }
    air_quality_metrics?.let { return TelemetryReading.AirQuality(it) }
    return null
}
