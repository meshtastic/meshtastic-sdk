/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.ext

import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.StoreForwardEvent
import org.meshtastic.sdk.StoreForwardStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StoreForwardApiTest {

    @Test
    fun storeForwardStatsDefaults() {
        val stats = StoreForwardStats()
        assertEquals(0, stats.messagesStored)
        assertEquals(0, stats.messagesMax)
        assertEquals(false, stats.heartbeat)
    }

    @Test
    fun storeForwardStatsWithValues() {
        val stats = StoreForwardStats(
            messagesStored = 42,
            messagesMax = 100,
            uptime = 3600,
            requests = 10,
            requestsFailed = 1,
            heartbeat = true,
        )
        assertEquals(42, stats.messagesStored)
        assertEquals(100, stats.messagesMax)
        assertEquals(true, stats.heartbeat)
    }

    @Test
    fun storeForwardEventsAreSealed() {
        val discovered: StoreForwardEvent = StoreForwardEvent.ServerDiscovered(NodeId(1))
        assertIs<StoreForwardEvent.ServerDiscovered>(discovered)
        assertEquals(NodeId(1), discovered.nodeId)

        val lost: StoreForwardEvent = StoreForwardEvent.ServerLost(NodeId(2))
        assertIs<StoreForwardEvent.ServerLost>(lost)

        val started: StoreForwardEvent = StoreForwardEvent.HistoryReplayStarted(NodeId(3), messageCount = 5)
        assertIs<StoreForwardEvent.HistoryReplayStarted>(started)
        assertEquals(5, started.messageCount)

        val complete: StoreForwardEvent = StoreForwardEvent.HistoryReplayComplete(NodeId(3), delivered = 4)
        assertIs<StoreForwardEvent.HistoryReplayComplete>(complete)
        assertEquals(4, complete.delivered)

        val heartbeat: StoreForwardEvent = StoreForwardEvent.Heartbeat(NodeId(5))
        assertIs<StoreForwardEvent.Heartbeat>(heartbeat)
    }
}
