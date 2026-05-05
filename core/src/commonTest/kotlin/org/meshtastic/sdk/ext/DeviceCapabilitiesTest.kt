/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.ext

import org.meshtastic.sdk.DeviceCapabilities
import org.meshtastic.sdk.DeviceVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceCapabilitiesTest {
    @Test fun parsesVersions() {
        assertEquals(20712, DeviceVersion("2.7.12").asInt)
        assertEquals(20000, DeviceVersion("2.0").asInt)
        assertEquals(0, DeviceVersion("invalid").asInt)
    }

    @Test fun comparesVersions() {
        assertTrue(DeviceVersion("2.7.18") > DeviceVersion("2.7.12"))
        assertTrue(DeviceVersion("3.0.0") > DeviceVersion("2.99.99"))
    }

    @Test fun detectsCapabilitiesByVersion() {
        val current = DeviceCapabilities("2.7.18")
        assertTrue(current.canMuteNode)
        assertTrue(current.canSendVerifiedContacts)

        val old = DeviceCapabilities("2.6.7")
        assertFalse(old.supportsQrCodeSharing)
    }

    @Test fun nullVersionDisablesAllCapabilities() {
        val capabilities = DeviceCapabilities(null)
        assertFalse(capabilities.canMuteNode)
        assertFalse(capabilities.canSendVerifiedContacts)
        assertFalse(capabilities.canToggleTelemetryEnabled)
        assertFalse(capabilities.canToggleUnmessageable)
        assertFalse(capabilities.supportsQrCodeSharing)
        assertFalse(capabilities.supportsStatusMessage)
        assertFalse(capabilities.supportsTrafficManagementConfig)
        assertFalse(capabilities.supportsTakConfig)
        assertFalse(capabilities.supportsSecondaryChannelLocation)
        assertFalse(capabilities.supportsEsp32Ota)
    }
}
