/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.RouteDiscovery
import kotlin.time.Duration
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
     * Return a [RoutingApi] whose calls wait up to [timeout] for the reply, instead of the client's
     * `rpcTimeout`. Use it for a multi-hop request whose answer, or the routing error that ends
     * it, can arrive after a local round-trip would have.
     *
     * @param timeout how long each call waits for its reply or routing error
     */
    public fun withRpcTimeout(timeout: Duration): RoutingApi

    /**
     * Trace the route to [dest] and wait for its reply.
     *
     * Sends an empty `RouteDiscovery` on `TRACEROUTE_APP` with `want_response`, `want_ack` and
     * `Data.dest`, as firmware's `TraceRouteModule` expects.
     *
     * @param hopLimit hops the request may travel; `null` uses the device's `lora.hop_limit`.
     * @param channel channel to send on; `null` uses the channel [dest] was last heard on.
     * @param requestId non-zero packet id to use, for a caller that logs the request; `null`
     *   allocates one.
     * @return on success, the [RouteDiscovery] with `route`, `route_back` and per-hop SNR.
     */
    public suspend fun traceRoute(
        dest: NodeId,
        hopLimit: Int? = null,
        channel: ChannelIndex? = null,
        requestId: Int? = null,
    ): AdminResult<RouteDiscovery>

    /**
     * Request the [ProtoNeighborInfo] of [node] (default: local): its immediate neighbors and their
     * last-heard SNR and interval. A remote request carries `want_ack`, `Data.dest` and the device
     * hop limit, on [channel] or the channel the node was last heard on.
     *
     * @param requestId non-zero packet id to use; `null` allocates one.
     */
    public suspend fun requestNeighborInfo(
        node: NodeId = NodeId.LOCAL,
        channel: ChannelIndex? = null,
        requestId: Int? = null,
    ): AdminResult<ProtoNeighborInfo>

    public companion object {
        public const val DEFAULT_HOP_LIMIT: Int = 7
    }
}
