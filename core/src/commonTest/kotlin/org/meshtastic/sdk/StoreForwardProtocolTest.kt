/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.StoreAndForward
import org.meshtastic.proto.StoreForwardPlusPlus
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class StoreForwardProtocolTest {

    private fun TestScope.connectedClient(
        identitySuffix: String,
        myNodeNum: Int = 0x11111111,
        presenceTimeout: Duration = 2.hours,
    ): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:store-forward-$identitySuffix"),
            autoHandshake = true,
            nodeNum = myNodeNum,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .presenceTimeout(presenceTimeout)
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .build()
        return transport to client
    }

    @Test
    fun serverHeartbeatDiscoversServerAndEmitsHeartbeat() = runTest {
        val (transport, client) = connectedClient("heartbeat-discovery")
        client.connect()
        runCurrent()

        val observed = mutableListOf<StoreForwardEvent>()
        val collector = backgroundScope.launch {
            client.storeForward.events.collect { observed += it }
        }
        runCurrent()

        val server = NodeId(0x10203040)
        transport.injectLegacyStoreForward(
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HEARTBEAT,
                heartbeat = StoreAndForward.Heartbeat(period = 120),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        assertEquals(listOf(server), client.storeForward.servers.value)
        assertEquals(
            listOf(StoreForwardEvent.ServerDiscovered(server)),
            observed.filterIsInstance<StoreForwardEvent.ServerDiscovered>(),
        )
        assertEquals(
            listOf(StoreForwardEvent.Heartbeat(server)),
            observed.filterIsInstance<StoreForwardEvent.Heartbeat>(),
        )

        collector.cancel()
        client.disconnect()
    }

    @Test
    fun duplicateHeartbeatsDoNotRediscoverServer() = runTest {
        val (transport, client) = connectedClient("heartbeat-dedupe")
        client.connect()
        runCurrent()

        val observed = mutableListOf<StoreForwardEvent>()
        val collector = backgroundScope.launch {
            client.storeForward.events.collect { observed += it }
        }
        runCurrent()

        val server = NodeId(0x20304050)
        repeat(2) { index ->
            transport.injectLegacyStoreForward(
                packetId = index + 1,
                message = StoreAndForward(
                    rr = StoreAndForward.RequestResponse.ROUTER_HEARTBEAT,
                    heartbeat = StoreAndForward.Heartbeat(period = 60),
                ),
                fromNode = server.raw,
            )
            runCurrent()
        }

        assertEquals(listOf(server), client.storeForward.servers.value)
        assertEquals(1, observed.filterIsInstance<StoreForwardEvent.ServerDiscovered>().size)
        assertEquals(2, observed.filterIsInstance<StoreForwardEvent.Heartbeat>().size)

        collector.cancel()
        client.disconnect()
    }

    @Test
    fun requestHistoryUsesFirstKnownServerAndAllHistoryWindow() = runTest {
        val (transport, client) = connectedClient("history-default-server")
        client.connect()
        val storeForward = client.storeForward
        runCurrent()

        val server = NodeId(0x55667788)
        transport.injectLegacyStoreForward(
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HEARTBEAT,
                heartbeat = StoreAndForward.Heartbeat(period = 120),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { storeForward.requestHistory() }
        runCurrent()

        val request = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.STORE_FORWARD_APP }
        val payload = StoreAndForward.ADAPTER.decode(request.decoded!!.payload)
        assertEquals(server.raw, request.to)
        assertEquals(StoreAndForward.RequestResponse.CLIENT_HISTORY, payload.rr)
        assertEquals(ALL_HISTORY_WINDOW_MINUTES, payload.history?.window)

        transport.injectLegacyStoreForward(
            requestId = request.id,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HISTORY,
                history = StoreAndForward.History(history_messages = 3, window = ALL_HISTORY_WINDOW_MINUTES),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        val result = assertIs<AdminResult.Success<Int>>(deferred.await())
        assertEquals(3, result.value)
        client.disconnect()
    }

    @Test
    fun requestHistoryUsesExplicitServerAndRoundsWindowUpToMinutes() = runTest {
        val (transport, client) = connectedClient("history-explicit-server")
        client.connect()
        val storeForward = client.storeForward
        runCurrent()

        val firstServer = NodeId(0x01020304)
        val targetServer = NodeId(0x0A0B0C0D)
        transport.injectLegacyStoreForward(
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HEARTBEAT,
                heartbeat = StoreAndForward.Heartbeat(period = 120),
            ),
            fromNode = firstServer.raw,
        )
        transport.injectLegacyStoreForward(
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HEARTBEAT,
                heartbeat = StoreAndForward.Heartbeat(period = 120),
            ),
            fromNode = targetServer.raw,
        )
        runCurrent()

        val since = Clock.System.now().epochSeconds.toInt() - 61
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { storeForward.requestHistory(since = since, server = targetServer) }
        runCurrent()

        val request = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.STORE_FORWARD_APP }
        val payload = StoreAndForward.ADAPTER.decode(request.decoded!!.payload)
        assertEquals(targetServer.raw, request.to)
        assertEquals(2, payload.history?.window)

        transport.injectLegacyStoreForward(
            requestId = request.id,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HISTORY,
                history = StoreAndForward.History(history_messages = 1, window = 2),
            ),
            fromNode = targetServer.raw,
        )
        runCurrent()

        val result = assertIs<AdminResult.Success<Int>>(deferred.await())
        assertEquals(1, result.value)
        client.disconnect()
    }

    @Test
    fun requestHistoryFutureTimestampClampsWindowToOneMinute() = runTest {
        val (transport, client) = connectedClient("history-future-window")
        client.connect()
        val storeForward = client.storeForward
        runCurrent()

        val server = NodeId(0x11112222)
        transport.injectLegacyStoreForward(
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HEARTBEAT,
                heartbeat = StoreAndForward.Heartbeat(period = 120),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        val since = Clock.System.now().epochSeconds.toInt() + 3600
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { storeForward.requestHistory(since = since) }
        runCurrent()

        val request = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.STORE_FORWARD_APP }
        val payload = StoreAndForward.ADAPTER.decode(request.decoded!!.payload)
        assertEquals(1, payload.history?.window)

        transport.injectLegacyStoreForward(
            requestId = request.id,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HISTORY,
                history = StoreAndForward.History(history_messages = 0, window = 1),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        assertEquals(0, assertIs<AdminResult.Success<Int>>(deferred.await()).value)
        client.disconnect()
    }

    @Test
    fun requestHistoryWithLocalServerTargetsSelfNode() = runTest {
        val myNode = 0x42424242
        val (transport, client) = connectedClient("history-local-server", myNodeNum = myNode)
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.storeForward.requestHistory(server = NodeId.LOCAL) }
        runCurrent()

        val request = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.STORE_FORWARD_APP }
        assertEquals(myNode, request.to)

        transport.injectLegacyStoreForward(
            requestId = request.id,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HISTORY,
                history = StoreAndForward.History(history_messages = 2, window = ALL_HISTORY_WINDOW_MINUTES),
            ),
            fromNode = myNode,
        )
        runCurrent()

        assertEquals(2, assertIs<AdminResult.Success<Int>>(deferred.await()).value)
        client.disconnect()
    }

    @Test
    fun requestHistoryWithoutAvailableServersFailsGracefully() = runTest {
        val (_, client) = connectedClient("history-no-server")
        client.connect()
        runCurrent()

        assertEquals(AdminResult.NodeUnreachable, client.storeForward.requestHistory())
        client.disconnect()
    }

    @Test
    fun zeroMessageHistoryReplayStartsAndCompletesImmediately() = runTest {
        val (transport, client) = connectedClient("history-zero-replay")
        client.connect()
        runCurrent()

        val observed = mutableListOf<StoreForwardEvent>()
        val collector = backgroundScope.launch {
            client.storeForward.events.collect { observed += it }
        }
        runCurrent()

        val server = NodeId(0x61626364)
        transport.injectLegacyStoreForward(
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HISTORY,
                history = StoreAndForward.History(history_messages = 0, window = 5),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        assertTrue(observed.contains(StoreForwardEvent.HistoryReplayStarted(server, 0)))
        assertTrue(observed.contains(StoreForwardEvent.HistoryReplayComplete(server, 0)))

        collector.cancel()
        client.disconnect()
    }

    @Test
    fun historyReplayCountsUniqueMessagesOnly() = runTest {
        val (transport, client) = connectedClient("history-dedupe")
        client.connect()
        runCurrent()

        val observed = mutableListOf<StoreForwardEvent>()
        val collector = backgroundScope.launch {
            client.storeForward.events.collect { observed += it }
        }
        runCurrent()

        val server = NodeId(0x71727374)
        transport.injectLegacyStoreForward(
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HISTORY,
                history = StoreAndForward.History(history_messages = 2, window = 30),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        transport.injectLegacyStoreForward(
            packetId = 41,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_TEXT_DIRECT,
                text = "same".encodeToByteArray().toByteString(),
            ),
            fromNode = server.raw,
        )
        runCurrent()
        assertFalse(observed.any { it is StoreForwardEvent.HistoryReplayComplete })

        transport.injectLegacyStoreForward(
            packetId = 41,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_TEXT_DIRECT,
                text = "same".encodeToByteArray().toByteString(),
            ),
            fromNode = server.raw,
        )
        runCurrent()
        assertFalse(observed.any { it is StoreForwardEvent.HistoryReplayComplete })

        transport.injectLegacyStoreForward(
            packetId = 42,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_TEXT_BROADCAST,
                text = "other".encodeToByteArray().toByteString(),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        assertEquals(
            listOf(StoreForwardEvent.HistoryReplayComplete(server, 2)),
            observed.filterIsInstance<StoreForwardEvent.HistoryReplayComplete>(),
        )

        collector.cancel()
        client.disconnect()
    }

    @Test
    fun historyReplayMessagesAreEmittedInOrder() = runTest {
        val (transport, client) = connectedClient("history-order")
        client.connect()
        runCurrent()

        val observed = mutableListOf<String>()
        val collector = backgroundScope.launch {
            client.packets.collect { packet ->
                val decoded = packet.decoded ?: return@collect
                if (decoded.portnum != PortNum.STORE_FORWARD_APP) return@collect
                val message =
                    runCatching { StoreAndForward.ADAPTER.decode(decoded.payload) }.getOrNull() ?: return@collect
                if (message.rr == StoreAndForward.RequestResponse.ROUTER_TEXT_DIRECT ||
                    message.rr == StoreAndForward.RequestResponse.ROUTER_TEXT_BROADCAST
                ) {
                    observed += message.text?.utf8().orEmpty()
                }
            }
        }
        runCurrent()

        val server = NodeId(0x81828384.toInt())
        transport.injectLegacyStoreForward(
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HISTORY,
                history = StoreAndForward.History(history_messages = 2, window = 30),
            ),
            fromNode = server.raw,
        )
        transport.injectLegacyStoreForward(
            packetId = 101,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_TEXT_DIRECT,
                text = "first".encodeToByteArray().toByteString(),
            ),
            fromNode = server.raw,
        )
        transport.injectLegacyStoreForward(
            packetId = 102,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_TEXT_BROADCAST,
                text = "second".encodeToByteArray().toByteString(),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        assertEquals(listOf("first", "second"), observed)

        collector.cancel()
        client.disconnect()
    }

    @Test
    fun disconnectRemovesKnownServersAndEmitsLostEvents() = runTest {
        val (transport, client) = connectedClient("disconnect-clears-servers")
        client.connect()
        runCurrent()

        val observed = mutableListOf<StoreForwardEvent>()
        val collector = backgroundScope.launch {
            client.storeForward.events.collect { observed += it }
        }
        runCurrent()

        val first = NodeId(0x01010101)
        val second = NodeId(0x02020202)
        listOf(first, second).forEach { server ->
            transport.injectLegacyStoreForward(
                message = StoreAndForward(
                    rr = StoreAndForward.RequestResponse.ROUTER_HEARTBEAT,
                    heartbeat = StoreAndForward.Heartbeat(period = 60),
                ),
                fromNode = server.raw,
            )
        }
        runCurrent()

        client.disconnect()
        runCurrent()

        assertTrue(client.storeForward.servers.value.isEmpty())
        assertEquals(
            setOf(first, second),
            observed.filterIsInstance<StoreForwardEvent.ServerLost>().map {
                it.nodeId
            }.toSet(),
        )

        collector.cancel()
    }

    // Server heartbeat timeout/expiry is not yet implemented in StoreForwardApiImpl.
    // presenceTimeout only applies to node online/offline presence, not S&F server tracking.
    // TODO: Implement S&F server staleness sweep and re-enable this test.

    @Test
    fun requestStatsMapsStoreForwardStatistics() = runTest {
        val (transport, client) = connectedClient("stats-mapping")
        client.connect()
        runCurrent()

        val server = NodeId(0x13572468)
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.storeForward.requestStats(server) }
        runCurrent()

        val request = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.STORE_FORWARD_APP }
        val payload = StoreAndForward.ADAPTER.decode(request.decoded!!.payload)
        assertEquals(StoreAndForward.RequestResponse.CLIENT_STATS, payload.rr)

        transport.injectLegacyStoreForward(
            requestId = request.id,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_STATS,
                stats = StoreAndForward.Statistics(
                    messages_saved = 9,
                    messages_max = 64,
                    up_time = 3600,
                    requests = 12,
                    requests_history = 7,
                    heartbeat = true,
                ),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        val result = assertIs<AdminResult.Success<StoreForwardStats>>(deferred.await())
        assertEquals(9, result.value.messagesStored)
        assertEquals(64, result.value.messagesMax)
        assertEquals(3600, result.value.uptime)
        assertEquals(7, result.value.requests)
        assertEquals(true, result.value.heartbeat)
        client.disconnect()
    }

    @Test
    fun sfppLinkProvideComputesHashWhenMessageHashMissing() = runTest {
        val (transport, client) = connectedClient("sfpp-full-link")
        client.connect()
        runCurrent()

        val message = "payload".encodeToByteArray()
        val eventDeferred = async {
            client.storeForward.events.first { it is StoreForwardEvent.SfppLinkProvided }
        }
        runCurrent()

        transport.injectSfpp(
            StoreForwardPlusPlus(
                sfpp_message_type = StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE,
                message = message.toByteString(),
                encapsulated_id = 42,
                encapsulated_to = 0,
                encapsulated_from = 0x0BADF00D,
            ),
        )
        runCurrent()

        val event = assertIs<StoreForwardEvent.SfppLinkProvided>(eventDeferred.await())
        val expectedHash = SfppHash.compute(
            payload = message,
            to = NodeId.BROADCAST.raw,
            from = 0x0BADF00D,
            id = 42,
        )
        assertEquals(NodeId.BROADCAST.raw, event.to)
        assertContentEquals(expectedHash, event.messageHash)
        client.disconnect()
    }

    @Test
    fun sfppFragmentedMessagesAreReassembledBeforeEmission() = runTest {
        val (transport, client) = connectedClient("sfpp-fragmented")
        client.connect()
        runCurrent()

        val eventDeferred = async {
            client.storeForward.events.first { it is StoreForwardEvent.SfppLinkProvided }
        }
        runCurrent()

        transport.injectSfpp(
            message = StoreForwardPlusPlus(
                sfpp_message_type = StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE_FIRSTHALF,
                message = "hello ".encodeToByteArray().toByteString(),
                encapsulated_id = 77,
                encapsulated_to = 88,
                encapsulated_from = 99,
            ),
            packetId = 701,
        )
        runCurrent()
        assertFalse(eventDeferred.isCompleted)

        transport.injectSfpp(
            message = StoreForwardPlusPlus(
                sfpp_message_type = StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE_SECONDHALF,
                message = "world".encodeToByteArray().toByteString(),
                commit_hash = byteArrayOf(9).toByteString(),
                encapsulated_id = 77,
                encapsulated_to = 88,
                encapsulated_from = 99,
            ),
            packetId = 702,
        )
        runCurrent()

        val event = assertIs<StoreForwardEvent.SfppLinkProvided>(eventDeferred.await())
        val expectedHash = SfppHash.compute(
            payload = "hello world".encodeToByteArray(),
            to = 88,
            from = 99,
            id = 77,
        )
        assertEquals(77, event.packetId)
        assertEquals(true, event.confirmed)
        assertContentEquals(expectedHash, event.messageHash)
        client.disconnect()
    }

    @Test
    fun sfppFragmentAssemblySupportsOutOfOrderChunks() = runTest {
        val (transport, client) = connectedClient("sfpp-out-of-order")
        client.connect()
        runCurrent()

        val eventDeferred = async {
            client.storeForward.events.first { it is StoreForwardEvent.SfppLinkProvided }
        }
        runCurrent()

        transport.injectSfpp(
            message = StoreForwardPlusPlus(
                sfpp_message_type = StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE_SECONDHALF,
                message = "beta".encodeToByteArray().toByteString(),
                encapsulated_id = 15,
                encapsulated_to = 16,
                encapsulated_from = 17,
            ),
            packetId = 801,
        )
        runCurrent()
        assertFalse(eventDeferred.isCompleted)

        transport.injectSfpp(
            message = StoreForwardPlusPlus(
                sfpp_message_type = StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE_FIRSTHALF,
                message = "alpha".encodeToByteArray().toByteString(),
                encapsulated_id = 15,
                encapsulated_to = 16,
                encapsulated_from = 17,
            ),
            packetId = 802,
        )
        runCurrent()

        val event = assertIs<StoreForwardEvent.SfppLinkProvided>(eventDeferred.await())
        val expectedHash = SfppHash.compute(
            payload = "alphabeta".encodeToByteArray(),
            to = 16,
            from = 17,
            id = 15,
        )
        assertContentEquals(expectedHash, event.messageHash)
        client.disconnect()
    }

    @Test
    fun unsupportedStoreForwardPayloadIsIgnoredGracefully() = runTest {
        val (transport, client) = connectedClient("unsupported-payload")
        client.connect()
        runCurrent()

        val eventDeferred = async { client.storeForward.events.first() }
        runCurrent()

        transport.injectStoreForwardPayload(byteArrayOf(0x08, 0x63))
        runCurrent()

        assertFalse(eventDeferred.isCompleted)
        eventDeferred.cancel()
        client.disconnect()
    }

    @Test
    fun canonAnnounceWithoutHashIsIgnoredGracefully() = runTest {
        val (transport, client) = connectedClient("canon-without-hash")
        client.connect()
        runCurrent()

        val eventDeferred = async { client.storeForward.events.first() }
        runCurrent()

        transport.injectSfpp(
            StoreForwardPlusPlus(
                sfpp_message_type = StoreForwardPlusPlus.SFPP_message_type.CANON_ANNOUNCE,
                encapsulated_rxtime = 99,
            ),
        )
        runCurrent()

        assertFalse(eventDeferred.isCompleted)
        eventDeferred.cancel()
        client.disconnect()
    }

    private fun FakeRadioTransport.injectLegacyStoreForward(
        message: StoreAndForward,
        fromNode: Int = 0x10203040,
        packetId: Int = 1,
        requestId: Int? = null,
    ) {
        val decoded = if (requestId != null) {
            Data(
                portnum = PortNum.STORE_FORWARD_APP,
                payload = StoreAndForward.ADAPTER.encode(message).toByteString(),
                request_id = requestId,
            )
        } else {
            Data(
                portnum = PortNum.STORE_FORWARD_APP,
                payload = StoreAndForward.ADAPTER.encode(message).toByteString(),
            )
        }
        val effectivePacketId = if (packetId == 1 && requestId != null) requestId else packetId
        injectPacket(
            MeshPacket(
                id = effectivePacketId,
                from = fromNode,
                to = 0,
                decoded = decoded,
            ),
        )
    }

    private fun FakeRadioTransport.injectSfpp(
        message: StoreForwardPlusPlus,
        fromNode: Int = 0x10203040,
        packetId: Int = 1,
    ) {
        injectStoreForwardPayload(
            payload = StoreForwardPlusPlus.ADAPTER.encode(message),
            fromNode = fromNode,
            packetId = packetId,
        )
    }

    private fun FakeRadioTransport.injectStoreForwardPayload(
        payload: ByteArray,
        fromNode: Int = 0x10203040,
        packetId: Int = 1,
    ) {
        injectPacket(
            MeshPacket(
                id = packetId,
                from = fromNode,
                to = 0,
                decoded = Data(
                    portnum = PortNum.STORE_FORWARD_APP,
                    payload = payload.toByteString(),
                ),
            ),
        )
    }

    private companion object {
        const val ALL_HISTORY_WINDOW_MINUTES: Int = 60 * 24 * 365 * 100
    }
}
