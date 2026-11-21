/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RoutingApi
import kotlin.time.Duration
import org.meshtastic.proto.NeighborInfo as ProtoNeighborInfo

/**
 * Engine-backed [RoutingApi].
 *
 * - [traceRoute] sends an empty `RouteDiscovery` on `TRACEROUTE_APP` with `want_response` and
 *   `want_ack`; `TraceRouteModule` on each hop appends itself and the destination replies on the
 *   same port. The dispatcher matches by `request_id`.
 * - [requestNeighborInfo] sends an empty [ProtoNeighborInfo] on `NEIGHBORINFO_APP` with
 *   `want_response = true`. The neighborinfo module on the device responds with its current
 *   neighbor table.
 */
internal class RoutingApiImpl(private val engine: MeshEngine, private val rpcTimeout: Duration) : RoutingApi {

    override fun withRpcTimeout(timeout: Duration): RoutingApi = RoutingApiImpl(engine, timeout)

    override suspend fun traceRoute(
        dest: NodeId,
        hopLimit: Int?,
        channel: ChannelIndex?,
        requestId: Int?,
    ): AdminResult<RouteDiscovery> {
        val local = engine.myNodeNumOrNull() ?: return AdminResult.NodeUnreachable
        val id = requestId?.takeIf { it != 0 } ?: engine.nextMessageId().raw
        // TraceRouteModule answers only TRACEROUTE_APP; RoutingModule never replies to a route_request.
        val packet = MeshPacket.Builder().also { wb ->
            wb.id = id
            wb.from = local
            wb.to = dest.raw
            wb.channel = (channel ?: heardChannel(dest)).raw
            wb.want_ack = true
            wb.hop_limit = hopLimit ?: engine.defaultHopLimit()
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.TRACEROUTE_APP
                wb.payload = RouteDiscovery.ADAPTER.encode(RouteDiscovery.Builder().build()).toByteString()
                wb.want_response = true
                wb.dest = dest.raw
            }.build()
        }.build()
        return engine.submitRpc(packet, id, ResponseKind.RouteDiscoveryReply, rpcTimeout)
    }

    override suspend fun requestNeighborInfo(
        node: NodeId,
        channel: ChannelIndex?,
        requestId: Int?,
    ): AdminResult<ProtoNeighborInfo> {
        val local = engine.myNodeNumOrNull() ?: return AdminResult.NodeUnreachable
        val target = if (node == NodeId.LOCAL) NodeId(local) else node
        val remote = target.raw != local
        val id = requestId?.takeIf { it != 0 } ?: engine.nextMessageId().raw
        val payload = ProtoNeighborInfo.ADAPTER.encode(ProtoNeighborInfo.Builder().build()).toByteString()
        val packet = MeshPacket.Builder().also { wb ->
            wb.id = id
            wb.from = local
            wb.to = target.raw
            if (remote) {
                // Without want_ack firmware keeps a zero hop limit, so the request would stop at a neighbor.
                wb.channel = (channel ?: heardChannel(target)).raw
                wb.want_ack = true
                wb.hop_limit = engine.defaultHopLimit()
            }
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.NEIGHBORINFO_APP
                wb.payload = payload
                wb.want_response = true
                if (remote) wb.dest = target.raw
            }.build()
        }.build()
        return engine.submitRpc(packet, id, ResponseKind.NeighborInfoReply, rpcTimeout)
    }

    private fun heardChannel(node: NodeId): ChannelIndex = ChannelIndex(engine.nodeSnapshot()[node]?.channel ?: 0)
}
