/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.StoreAndForward

private const val DEFAULT_REPLAY_WINDOW: Int = 256

/**
 * The id the stored message had when it was first sent, for a store-and-forward text replay.
 *
 * A server replays history under a fresh packet id and carries the stored id in
 * `StoreAndForward.original_id`; `MeshPacket.from` stays the original sender. Returns `null` for
 * anything that is not a `ROUTER_TEXT_DIRECT` or `ROUTER_TEXT_BROADCAST` reply, when `original_id`
 * is `0`, or when the payload does not decode.
 *
 * @since 0.3.0
 */
public fun MeshPacket.storeForwardOriginalId(): Int? {
    val data = decoded ?: return null
    if (data.portnum != PortNum.STORE_FORWARD_APP) return null
    val message = try {
        StoreAndForward.ADAPTER.decode(data.payload)
    } catch (_: Exception) {
        return null
    }
    val isText = message.rr == StoreAndForward.RequestResponse.ROUTER_TEXT_DIRECT ||
        message.rr == StoreAndForward.RequestResponse.ROUTER_TEXT_BROADCAST
    return message.original_id.takeIf { isText && it != 0 }
}

/**
 * Drops store-and-forward text replays whose message this collection already delivered.
 *
 * A replay is dropped when its `(from, original_id)` matches an earlier replay or an earlier
 * `TEXT_MESSAGE_APP` packet with that `(from, id)`. Replays without an `original_id` and every
 * other packet pass through unchanged, and a live text is never dropped. A server that replays to
 * the local phone link sends a plain text under the original id, which this cannot tell from a
 * live packet.
 *
 * Memory is bounded: the last [window] distinct keys are remembered per collection, oldest
 * forgotten first, so a replay older than that is delivered again.
 *
 * @param window number of keys remembered; must be positive.
 * @since 0.3.0
 */
public fun Flow<MeshPacket>.dropDuplicateStoreForwardReplays(window: Int = DEFAULT_REPLAY_WINDOW): Flow<MeshPacket> {
    require(window > 0) { "window must be positive, was $window" }
    val upstream = this
    return flow {
        val seen = LinkedHashSet<Long>()
        fun remember(key: Long) {
            if (!seen.add(key)) return
            if (seen.size > window) {
                val oldest = seen.iterator()
                oldest.next()
                oldest.remove()
            }
        }
        upstream.collect { packet ->
            val originalId = packet.storeForwardOriginalId()
            if (originalId != null) {
                val key = replayKey(packet.from, originalId)
                if (key in seen) return@collect
                remember(key)
            } else if (packet.decoded?.portnum == PortNum.TEXT_MESSAGE_APP) {
                remember(replayKey(packet.from, packet.id))
            }
            emit(packet)
        }
    }
}

private fun replayKey(from: Int, id: Int): Long = (from.toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)
