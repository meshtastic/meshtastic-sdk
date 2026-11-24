/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString.Companion.decodeHex
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionPasskeyTest {

    // Regression: the default data-class toString leaks a live admin credential into any log line.
    @Test
    fun toStringOmitsThePasskeyBytes() {
        val bytes = "deadbeefcafe0123".decodeHex()
        val text = SessionPasskey(bytes, 42L).toString()

        assertFalse(text.contains("deadbeefcafe0123"), text)
        assertFalse(text.contains(bytes.base64()), text)
        assertTrue(text.contains("42"), text)
    }
}
