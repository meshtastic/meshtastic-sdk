/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.SharedContact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedContactUrlTest {
    @Test fun roundTripSharedContact() {
        val contact = SharedContact(
            node_num = 0xa1b2c3d4.toInt(),
            should_ignore = true,
            manually_verified = true,
        )

        val url = contact.toUrl()

        assertTrue(url.startsWith(SharedContactUrl.PREFIX))
        assertEquals(contact, SharedContactUrl.parse(url))
    }

    @Test fun parseRejectsInvalidUrl() {
        assertNull(SharedContactUrl.parse("https://example.com/contact"))
        assertNull(SharedContactUrl.parse("https://meshtastic.org/v/#@@@"))
    }

    @Test fun parseIgnoresQueryParams() {
        val contact = SharedContact(node_num = 1234)
        val withQuery = contact.toUrl() + "?from=test"

        val parsed = SharedContactUrl.parse(withQuery)

        assertNotNull(parsed)
        assertEquals(contact, parsed)
    }
}
