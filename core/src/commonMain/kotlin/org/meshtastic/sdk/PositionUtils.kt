/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geographic utility functions for mesh node positions.
 */
public object PositionUtils {
    private const val EARTH_RADIUS_METERS: Double = 6371e3

    /** Converts a protobuf position integer (1e-7 degrees) to a double. */
    public fun intToDegrees(positionInt: Int): Double = positionInt * 1e-7

    /** Returns true if lat/lng are within valid bounds and not both zero. */
    public fun isValidPosition(latitude: Double, longitude: Double): Boolean =
        !(latitude == 0.0 && longitude == 0.0) &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0

    /**
     * Computes the great-circle distance between two points using the Haversine formula.
     *
     * @return distance in meters
     */
    public fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = lat1.toRadians()
        val lat2Rad = lat2.toRadians()
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a = sin(dLat / 2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_METERS * 2 * asin(sqrt(a))
    }

    /** Overload accepting [LatLng] instances. */
    public fun distance(a: LatLng, b: LatLng): Double = distance(a.latitude, a.longitude, b.latitude, b.longitude)

    /**
     * Computes the initial bearing from point 1 to point 2.
     *
     * @return bearing in degrees (0 = north, 90 = east, etc.)
     */
    public fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = lat1.toRadians()
        val lat2Rad = lat2.toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        return (atan2(y, x).toDegrees() + 360.0) % 360.0
    }

    /** Overload accepting [LatLng] instances. */
    public fun bearing(from: LatLng, to: LatLng): Double = bearing(from.latitude, from.longitude, to.latitude, to.longitude)

    private fun Double.toRadians(): Double = this * PI / 180.0

    private fun Double.toDegrees(): Double = this * 180.0 / PI
}
