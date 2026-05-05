/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * API for interacting with Store-and-Forward (S&F) nodes on the mesh.
 *
 * S&F nodes temporarily store messages for offline nodes and deliver them when the
 * target comes back online. This API enables clients to discover S&F servers,
 * request missed messages, and query S&F statistics.
 *
 * **Note:** This API requires firmware support for the STORE_FORWARD_APP port number.
 * Not all nodes on the mesh will have S&F capabilities.
 *
 * Access via `RadioClient.storeForward` (available after connection).
 *
 * @since 0.2.0
 */
public interface StoreForwardApi {

    /**
     * Known S&F server nodes on the mesh.
     *
     * Automatically populated when nodes advertise S&F capability via their
     * NodeInfo or heartbeat. Updated reactively.
     */
    public val servers: StateFlow<List<NodeId>>

    /**
     * Request delivery of messages stored for this node since the given timestamp.
     *
     * The S&F server will replay stored messages matching this node's ID.
     * Messages are delivered via the normal `RadioClient.packets` flow.
     *
     * @param since seconds since epoch — only messages after this time are requested.
     *              If null, requests all available stored messages.
     * @param server specific S&F server to query. If null, queries the first known server.
     * @return the number of messages the server reports as pending, or failure reason
     */
    public suspend fun requestHistory(
        since: Int? = null,
        server: NodeId? = null,
    ): AdminResult<Int>

    /**
     * Query statistics from a Store-and-Forward server.
     *
     * @param server the S&F node to query
     * @return server statistics including capacity, stored message count, and uptime
     */
    public suspend fun requestStats(server: NodeId): AdminResult<StoreForwardStats>

    /**
     * Flow of S&F-specific events (heartbeats, delivery confirmations, etc.).
     */
    public val events: Flow<StoreForwardEvent>
}

/**
 * Statistics reported by a Store-and-Forward server node.
 *
 * @property messagesStored current number of messages held in the store
 * @property messagesMax maximum storage capacity
 * @property uptime server uptime in seconds
 * @property requests total number of history requests served
 * @property requestsFailed number of failed history requests
 * @property heartbeat whether the server sends periodic heartbeats
 */
public data class StoreForwardStats(
    val messagesStored: Int = 0,
    val messagesMax: Int = 0,
    val uptime: Int = 0,
    val requests: Int = 0,
    val requestsFailed: Int = 0,
    val heartbeat: Boolean = false,
)

/**
 * Events specific to Store-and-Forward operations.
 */
public sealed interface StoreForwardEvent {
    /**
     * A S&F server was discovered on the mesh.
     */
    public data class ServerDiscovered(val nodeId: NodeId) : StoreForwardEvent

    /**
     * A S&F server went offline or was removed.
     */
    public data class ServerLost(val nodeId: NodeId) : StoreForwardEvent

    /**
     * History replay has started — messages are being delivered.
     *
     * @property server the S&F node delivering messages
     * @property messageCount number of messages being replayed
     */
    public data class HistoryReplayStarted(
        val server: NodeId,
        val messageCount: Int,
    ) : StoreForwardEvent

    /**
     * History replay is complete.
     */
    public data class HistoryReplayComplete(
        val server: NodeId,
        val delivered: Int,
    ) : StoreForwardEvent

    /**
     * Heartbeat received from a S&F server (indicates it's still active).
     */
    public data class Heartbeat(val server: NodeId) : StoreForwardEvent

    /** An SFPP link was provided — message is being routed or confirmed. */
    public data class SfppLinkProvided(
        val packetId: Int,
        val from: Int,
        val to: Int,
        val messageHash: ByteArray?,
        val confirmed: Boolean,
    ) : StoreForwardEvent {
        override fun equals(other: Any?): Boolean = other is SfppLinkProvided &&
            packetId == other.packetId &&
            from == other.from &&
            to == other.to &&
            confirmed == other.confirmed &&
            messageHash.contentEquals(other.messageHash)

        override fun hashCode(): Int = (((packetId * 31 + from) * 31 + to) * 31 + confirmed.hashCode()) * 31 +
            messageHash.contentHashCode()
    }

    /** An SFPP canon announce — message is confirmed on the chain. */
    public data class SfppCanonAnnounced(
        val messageHash: ByteArray,
        val rxTime: Long,
    ) : StoreForwardEvent {
        override fun equals(other: Any?): Boolean = other is SfppCanonAnnounced &&
            messageHash.contentEquals(other.messageHash) &&
            rxTime == other.rxTime

        override fun hashCode(): Int = messageHash.contentHashCode() * 31 + rxTime.hashCode()
    }
}
