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
import org.meshtastic.proto.Routing
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RoutingApi
import kotlin.time.Duration
import org.meshtastic.proto.NeighborInfo as ProtoNeighborInfo

/**
 * Engine-backed [RoutingApi].
 *
 * - [traceRoute] sends an empty `RouteDiscovery` wrapped in a [Routing] envelope on
 *   `ROUTING_APP` with `want_response = true`. The mesh propagates the discovery hop-by-hop;
 *   the destination replies with `route_reply` populated. The dispatcher matches by
 *   `request_id`.
 * - [requestNeighborInfo] sends an empty [ProtoNeighborInfo] on `NEIGHBORINFO_APP` with
 *   `want_response = true`. The neighborinfo module on the device responds with its current
 *   neighbor table.
 */
internal class RoutingApiImpl(private val engine: MeshEngine, private val rpcTimeout: Duration) : RoutingApi {

    override suspend fun traceRoute(dest: NodeId, hopLimit: Int): AdminResult<RouteDiscovery> {
        if (engine.myNodeNumOrNull() == null) return AdminResult.NodeUnreachable
        val requestId = engine.nextMessageId().raw
        val payload = Routing.ADAPTER.encode(Routing.Builder().also { wb ->wb.route_request = RouteDiscovery.Builder().build()}.build()).toByteString()
        val packet = MeshPacket.Builder().also { wb ->
        wb.id = requestId
        wb.from = engine.myNodeNumOrNull() ?: 0
        wb.to = dest.raw
        wb.hop_limit = hopLimit.coerceAtLeast(0)
        wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.ROUTING_APP
                    wb.payload = payload
                    wb.want_response = true
                    }.build()
        }.build()
        return engine.submitRpc(packet, requestId, ResponseKind.RouteDiscoveryReply, rpcTimeout)
    }

    override suspend fun requestNeighborInfo(node: NodeId): AdminResult<ProtoNeighborInfo> {
        val target = if (node == NodeId.LOCAL) {
            NodeId(engine.myNodeNumOrNull() ?: return AdminResult.NodeUnreachable)
        } else {
            node
        }
        val requestId = engine.nextMessageId().raw
        val payload = ProtoNeighborInfo.ADAPTER.encode(ProtoNeighborInfo.Builder().build()).toByteString()
        val packet = MeshPacket.Builder().also { wb ->
        wb.id = requestId
        wb.from = engine.myNodeNumOrNull() ?: 0
        wb.to = target.raw
        wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.NEIGHBORINFO_APP
                    wb.payload = payload
                    wb.want_response = true
                    }.build()
        }.build()
        return engine.submitRpc(packet, requestId, ResponseKind.NeighborInfoReply, rpcTimeout)
    }
}
