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
import kotlin.test.assertIs

class NodeChangePresenceTest {
    @Test
    fun presenceChangesImplementNodeChange() {
        val wentOffline = NodeChange.WentOffline(nodeId = NodeId(1), lastHeard = 123)
        val cameOnline = NodeChange.CameOnline(nodeId = NodeId(2))

        assertIs<NodeChange>(wentOffline)
        assertIs<NodeChange>(cameOnline)
        assertEquals(123, wentOffline.lastHeard)
        assertEquals(NodeId(2), cameOnline.nodeId)
    }

    @Test
    fun presenceChangesCanBePatternMatched() {
        val labels = listOf<NodeChange>(
            NodeChange.WentOffline(nodeId = NodeId(1), lastHeard = 321),
            NodeChange.CameOnline(nodeId = NodeId(2)),
        ).map { change ->
            when (change) {
                is NodeChange.Snapshot -> "snapshot"
                is NodeChange.Added -> "added"
                is NodeChange.Updated -> "updated"
                is NodeChange.Removed -> "removed"
                is NodeChange.WentOffline -> "offline:${change.lastHeard}"
                is NodeChange.CameOnline -> "online:${change.nodeId.toHex()}"
            }
        }

        assertEquals(listOf("offline:321", "online:00000002"), labels)
    }
}
