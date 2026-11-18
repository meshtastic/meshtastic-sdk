/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleScanMatchTest {
    private val objectPath = "/org/bluez/hci0/dev_34_B7_DA_62_18_C5"

    @Test
    fun emptyNeedleMatchesAnything() {
        assertTrue(bleAdvertisementMatches("", null, null, objectPath))
    }

    @Test
    fun matchesNameCaseInsensitively() {
        assertTrue(bleAdvertisementMatches("18c4", "Meshtastic_18C4", null, "id"))
        assertFalse(bleAdvertisementMatches("zzzz", "Meshtastic_18C4", "peripheral", "id"))
    }

    @Test
    fun matchesPeripheralName() {
        assertTrue(bleAdvertisementMatches("heltec", null, "Heltec V3", "id"))
    }

    @Test
    fun macNeedleMatchesABlueZObjectPath() {
        assertTrue(bleAdvertisementMatches("34:B7:DA:62:18:C5", null, null, objectPath))
        assertTrue(bleAdvertisementMatches("34:b7:da:62:18:c5", null, null, objectPath))
        assertFalse(bleAdvertisementMatches("34:B7:DA:62:18:C6", null, null, objectPath))
    }

    @Test
    fun macNeedleStillMatchesAColonIdentifier() {
        assertTrue(bleAdvertisementMatches("34:B7:DA:62:18:C5", null, null, "34:B7:DA:62:18:C5"))
    }
}
