/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Waypoint
import kotlin.random.Random
import kotlin.time.Instant

/** `Waypoint.expire` value firmware and the apps treat as "deleted". */
private const val WAYPOINT_DELETED_EXPIRE = 1

/**
 * Whether this waypoint is no longer live at [now].
 *
 * `expire == 0` means the waypoint never expires and `expire == 1` is the mesh convention for
 * "delete this waypoint" (`firmware:src/meshUtils.h:95-102`); any other value is an epoch-seconds
 * deadline and the waypoint is expired once [now] reaches it.
 *
 * The SDK keeps no waypoint store: history and the current set of waypoints are the consumer's
 * (docs/roadmap.md R-10). Fold [RadioClient.packets] through [asWaypoint] and drop what this
 * returns `true` for.
 *
 * @since 0.3.0
 */
public fun Waypoint.isExpired(now: Instant): Boolean {
    val deadline = expire.toLong() and 0xFFFFFFFFL
    if (deadline == 0L) return false
    return deadline <= now.epochSeconds
}

/**
 * Whether [node] may edit or delete this waypoint: true when it is unlocked (`locked_to == 0`)
 * or locked to exactly [node].
 *
 * @since 0.3.0
 */
public fun Waypoint.canBeEditedBy(node: NodeId): Boolean = locked_to == 0 || locked_to == node.raw

/**
 * A fresh, non-zero waypoint id. Waypoints are keyed by id, so two with the same id replace one
 * another on every device, and [sendWaypoint] refuses id `0`.
 *
 * @since 0.3.0
 */
public fun newWaypointId(random: Random = Random): Int {
    while (true) {
        val id = random.nextInt()
        if (id != 0) return id
    }
}

/**
 * Broadcast [waypoint] on [WAYPOINT_APP][PortNum.WAYPOINT_APP], creating it or, with an id
 * already in use, replacing it on every device that holds it.
 *
 * Give a new waypoint an id from [newWaypointId]. The SDK keeps no waypoint store, so the caller
 * tracks what it has sent.
 *
 * @throws IllegalArgumentException if [Waypoint.id] is `0`
 * @throws MeshtasticException.Protocol if the waypoint is locked to a node other than ours
 * @throws MeshtasticException.NotConnected if not currently connected
 * @since 0.3.0
 */
public suspend fun RadioClient.sendWaypoint(
    waypoint: Waypoint,
    to: NodeId = NodeId.BROADCAST,
    channel: ChannelIndex = ChannelIndex(0),
    wantAck: Boolean = false,
): MessageHandle {
    require(waypoint.id != 0) { "a waypoint id must be non-zero" }
    requireOwnerOf(waypoint)
    return send(
        portnum = PortNum.WAYPOINT_APP,
        payload = Waypoint.ADAPTER.encode(waypoint),
        to = to,
        channel = channel,
        wantAck = wantAck,
    )
}

/**
 * Delete [waypoint] mesh-wide by re-sending it with `expire = 1`, the deletion convention
 * firmware and the Android app use (`firmware:src/modules/WaypointModule.cpp:226-227`).
 *
 * @throws IllegalArgumentException if [Waypoint.id] is `0`
 * @throws MeshtasticException.Protocol if the waypoint is locked to a node other than ours
 * @throws MeshtasticException.NotConnected if not currently connected
 * @since 0.3.0
 */
public suspend fun RadioClient.deleteWaypoint(
    waypoint: Waypoint,
    to: NodeId = NodeId.BROADCAST,
    channel: ChannelIndex = ChannelIndex(0),
    wantAck: Boolean = false,
): MessageHandle = sendWaypoint(
    waypoint = waypoint.newBuilder().also { wb -> wb.expire = WAYPOINT_DELETED_EXPIRE }.build(),
    to = to,
    channel = channel,
    wantAck = wantAck,
)

private fun RadioClient.requireOwnerOf(waypoint: Waypoint) {
    if (waypoint.locked_to == 0) return
    val own = ownNode.value?.num
    if (own == null || !waypoint.canBeEditedBy(NodeId(own))) {
        throw MeshtasticException.Protocol("waypoint ${waypoint.id} is locked to another node")
    }
}
