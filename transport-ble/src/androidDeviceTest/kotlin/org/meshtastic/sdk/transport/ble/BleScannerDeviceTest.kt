/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportSpec

/**
 * Real-hardware check for [BleScanner]: scan, pick a result, build a transport from it.
 * Skips (does not fail) when no Meshtastic radio is advertising in range.
 */
class BleScannerDeviceTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Test
    fun scanEmitsRadiosThatBuildTransports() = runBlocking {
        val radios = withTimeoutOrNull(SCAN_WINDOW_MS) { BleScanner.scan().take(3).toList() }.orEmpty()
        assumeTrue("No Meshtastic radio advertising in range - skipping", radios.isNotEmpty())

        val radio = radios.first()
        assertTrue("address must be non-blank: $radio", radio.address.isNotBlank())
        assertTrue("rssi must be negative dBm: $radio", radio.rssi?.let { it < 0 } ?: false)

        val transport = BleTransport(radio)
        try {
            assertEquals(TransportIdentity.of(TransportSpec.Ble(radio.address)), transport.identity)
        } finally {
            transport.shutdown()
        }
    }

    private companion object {
        const val SCAN_WINDOW_MS = 10_000L
    }
}
