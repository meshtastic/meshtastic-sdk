/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

/**
 * Parsed result of a traceroute discovery, representing the full forward and backward routes.
 *
 * @property route Full forward route (source → destination), including endpoints
 * @property routeBack Full return route (destination → source), including endpoints
 * @property snrTowards Per-hop SNR values on the forward route (may be empty if not available)
 * @property snrBack Per-hop SNR values on the return route (may be empty if not available)
 * @property hopsAway Number of hops between source and destination
 */
public data class RouteDiscoveryResult(
    public val route: List<NodeId>,
    public val routeBack: List<NodeId>,
    public val snrTowards: List<Float> = emptyList(),
    public val snrBack: List<Float> = emptyList(),
) {
    /** Number of intermediate hops (excludes source and destination). */
    public val hopsAway: Int get() = maxOf(0, route.size - 2)

    /**
     * Formats the route as a human-readable string using the provided node name resolver.
     */
    public fun formatRoute(resolveNode: (NodeId) -> String): String = buildString {
        appendLine("Route (${route.size} nodes):")
        route.forEachIndexed { i, nodeId ->
            append("  ${resolveNode(nodeId)}")
            if (i < snrTowards.size) append(" (SNR: ${snrTowards[i]})")
            appendLine()
        }
        if (routeBack.isNotEmpty()) {
            appendLine("Route back (${routeBack.size} nodes):")
            routeBack.forEachIndexed { i, nodeId ->
                append("  ${resolveNode(nodeId)}")
                if (i < snrBack.size) append(" (SNR: ${snrBack[i]})")
                appendLine()
            }
        }
    }

    public companion object {
        /**
         * Reconstructs a full route from a RouteDiscovery proto response.
         *
         * @param source the node that initiated the traceroute
         * @param destination the target node
         * @param intermediateRoute intermediate node IDs from the proto route field
         * @param intermediateRouteBack intermediate node IDs from the proto route_back field
         * @param snrTowards SNR values from proto snr_towards field
         * @param snrBack SNR values from proto snr_back field
         */
        public fun fromProto(
            source: NodeId,
            destination: NodeId,
            intermediateRoute: List<Int>,
            intermediateRouteBack: List<Int>,
            snrTowards: List<Float> = emptyList(),
            snrBack: List<Float> = emptyList(),
        ): RouteDiscoveryResult = RouteDiscoveryResult(
            route = listOf(source) + intermediateRoute.map { NodeId(it) } + destination,
            routeBack = listOf(destination) + intermediateRouteBack.map { NodeId(it) } + source,
            snrTowards = snrTowards,
            snrBack = snrBack,
        )
    }
}
