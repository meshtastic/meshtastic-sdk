/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.StoreAndForward
import org.meshtastic.proto.StoreForwardPlusPlus
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.SfppHash
import org.meshtastic.sdk.StoreForwardApi
import org.meshtastic.sdk.StoreForwardEvent
import org.meshtastic.sdk.StoreForwardStats
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

internal class StoreForwardApiImpl(
    private val engine: MeshEngine,
    private val packetsFlow: Flow<MeshPacket>,
    private val rpcTimeout: Duration,
    coroutineContext: CoroutineContext,
    private val nowProvider: () -> Instant = { Clock.System.now() },
) : StoreForwardApi {

    private val scope = CoroutineScope(coroutineContext + CoroutineName("meshtastic-store-forward"))
    private val knownServers = linkedSetOf<NodeId>()
    private val activeReplays = mutableMapOf<NodeId, ReplayProgress>()

    private val _servers = MutableStateFlow<List<NodeId>>(emptyList())
    override val servers = _servers.asStateFlow()

    private val _events = MutableSharedFlow<StoreForwardEvent>(extraBufferCapacity = 16)
    override val events: Flow<StoreForwardEvent> = _events.asSharedFlow()

    init {
        scope.launch {
            merge(
                packetsFlow.map { InternalSignal.Packet(it) },
                engine.nodes.map { InternalSignal.Node(it) },
                engine.connectionState.map { InternalSignal.Connection(it) },
            ).collect { signal ->
                when (signal) {
                    is InternalSignal.Packet -> handlePacket(signal.packet)
                    is InternalSignal.Node -> handleNodeChange(signal.change)
                    is InternalSignal.Connection -> handleConnection(signal.state)
                }
            }
        }
    }

    override suspend fun requestHistory(since: Int?, server: NodeId?): AdminResult<Int> {
        val myNode = engine.myNodeNumOrNull() ?: return AdminResult.NodeUnreachable
        val targetServer = resolveServer(server) ?: return AdminResult.NodeUnreachable
        val requestId = engine.nextMessageId().raw
        val payload = StoreAndForward.ADAPTER.encode(
            StoreAndForward(
                rr = StoreAndForward.RequestResponse.CLIENT_HISTORY,
                history = StoreAndForward.History(window = historyWindowMinutes(since)),
            ),
        ).toByteString()
        val packet = MeshPacket(
            id = requestId,
            from = myNode,
            to = targetServer.raw,
            decoded = Data(
                portnum = PortNum.STORE_FORWARD_APP,
                payload = payload,
                want_response = true,
            ),
        )
        return when (val result = engine.submitRpc(packet, requestId, ResponseKind.StoreForwardReply, rpcTimeout)) {
            is AdminResult.Success -> AdminResult.Success(result.value.history_messages)
            AdminResult.Timeout -> AdminResult.Timeout
            AdminResult.NodeUnreachable -> AdminResult.NodeUnreachable
            AdminResult.SessionKeyExpired -> AdminResult.SessionKeyExpired
            AdminResult.Unauthorized -> AdminResult.Unauthorized
            is AdminResult.Failed -> result
        }
    }

    override suspend fun requestStats(server: NodeId): AdminResult<StoreForwardStats> {
        val myNode = engine.myNodeNumOrNull() ?: return AdminResult.NodeUnreachable
        val targetServer = resolveServer(server) ?: return AdminResult.NodeUnreachable
        val requestId = engine.nextMessageId().raw
        val payload = StoreAndForward.ADAPTER.encode(
            StoreAndForward(rr = StoreAndForward.RequestResponse.CLIENT_STATS),
        ).toByteString()
        val packet = MeshPacket(
            id = requestId,
            from = myNode,
            to = targetServer.raw,
            decoded = Data(
                portnum = PortNum.STORE_FORWARD_APP,
                payload = payload,
                want_response = true,
            ),
        )
        return when (val result = engine.submitRpc(packet, requestId, ResponseKind.StoreForwardStatsReply, rpcTimeout)) {
            is AdminResult.Success -> AdminResult.Success(result.value.toSdkStats())
            AdminResult.Timeout -> AdminResult.Timeout
            AdminResult.NodeUnreachable -> AdminResult.NodeUnreachable
            AdminResult.SessionKeyExpired -> AdminResult.SessionKeyExpired
            AdminResult.Unauthorized -> AdminResult.Unauthorized
            is AdminResult.Failed -> result
        }
    }

    private suspend fun handlePacket(packet: MeshPacket) {
        val decoded = packet.decoded ?: return
        if (decoded.portnum != PortNum.STORE_FORWARD_APP || packet.from == 0) return

        val legacy = try {
            StoreAndForward.ADAPTER.decode(decoded.payload)
        } catch (_: Exception) {
            null
        }
        if (legacy != null && legacy.unknownFields.size == 0 && looksLikeLegacyStoreForward(legacy)) {
            handleLegacySf(legacy, NodeId(packet.from))
            return
        }

        val sfpp = try {
            StoreForwardPlusPlus.ADAPTER.decode(decoded.payload)
        } catch (_: Exception) {
            null
        }
        if (sfpp != null) {
            handleSfpp(sfpp)
        }
    }

    private suspend fun handleLegacySf(message: StoreAndForward, server: NodeId) {
        when (message.rr) {
            StoreAndForward.RequestResponse.ROUTER_HEARTBEAT,
            StoreAndForward.RequestResponse.ROUTER_PONG,
            -> {
                rememberServer(server)
                _events.emit(StoreForwardEvent.Heartbeat(server))
            }

            StoreAndForward.RequestResponse.ROUTER_HISTORY -> {
                rememberServer(server)
                val pending = message.history?.history_messages ?: 0
                _events.emit(StoreForwardEvent.HistoryReplayStarted(server, pending))
                if (pending <= 0) {
                    activeReplays.remove(server)
                    _events.emit(StoreForwardEvent.HistoryReplayComplete(server, 0))
                } else {
                    activeReplays[server] = ReplayProgress(expected = pending)
                }
            }

            StoreAndForward.RequestResponse.ROUTER_TEXT_BROADCAST,
            StoreAndForward.RequestResponse.ROUTER_TEXT_DIRECT,
            -> {
                rememberServer(server)
                val progress = activeReplays[server] ?: return
                val delivered = progress.delivered + 1
                if (delivered >= progress.expected) {
                    activeReplays.remove(server)
                    _events.emit(StoreForwardEvent.HistoryReplayComplete(server, delivered))
                } else {
                    activeReplays[server] = progress.copy(delivered = delivered)
                }
            }

            StoreAndForward.RequestResponse.ROUTER_STATS,
            StoreAndForward.RequestResponse.ROUTER_BUSY,
            StoreAndForward.RequestResponse.ROUTER_ERROR,
            StoreAndForward.RequestResponse.ROUTER_PING,
            -> rememberServer(server)

            else -> Unit
        }
    }

    private fun looksLikeLegacyStoreForward(message: StoreAndForward): Boolean = when (message.rr) {
        StoreAndForward.RequestResponse.ROUTER_HEARTBEAT -> message.heartbeat != null
        StoreAndForward.RequestResponse.ROUTER_HISTORY -> message.history != null
        StoreAndForward.RequestResponse.ROUTER_STATS -> message.stats != null
        StoreAndForward.RequestResponse.ROUTER_TEXT_BROADCAST,
        StoreAndForward.RequestResponse.ROUTER_TEXT_DIRECT,
        -> message.text != null

        StoreAndForward.RequestResponse.ROUTER_PONG,
        StoreAndForward.RequestResponse.ROUTER_BUSY,
        StoreAndForward.RequestResponse.ROUTER_ERROR,
        StoreAndForward.RequestResponse.ROUTER_PING,
        -> message.stats == null && message.history == null && message.heartbeat == null && message.text == null

        else -> false
    }

    private suspend fun handleSfpp(sfpp: StoreForwardPlusPlus) {
        when (sfpp.sfpp_message_type) {
            StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE,
            StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE_FIRSTHALF,
            StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE_SECONDHALF,
            -> handleLinkProvide(sfpp)

            StoreForwardPlusPlus.SFPP_message_type.CANON_ANNOUNCE -> handleCanonAnnounce(sfpp)
            else -> Unit
        }
    }

    private suspend fun handleLinkProvide(sfpp: StoreForwardPlusPlus) {
        val confirmed = sfpp.commit_hash.size != 0
        val isFragment = sfpp.sfpp_message_type != StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE
        val normalizedTo = if (sfpp.encapsulated_to == 0) NodeId.BROADCAST.raw else sfpp.encapsulated_to
        val hash = when {
            sfpp.message_hash.size != 0 -> sfpp.message_hash.toByteArray()
            !isFragment && sfpp.message.size != 0 -> SfppHash.compute(
                payload = sfpp.message.toByteArray(),
                to = normalizedTo,
                from = sfpp.encapsulated_from,
                id = sfpp.encapsulated_id,
            )

            else -> null
        }
        _events.emit(
            StoreForwardEvent.SfppLinkProvided(
                packetId = sfpp.encapsulated_id,
                from = sfpp.encapsulated_from,
                to = normalizedTo,
                messageHash = hash,
                confirmed = confirmed,
            ),
        )
    }

    private suspend fun handleCanonAnnounce(sfpp: StoreForwardPlusPlus) {
        if (sfpp.message_hash.size == 0) return
        _events.emit(
            StoreForwardEvent.SfppCanonAnnounced(
                messageHash = sfpp.message_hash.toByteArray(),
                rxTime = sfpp.encapsulated_rxtime.toLong() and 0xFFFFFFFFL,
            ),
        )
    }

    private suspend fun handleNodeChange(change: NodeChange) {
        if (change is NodeChange.Removed) {
            forgetServer(change.nodeId)
        }
    }

    private suspend fun handleConnection(state: ConnectionState) {
        if (state == ConnectionState.Disconnected) {
            clearServers()
        }
    }

    private suspend fun rememberServer(server: NodeId) {
        if (knownServers.add(server)) {
            _servers.value = knownServers.toList()
            _events.emit(StoreForwardEvent.ServerDiscovered(server))
        }
    }

    private suspend fun forgetServer(server: NodeId) {
        if (knownServers.remove(server)) {
            activeReplays.remove(server)
            _servers.value = knownServers.toList()
            _events.emit(StoreForwardEvent.ServerLost(server))
        }
    }

    private suspend fun clearServers() {
        if (knownServers.isEmpty()) return
        val lost = knownServers.toList()
        knownServers.clear()
        activeReplays.clear()
        _servers.value = emptyList()
        lost.forEach { _events.emit(StoreForwardEvent.ServerLost(it)) }
    }

    private fun resolveServer(server: NodeId?): NodeId? {
        val candidate = server ?: _servers.value.firstOrNull() ?: return null
        return if (candidate == NodeId.LOCAL) {
            engine.myNodeNumOrNull()?.let(::NodeId)
        } else {
            candidate
        }
    }

    private fun historyWindowMinutes(since: Int?): Int {
        if (since == null) return ALL_HISTORY_WINDOW_MINUTES
        val ageSeconds = (nowProvider().epochSeconds - since.toLong()).coerceAtLeast(0)
        return ((ageSeconds + 59) / 60)
            .coerceIn(1, ALL_HISTORY_WINDOW_MINUTES.toLong())
            .toInt()
    }

    private fun StoreAndForward.Statistics.toSdkStats(): StoreForwardStats = StoreForwardStats(
        messagesStored = messages_saved,
        messagesMax = messages_max,
        uptime = up_time,
        requests = requests_history,
        requestsFailed = 0,
        heartbeat = heartbeat,
    )

    private sealed interface InternalSignal {
        data class Packet(val packet: MeshPacket) : InternalSignal
        data class Node(val change: NodeChange) : InternalSignal
        data class Connection(val state: ConnectionState) : InternalSignal
    }

    private data class ReplayProgress(val expected: Int, val delivered: Int = 0)

    private companion object {
        const val ALL_HISTORY_WINDOW_MINUTES: Int = 60 * 24 * 365 * 100
    }
}
