/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import com.squareup.wire.ProtoAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import okio.ByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NeighborInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import org.meshtastic.proto.Waypoint
private fun MeshPacket.payloadOrNull(expected: PortNum): ByteString? {
    val data = decoded ?: return null
    if (data.portnum != expected) return null
    val payload = data.payload
    if (payload.size == 0) return null
    return payload
}

private fun <T> MeshPacket.decodeIfPort(expected: PortNum, adapter: ProtoAdapter<T>): T? {
    val payload = payloadOrNull(expected) ?: return null
    return runCatching { adapter.decode(payload) }.getOrNull()
}

/**
 * Decodes the payload as a UTF-8 string if [MeshPacket.decoded.portnum] matches [PortNum.TEXT_MESSAGE_APP].
 */
public fun MeshPacket.asText(): String? = payloadOrNull(PortNum.TEXT_MESSAGE_APP)?.utf8()

/**
 * Filters [RadioClient.packets] to only text-message packets — those with
 * `decoded.portnum == [PortNum.TEXT_MESSAGE_APP]`.
 *
 * Each emitted [MeshPacket] can be accessed via:
 * - `asText()` — decoded UTF-8 body; returns `null` if the payload is empty
 * - `NodeId(packet.from)` — sender node
 * - `ChannelIndex(packet.channel)` — channel index (0–7)
 * - `packet.rx_time` — receive timestamp (Unix seconds, `Int`)
 * - `packet.to` — destination node num (`0xFFFFFFFF` = broadcast)
 *
 * This flow is **hot with no replay** — it inherits [RadioClient.packets] semantics.
 * Subscribers receive only packets that arrive after they start collecting.
 */
public val RadioClient.textMessages: Flow<MeshPacket>
    get() = packets.filter { it.decoded?.portnum == PortNum.TEXT_MESSAGE_APP }

/**
 * Decodes the payload as [Position] if [MeshPacket.decoded.portnum] matches [PortNum.POSITION_APP].
 */
public fun MeshPacket.asPosition(): Position? = decodeIfPort(PortNum.POSITION_APP, Position.ADAPTER)

/**
 * Decodes the payload as [User] if [MeshPacket.decoded.portnum] matches [PortNum.NODEINFO_APP].
 *
 * Note: [PortNum.NODEINFO_APP] can contain either a [User] or [NodeInfo] protobuf depending on
 * context; use [asNodeInfo] to attempt the latter.
 */
public fun MeshPacket.asNodeInfoUser(): User? = decodeIfPort(PortNum.NODEINFO_APP, User.ADAPTER)

/**
 * Decodes the payload as [NodeInfo] if [MeshPacket.decoded.portnum] matches [PortNum.NODEINFO_APP].
 */
public fun MeshPacket.asNodeInfo(): NodeInfo? = decodeIfPort(PortNum.NODEINFO_APP, NodeInfo.ADAPTER)

/**
 * Decodes the payload as [Telemetry] if [MeshPacket.decoded.portnum] matches [PortNum.TELEMETRY_APP].
 */
public fun MeshPacket.asTelemetry(): Telemetry? = decodeIfPort(PortNum.TELEMETRY_APP, Telemetry.ADAPTER)

/**
 * Decodes the payload as [AdminMessage] if [MeshPacket.decoded.portnum] matches [PortNum.ADMIN_APP].
 */
public fun MeshPacket.asAdminMessage(): AdminMessage? = decodeIfPort(PortNum.ADMIN_APP, AdminMessage.ADAPTER)

/**
 * Decodes the payload as [Routing] if [MeshPacket.decoded.portnum] matches [PortNum.ROUTING_APP].
 */
public fun MeshPacket.asRouting(): Routing? = decodeIfPort(PortNum.ROUTING_APP, Routing.ADAPTER)

/**
 * Decodes the payload as [Waypoint] if [MeshPacket.decoded.portnum] matches [PortNum.WAYPOINT_APP].
 */
public fun MeshPacket.asWaypoint(): Waypoint? = decodeIfPort(PortNum.WAYPOINT_APP, Waypoint.ADAPTER)

/**
 * Decodes the payload as [RouteDiscovery] if [MeshPacket.decoded.portnum] matches [PortNum.TRACEROUTE_APP].
 */
public fun MeshPacket.asTraceroute(): RouteDiscovery? = decodeIfPort(PortNum.TRACEROUTE_APP, RouteDiscovery.ADAPTER)

/**
 * Decodes the payload as [NeighborInfo] if [MeshPacket.decoded.portnum] matches [PortNum.NEIGHBORINFO_APP].
 */
public fun MeshPacket.asNeighborInfo(): NeighborInfo? = decodeIfPort(PortNum.NEIGHBORINFO_APP, NeighborInfo.ADAPTER)
