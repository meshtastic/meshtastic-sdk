/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal.sfpp

import org.meshtastic.proto.Channel
import org.meshtastic.proto.Data
import org.meshtastic.proto.PortNum

/** A replay that decrypted on one of our channels and passed the text check. */
internal class SfppRecovered(
    val replay: SfppReplay,
    val channelIndex: Int,
    val data: Data,
    val text: String,
    /** Some copy's archive attested `origin_verified` by signing or PKI-encrypting frame 0. */
    val senderVerified: Boolean,
    /** [placedAtMs] comes from a signed or PKI-encrypted frame 0. */
    val timeAttested: Boolean,
    /** Where the message belongs: the best copy's receipt time minus its `heard_ago_s`. */
    val placedAtMs: Long,
)

/**
 * The SF++ app client's receive path: assemble frames, decrypt with the channel key, keep only real
 * texts, and drop anything already held.
 *
 * Only the primary channel is tried; per-channel opt-in comes later. A replay addressed to a single
 * node is dropped until client requests are authenticated. A message held live is never recovered;
 * a recovered one is recovered again only by a copy that raises its trust or places it earlier.
 * Placement is receipt time minus `heard_ago_s`, so one archive repeating itself later is no change,
 * and an unattested copy never moves a placement an attested one made. Each result carries the
 * merged best trust and placement, so the latest result for a message supersedes earlier ones.
 */
internal class SfppReplayClient(
    private val assembler: SfppFrameAssembler = SfppFrameAssembler(),
    private val heldLimit: Int = HELD_LIMIT,
) {
    private class Held(val trust: Int, val placedAtMs: Long) {
        val timeAttested: Boolean get() = trust and TIME_ATTESTED != 0
    }

    private val held = LinkedHashMap<Long, Held>()

    /** Records a message held live (received or sent) so no replay of it is recovered. */
    fun markSeen(from: Int, id: Int) = remember(key(from, id), Held(LIVE, 0))

    fun onFrame(
        frame: SfppFrame,
        sender: Int,
        attested: Boolean,
        channels: List<Channel>,
        nowMs: Long,
    ): SfppRecovered? {
        val replay = assembler.offer(frame, sender, attested, nowMs) ?: return null
        if (replay.to != SfppFrameAssembler.BROADCAST) return null
        val messageKey = key(replay.from, replay.id)
        val prior = held[messageKey]
        if (prior?.trust == LIVE) return null
        val trust = when {
            !replay.envelopeAttested -> 0
            replay.originVerified -> TIME_ATTESTED or SENDER_VERIFIED
            else -> TIME_ATTESTED
        }
        val placedAtMs = nowMs - replay.heardAgoS * MS_PER_SECOND
        val placement = when {
            prior == null -> placedAtMs

            // An attested time replaces an unattested one wherever it lands; the reverse never happens.
            replay.envelopeAttested != prior.timeAttested ->
                if (replay.envelopeAttested) placedAtMs else prior.placedAtMs

            placedAtMs < prior.placedAtMs - PLACEMENT_SLACK_MS -> placedAtMs

            else -> prior.placedAtMs
        }
        val merged = trust or (prior?.trust ?: 0)
        if (prior != null && merged == prior.trust && placement == prior.placedAtMs) return null
        val primary = channels.firstOrNull { it.role == Channel.Role.PRIMARY } ?: return null
        val data = decryptText(replay, primary) ?: return null
        remember(messageKey, Held(merged, placement))
        return SfppRecovered(
            replay = replay,
            channelIndex = primary.index,
            data = data,
            text = data.payload.utf8(),
            senderVerified = merged and SENDER_VERIFIED != 0,
            timeAttested = merged and TIME_ATTESTED != 0,
            placedAtMs = placement,
        )
    }

    private fun decryptText(replay: SfppReplay, channel: Channel): Data? {
        val settings = channel.settings ?: return null
        val key = ChannelCipher.expandKey(settings.psk.toByteArray())
        val plain = if (settings.use_aead) {
            ChannelCipher.ccm(key, replay.from, replay.to, replay.id, replay.ciphertext)
        } else {
            ChannelCipher.ctr(key, replay.from, replay.id, replay.ciphertext)
        }
        return plain?.let { SfppTextValidator.decodeText(it) }
    }

    private fun remember(key: Long, value: Held) {
        held.remove(key)
        held[key] = value
        while (held.size > heldLimit) held.remove(held.keys.first())
    }

    private fun key(from: Int, id: Int): Long = (from.toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)

    private companion object {
        const val HELD_LIMIT = 2048
        const val LIVE = Int.MAX_VALUE
        const val TIME_ATTESTED = 1
        const val SENDER_VERIFIED = 2
        const val MS_PER_SECOND = 1000L

        // heard_ago_s is whole seconds and receipt times jitter, so placements this close are one placement.
        const val PLACEMENT_SLACK_MS = 2000L
    }
}

/** Decides whether a decrypted replay is a real text message (spec §5.3). */
internal object SfppTextValidator {
    /** The decoded `Data` when [plaintext] is a non-empty, printable UTF-8 text message, else null. */
    fun decodeText(plaintext: ByteArray): Data? {
        val data = try {
            Data.ADAPTER.decode(plaintext)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return null
        }
        if (data.portnum != PortNum.TEXT_MESSAGE_APP || data.payload.size == 0) return null
        val text = try {
            data.payload.toByteArray().decodeToString(throwOnInvalidSequence = true)
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            return null
        }
        return if (text.all { it == '\n' || it == '\t' || !it.isISOControl() }) data else null
    }
}
