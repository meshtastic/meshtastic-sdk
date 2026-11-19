/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Channel
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo

/** Ordered receive events for a single [RadioClient]. Session IDs are scoped to that client. */
public sealed interface RadioSessionEvent {
    /** Connection generation that produced this event. */
    public val sessionId: Long

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
    ) : RadioSessionEvent

    /** A packet accepted by the SDK receive pipeline and offered to this consumer. */
    public data class Packet(
        override val sessionId: Long,
        /** Delivery sequence within the session, starting at one. */
        public val sequence: Long,
        /** Decoded mesh packet, in the same form as [RadioClient.packets]. */
        public val packet: MeshPacket,
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
        (firstMissedSequence?.let { " at packet sequence $it" } ?: ""),
)
