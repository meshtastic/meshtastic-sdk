/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BondedBleDeviceTest {

    @Test
    fun blankNameBecomesNull() {
        val devices = listOf<Pair<String?, String>>("  " to "AA:BB:CC:DD:EE:01", null to "AA:BB:CC:DD:EE:02")
            .toBondedBleDevices()
        assertNull(devices[0].name)
        assertNull(devices[1].name)
    }

    @Test
    fun duplicateAddressesCollapseIgnoringCase() {
        val devices = listOf<Pair<String?, String>>("A" to "aa:bb:cc:dd:ee:01", "A" to "AA:BB:CC:DD:EE:01")
            .toBondedBleDevices()
        assertEquals(1, devices.size)
    }

    @Test
    fun addressIsKeptVerbatimForTransportIdentity() {
        val device = listOf<Pair<String?, String>>(
            "Meshtastic_1234" to "AA:BB:CC:DD:EE:01",
        ).toBondedBleDevices().single()
        assertEquals("AA:BB:CC:DD:EE:01", device.address)
        assertEquals("Meshtastic_1234", device.name)
    }
}
