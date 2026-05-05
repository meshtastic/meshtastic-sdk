/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.ext

import org.meshtastic.sdk.LatLng
import org.meshtastic.sdk.PositionUtils
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PositionUtilsTest {
    @Test fun convertsScaledIntegersToDegrees() {
        assertClose(37.712, PositionUtils.intToDegrees(377120000), 1e-9)
    }

    @Test fun validatesPositions() {
        assertFalse(PositionUtils.isValidPosition(0.0, 0.0))
        assertTrue(PositionUtils.isValidPosition(37.7, -122.4))
        assertFalse(PositionUtils.isValidPosition(91.0, 0.0))
    }

    @Test fun computesDistance() {
        val sf = LatLng(37.7749, -122.4194)
        val la = LatLng(34.0522, -118.2437)

        assertClose(559_000.0, PositionUtils.distance(sf, la), 5_000.0)
    }

    @Test fun computesBearing() {
        assertClose(90.0, PositionUtils.bearing(0.0, 0.0, 0.0, 1.0), 0.0001)
        assertClose(0.0, PositionUtils.bearing(0.0, 0.0, 1.0, 0.0), 0.0001)
    }
}

private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
    require(abs(expected - actual) <= tolerance) { "expected $expected got $actual" }
}
