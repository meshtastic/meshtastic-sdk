/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LatLngTest {
    @Test fun decodesScaledIntegers() {
        val pos = Position(latitude_i = 374200000, longitude_i = -1220800000, altitude = 25)
        val ll = pos.toLatLng()
        assertNotNull(ll)
        assertEquals(37.42, ll.latitude, 0.0001)
        assertEquals(-122.08, ll.longitude, 0.0001)
        assertEquals(25, ll.altitudeMeters)
    }

    @Test fun zeroCoordinatesReturnNull() {
        assertNull(Position(latitude_i = 0, longitude_i = 0).toLatLng())
        assertNull(Position().toLatLng())
    }
}

private fun assertEquals(expected: Double, actual: Double, eps: Double) {
    require(kotlin.math.abs(expected - actual) <= eps) { "expected $expected got $actual" }
}
