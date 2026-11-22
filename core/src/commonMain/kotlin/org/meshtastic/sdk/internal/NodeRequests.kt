/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.User

// One builder per request, shared by the MessageHandle helpers and the reply-awaiting RoutingApi calls.

/** `POSITION_APP` request carrying [position] (stamped with [nowEpochSeconds] when its `time` is 0), or empty. */
internal fun positionRequestPacket(
    to: Int,
    channel: Int,
    hopLimit: Int,
    position: Position?,
    nowEpochSeconds: () -> Int,
): MeshPacket {
    val payload = position?.let { pos ->
        val stamped = if (pos.time == 0) pos.newBuilder().also { it.time = nowEpochSeconds() }.build() else pos
        Position.ADAPTER.encode(stamped).toByteString()
    } ?: ByteString.EMPTY
    return MeshPacket.Builder().also { wb ->
        wb.to = to
        wb.channel = channel
        wb.want_ack = false
        wb.hop_limit = hopLimit
        wb.priority = MeshPacket.Priority.BACKGROUND
        wb.decoded = Data.Builder().also { wb ->
            wb.portnum = PortNum.POSITION_APP
            wb.payload = payload
            wb.want_response = true
        }.build()
    }.build()
}

/** `NODEINFO_APP` request carrying [user], or an empty payload when it is `null`. */
internal fun nodeInfoRequestPacket(to: Int, channel: Int, hopLimit: Int, user: User?): MeshPacket =
    MeshPacket.Builder().also { wb ->
        wb.to = to
        wb.channel = channel
        wb.want_ack = false
        wb.hop_limit = hopLimit
        wb.decoded = Data.Builder().also { wb ->
            wb.portnum = PortNum.NODEINFO_APP
            wb.payload = user?.let { User.ADAPTER.encode(it).toByteString() } ?: ByteString.EMPTY
            wb.want_response = true
        }.build()
    }.build()
