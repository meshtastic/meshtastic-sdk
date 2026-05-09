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
import kotlin.test.assertTrue

class NeighborInfoTest {
    @Test
    fun fromProtoParsesKnownValues() {
        val info = NeighborInfo.fromProto(
            reportingNode = 0x1234,
            neighborNodeIds = listOf(0x2001, 0x2002),
            snrValues = listOf(7.5f, -2.25f),
            timestamp = 1_700_000_000,
        )

        assertEquals(NodeId(0x1234), info.nodeId)
        assertEquals(2, info.neighbors.size)
        assertEquals(NeighborInfo.Neighbor(NodeId(0x2001), 7.5f), info.neighbors[0])
        assertEquals(NeighborInfo.Neighbor(NodeId(0x2002), -2.25f), info.neighbors[1])
        assertEquals(1_700_000_000, info.lastUpdated)
    }

    @Test
    fun fromProtoSupportsEmptyNeighbors() {
        val info = NeighborInfo.fromProto(
            reportingNode = 0x1234,
            neighborNodeIds = emptyList(),
            snrValues = emptyList(),
        )

        assertTrue(info.neighbors.isEmpty())
        assertEquals(0, info.lastUpdated)
    }

    @Test
    fun formatOutputsReadableSummary() {
        val info = NeighborInfo(
            nodeId = NodeId(1),
            neighbors = listOf(
                NeighborInfo.Neighbor(nodeId = NodeId(2), snr = 7.5f),
                NeighborInfo.Neighbor(nodeId = NodeId(3), snr = -1.0f),
            ),
        )

        assertEquals(
            "Neighbors of 00000001 (2):\n" +
                "  00000002 — SNR: 7.5 dB\n" +
                "  00000003 — SNR: -1.0 dB\n",
            info.format(),
        )
    }
}
