/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeInfosTest {
    @Test fun displayIdAndShortNameFallbacks() {
        val n = NodeInfo(num = 0xa1b2c3d4.toInt())
        assertEquals("!a1b2c3d4", n.displayId)
        assertEquals("c3d4", n.shortName)
        assertEquals("!a1b2c3d4", n.longName)
    }

    @Test fun userPopulatedNamesWin() {
        val n = NodeInfo(num = 1, user = User(id = "!00000001", short_name = "AL", long_name = "Alice"))
        assertEquals("AL", n.shortName)
        assertEquals("Alice", n.longName)
    }

    @Test fun curatedHardwareNames() {
        assertEquals("T-Beam", HardwareModel.TBEAM.displayName)
        assertEquals("Heltec Wireless Tracker", HardwareModel.HELTEC_WIRELESS_TRACKER.displayName)
        assertEquals("Unknown", HardwareModel.UNSET.displayName)
    }
}
