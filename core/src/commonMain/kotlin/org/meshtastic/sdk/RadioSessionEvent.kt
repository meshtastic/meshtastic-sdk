/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Channel
import org.meshtastic.proto.FileInfo
import org.meshtastic.proto.LoRaRegionPresetMap
import org.meshtastic.proto.LockdownStatus
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo

/** Ordered receive events for a single [RadioClient]. Session IDs are scoped to that client. */
public sealed interface RadioSessionEvent {
    /** Connection generation that produced this event. */
    public val sessionId: Long

    /** A lockdown status from this link. May precede [Ready] when authentication is required. */
    public data class Lockdown(
        override val sessionId: Long,
        public val sequence: Long,
        public val status: LockdownStatus,
    ) : RadioSessionEvent

    /** Handshake snapshot published before any packets from this session. */
    public data class Ready(
        override val sessionId: Long,
        /** Local identity reported during this handshake, when available. */
        public val myInfo: MyNodeInfo?,
        /** Config and metadata committed by this handshake, when available. */
        public val configBundle: ConfigBundle?,
        /** Channels reported during this handshake, or null when the radio sent none. */
        public val channels: List<Channel>?,
        /** Node map at the handshake boundary, before retained packets are applied. */
        public val nodes: Map<NodeId, NodeInfo>,
        /** File manifest reported during the config download. */
        public val files: List<FileInfo> = emptyList(),
        /** Firmware regional modem presets, when reported. */
        public val regionPresets: LoRaRegionPresetMap? = null,
    ) : RadioSessionEvent

    /** A packet accepted by the SDK receive pipeline and offered to this consumer. */
    public data class Packet(
        override val sessionId: Long,
        /** Delivery sequence within the session, including lockdown status. */
        public val sequence: Long,
        /** Decoded mesh packet, in the same form as [RadioClient.packets]. */
        public val packet: MeshPacket,
        /** The SDK matched this ADMIN_APP response to a request on this link. */
        public val correlatedAdminResponse: Boolean = false,
    ) : RadioSessionEvent

    /**
     * A change to the session node map after [Ready]: a node heard, updated, removed, or going offline
     * or coming back. It follows the [Packet] that caused it.
     */
    public data class NodeChanged(
        override val sessionId: Long,
        public val sequence: Long,
        public val change: NodeChange,
    ) : RadioSessionEvent

    /** The whole config bundle after a change following [Ready], from an acknowledged write or another client. */
    public data class ConfigChanged(
        override val sessionId: Long,
        public val sequence: Long,
        public val bundle: ConfigBundle,
    ) : RadioSessionEvent

    /** The whole channel list after a change following [Ready]. */
    public data class ChannelsChanged(
        override val sessionId: Long,
        public val sequence: Long,
        public val channels: List<Channel>,
    ) : RadioSessionEvent

    /**
     * A [MeshEvent] from this session, in order with its packets. [MeshEvent.LockdownStatusChanged] arrives
     * as [Lockdown] instead; [MeshEvent.QueueStatusChanged] and [MeshEvent.PacketsDropped] stay on
     * [RadioClient.events], since queue receipts settle handles and this stream reports its own loss.
     */
    public data class Event(override val sessionId: Long, public val sequence: Long, public val event: MeshEvent) :
        RadioSessionEvent

    /** A complete nodes-only download from a session that remains connected. */
    public data class NodeDbRefreshed(
        override val sessionId: Long,
        /** Delivery sequence shared with packets and envelopes in the session. */
        public val sequence: Long,
        /** Node records delivered by this refresh, keyed by node number. */
        public val nodes: Map<NodeId, NodeInfo>,
    ) : RadioSessionEvent

    /** End of a session that previously emitted [Ready]. */
    public data class Ended(override val sessionId: Long) : RadioSessionEvent
}

/** Receive coverage was lost. The stream delivers its admitted prefix before throwing this error. */
public class RadioSessionOverflowException internal constructor(
    /** Connection generation where coverage was lost. */
    public val sessionId: Long,
    /** First missed delivery sequence, or null if a handshake buffer or session boundary overflowed. */
    public val firstMissedSequence: Long?,
) : Exception(
    "Receive stream overflowed in session $sessionId" +
        (firstMissedSequence?.let { " at delivery sequence $it" } ?: ""),
)
