/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.ext

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.StoreAndForward
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.StoreForwardEvent
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class StoreForwardImplTest {

    private fun kotlinx.coroutines.test.TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:p2-store-forward"),
            autoHandshake = true,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .build()
        return transport to client
    }

    @Test
    fun storeForwardStartsEmptyAndEventsFlowIsCollectible() = runTest {
        val (_, client) = connectedClient()
        client.connect()
        runCurrent()

        val storeForward = client.storeForward
        assertNotNull(storeForward)
        assertEquals(emptyList<NodeId>(), storeForward.servers.value)

        val collected = mutableListOf<StoreForwardEvent>()
        val collector = backgroundScope.launch {
            storeForward.events.collect { collected += it }
        }
        runCurrent()

        assertTrue(collector.isActive)
        assertTrue(collected.isEmpty())

        collector.cancel()
        client.disconnect()
    }

    @Test
    fun storeForwardTracksServersAndHeartbeats() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val collected = mutableListOf<StoreForwardEvent>()
        val collector = backgroundScope.launch {
            client.storeForward.events.collect { collected += it }
        }
        runCurrent()

        val server = NodeId(0x10203040)
        transport.injectStoreForwardResponse(
            requestId = 0,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HEARTBEAT,
                heartbeat = StoreAndForward.Heartbeat(period = 300),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        assertEquals(listOf(server), client.storeForward.servers.value)
        assertEquals(StoreForwardEvent.ServerDiscovered(server), collected.first())
        assertEquals(StoreForwardEvent.Heartbeat(server), collected.last())

        collector.cancel()
        client.disconnect()
    }

    @Test
    fun requestHistoryUsesKnownServerAndReturnsPendingCount() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val storeForward = client.storeForward
        runCurrent()
        val server = NodeId(0x55667788)
        transport.injectStoreForwardResponse(
            requestId = 0,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HEARTBEAT,
                heartbeat = StoreAndForward.Heartbeat(period = 120),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { storeForward.requestHistory(server = null) }
        runCurrent()

        val request = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.STORE_FORWARD_APP }
        val payload = StoreAndForward.ADAPTER.decode(request.decoded!!.payload)
        assertEquals(server.raw, request.to)
        assertEquals(StoreAndForward.RequestResponse.CLIENT_HISTORY, payload.rr)

        transport.injectStoreForwardResponse(
            requestId = request.id,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HISTORY,
                history = StoreAndForward.History(history_messages = 3, window = 120000),
            ),
            fromNode = server.raw,
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<Int>>(result)
        assertEquals(3, result.value)
        client.disconnect()
    }

    @Test
    fun requestStatsMapsProtoStatistics() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val server = NodeId(0x12345678)
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.storeForward.requestStats(server) }
        runCurrent()

        val request = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.STORE_FORWARD_APP }
        val payload = StoreAndForward.ADAPTER.decode(request.decoded!!.payload)
        assertEquals(StoreAndForward.RequestResponse.CLIENT_STATS, payload.rr)

        transport.injectStoreForwardResponse(
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

        val result = deferred.await()
        assertIs<AdminResult.Success<org.meshtastic.sdk.StoreForwardStats>>(result)
        assertEquals(9, result.value.messagesStored)
        assertEquals(64, result.value.messagesMax)
        assertEquals(3600, result.value.uptime)
        assertEquals(7, result.value.requests)
        assertEquals(0, result.value.requestsFailed)
        assertEquals(true, result.value.heartbeat)
        client.disconnect()
    }
}
