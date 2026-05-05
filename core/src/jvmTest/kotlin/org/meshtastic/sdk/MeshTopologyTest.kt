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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshTopologyTest {
    @Test
    fun `addNeighborInfo populates nodes and edges`() {
        val topology = MeshTopology()

        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(1),
                neighbors = listOf(
                    NeighborInfo.Neighbor(nodeId = NodeId(2), snr = 7.5f),
                    NeighborInfo.Neighbor(nodeId = NodeId(3), snr = -1.0f),
                ),
                lastUpdated = 99,
            ),
        )

        assertEquals(setOf(NodeId(1), NodeId(2), NodeId(3)), topology.nodes)
        assertEquals(2, topology.edgeCount)
        assertEquals(
            setOf(
                MeshTopology.Edge(NodeId(1), NodeId(2), 7.5f, 99),
                MeshTopology.Edge(NodeId(1), NodeId(3), -1.0f, 99),
            ),
            topology.getNeighbors(NodeId(1)).toSet(),
        )
        assertEquals(MeshTopology.Edge(NodeId(1), NodeId(2), 7.5f, 99), topology.getEdge(NodeId(1), NodeId(2)))
    }

    @Test
    fun `getNeighbors returns empty for unknown node`() {
        val topology = MeshTopology()

        assertTrue(topology.getNeighbors(NodeId(404)).isEmpty())
    }

    @Test
    fun `isDirectReach works bidirectionally`() {
        val topology = MeshTopology()

        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(1),
                neighbors = listOf(NeighborInfo.Neighbor(nodeId = NodeId(2), snr = 4.0f)),
            ),
        )

        assertTrue(topology.isDirectReach(NodeId(1), NodeId(2)))
        assertTrue(topology.isDirectReach(NodeId(2), NodeId(1)))
        assertFalse(topology.isDirectReach(NodeId(1), NodeId(3)))
    }

    @Test
    fun `shortestPath finds multi-hop route`() {
        val topology = MeshTopology()

        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(1),
                neighbors = listOf(NeighborInfo.Neighbor(nodeId = NodeId(2), snr = 5.0f)),
            ),
        )
        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(2),
                neighbors = listOf(NeighborInfo.Neighbor(nodeId = NodeId(3), snr = 3.0f)),
            ),
        )
        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(3),
                neighbors = listOf(NeighborInfo.Neighbor(nodeId = NodeId(4), snr = 1.0f)),
            ),
        )

        assertEquals(
            listOf(NodeId(1), NodeId(2), NodeId(3), NodeId(4)),
            topology.shortestPath(NodeId(1), NodeId(4)),
        )
    }

    @Test
    fun `shortestPath returns empty when unreachable`() {
        val topology = MeshTopology()

        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(1),
                neighbors = listOf(NeighborInfo.Neighbor(nodeId = NodeId(2), snr = 5.0f)),
            ),
        )
        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(4),
                neighbors = listOf(NeighborInfo.Neighbor(nodeId = NodeId(5), snr = 2.0f)),
            ),
        )

        assertTrue(topology.shortestPath(NodeId(1), NodeId(5)).isEmpty())
    }

    @Test
    fun `removeNode clears all references`() {
        val topology = MeshTopology()

        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(1),
                neighbors = listOf(
                    NeighborInfo.Neighbor(nodeId = NodeId(2), snr = 5.0f),
                    NeighborInfo.Neighbor(nodeId = NodeId(3), snr = 1.0f),
                ),
            ),
        )
        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(4),
                neighbors = listOf(NeighborInfo.Neighbor(nodeId = NodeId(2), snr = 2.0f)),
            ),
        )

        topology.removeNode(NodeId(2))

        assertFalse(NodeId(2) in topology.nodes)
        assertNull(topology.getEdge(NodeId(1), NodeId(2)))
        assertNull(topology.getEdge(NodeId(4), NodeId(2)))
        assertFalse(topology.isDirectReach(NodeId(1), NodeId(2)))
        assertEquals(listOf(MeshTopology.Edge(NodeId(1), NodeId(3), 1.0f, 0)), topology.allEdges())
    }

    @Test
    fun `addNeighborInfo replaces existing edges from same reporter`() {
        val topology = MeshTopology()

        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(1),
                neighbors = listOf(
                    NeighborInfo.Neighbor(nodeId = NodeId(2), snr = 5.0f),
                    NeighborInfo.Neighbor(nodeId = NodeId(3), snr = 4.0f),
                ),
            ),
        )
        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(1),
                neighbors = listOf(NeighborInfo.Neighbor(nodeId = NodeId(4), snr = 9.0f)),
                lastUpdated = 10,
            ),
        )

        assertEquals(listOf(MeshTopology.Edge(NodeId(1), NodeId(4), 9.0f, 10)), topology.getNeighbors(NodeId(1)))
        assertNull(topology.getEdge(NodeId(1), NodeId(2)))
        assertNull(topology.getEdge(NodeId(1), NodeId(3)))
        assertEquals(setOf(NodeId(1), NodeId(4)), topology.nodes)
    }

    @Test
    fun `allEdges returns correct count`() {
        val topology = MeshTopology()

        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(1),
                neighbors = listOf(
                    NeighborInfo.Neighbor(nodeId = NodeId(2), snr = 1.0f),
                    NeighborInfo.Neighbor(nodeId = NodeId(3), snr = 2.0f),
                ),
            ),
        )
        topology.addNeighborInfo(
            NeighborInfo(
                nodeId = NodeId(4),
                neighbors = listOf(NeighborInfo.Neighbor(nodeId = NodeId(1), snr = 3.0f)),
            ),
        )

        assertEquals(3, topology.allEdges().size)
        assertEquals(3, topology.edgeCount)
    }
}
