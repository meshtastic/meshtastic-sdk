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

/**
 * DSL marker for the [SendBuilder] outbound-packet builder. Restricts implicit-receiver
 * scoping so that nested DSLs do not accidentally capture builder methods from an outer
 * scope.
 */
@DslMarker
@Retention(AnnotationRetention.BINARY)
public annotation class MeshSendDsl

/**
 * Builder for an outbound [MeshPacket]. Created by [RadioClient.send] (DSL form).
 *
 * Exactly one payload setter ([text], [data], [position], or [proto]) must be called per
 * builder. Calling more than one — or calling none — throws [IllegalStateException] from
 * `build`.
 *
 * When [proto] is used the entire packet is taken as-is; subsequent calls to [to],
 * [channel], [wantAck], [hopLimit], [priority], [id], [replyTo], [reaction] or [pkiEncrypted]
 * throw [IllegalStateException] because they would silently overwrite caller-supplied wire
 * fields. Set those on the [MeshPacket] you pass to [proto] instead.
 *
 * Example:
 * ```kotlin
 * val handle = client.send {
 *     text("hello world")
 *     to(NodeId(0xa1b2c3d4.toInt()))
 *     channel(ChannelIndex(2))
 *     wantAck()
 *     hopLimit(3)
 * }
 * ```
 *
 * @since 0.1.0
 */
@MeshSendDsl
public class SendBuilder internal constructor() {

    private var portnum: PortNum? = null
    private var payload: ByteString? = null
    private var rawPacket: MeshPacket? = null
    private var payloadCallSites: Int = 0

    private var to: NodeId = NodeId.BROADCAST
    private var channel: ChannelIndex = ChannelIndex(0)
    private var wantAck: Boolean = false
    private var hopLimit: Int? = null
    private var priority: MeshPacket.Priority = MeshPacket.Priority.UNSET
    private var id: MessageId? = null
    private var replyTo: MessageId? = null
    private var reaction: Boolean = false
    private var pkiEncrypted: Boolean = false
    private var pinnedPublicKey: ByteString? = null

    /** Set the destination [NodeId]. Defaults to [NodeId.BROADCAST]. */
    public fun to(nodeId: NodeId) {
        checkNotProtoOnly("to(NodeId)")
        this.to = nodeId
    }

    /** Set the [ChannelIndex] this packet is sent on. Defaults to channel 0. */
    public fun channel(channel: ChannelIndex) {
        checkNotProtoOnly("channel(ChannelIndex)")
        this.channel = channel
    }

    /**
     * Request a delivery ACK from the firmware. Defaults to `false` (no ACK requested).
     */
    public fun wantAck(value: Boolean) {
        checkNotProtoOnly("wantAck()")
        this.wantAck = value
    }

    /** Shorthand for `wantAck(true)`. */
    public fun wantAck(): Unit = wantAck(true)

    /**
     * Override the per-packet hop limit. `null` (the default) sends the device's configured
     * `lora.hop_limit`, or 3 before the handshake has delivered it; see the `hopLimit`
     * parameter of [RadioClient.send] for why the SDK resolves it rather than sending `0`.
     */
    public fun hopLimit(hops: Int?) {
        checkNotProtoOnly("hopLimit(Int)")
        this.hopLimit = hops
    }

    /** Set the packet priority, for example `BACKGROUND` for position and telemetry traffic. Defaults to `UNSET`. */
    public fun priority(value: MeshPacket.Priority) {
        checkNotProtoOnly("priority(Priority)")
        this.priority = value
    }

    /**
     * Send under a caller-chosen packet id, for example one from [RadioClient.nextPacketId] that a durable
     * outbox stored before the send. The returned handle carries it. A send whose id an in-flight send or
     * RPC still holds fails with [SendFailure.IdCollision]. Without it the SDK assigns the id.
     *
     * @throws IllegalArgumentException if [id] is zero, which the wire reserves for "unset"
     * @since 0.3.0
     */
    public fun id(id: MessageId) {
        checkNotProtoOnly("id(MessageId)")
        require(id.raw != 0) { "packet id 0 means unset on the wire" }
        this.id = id
    }

    /**
     * Mark this packet as a reply to [message], the packet id of the message it answers (`Data.reply_id`).
     *
     * @since 0.3.0
     */
    public fun replyTo(message: MessageId) {
        checkNotProtoOnly("replyTo(MessageId)")
        this.replyTo = message
    }

    /**
     * Mark the payload as a reaction to the message named by [replyTo] rather than a standalone message
     * (`Data.emoji = 1`). Defaults to `false`.
     *
     * @since 0.3.0
     */
    public fun reaction(value: Boolean) {
        checkNotProtoOnly("reaction()")
        this.reaction = value
    }

    /**
     * Shorthand for `reaction(true)`.
     *
     * @since 0.3.0
     */
    public fun reaction(): Unit = reaction(true)

    /**
     * Encrypt to the recipient's public key (PKI direct message) instead of a channel key. The packet goes
     * out on channel 0 with `pki_encrypted` set and `public_key` set to the key the SDK holds for [to], or
     * empty when it holds none, in which case the firmware uses its own copy or refuses the send with
     * `PKI_SEND_FAIL_PUBLIC_KEY`. `send { … }` throws [IllegalStateException] for a broadcast destination.
     *
     * @since 0.3.0
     */
    public fun pkiEncrypted() {
        checkNotProtoOnly("pkiEncrypted()")
        this.pkiEncrypted = true
        this.pinnedPublicKey = null
    }

