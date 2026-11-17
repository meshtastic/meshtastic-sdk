/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real-hardware check for [BleTransport.deviceLogs]. Skips (does not fail) when no radio is in
 * range or the radio streams no logs (`security.debug_log_api_enabled` off).
 */
class BleDeviceLogsDeviceTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Test
    fun deviceLogsDeliversRecordsWhenTheRadioStreamsThem() = runBlocking {
        val radio = withTimeoutOrNull(SCAN_WINDOW_MS) { BleScanner.scan().first() }
        assumeTrue("No Meshtastic radio advertising in range - skipping", radio != null)

        val transport = BleTransport(radio!!)
        try {
            withTimeout(CONNECT_WINDOW_MS) { transport.connect() }
            val record = withTimeoutOrNull(LOG_WINDOW_MS) { transport.deviceLogs().first() }
            assumeTrue("Radio streamed no logs (debug_log_api_enabled off?) - skipping", record != null)
            assertTrue("message must be non-empty: $record", record!!.message.isNotEmpty())
        } finally {
            transport.disconnect()
            transport.shutdown()
        }
    }

    private companion object {
        const val SCAN_WINDOW_MS = 10_000L
        const val CONNECT_WINDOW_MS = 60_000L
        const val LOG_WINDOW_MS = 15_000L
    }
}
