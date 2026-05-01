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
 * [channel], [wantAck], or [hopLimit] throw [IllegalStateException] because they would
 * silently overwrite caller-supplied wire fields. Set those on the [MeshPacket] you pass
 * to [proto] instead.
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
     * Override the per-packet hop limit. `null` (the default) leaves the proto-default
     * `0` so the firmware applies its configured global default.
     */
    public fun hopLimit(hops: Int?) {
        checkNotProtoOnly("hopLimit(Int)")
        this.hopLimit = hops
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
        val pos = Position(
            latitude_i = (latLng.latitude * POSITION_SCALE).toInt(),
            longitude_i = (latLng.longitude * POSITION_SCALE).toInt(),
            altitude = latLng.altitudeMeters,
        )
        this.payload = Position.ADAPTER.encode(pos).toByteString()
    }

    /**
     * Escape hatch — use [packet] verbatim as the entire outbound packet. After calling
     * [proto], the convenience setters [to], [channel], [wantAck], and [hopLimit] all
     * throw [IllegalStateException].
     */
    public fun proto(packet: MeshPacket) {
        recordPayload()
        this.rawPacket = packet
    }

    private fun recordPayload() {
        payloadCallSites += 1
    }

    private fun checkNotProtoOnly(method: String) {
        check(rawPacket == null) {
            "$method cannot be combined with proto(MeshPacket); set those fields on " +
                "the MeshPacket you pass to proto() instead."
        }
    }

    internal fun build(): MeshPacket {
        check(payloadCallSites > 0) {
            "send { ... } requires exactly one of text(), data(), position(), or proto(); none was called."
        }
        check(payloadCallSites == 1) {
            "send { ... } requires exactly one of text(), data(), position(), or proto(); $payloadCallSites were called."
        }
        rawPacket?.let { return it }
        val portnum = checkNotNull(portnum)
        val payload = checkNotNull(payload)
        return MeshPacket(
            to = to.raw,
            channel = channel.raw,
            want_ack = wantAck,
            hop_limit = hopLimit ?: 0,
            decoded = Data(portnum = portnum, payload = payload, want_response = false),
        )
    }
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
    return send(builder.build())
}
