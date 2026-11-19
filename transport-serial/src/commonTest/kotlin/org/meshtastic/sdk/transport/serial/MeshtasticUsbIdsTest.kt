/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeshtasticUsbIdsTest {

    @Test
    fun knownVendorIdsAreLikelyMeshtastic() {
        assertTrue(SerialPortInfo(name = "a", usbVendorId = 0x10C4, usbProductId = 0xEA60).isLikelyMeshtastic())
        assertTrue(SerialPortInfo(name = "b", usbVendorId = 0x239A).isLikelyMeshtastic())
    }

    @Test
    fun otherVendorIdsAndNativePortsAreNot() {
        assertFalse(SerialPortInfo(name = "c", usbVendorId = 0x046D).isLikelyMeshtastic())
        assertFalse(SerialPortInfo(name = "/dev/ttyS0").isLikelyMeshtastic())
    }
}
