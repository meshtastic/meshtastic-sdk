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
import org.meshtastic.proto.NeighborInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.Routing
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RoutingApi
import kotlin.time.Duration

/**
 * Engine-backed [RoutingApi].
 *
 * - [traceRoute] sends an empty `RouteDiscovery` wrapped in a [Routing] envelope on
 *   `ROUTING_APP` with `want_response = true`. The mesh propagates the discovery hop-by-hop;
 *   the destination replies with `route_reply` populated. The dispatcher matches by
 *   `request_id`.
 * - [requestNeighborInfo] sends an empty [NeighborInfo] on `NEIGHBORINFO_APP` with
 *   `want_response = true`. The neighborinfo module on the device responds with its current
 *   neighbor table.
 */
internal class RoutingApiImpl(private val engine: MeshEngine, private val rpcTimeout: Duration) : RoutingApi {

    override suspend fun traceRoute(dest: NodeId, hopLimit: Int): AdminResult<RouteDiscovery> {
        if (engine.myNodeNumOrNull() == null) return AdminResult.NodeUnreachable
        val requestId = engine.nextMessageId().raw
        val payload = Routing.ADAPTER.encode(Routing(route_request = RouteDiscovery())).toByteString()
        val packet = MeshPacket(
            id = requestId,
            from = engine.myNodeNumOrNull() ?: 0,
            to = dest.raw,
            hop_limit = hopLimit.coerceAtLeast(0),
            decoded = Data(
                portnum = PortNum.ROUTING_APP,
                payload = payload,
                want_response = true,
            ),
        )
        return engine.submitRpc(packet, requestId, ResponseKind.RouteDiscoveryReply, rpcTimeout)
    }

    override suspend fun requestNeighborInfo(node: NodeId): AdminResult<NeighborInfo> {
        val target = if (node == NodeId.LOCAL) {
            NodeId(engine.myNodeNumOrNull() ?: return AdminResult.NodeUnreachable)
        } else {
            node
        }
        val requestId = engine.nextMessageId().raw
        val payload = NeighborInfo.ADAPTER.encode(NeighborInfo()).toByteString()
        val packet = MeshPacket(
            id = requestId,
            from = engine.myNodeNumOrNull() ?: 0,
            to = target.raw,
            decoded = Data(
                portnum = PortNum.NEIGHBORINFO_APP,
                payload = payload,
                want_response = true,
            ),
        )
        return engine.submitRpc(packet, requestId, ResponseKind.NeighborInfoReply, rpcTimeout)
    }
}
