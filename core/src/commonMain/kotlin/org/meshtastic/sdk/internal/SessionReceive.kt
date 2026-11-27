/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

/**
 * Only the engine actor publishes. The channel decouples the consumer's pace from the actor.
 *
 * With [restartOnOverflow] and a collector, an overflow closes the current channel with the error,
 * opens a fresh one for the next collection, and calls [onOverflow] so the engine can restart the
 * session. The rest of the overflowed session is not delivered; its [RadioSessionEvent.Ended] opens
 * the new stream. With no collector a restart could not help, so the stream just fails.
 */
internal class SessionReceive(
    private val capacity: Int,
    private val restartOnOverflow: Boolean = false,
    private val onOverflow: (sessionId: Long) -> Unit = {},
) {
    private class Stream(val channel: Channel<RadioSessionEvent>) {
        val flow: Flow<RadioSessionEvent> = channel.consumeAsFlow()
    }

    // ADR-002 exception: the actor replaces the stream after an overflow; a collector reads it when it starts.
    private val stream = atomic(Stream(Channel(capacity)))
    private val channel: Channel<RadioSessionEvent> get() = stream.value.channel

    // ADR-002 exception: collectors start and stop on their own coroutines; the actor reads the count.
    private val collectors = atomic(0)
    val events: Flow<RadioSessionEvent> = flow {
        collectors.incrementAndGet()
        try {
            emitAll(stream.value.flow)
        } finally {
            collectors.decrementAndGet()
        }
    }

    // Set by an overflow until the next session begins: nothing more of the overflowed session is published.
    private var lost = false

    var sessionId = 0L
        private set
    private var sequence = 0L
    private var delivering = false
    private var started = false

    // The engine numbers sessions, not the link: a renumber restarts the handshake on the same link.
    fun begin(id: Long) {
        sessionId = id
        sequence = 0L
        started = false
        lost = false
    }

    fun ready(snapshot: RadioSessionEvent.Ready) {
        if (lost) return
        delivering = offer(snapshot)
        started = true
    }

    fun lockdown(status: LockdownStatus) {
        if (lost) return
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
        offer(RadioSessionEvent.Ended(sessionId))
    }

    fun nodeDbRefreshed(nodes: Map<NodeId, NodeInfo>) {
        if (delivering) delivering = offer(RadioSessionEvent.NodeDbRefreshed(sessionId, ++sequence, nodes))
    }

    fun finish() {
        end()
        channel.close()
    }

    fun handshakeOverflow() {
        overflow(RadioSessionOverflowException(sessionId, firstMissedSequence = null))
    }

    // [pending] is an Ended that could not be queued; it opens the replacement stream instead.
    private fun overflow(error: RadioSessionOverflowException, pending: RadioSessionEvent.Ended? = null) {
        if (lost) return
        if (!restartOnOverflow || collectors.value == 0) {
            channel.close(error)
            return
        }
        lost = true
        delivering = false
        // The replacement is published before the close, so a collector that sees the error and collects
        // again, or stops and drops the count, finds it.
        val failed = channel
        val replacement = Stream(Channel(capacity))
        pending?.let { replacement.channel.trySend(it) }
        stream.value = replacement
        failed.close(error)
        onOverflow(sessionId)
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
            overflow(RadioSessionOverflowException(sessionId, missed), event as? RadioSessionEvent.Ended)
        }
        return result.isSuccess
    }
}
