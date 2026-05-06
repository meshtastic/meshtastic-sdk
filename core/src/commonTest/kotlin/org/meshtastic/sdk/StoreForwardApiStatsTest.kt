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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.StoreAndForward
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StoreForwardApiStatsTest {

    @Test
    fun `requestStats returns statistics from server`() = runTest {
        val (transport, client) = connectedClient("stats-response")
        client.connect()
        runCurrent()

        val server = NodeId(0xABCD0001.toInt())
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.storeForward.requestStats(server) }
        runCurrent()

        val request = transport.lastStoreForwardRequest(outboundBefore)
        val payload = StoreAndForward.ADAPTER.decode(request.decoded!!.payload)
        assertEquals(server.raw, request.to)
        assertEquals(StoreAndForward.RequestResponse.CLIENT_STATS, payload.rr)

        transport.injectStatsResponse(
            requestId = request.id,
            server = server,
            stats = StoreAndForward.Statistics(
                messages_saved = 9,
                messages_max = 64,
                up_time = 3600,
                requests = 12,
                requests_history = 7,
                heartbeat = true,
            ),
        )
        runCurrent()

        val result = assertIs<AdminResult.Success<StoreForwardStats>>(deferred.await())
        assertEquals(9, result.value.messagesStored)
        assertEquals(64, result.value.messagesMax)
        assertEquals(3600, result.value.uptime)
        assertEquals(7, result.value.requests)
        assertEquals(0, result.value.requestsFailed)
        assertEquals(true, result.value.heartbeat)
        client.disconnect()
    }

    @Test
    fun `requestStats with null server uses first discovered server`() = runTest {
        val (transport, client) = connectedClient("stats-default-server")
        client.connect()
        runCurrent()

        val storeForward = client.storeForward
        runCurrent()
        val server = NodeId(0xABCD0001.toInt())
        transport.injectHeartbeat(server)
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { storeForward.requestStats() }
        runCurrent()

        val request = transport.lastStoreForwardRequest(outboundBefore)
        assertEquals(server.raw, request.to)

        transport.injectStatsResponse(request.id, server)
        runCurrent()

        assertIs<AdminResult.Success<StoreForwardStats>>(deferred.await())
        client.disconnect()
    }

    @Test
    fun `requestStats throws on timeout`() = runTest {
        val (transport, client) = connectedClient("stats-timeout", rpcTimeout = 5.seconds)
        client.connect()
        runCurrent()

        val storeForward = client.storeForward
        runCurrent()
        val server = NodeId(0xABCD0001.toInt())
        transport.injectHeartbeat(server)
        runCurrent()

        val deferred = async { storeForward.requestStats() }
        runCurrent()

        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(AdminResult.Timeout, deferred.await())
        client.disconnect()
    }

    @Test
    fun `requestStats with explicit server sends to that node`() = runTest {
        val (transport, client) = connectedClient("stats-explicit-server")
        client.connect()
        runCurrent()

        val storeForward = client.storeForward
        runCurrent()
        val firstServer = NodeId(0xABCD0001.toInt())
        val targetServer = NodeId(0xABCD0002.toInt())
        transport.injectHeartbeat(firstServer)
        transport.injectHeartbeat(targetServer)
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { storeForward.requestStats(targetServer) }
        runCurrent()

        val request = transport.lastStoreForwardRequest(outboundBefore)
        assertEquals(targetServer.raw, request.to)

        transport.injectStatsResponse(request.id, targetServer)
        runCurrent()

        assertIs<AdminResult.Success<StoreForwardStats>>(deferred.await())
        client.disconnect()
    }

    @Test
    fun `requestStats throws when no servers known and server is null`() = runTest {
        val (_, client) = connectedClient("stats-missing-server")
        client.connect()
        runCurrent()

        assertEquals(AdminResult.NodeUnreachable, client.storeForward.requestStats())
        client.disconnect()
    }

    @Test
    fun `server discovery via heartbeat adds to servers list`() = runTest {
        val (transport, client) = connectedClient("server-discovery")
        client.connect()
        runCurrent()

        val storeForward = client.storeForward
        runCurrent()
        val server = NodeId(0xABCD0001.toInt())
        transport.injectHeartbeat(server)
        runCurrent()

        assertEquals(listOf(server), storeForward.servers.value)
        client.disconnect()
    }

    @Test
    fun `multiple server heartbeats accumulate`() = runTest {
        val (transport, client) = connectedClient("server-accumulation")
        client.connect()
        runCurrent()

        val storeForward = client.storeForward
        runCurrent()
        val firstServer = NodeId(0xABCD0001.toInt())
        val secondServer = NodeId(0xABCD0002.toInt())
        transport.injectHeartbeat(firstServer)
        transport.injectHeartbeat(secondServer)
        runCurrent()

        assertEquals(listOf(firstServer, secondServer), storeForward.servers.value)
        client.disconnect()
    }

    @Test
    fun `server loss removes from servers list`() = runTest {
        val (transport, client) = connectedClient("server-loss", presenceTimeout = 5.seconds)
        client.connect()
        runCurrent()

        val storeForward = client.storeForward
        runCurrent()
        val server = NodeId(0xABCD0001.toInt())
        transport.injectHeartbeat(server)
        runCurrent()
        assertEquals(listOf(server), storeForward.servers.value)

        advanceTimeBy(31.seconds)
        runCurrent()

        assertTrue(storeForward.servers.value.isEmpty())
        client.disconnect()
    }

    @Test
    fun `servers initially empty`() = runTest {
        val (_, client) = connectedClient("servers-empty")
        client.connect()
        runCurrent()

        assertTrue(client.storeForward.servers.value.isEmpty())
        client.disconnect()
    }

    @Test
    fun `ServerDiscovered event emitted on first heartbeat`() = runTest {
        val (transport, client) = connectedClient("event-discovered")
        client.connect()
        runCurrent()

        val server = NodeId(0xABCD0001.toInt())
        val event = async {
            client.storeForward.events.first { it is StoreForwardEvent.ServerDiscovered }
        }
        runCurrent()

        transport.injectHeartbeat(server)
        runCurrent()

        assertEquals(StoreForwardEvent.ServerDiscovered(server), event.await())
        client.disconnect()
    }

    @Test
    fun `ServerLost event emitted when server times out`() = runTest {
        val (transport, client) = connectedClient("event-lost", presenceTimeout = 5.seconds)
        client.connect()
        runCurrent()

        val server = NodeId(0xABCD0001.toInt())
        val event = async {
            client.storeForward.events.first { it is StoreForwardEvent.ServerLost }
        }
        runCurrent()

        transport.injectHeartbeat(server)
        runCurrent()
        advanceTimeBy(31.seconds)
        runCurrent()

        assertEquals(StoreForwardEvent.ServerLost(server), event.await())
        client.disconnect()
    }

    private fun TestScope.connectedClient(
        identitySuffix: String,
        presenceTimeout: Duration = 5.seconds,
        rpcTimeout: Duration = 60.seconds,
    ): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:sf-$identitySuffix"),
            autoHandshake = true,
            nodeNum = 0x11111111,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .clock(SchedulerClock { currentTime })
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .presenceTimeout(presenceTimeout)
            .rpcTimeout(rpcTimeout)
            .build()
        return transport to client
    }

    private fun FakeRadioTransport.injectHeartbeat(server: NodeId, period: Int = 900) {
        injectStoreForwardResponse(
            requestId = 0,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_HEARTBEAT,
                heartbeat = StoreAndForward.Heartbeat(period = period, secondary = 0),
            ),
            fromNode = server.raw,
        )
    }

    private fun FakeRadioTransport.injectStatsResponse(
        requestId: Int,
        server: NodeId,
        stats: StoreAndForward.Statistics = StoreAndForward.Statistics(
            messages_saved = 1,
            messages_max = 2,
            up_time = 3,
            requests_history = 4,
            heartbeat = true,
        ),
    ) {
        injectStoreForwardResponse(
            requestId = requestId,
            message = StoreAndForward(
                rr = StoreAndForward.RequestResponse.ROUTER_STATS,
                stats = stats,
            ),
            fromNode = server.raw,
        )
    }

    private fun FakeRadioTransport.lastStoreForwardRequest(outboundBefore: Int) =
        outboundPackets()
            .drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.STORE_FORWARD_APP }

    private class SchedulerClock(private val nowMs: () -> Long) : kotlin.time.Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs())
    }
}
