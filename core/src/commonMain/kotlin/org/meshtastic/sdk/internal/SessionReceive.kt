/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import org.meshtastic.proto.LockdownStatus
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.MeshEvent
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioSessionEvent
import org.meshtastic.sdk.RadioSessionOverflowException
import org.meshtastic.proto.Channel as ProtoChannel

/** Only the engine actor publishes. The channel decouples the consumer's pace from the actor. */
internal class SessionReceive(capacity: Int) {
    private val channel = Channel<RadioSessionEvent>(capacity)
    val events = channel.consumeAsFlow()

    var sessionId = 0L
        private set
    private var sequence = 0L
    private var delivering = false
    private var started = false

    // Ended closes only a session that published Ready; a lockdown-only session ends silently.
    private var readyPublished = false

    // Sessions are numbered here, not by link: a renumber restarts the handshake on the same link.
    fun begin() {
        sessionId++
        sequence = 0L
        started = false
        readyPublished = false
    }

    fun ready(snapshot: RadioSessionEvent.Ready) {
        delivering = offer(snapshot)
        started = true
        readyPublished = true
    }

    fun lockdown(status: LockdownStatus) {
        started = true
        offer(RadioSessionEvent.Lockdown(sessionId, ++sequence, status))
    }

    fun packet(packet: MeshPacket, correlatedAdminResponse: Boolean) {
        if (delivering) {
            delivering = offer(RadioSessionEvent.Packet(sessionId, ++sequence, packet, correlatedAdminResponse))
        }
    }

    fun nodeChanged(change: NodeChange) {
        if (delivering) delivering = offer(RadioSessionEvent.NodeChanged(sessionId, ++sequence, change))
    }

    fun configChanged(bundle: ConfigBundle) {
        if (delivering) delivering = offer(RadioSessionEvent.ConfigChanged(sessionId, ++sequence, bundle))
    }

    fun channelsChanged(channels: List<ProtoChannel>) {
        if (delivering) delivering = offer(RadioSessionEvent.ChannelsChanged(sessionId, ++sequence, channels))
    }

    fun event(event: MeshEvent) {
        if (delivering) delivering = offer(RadioSessionEvent.Event(sessionId, ++sequence, event))
    }

    fun end() {
        if (!started) return
        started = false
        delivering = false
        if (readyPublished) offer(RadioSessionEvent.Ended(sessionId))
        readyPublished = false
    }

    fun nodeDbRefreshed(nodes: Map<NodeId, NodeInfo>) {
        if (delivering) delivering = offer(RadioSessionEvent.NodeDbRefreshed(sessionId, ++sequence, nodes))
    }

    fun finish() {
        end()
        channel.close()
    }

    fun handshakeOverflow() {
        channel.close(RadioSessionOverflowException(sessionId, firstMissedSequence = null))
    }

    private fun offer(event: RadioSessionEvent): Boolean {
        val result = channel.trySend(event)
        if (result.isFailure && !result.isClosed) {
            val missed = when (event) {
                is RadioSessionEvent.Packet -> event.sequence
                is RadioSessionEvent.Lockdown -> event.sequence
                is RadioSessionEvent.NodeChanged -> event.sequence
                is RadioSessionEvent.ConfigChanged -> event.sequence
                is RadioSessionEvent.ChannelsChanged -> event.sequence
                is RadioSessionEvent.Event -> event.sequence
                is RadioSessionEvent.NodeDbRefreshed -> event.sequence
                is RadioSessionEvent.Ready, is RadioSessionEvent.Ended -> null
            }
            channel.close(RadioSessionOverflowException(sessionId, missed))
        }
        return result.isSuccess
    }
}
