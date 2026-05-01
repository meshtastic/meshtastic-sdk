/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Position
/**
 * A geographic coordinate pair with an optional altitude.
 *
 * @property latitude degrees north (positive) or south (negative).
 * @property longitude degrees east (positive) or west (negative).
 * @property altitudeMeters height above sea level in meters, if known.
 */
public data class LatLng(
    public val latitude: Double,
    public val longitude: Double,
    public val altitudeMeters: Int? = null,
)

/**
 * Converts a protobuf [Position] to an SDK [LatLng].
 *
 * Returns `null` if the position is missing required fields or if both latitude and longitude
 * are zero (typically indicating an uninitialized fix).
 */
public fun Position.toLatLng(): LatLng? {
    val latI = latitude_i ?: return null
    val lonI = longitude_i ?: return null
    if (latI == 0 && lonI == 0) return null
    return LatLng(latitude = latI / 1e7, longitude = lonI / 1e7, altitudeMeters = altitude)
}
