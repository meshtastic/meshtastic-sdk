/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CongestionTest {
    @Test fun levelIsLowWhenBothMetricsAreBelowMediumThreshold() {
        assertEquals(CongestionLevel.LOW, CongestionMetrics(airUtilTx = 10f, channelUtil = 20f).level)
    }

    @Test fun levelIsMediumWhenOneMetricCrossesMediumThreshold() {
        assertEquals(CongestionLevel.MEDIUM, CongestionMetrics(airUtilTx = 25f, channelUtil = 10f).level)
    }

    @Test fun levelIsHighWhenOneMetricCrossesHighThreshold() {
        assertEquals(CongestionLevel.HIGH, CongestionMetrics(airUtilTx = 10f, channelUtil = 50f).level)
    }

    @Test fun levelIsCriticalWhenOneMetricCrossesCriticalThreshold() {
        assertEquals(CongestionLevel.CRITICAL, CongestionMetrics(airUtilTx = 75f, channelUtil = 10f).level)
    }

    @Test fun suggestedBackoffIncreasesWithLevel() {
        val low = CongestionMetrics(airUtilTx = 10f, channelUtil = 10f).suggestedBackoff
        val medium = CongestionMetrics(airUtilTx = 25f, channelUtil = 10f).suggestedBackoff
        val high = CongestionMetrics(airUtilTx = 50f, channelUtil = 10f).suggestedBackoff
        val critical = CongestionMetrics(airUtilTx = 75f, channelUtil = 10f).suggestedBackoff

        assertEquals(kotlin.time.Duration.ZERO, low)
        assertEquals(5.seconds, medium)
        assertEquals(15.seconds, high)
        assertEquals(30.seconds, critical)
        assertTrue(low < medium)
        assertTrue(medium < high)
        assertTrue(high < critical)
    }

    @Test fun canSendNonUrgentOnlyForLowAndMedium() {
        assertTrue(CongestionMetrics(airUtilTx = 10f, channelUtil = 10f).canSendNonUrgent)
        assertTrue(CongestionMetrics(airUtilTx = 25f, channelUtil = 10f).canSendNonUrgent)
        assertFalse(CongestionMetrics(airUtilTx = 50f, channelUtil = 10f).canSendNonUrgent)
        assertFalse(CongestionMetrics(airUtilTx = 75f, channelUtil = 10f).canSendNonUrgent)
    }
}
