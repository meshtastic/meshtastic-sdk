/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real-hardware check for [bondedBleDevices]. Skips (does not fail) when the phone has no
 * bonded device.
 */
class BondedBleDeviceDeviceTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.BLUETOOTH_CONNECT)

    @Test
    fun bondedDevicesBuildTransports() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val devices = bondedBleDevices(context)
        assumeTrue("No bonded Bluetooth device - skipping", devices.isNotEmpty())

        val device = devices.first()
        assertTrue("address must be non-blank: $device", device.address.isNotBlank())
        val transport = BleTransport(device)
        try {
            assertEquals(device.address, transport.identity.raw)
        } finally {
            transport.shutdown()
        }
    }
}
