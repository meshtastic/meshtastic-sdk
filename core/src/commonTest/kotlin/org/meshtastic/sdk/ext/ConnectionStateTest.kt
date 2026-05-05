/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionStateTest {
    private val reconnecting = ConnectionState.Reconnecting(MeshtasticException.Transport("link lost"), attempt = 3)

    @Test fun isUsableOnlyWhenConnected() {
        assertFalse(ConnectionState.Disconnected.isUsable)
        assertFalse(ConnectionState.Connecting(attempt = 1).isUsable)
        assertFalse(ConnectionState.Configuring(ConfigPhase.Stage2, progress = 0.5f).isUsable)
        assertTrue(ConnectionState.Connected.isUsable)
        assertFalse(reconnecting.isUsable)
    }

    @Test fun isInProgressForActiveStates() {
        assertFalse(ConnectionState.Disconnected.isInProgress)
        assertTrue(ConnectionState.Connecting(attempt = 2).isInProgress)
        assertTrue(ConnectionState.Configuring(ConfigPhase.Stage1, progress = 0.25f).isInProgress)
        assertFalse(ConnectionState.Connected.isInProgress)
        assertTrue(reconnecting.isInProgress)
    }

    @Test fun statusMessageFormatsEachState() {
        assertEquals("Disconnected", ConnectionState.Disconnected.statusMessage)
        assertEquals("Connecting (attempt 2)", ConnectionState.Connecting(attempt = 2).statusMessage)
        assertEquals(
            "Configuring: Settling (37%)",
            ConnectionState.Configuring(ConfigPhase.Settling, progress = 0.375f).statusMessage,
        )
        assertEquals("Connected", ConnectionState.Connected.statusMessage)
        assertEquals("Reconnecting (attempt 3)", reconnecting.statusMessage)
    }
}
