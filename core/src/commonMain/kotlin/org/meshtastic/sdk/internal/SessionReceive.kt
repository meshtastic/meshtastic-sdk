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
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.sdk.RadioSessionEvent
import org.meshtastic.sdk.RadioSessionOverflowException

/** Only the engine actor publishes. The channel decouples the consumer's pace from the actor. */
internal class SessionReceive(capacity: Int, private val includeEnvelopes: Boolean) {
    private val channel = Channel<RadioSessionEvent>(capacity)
    val events = channel.consumeAsFlow()

    var sessionId = 0L
        private set
    private var sequence = 0L
    private var delivering = false

    fun begin(generation: Long) {
        sessionId = generation
        sequence = 0L
    }

    fun ready(snapshot: RadioSessionEvent.Ready) {
        delivering = offer(snapshot)
    }

    fun packet(packet: MeshPacket) {
        if (delivering) delivering = offer(RadioSessionEvent.Packet(sessionId, ++sequence, packet))
    }

    fun envelope(envelope: FromRadio) {
        if (delivering &&
            includeEnvelopes
        ) {
            delivering = offer(RadioSessionEvent.Envelope(sessionId, ++sequence, envelope))
        }
    }

    fun end() {
        if (!delivering) return
        delivering = false
        offer(RadioSessionEvent.Ended(sessionId))
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
                is RadioSessionEvent.Envelope -> event.sequence
                is RadioSessionEvent.Ready, is RadioSessionEvent.Ended -> null
            }
            channel.close(RadioSessionOverflowException(sessionId, missed))
        }
        return result.isSuccess
    }
}
