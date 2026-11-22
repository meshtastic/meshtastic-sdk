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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.meshtastic.sdk.TransportState

/**
 * Real-hardware check that a transport built with every [BleResilience] option on still connects
 * to a bonded radio. Skips (does not fail) when no radio bonded to the phone is advertising.
 */
class BleResilienceDeviceTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Test
    fun bondedRadioConnectsWithEveryMechanismEnabled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = advertisingBondedRadio(context)
        assumeTrue("No bonded radio advertising in range - skipping", device != null)

        val transport = BleTransport(
            device!!,
            BleResilience(bondFirst = true, autoConnectFallback = true, refreshStaleGattCache = true),
        )
        try {
            withTimeout(CONNECT_WINDOW_MS) { transport.connect() }
            assertEquals(TransportState.Connected, transport.state.value)
        } finally {
            transport.disconnect()
            transport.shutdown()
        }
    }

    private companion object {
        const val CONNECT_WINDOW_MS = 120_000L
    }
}
