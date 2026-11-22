/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Position
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.User
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import org.meshtastic.proto.NeighborInfo as ProtoNeighborInfo

/**
 * Mesh route discovery, neighbor enumeration, and position and node-info requests that wait for
 * the target's reply.
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
     *   allocates one. An id another request still holds returns [AdminResult.NodeUnreachable]
     *   and leaves that request alone.
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
     * @param requestId non-zero packet id to use; `null` allocates one. An id another request still
     *   holds returns [AdminResult.NodeUnreachable] and leaves that request alone.
     */
    public suspend fun requestNeighborInfo(
        node: NodeId = NodeId.LOCAL,
        channel: ChannelIndex? = null,
        requestId: Int? = null,
    ): AdminResult<ProtoNeighborInfo>

    /**
     * Request [node]'s [Position] and wait for its reply: the packet
     * [RadioClient.requestPosition] sends (`POSITION_APP`, `want_response`, no `want_ack`, background
     * priority, the device hop limit), matched on the reply's `request_id` and sender.
     *
     * Firmware answers with its own position at the requesting channel's precision. It sends no
     * reply, so the call returns [AdminResult.Timeout], when it answered any position request in the
     * last 3 minutes (except in `LOST_AND_FOUND` role) or runs the `TAK_TRACKER` role, which
     * answers on `ATAK_PLUGIN_V2` instead. With position sharing off on the channel (precision 0)
     * or no fix it answers `Routing.Error.NO_RESPONSE`, returned as [AdminResult.Failed].
     *
     * @param channel channel to send on; `null` uses the channel [node] was last heard on.
     * @param position our position to share with [node], `time` set to now when it is `0`; `null`
     *   sends an empty payload.
     * @param requestId non-zero packet id to use; `null` allocates one. An id another request still
     *   holds returns [AdminResult.NodeUnreachable] and leaves that request alone.
     * @throws IllegalArgumentException if [node] is our own node or [NodeId.LOCAL]
     * @since 0.3.0
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    public suspend fun requestPosition(
        node: NodeId,
        channel: ChannelIndex? = null,
        position: Position? = null,
        requestId: Int? = null,
    ): AdminResult<Position>

    /**
     * Request [node]'s [User] and wait for its reply: the packet [RadioClient.requestNodeInfo]
     * sends with our own user (`NODEINFO_APP`, `want_response`, no `want_ack`, the device hop
     * limit), matched on the reply's `request_id` and sender. A traffic-management node may answer
     * from its cache in [node]'s name; that reply counts.
     *
     * Firmware never answers with a routing error here. It sends no reply, so the call returns
     * [AdminResult.Timeout], when it already heard a node-info request from us in the last 12 hours
     * (`USERPREFS_NODEINFO_REPLY_SUPPRESS_SECS`), sent its own node info within its throttle window
     * (30 minutes, longer on a busy mesh), or sees channel utilization over 40 %.
     *
     * @param channel channel to send on; `null` uses the channel [node] was last heard on.
     * @param requestId non-zero packet id to use; `null` allocates one. An id another request still
     *   holds returns [AdminResult.NodeUnreachable] and leaves that request alone.
     * @throws IllegalArgumentException if [node] is our own node or [NodeId.LOCAL]
     * @since 0.3.0
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    public suspend fun requestNodeInfo(
        node: NodeId,
        channel: ChannelIndex? = null,
        requestId: Int? = null,
    ): AdminResult<User>

    public companion object {
        public const val DEFAULT_HOP_LIMIT: Int = 7
    }
}
