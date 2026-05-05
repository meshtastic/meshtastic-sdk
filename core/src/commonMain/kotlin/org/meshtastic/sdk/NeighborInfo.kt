/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

/**
 * Parsed neighbor information for a node, representing its directly-reachable peers.
 *
 * @property nodeId the node reporting its neighbors
 * @property neighbors list of neighbor entries with signal quality
 * @property lastUpdated seconds since epoch when this info was received
 */
public data class NeighborInfo(
    public val nodeId: NodeId,
    public val neighbors: List<Neighbor>,
    public val lastUpdated: Int = 0,
) {
    /**
     * A single neighbor entry.
     *
     * @property nodeId the neighbor's node ID
     * @property snr signal-to-noise ratio in dB (higher is better)
     */
    public data class Neighbor(
        public val nodeId: NodeId,
        public val snr: Float,
    )

    /**
     * Formats the neighbor list as a human-readable string.
     */
    public fun format(resolveNode: (NodeId) -> String = { it.toHex() }): String = buildString {
        appendLine("Neighbors of ${resolveNode(nodeId)} (${neighbors.size}):")
        neighbors.forEach { neighbor ->
            appendLine("  ${resolveNode(neighbor.nodeId)} — SNR: ${neighbor.snr} dB")
        }
    }

    public companion object {
        /**
         * Parse from proto NeighborInfo fields.
         *
         * @param reportingNode the node that sent the neighbor info
         * @param neighborNodeIds list of neighbor node numbers
         * @param snrValues corresponding SNR values (same order/length as nodeIds)
         * @param timestamp seconds since epoch
         */
        public fun fromProto(
            reportingNode: Int,
            neighborNodeIds: List<Int>,
            snrValues: List<Float>,
            timestamp: Int = 0,
        ): NeighborInfo = NeighborInfo(
            nodeId = NodeId(reportingNode),
            neighbors = neighborNodeIds.zip(snrValues) { nodeId, snr ->
                Neighbor(nodeId = NodeId(nodeId), snr = snr)
            },
            lastUpdated = timestamp,
        )
    }
}
