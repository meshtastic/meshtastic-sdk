/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.Telemetry
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

class TelemetryReadingsTest {
    @Test fun deviceMetricsTakePriority() {
        val r = Telemetry(device_metrics = DeviceMetrics(battery_level = 50)).toReading()
        assertIs<TelemetryReading.Device>(r)
    }

    @Test fun environmentVariant() {
        val r = Telemetry(environment_metrics = EnvironmentMetrics(temperature = 20f)).toReading()
        assertIs<TelemetryReading.Environment>(r)
    }

    @Test fun nullWhenEmpty() {
        assertNull(Telemetry().toReading())
    }
}
