/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position

internal const val POSITION_SCALE = 1e7

/**
 * Send a [Position] payload to [to] (defaults to broadcast).
 *
 * Encodes [latLng] using the firmware int32 / 1e7 lat/lng scaling.
 *
 * @since 0.1.0
 */
public suspend fun RadioClient.sendPosition(
    latLng: LatLng,
    to: NodeId = NodeId.BROADCAST,
    channel: ChannelIndex = ChannelIndex(0),
    wantAck: Boolean = false,
): MessageHandle {
    val payload = Position.Builder().also { wb ->
        wb.latitude_i = (latLng.latitude * POSITION_SCALE).toInt()
        wb.longitude_i = (latLng.longitude * POSITION_SCALE).toInt()
        wb.altitude = latLng.altitudeMeters
    }.build()
    return send(
        portnum = PortNum.POSITION_APP,
        payload = Position.ADAPTER.encode(payload),
        to = to,
        channel = channel,
        wantAck = wantAck,
    )
}

/**
 * Request the current [Position] from node [from].
 *
 * Sends an empty Position payload with `want_response = true`; the firmware
 * replies with the node's last known fix.
 *
 * @since 0.1.0
 */
public fun RadioClient.requestPosition(from: NodeId, channel: ChannelIndex = ChannelIndex(0)): MessageHandle {
    val packet = MeshPacket.Builder().also { wb ->
        wb.to = from.raw
        wb.channel = channel.raw
        wb.want_ack = false
        wb.decoded = Data.Builder().also { wb ->
            wb.portnum = PortNum.POSITION_APP
            wb.payload = ByteString.EMPTY
            wb.want_response = true
        }.build()
    }.build()
    return send(packet)
}

/**
 * Send a unicast text message to [to]. Defaults [wantAck] to `true`.
 *
 * @since 0.1.0
 */
public suspend fun RadioClient.sendDirectMessage(
    to: NodeId,
    text: String,
    channel: ChannelIndex = ChannelIndex(0),
    wantAck: Boolean = true,
): MessageHandle = send(
    portnum = PortNum.TEXT_MESSAGE_APP,
    payload = text.encodeToByteArray(),
    to = to,
    channel = channel,
    wantAck = wantAck,
)

/**
 * Send a PKI-encrypted unicast text message to [to].
 *
 * Sets `pki_encrypted = true` and forces channel `0`. Requires the
 * recipient's public key to already be present in the local NodeDB.
 *
 * @since 0.1.0
 */
public fun RadioClient.sendDirectMessageEncrypted(to: NodeId, text: String, wantAck: Boolean = true): MessageHandle {
    val packet = MeshPacket.Builder().also { wb ->
        wb.to = to.raw
        wb.channel = 0
        wb.want_ack = wantAck
        wb.pki_encrypted = true
        wb.decoded = Data.Builder().also { wb ->
            wb.portnum = PortNum.TEXT_MESSAGE_APP
            wb.payload = text.encodeToByteArray().toByteString()
        }.build()
    }.build()
    return send(packet)
}
