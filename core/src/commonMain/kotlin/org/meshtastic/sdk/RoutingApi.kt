/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.NeighborInfo as ProtoNeighborInfo

/**
 * Mesh route discovery and neighbor enumeration RPCs.
 *
 * Acquired via [RadioClient.routing]. Available only while the client is connected.
 *
 * @since 0.1.0
 */
public interface RoutingApi {

    /**
     * Send a `RouteDiscovery` request to [dest] and wait for the route_reply.
     *
     * @param dest the destination [NodeId].
     * @param hopLimit maximum mesh hops the discovery may traverse. The mesh truncates the
     *   discovery if more nodes lie on the path. Default: `7` (firmware default).
     * @return on success, the [RouteDiscovery] populated with `route` (forward) + `route_back`
     *   plus per-hop SNR. On failure, an [AdminResult] error variant.
     */
    public suspend fun traceRoute(dest: NodeId, hopLimit: Int = DEFAULT_HOP_LIMIT): AdminResult<RouteDiscovery>

    /**
     * Request the [ProtoNeighborInfo] of [node] (default: local). Surfaces immediate neighbors and
     * their last-heard SNR / interval.
     */
    public suspend fun requestNeighborInfo(node: NodeId = NodeId.LOCAL): AdminResult<ProtoNeighborInfo>

    public companion object {
        public const val DEFAULT_HOP_LIMIT: Int = 7
    }
}
