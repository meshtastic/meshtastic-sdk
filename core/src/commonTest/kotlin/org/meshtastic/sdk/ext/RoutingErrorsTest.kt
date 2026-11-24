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
        assertTrue("rate limit" in SendFailure.RateLimited.humanMessage().lowercase())
        assertTrue("channel" in SendFailure.Other(Routing.Error.NO_CHANNEL).humanMessage().lowercase())
        assertTrue("oops" in SendFailure.Unknown("oops").humanMessage())
    }

    // Regression: the app's hand-kept reverse table drifting from the SDK's failure-to-code mapping.
    @Test fun sendFailureRoutingErrorNamesTheCodeBehindEachFailure() {
        assertEquals(Routing.Error.NO_ROUTE, SendFailure.NoRoute.routingError)
        assertEquals(Routing.Error.MAX_RETRANSMIT, SendFailure.MaxRetransmit.routingError)
        assertEquals(Routing.Error.TIMEOUT, SendFailure.Timeout.routingError)
        assertEquals(Routing.Error.DUTY_CYCLE_LIMIT, SendFailure.DutyCycleLimit.routingError)
        assertEquals(Routing.Error.RATE_LIMIT_EXCEEDED, SendFailure.RateLimited.routingError)
        // SendFailure-typed receivers resolve to the extension; a concrete subtype would read the member getter.
        val other: SendFailure = SendFailure.Other(Routing.Error.GOT_NAK)
        assertEquals(Routing.Error.GOT_NAK, other.routingError)
        val rejected: SendFailure = SendFailure.QueueRejected(9)
        assertEquals(Routing.Error.DUTY_CYCLE_LIMIT, rejected.routingError)
        val ambiguous: SendFailure = SendFailure.QueueRejected(33)
        assertNull(ambiguous.routingError)
        assertNull(SendFailure.Disconnected.routingError)
        assertNull(SendFailure.Cancelled.routingError)
        assertNull(SendFailure.AckTimeout.routingError)
        assertNull(SendFailure.QueueFull.routingError)
        assertNull(SendFailure.IdCollision.routingError)
        assertNull(SendFailure.HandshakeFailed.routingError)
        assertNull(SendFailure.Unknown("x").routingError)
    }

    // Regression: res 33 (firmware ERRNO_NO_INTERFACES or Routing.Error.NOT_AUTHORIZED) read as NOT_AUTHORIZED.
    @Test fun queueRejectedRoutingErrorIsNullForAmbiguousRes32To35() {
        assertEquals(Routing.Error.DUTY_CYCLE_LIMIT, SendFailure.QueueRejected(9).routingError)
        assertEquals(Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY, SendFailure.QueueRejected(39).routingError)
        for (res in 32..35) assertNull(SendFailure.QueueRejected(res).routingError, "res=$res")
        assertNull(SendFailure.QueueRejected(100).routingError)
        assertNull(SendFailure.QueueRejected(0).routingError)
    }

    @Test fun actionableMessageNeverEmpty() {
        for (e in Routing.Error.entries) {
            val m = e.actionableMessage()
            assertNotNull(m)
            assertTrue(m.isNotBlank(), "blank for $e")
        }
    }
}