    /**
     * As [pkiEncrypted], pinned to [publicKey]: firmware refuses the send with `PKI_FAILED` when its stored key
     * for the recipient differs, so a key the host has verified cannot be swapped underneath it.
     *
     * @since 0.3.0
     */
    public fun pkiEncrypted(publicKey: ByteString) {
        checkNotProtoOnly("pkiEncrypted(ByteString)")
        this.pkiEncrypted = true
        this.pinnedPublicKey = publicKey
    }

    /** Encode [text] as a `TEXT_MESSAGE_APP` payload (UTF-8). Mutually exclusive with the other payload setters. */
    public fun text(text: String) {
        recordPayload()
        this.portnum = PortNum.TEXT_MESSAGE_APP
        this.payload = text.encodeToByteArray().toByteString()
    }

    /** Use [bytes] as the wire payload for [portnum]. Mutually exclusive with the other payload setters. */
    public fun data(portnum: PortNum, bytes: ByteArray) {
        recordPayload()
        this.portnum = portnum
        this.payload = bytes.toByteString()
    }

    /**
     * Encode a [Position] payload from [latLng] (latitude/longitude in degrees, optional
     * altitude in meters). Mutually exclusive with the other payload setters. Uses
     * `latitude_i = (lat * 1e7).toInt()` per firmware convention.
     */
    public fun position(latLng: LatLng) {
        recordPayload()
        this.portnum = PortNum.POSITION_APP
        val pos = Position.Builder().also { wb ->
            wb.latitude_i = (latLng.latitude * POSITION_SCALE).toInt()
            wb.longitude_i = (latLng.longitude * POSITION_SCALE).toInt()
            wb.altitude = latLng.altitudeMeters
        }.build()
        this.payload = Position.ADAPTER.encode(pos).toByteString()
    }

    /**
     * Escape hatch — use [packet] verbatim as the entire outbound packet. After calling
     * [proto], the convenience setters ([to], [channel], [wantAck], [hopLimit], [priority],
     * [id], [replyTo], [reaction], [pkiEncrypted]) all throw [IllegalStateException].
     */
    public fun proto(packet: MeshPacket) {
        recordPayload()
        this.rawPacket = packet
    }

    internal val needsStoredPublicKey: Boolean get() = pkiEncrypted && pinnedPublicKey == null

    private fun recordPayload() {
        payloadCallSites += 1
    }

    private fun checkNotProtoOnly(method: String) {
        check(rawPacket == null) {
            "$method cannot be combined with proto(MeshPacket); set those fields on " +
                "the MeshPacket you pass to proto() instead."
        }
    }

    internal fun build(defaultHopLimit: Int, storedPublicKey: (NodeId) -> ByteString? = { null }): MeshPacket {
        check(payloadCallSites > 0) {
            "send { ... } requires exactly one of text(), data(), position(), or proto(); none was called."
        }
        check(payloadCallSites == 1) {
            "send { ... } requires exactly one of text(), data(), position(), or proto(); $payloadCallSites were called."
        }
        rawPacket?.let { return it }
        val portnum = checkNotNull(portnum)
        val payload = checkNotNull(payload)
        check(!pkiEncrypted || to != NodeId.BROADCAST) { "pkiEncrypted() needs a unicast destination" }
        return MeshPacket.Builder().also { wb ->
            wb.to = to.raw
            wb.id = id?.raw ?: 0
            wb.want_ack = wantAck
            wb.hop_limit = hopLimit ?: defaultHopLimit
            wb.priority = priority
            if (pkiEncrypted) {
                wb.channel = 0
                wb.pki_encrypted = true
                wb.public_key = pinnedPublicKey ?: storedPublicKey(to) ?: ByteString.EMPTY
            } else {
                wb.channel = channel.raw
            }
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = portnum
                wb.payload = payload
                wb.want_response = false
                wb.reply_id = replyTo?.raw ?: 0
                wb.emoji = if (reaction) EMOJI_INDICATOR else 0
            }.build()
        }.build()
    }
}

// `Data.emoji` is a boolean carried as fixed32 (mesh.proto); 1 marks a reaction.
private const val EMOJI_INDICATOR: Int = 1

// Curve25519 public key length, as `User.public_key` carries it.
private const val PKI_PUBLIC_KEY_SIZE: Int = 32

private suspend fun RadioClient.storedPublicKeys(builder: SendBuilder): (NodeId) -> ByteString? {
    if (!builder.needsStoredPublicKey) return { null }
    val nodes = nodeSnapshot()
    return { node -> nodes[node]?.user?.public_key?.takeIf { it.size == PKI_PUBLIC_KEY_SIZE } }
}

/**
 * DSL form of [RadioClient.send]. Builds a [MeshPacket] via [SendBuilder] and enqueues it.
 *
 * Example:
 * ```kotlin
 * val handle = client.send {
 *     text("hello world")
 *     to(NodeId(0xa1b2c3d4.toInt()))
 *     channel(ChannelIndex(2))
 *     wantAck()
 *     hopLimit(3)
 * }
 * ```
 *
 * @throws IllegalStateException if zero or more than one payload setter was called
 * @since 0.1.0
 */
public suspend fun RadioClient.send(block: SendBuilder.() -> Unit): MessageHandle {
    val builder = SendBuilder().apply(block)
    return send(builder.build(defaultHopLimit(), storedPublicKeys(builder)))
}

/**
 * DSL form of [RadioClient.sendPaced]. Builds the same [MeshPacket] as `send { … }` and queues it
 * behind the current radio queue write.
 *
 * @throws IllegalStateException if zero or more than one payload setter was called
 * @since 0.3.0
 */
public suspend fun RadioClient.sendPaced(block: SendBuilder.() -> Unit): PacedMessageHandle {
    val builder = SendBuilder().apply(block)
    return sendPaced(builder.build(defaultHopLimit(), storedPublicKeys(builder)))
}
