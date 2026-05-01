/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Routing
import org.meshtastic.sdk.SendFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingErrorsTest {
    @Test fun actionableMessages() {
        assertTrue("channel" in Routing.Error.NO_CHANNEL.actionableMessage().lowercase())
        assertTrue("pki" in Routing.Error.PKI_FAILED.actionableMessage().lowercase())
        assertTrue("rate" in Routing.Error.RATE_LIMIT_EXCEEDED.actionableMessage().lowercase())
    }

    @Test fun suggestedActions() {
        assertEquals("check_channel", Routing.Error.NO_CHANNEL.suggestedAction())
        assertEquals("check_pki", Routing.Error.PKI_FAILED.suggestedAction())
        assertEquals("retry", Routing.Error.NO_ROUTE.suggestedAction())
        assertEquals("wait_for_ack", Routing.Error.DUTY_CYCLE_LIMIT.suggestedAction())
        assertNull(Routing.Error.NONE.suggestedAction())
    }

    @Test fun sendFailureHumanMessages() {
        assertTrue("route" in SendFailure.NoRoute.humanMessage().lowercase())
        assertTrue("disconnect" in SendFailure.Disconnected.humanMessage().lowercase())
        assertTrue("channel" in SendFailure.Other(Routing.Error.NO_CHANNEL).humanMessage().lowercase())
        assertTrue("oops" in SendFailure.Unknown("oops").humanMessage())
    }

    @Test fun actionableMessageNeverEmpty() {
        for (e in Routing.Error.entries) {
            val m = e.actionableMessage()
            assertNotNull(m)
            assertTrue(m.isNotBlank(), "blank for $e")
        }
    }
}
