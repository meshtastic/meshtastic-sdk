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
import kotlin.test.assertNull

class MqttEventsTest {
    @Test fun mqttConnectedIsMeshEvent() {
        val event: MeshEvent = MeshEvent.MqttConnected
        assertEquals("connected", describe(event))
    }

    @Test fun mqttDisconnectedCarriesReason() {
        val event: MeshEvent = MeshEvent.MqttDisconnected(reason = "broker closed connection")
        assertEquals("broker closed connection", describe(event))
        assertEquals("broker closed connection", (event as MeshEvent.MqttDisconnected).reason)
    }

    @Test fun mqttDisconnectedDefaultsReasonToNull() {
        val event: MeshEvent.MqttDisconnected = MeshEvent.MqttDisconnected()
        assertNull(event.reason)
        assertEquals("none", describe(event))
    }

    private fun describe(event: MeshEvent): String = when (event) {
        MeshEvent.MqttConnected -> "connected"
        is MeshEvent.MqttDisconnected -> event.reason ?: "none"
        else -> "other"
    }
}
