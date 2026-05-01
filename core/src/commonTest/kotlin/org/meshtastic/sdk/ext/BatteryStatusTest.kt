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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryStatusTest {
    @Test fun normalReading() {
        val s = DeviceMetrics(battery_level = 55, voltage = 4.05f).toBatteryStatus()
        assertNotNull(s)
        assertEquals(55, s.percent)
        assertEquals(4.05f, s.voltageVolts)
        assertFalse(s.pluggedIn)
    }

    @Test fun pluggedInSentinel() {
        val s = DeviceMetrics(battery_level = 101, voltage = 5.0f).toBatteryStatus()!!
        assertEquals(100, s.percent)
        assertTrue(s.pluggedIn)
    }

    @Test fun coercesOutOfRangeIntoBounds() {
        assertEquals(100, DeviceMetrics(battery_level = 100).toBatteryStatus()!!.percent)
    }

    @Test fun emptyReturnsNull() {
        assertNull(DeviceMetrics().toBatteryStatus())
    }

    @Test fun telemetryDelegates() {
        val t = Telemetry(device_metrics = DeviceMetrics(battery_level = 42))
        assertEquals(42, t.toBatteryStatus()!!.percent)
        assertNull(Telemetry().toBatteryStatus())
    }
}
