/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.Paxcount
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.sdk.internal.positionRequestPacket
import kotlin.coroutines.cancellation.CancellationException

internal const val POSITION_SCALE = 1e7
private const val PKI_KEY_SIZE = 32

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
 * Sends an empty Position payload with `want_response = true` at background priority and the
 * device's hop limit, since firmware raises a zero hop limit only on a `want_ack` packet
 * (`firmware:src/mesh/Router.cpp:468`); it replies with its own position whatever the request carries
 * (`firmware:src/modules/PositionModule.cpp:291-304`, `src/mesh/MeshModule.cpp:186-190`).
 * Use [requestPositionOnHeardChannel] to address the node on the channel it was last heard on.
 * The handle tracks only the send; [RoutingApi.requestPosition] sends the same request and returns
 * the reply.
 *
 * @since 0.1.0
 */
public fun RadioClient.requestPosition(from: NodeId, channel: ChannelIndex = ChannelIndex(0)): MessageHandle =
    requestPosition(from, channel, position = null)

/**
 * [requestPosition] that also shares [position] with [from], as the Android app does.
 *
 * A non-null [position] is sent as the payload with `time` set to now when it is `0`, so set
 * coordinates only when they are valid. `null` sends the empty payload of [requestPosition].
 * Uses the device's hop limit.
 *
 * @since 0.3.0
 */
@Throws(MeshtasticException::class)
public fun RadioClient.requestPosition(
    from: NodeId,
    channel: ChannelIndex = ChannelIndex(0),
    position: Position?,
): MessageHandle = send(positionRequestPacket(from.raw, channel.raw, defaultHopLimit(), position) { nowEpochSeconds() })

/**
 * [requestPosition] carrying [position] to [from] on the channel [channelFor] resolves for it.
 *
 * @since 0.3.0
 */
@Throws(MeshtasticException::class, CancellationException::class)
public suspend fun RadioClient.requestPositionOnHeardChannel(from: NodeId, position: Position?): MessageHandle =
    requestPosition(from, channelFor(from), position)

/**
 * [RadioClient.requestNodeInfo] carrying our own user to [node] on the channel [channelFor] resolves for it.
 *
 * @throws IllegalArgumentException if [node] is our own node or [NodeId.LOCAL]
 * @since 0.3.0
 */
@Throws(MeshtasticException::class, IllegalArgumentException::class, CancellationException::class)
public suspend fun RadioClient.requestNodeInfoOnHeardChannel(node: NodeId): MessageHandle =
    requestNodeInfo(node, channelFor(node))

/**
 * Request [node]'s pax counter reading on the channel [channelFor] resolves for it.
 *
 * Sends an empty `Paxcount` on `PAXCOUNTER_APP` with `want_response = true`, `Data.dest = node`
 * and no `want_ack`. The reply arrives on [RadioClient.packets]; decode it with [asPaxcount].
 *
 * @since 0.3.0
 */
@Throws(MeshtasticException::class, CancellationException::class)
public suspend fun RadioClient.requestPaxcount(node: NodeId): MessageHandle {
    val channel = channelFor(node)
    val packet = MeshPacket.Builder().also { wb ->
        wb.to = node.raw
        wb.channel = channel.raw
        wb.want_ack = false
        wb.hop_limit = defaultHopLimit()
        wb.decoded = Data.Builder().also { wb ->
            wb.portnum = PortNum.PAXCOUNTER_APP
            wb.payload = Paxcount.ADAPTER.encode(Paxcount.Builder().build()).toByteString()
            wb.want_response = true
            wb.dest = node.raw
        }.build()
    }.build()
    return send(packet)
}

/**
 * The channel [node] was last heard on, from its node DB entry.
 *
 * `NodeInfo.channel` is `0` for the primary channel, for a node never heard live, for an unknown
 * node and for our own node, so those all resolve to channel `0`. A PKI-encrypted DM ignores the
 * channel; a plain unicast and a position request use it (Android: `CommandSenderImpl.kt:141`).
 *
 * @since 0.3.0
 */
public suspend fun RadioClient.channelFor(node: NodeId): ChannelIndex = ChannelIndex(nodeSnapshot()[node]?.channel ?: 0)

/**
 * [requestPosition] to [from] on the channel [channelFor] resolves for it.
 *
 * @since 0.3.0
 */
public suspend fun RadioClient.requestPositionOnHeardChannel(from: NodeId): MessageHandle =
    requestPosition(from, channelFor(from))

/**
 * [sendDirectMessage] to [to] on the channel [channelFor] resolves for it.
 *
 * @since 0.3.0
 */
public suspend fun RadioClient.sendDirectMessageOnHeardChannel(
    to: NodeId,
    text: String,
    wantAck: Boolean = true,
): MessageHandle = sendDirectMessage(to, text, channelFor(to), wantAck)

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
 * Sets `pki_encrypted = true`, forces channel `0` and fills `public_key` with the 32-byte key
 * stored for [to]. The device encrypts with its own NodeDB key and only compares the supplied
 * one: a different key fails the send with `PKI_FAILED`. With no stored key `public_key` stays
 * empty and the device rejects the send with `PKI_SEND_FAIL_PUBLIC_KEY`, reported as
 * [SendFailure.QueueRejected] (`res = 39`). A licensed (ham) device never uses PKI and fails
 * the send with `PKI_FAILED`.
 *
 * A plain [RadioClient.sendText] to a node the device holds a key for is PKI-encrypted by the
 * device itself, so this helper is only needed to demand PKI or pin the key.
 *
 * @throws MeshtasticException.NotConnected if not currently connected
 * @since 0.1.0
 */
@Throws(MeshtasticException::class, CancellationException::class)
public suspend fun RadioClient.sendDirectMessageEncrypted(
    to: NodeId,
    text: String,
    wantAck: Boolean = true,
): MessageHandle {
    val storedKey = nodeSnapshot()[to]?.user?.public_key?.takeIf { it.size == PKI_KEY_SIZE }
    val packet = MeshPacket.Builder().also { wb ->
        wb.to = to.raw
        wb.channel = 0
        wb.want_ack = wantAck
        wb.pki_encrypted = true
        if (storedKey != null) wb.public_key = storedKey
        wb.decoded = Data.Builder().also { wb ->
            wb.portnum = PortNum.TEXT_MESSAGE_APP
            wb.payload = text.encodeToByteArray().toByteString()
        }.build()
    }.build()
    return send(packet)
}
