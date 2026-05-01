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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class FirmwareTimeTest {
    @Test fun zeroSentinelMapsToNull() {
        assertNull(0.firmwareSecondsToInstant())
    }

    @Test fun roundTripPreservesValue() {
        val secs = 1_700_000_000
        val instant = secs.firmwareSecondsToInstant()!!
        assertEquals(secs.toLong(), instant.epochSeconds)
        assertEquals(secs, instant.toFirmwareSeconds())
    }

    @Test fun negativeInstantClampsToZero() {
        val past = Instant.fromEpochSeconds(-1L)
        assertEquals(0, past.toFirmwareSeconds())
    }

    @Test fun unsignedExtensionForLargeFirmwareSeconds() {
        val raw = 0x80000000.toInt()
        val instant = raw.firmwareSecondsToInstant()
        assertNotNull(instant)
        assertEquals(0x80000000L, instant.epochSeconds)
    }

    @Test fun relativeToBuckets() {
        val now = Instant.fromEpochSeconds(1_000_000_000L)
        assertEquals("just now", Instant.fromEpochSeconds(1_000_000_000L).relativeTo(now))
        assertEquals("3m ago", Instant.fromEpochSeconds(1_000_000_000L - 180L).relativeTo(now))
        assertEquals("2h ago", Instant.fromEpochSeconds(1_000_000_000L - 7_200L).relativeTo(now))
        assertEquals("5d ago", Instant.fromEpochSeconds(1_000_000_000L - 5L * 86_400L).relativeTo(now))
    }

    @Test fun relativeToOrNeverHandlesNull() {
        assertEquals("never", (null as Instant?).relativeToOrNever())
    }
}
