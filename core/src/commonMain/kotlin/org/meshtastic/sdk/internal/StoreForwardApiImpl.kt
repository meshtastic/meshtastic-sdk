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
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.MessageHandle
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.SfppHash
import org.meshtastic.sdk.StoreForwardApi
import org.meshtastic.sdk.StoreForwardEvent
import org.meshtastic.sdk.StoreForwardStats
import org.meshtastic.sdk.internal.sfpp.SfppFrame
import org.meshtastic.sdk.internal.sfpp.SfppReplayClient
import org.meshtastic.sdk.internal.sfpp.SfppWire
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Instant

internal class StoreForwardApiImpl(
    private val engine: MeshEngine,
    private val packetsFlow: Flow<MeshPacket>,
    private val rpcTimeout: Duration,
    coroutineContext: CoroutineContext,
    private val nowProvider: () -> Instant,
    private val sendPacket: (MeshPacket) -> MessageHandle,
) : StoreForwardApi {

    private val scope = CoroutineScope(coroutineContext + CoroutineName("meshtastic-store-forward"))
    private val knownServers = linkedSetOf<NodeId>()
    private val activeReplays = mutableMapOf<NodeId, ReplayProgress>()
    private val pendingSfppFragments = mutableMapOf<SfppFragmentKey, SfppFragmentState>()
    private val sfppReplays = SfppReplayClient()

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
                engine.outboundPackets.map { InternalSignal.Sent(it.value) },
            ).collect { signal ->
                when (signal) {
                    is InternalSignal.Packet -> handlePacket(signal.packet)
                    is InternalSignal.Node -> handleNodeChange(signal.change)
                    is InternalSignal.Connection -> handleConnection(signal.state)
                    is InternalSignal.Sent -> handleSent(signal.packet)
                }
            }
        }
    }

    override suspend fun requestHistory(since: Int?, server: NodeId?): AdminResult<Int> {
        val myNode = engine.myNodeNumOrNull() ?: return AdminResult.NodeUnreachable
        val targetServer = resolveServer(server) ?: return AdminResult.NodeUnreachable
        val payload = StoreAndForward.ADAPTER.encode(
            StoreAndForward.Builder().also { wb ->
                wb.rr = StoreAndForward.RequestResponse.CLIENT_HISTORY
                wb.history = StoreAndForward.History.Builder().also { wb ->
                    wb.window = historyWindowMinutes(since)
                }.build()
            }.build(),
        ).toByteString()
        val packet = MeshPacket.Builder().also { wb ->
            wb.from = myNode
            wb.to = targetServer.raw
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.STORE_FORWARD_APP
                wb.payload = payload
                wb.want_response = true
            }.build()
        }.build()
        return when (val result = engine.submitRpc(packet, ResponseKind.StoreForwardReply, rpcTimeout)) {
            is AdminResult.Success -> AdminResult.Success(result.value.history_messages)
            AdminResult.Timeout -> AdminResult.Timeout
            AdminResult.NodeUnreachable -> AdminResult.NodeUnreachable
            AdminResult.SessionKeyExpired -> AdminResult.SessionKeyExpired
            AdminResult.Unauthorized -> AdminResult.Unauthorized
            AdminResult.RateLimited -> AdminResult.RateLimited
            is AdminResult.Failed -> result
        }
    }

    override fun requestLocalHistory(lastRequest: Int, windowMinutes: Int, maxMessages: Int): MessageHandle {
        val myNode = engine.myNodeNumOrNull() ?: throw MeshtasticException.NotConnected()
        val payload = StoreAndForward.ADAPTER.encode(
            StoreAndForward.Builder().also { wb ->
                wb.rr = StoreAndForward.RequestResponse.CLIENT_HISTORY
                wb.history = StoreAndForward.History.Builder().also { wb ->
                    wb.last_request = lastRequest.coerceAtLeast(0)
                    wb.window = windowMinutes.coerceAtLeast(0)
                    wb.history_messages = maxMessages.coerceAtLeast(0)
                }.build()
            }.build(),
        ).toByteString()
        val packet = MeshPacket.Builder().also { wb ->
            wb.from = myNode
            wb.to = myNode
            wb.priority = MeshPacket.Priority.BACKGROUND
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.STORE_FORWARD_APP
                wb.payload = payload
            }.build()
        }.build()
        return sendPacket(packet)
    }

    override suspend fun requestStats(server: NodeId?): AdminResult<StoreForwardStats> {
        val myNode = engine.myNodeNumOrNull() ?: return AdminResult.NodeUnreachable
        val targetServer = resolveServer(server) ?: return AdminResult.NodeUnreachable
        val payload = StoreAndForward.ADAPTER.encode(
            StoreAndForward.Builder().also { wb ->
                wb.rr = StoreAndForward.RequestResponse.CLIENT_STATS
            }.build(),
        ).toByteString()
        val packet = MeshPacket.Builder().also { wb ->
            wb.from = myNode
            wb.to = targetServer.raw
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.STORE_FORWARD_APP
                wb.payload = payload
                wb.want_response = true
            }.build()
        }.build()
        return when (
            val result = engine.submitRpc(
                packet,
                ResponseKind.StoreForwardStatsReply,
                rpcTimeout,
            )
        ) {
            is AdminResult.Success -> AdminResult.Success(result.value.toSdkStats())
            AdminResult.Timeout -> AdminResult.Timeout
            AdminResult.NodeUnreachable -> AdminResult.NodeUnreachable
            AdminResult.SessionKeyExpired -> AdminResult.SessionKeyExpired
            AdminResult.Unauthorized -> AdminResult.Unauthorized
            AdminResult.RateLimited -> AdminResult.RateLimited
            is AdminResult.Failed -> result
        }
    }

    private suspend fun handlePacket(packet: MeshPacket) {
        val decoded = packet.decoded ?: return
        if (packet.from == 0) return
        when (decoded.portnum) {
            PortNum.STORE_FORWARD_PLUSPLUS_APP -> handleSfppPayload(decoded.payload.toByteArray(), packet)
            PortNum.STORE_FORWARD_APP -> handleStoreForwardPayload(decoded, packet)
            PortNum.TEXT_MESSAGE_APP -> sfppReplays.markSeen(packet.from, packet.id)
            else -> Unit
        }
    }

    // Our own texts come back from an archive too; the device fills in `from` when we leave it 0.
    private fun handleSent(packet: MeshPacket) {
        if (packet.decoded?.portnum != PortNum.TEXT_MESSAGE_APP || packet.id == 0) return
        val from = if (packet.from != 0) packet.from else engine.myNodeNumOrNull() ?: return
        sfppReplays.markSeen(from, packet.id)
    }

    private suspend fun handleStoreForwardPayload(decoded: Data, packet: MeshPacket) {
        val legacy = try {
            StoreAndForward.ADAPTER.decode(decoded.payload)
        } catch (_: Exception) {
            null
        }
        if (legacy != null && legacy.unknownFields.size == 0 && looksLikeLegacyStoreForward(legacy)) {
            handleLegacySf(legacy, packet)
            return
        }
        handleSfppPayload(decoded.payload.toByteArray(), packet)
    }

    // Types past the chain's are unknown to the pinned Wire enum, which would read them as CANON_ANNOUNCE.
    private suspend fun handleSfppPayload(payload: ByteArray, packet: MeshPacket) {
        val raw = SfppWire.decode(payload) ?: return
        when {
            raw.type == SfppWire.ITEM_FRAME -> handleItemFrame(raw, packet)

            raw.type in 0..SfppWire.LAST_CHAIN_TYPE -> {
                val sfpp = try {
                    StoreForwardPlusPlus.ADAPTER.decode(payload)
                } catch (_: Exception) {
                    null
                }
                if (sfpp != null) handleSfpp(sfpp)
            }

            else -> Unit
        }
    }

    private suspend fun handleItemFrame(frame: SfppFrame, packet: MeshPacket) {
        val attested = packet.xeddsa_signed || packet.pki_encrypted
        val now = nowProvider()
        val channels = engine.channelsState.value.orEmpty()
        val recovered = sfppReplays.onFrame(frame, packet.from, attested, channels, now.toEpochMilliseconds()) ?: return
        val replay = recovered.replay
        _events.emit(
            StoreForwardEvent.SfppMessageRecovered(
                from = NodeId(replay.from),
                packetId = replay.id,
                channelIndex = recovered.channelIndex,
                text = recovered.text,
                data = recovered.data,
                archive = NodeId(replay.archive),
                heardAgoSeconds = (now.toEpochMilliseconds() - recovered.placedAtMs) / MILLIS_PER_SECOND,
                heardAt = Instant.fromEpochMilliseconds(recovered.placedAtMs),
                senderVerified = recovered.senderVerified,
                timeAttested = recovered.timeAttested,
            ),
        )
    }

    private suspend fun handleLegacySf(message: StoreAndForward, packet: MeshPacket) {
        val server = NodeId(packet.from)
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
                val fingerprint: Any = if (message.original_id != 0) {
                    ReplayOriginalKey(from = packet.from, originalId = message.original_id)
                } else {
                    ReplayMessageFingerprint(
                        packetId = packet.id,
                        response = message.rr,
                        payload = message.text?.toByteArray(),
                    )
                }
                if (fingerprint in progress.seenMessages) return
                val delivered = progress.delivered + 1
                val updated = progress.copy(
                    delivered = delivered,
                    seenMessages = progress.seenMessages + fingerprint,
                )
                if (delivered >= progress.expected) {
                    activeReplays.remove(server)
                    _events.emit(StoreForwardEvent.HistoryReplayComplete(server, delivered))
                } else {
                    activeReplays[server] = updated
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
        val messageType = sfpp.sfpp_message_type
        val isFragment = messageType != StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE
        val normalizedTo = if (sfpp.encapsulated_to == 0) NodeId.BROADCAST.raw else sfpp.encapsulated_to
        val providedHash = sfpp.message_hash.takeIf { it.size != 0 }?.toByteArray()
        if (!isFragment || providedHash != null || sfpp.message.size == 0) {
            emitLinkProvided(
                packetId = sfpp.encapsulated_id,
                from = sfpp.encapsulated_from,
                to = normalizedTo,
                confirmed = confirmed,
                messageHash = when {
                    providedHash != null -> providedHash

                    !isFragment && sfpp.message.size != 0 -> SfppHash.compute(
                        payload = sfpp.message.toByteArray(),
                        to = normalizedTo,
                        from = sfpp.encapsulated_from,
                        id = sfpp.encapsulated_id,
                    )

                    else -> null
                },
            )
            return
        }

        val key = SfppFragmentKey(
            packetId = sfpp.encapsulated_id,
            from = sfpp.encapsulated_from,
            to = normalizedTo,
        )
        val existing = pendingSfppFragments[key] ?: SfppFragmentState()
        val updated = when (messageType) {
            StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE_FIRSTHALF -> existing.copy(
                firstHalf = sfpp.message.toByteArray(),
                confirmed = existing.confirmed || confirmed,
            )

            StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE_SECONDHALF -> existing.copy(
                secondHalf = sfpp.message.toByteArray(),
                confirmed = existing.confirmed || confirmed,
            )

            else -> existing
        }
        val firstHalf = updated.firstHalf
        val secondHalf = updated.secondHalf
        if (firstHalf != null && secondHalf != null) {
            pendingSfppFragments.remove(key)
            emitLinkProvided(
                packetId = key.packetId,
                from = key.from,
                to = key.to,
                confirmed = updated.confirmed,
                messageHash = SfppHash.compute(
                    payload = firstHalf + secondHalf,
                    to = key.to,
                    from = key.from,
                    id = key.packetId,
                ),
            )
        } else {
            pendingSfppFragments[key] = updated
        }
    }

    private suspend fun emitLinkProvided(
        packetId: Int,
        from: Int,
        to: Int,
        confirmed: Boolean,
        messageHash: ByteArray?,
    ) {
        _events.emit(
            StoreForwardEvent.SfppLinkProvided(
                packetId = packetId,
                from = from,
                to = to,
                messageHash = messageHash,
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
        when (change) {
            is NodeChange.Removed -> forgetServer(change.nodeId)
            is NodeChange.WentOffline -> forgetServer(change.nodeId)
            else -> Unit
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
        pendingSfppFragments.clear()
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
        data class Sent(val packet: MeshPacket) : InternalSignal
    }

    private data class ReplayProgress(
        val expected: Int,
        val delivered: Int = 0,
        val seenMessages: Set<Any> = emptySet(),
    )

    private data class ReplayOriginalKey(val from: Int, val originalId: Int)

    private data class ReplayMessageFingerprint(
        val packetId: Int,
        val response: StoreAndForward.RequestResponse,
        val payload: ByteArray?,
    ) {
        override fun equals(other: Any?): Boolean = other is ReplayMessageFingerprint &&
            packetId == other.packetId &&
            response == other.response &&
            payload.contentEquals(other.payload)

        override fun hashCode(): Int = ((packetId * 31) + response.hashCode()) * 31 + payload.contentHashCode()
    }

    private data class SfppFragmentKey(val packetId: Int, val from: Int, val to: Int)

    private data class SfppFragmentState(
        val firstHalf: ByteArray? = null,
        val secondHalf: ByteArray? = null,
        val confirmed: Boolean = false,
    )

    private companion object {
        const val ALL_HISTORY_WINDOW_MINUTES: Int = 60 * 24 * 365 * 100
        const val MILLIS_PER_SECOND: Long = 1000
    }
}
