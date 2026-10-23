/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import okio.ByteString
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import org.meshtastic.sdk.NodeId

/** Size of a node's Curve25519 public key (`User.public_key`). */
private const val PUBLIC_KEY_SIZE = 32

/**
 * Folds one live [packet] into the sender's node-DB entry, the way the firmware's own NodeDB does
 * (`NodeDB::updateFrom`, `NodeDB::updatePosition` and `NodeDB::updateUser` in
 * meshtastic/firmware `src/mesh/NodeDB.cpp`):
 *
 * - Every decoded packet from a peer refreshes `last_heard`, `via_mqtt` and `hops_away`, and `snr`
 *   when the packet was heard over the air. Packets from the local node don't touch these.
 * - `POSITION_APP` replaces `position`, keeping the previous `time` when the report carries none.
 * - `NODEINFO_APP` replaces `user` and records the packet's `channel` (the channel to reach the
 *   node on), unless it would change a public key already on file or claims the local node's key.
 *
 * Only an accepted `NODEINFO_APP` adds a node the DB doesn't know yet. Any other packet from an
 * unknown sender is ignored, so unauthenticated `from` values can't grow the node map.
 *
 * `last_heard` is the packet's `rx_time` when the radio set one (it leaves it out while its clock
 * is untrusted), else [nowEpochSeconds].
 *
 * Returns the merged entry, or `null` when the packet changes nothing.
 */
internal fun mergeLivePacket(
    existing: NodeInfo?,
    packet: MeshPacket,
    myNodeNum: Int,
    ownPublicKey: ByteString?,
    nowEpochSeconds: Long,
): NodeInfo? {
    val decoded = packet.decoded ?: return null
    if (packet.from == 0) return null
    val isSelf = packet.from == myNodeNum
    val user = if (decoded.portnum == PortNum.NODEINFO_APP) {
        acceptedUser(existing, packet, isSelf, ownPublicKey)
    } else {
        null
    }
    if (existing == null && user == null) return null
    val position = if (decoded.portnum == PortNum.POSITION_APP) {
        decodeOrNull { Position.ADAPTER.decode(decoded.payload) }
    } else {
        null
    }
    val builder = existing?.newBuilder() ?: NodeInfo.Builder().also { wb -> wb.num = packet.from }
    val merged = builder.also { wb ->
        if (user != null) {
            wb.user = user
            if (!isSelf) wb.channel = packet.channel
        }
        if (position != null) wb.position = mergedPosition(existing?.position, position)
        if (!isSelf) {
            wb.last_heard = packet.rx_time?.takeIf { it != 0 } ?: nowEpochSeconds.toInt()
            wb.via_mqtt = packet.via_mqtt
            if (isHeardOverTheAir(packet)) wb.snr = packet.rx_snr
            hopsAway(packet)?.let { wb.hops_away = it }
        }
    }.build()
    return merged.takeIf { it != existing }
}

/**
 * The [User] in a `NODEINFO_APP` packet, or `null` when it must not be stored: it would replace a
 * public key already on file (including with none), or a peer advertises the local node's own key.
 * The `id` is rewritten from the sender's node number, as the firmware does.
 */
private fun acceptedUser(existing: NodeInfo?, packet: MeshPacket, isSelf: Boolean, ownPublicKey: ByteString?): User? {
    val payload = packet.decoded?.payload ?: return null
    val incoming = decodeOrNull { User.ADAPTER.decode(payload) } ?: return null
    val key = incoming.public_key
    if (!isSelf && key.size == PUBLIC_KEY_SIZE && key == ownPublicKey) return null
    val knownKey = existing?.user?.public_key
    if (knownKey != null && knownKey.size == PUBLIC_KEY_SIZE && key != knownKey) return null
    return incoming.newBuilder().also { wb -> wb.id = NodeId(packet.from).toString() }.build()
}

/** A remote position report replaces the stored one, except for a `time` it doesn't carry. */
private fun mergedPosition(previous: Position?, incoming: Position): Position {
    if (incoming.time != 0 || previous == null || previous.time == 0) return incoming
    return incoming.newBuilder().also { wb -> wb.time = previous.time }.build()
}

/**
 * True when our own radio measured this reception: it sets `rx_rssi` only for packets it heard
 * over the air (the firmware's `has_rx_rssi` gate), never for MQTT downlinks.
 */
private fun isHeardOverTheAir(packet: MeshPacket): Boolean =
    packet.rx_rssi != null && packet.transport_mechanism != MeshPacket.TransportMechanism.TRANSPORT_MQTT

/** Hops the packet travelled, or `null` when unknown (no `hop_start`, or a tampered limit). */
private fun hopsAway(packet: MeshPacket): Int? {
    if (packet.hop_start == 0 || packet.hop_limit > packet.hop_start) return null
    return packet.hop_start - packet.hop_limit
}

private inline fun <T> decodeOrNull(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}
