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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.Routing
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import org.meshtastic.proto.NeighborInfo as ProtoNeighborInfo

@OptIn(ExperimentalCoroutinesApi::class)
class P2RoutingRpcTest {

    private fun kotlinx.coroutines.test.TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:p2-routing"),
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
    fun traceRouteResolvesOnRouteReply() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val dest = NodeId(0xa1b2c3d4.toInt())
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.routing.traceRoute(dest, hopLimit = 5) }
        runCurrent()

        val req = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.ROUTING_APP }
        assertEquals(dest.raw, req.to)
        assertEquals(5, req.hop_limit)

        val expected = RouteDiscovery(
            route = listOf(0x111, 0x222),
            snr_towards = listOf(40, 32),
            route_back = listOf(0x222, 0x111),
            snr_back = listOf(36, 40),
        )
        transport.injectRouteReply(requestId = req.id, reply = expected, fromNode = dest.raw)
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<RouteDiscovery>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }

    @Test
    fun traceRouteRoutingErrorMapsToFailure() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.routing.traceRoute(NodeId(0xdead)) }
        runCurrent()

        val req = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.ROUTING_APP }
        transport.injectRoutingError(requestId = req.id, error = Routing.Error.NO_ROUTE)
        runCurrent()

        val result = deferred.await()
        assertEquals(AdminResult.NodeUnreachable, result)
        client.disconnect()
    }

    @Test
    fun traceRouteTimesOut() = runTest {
        val (_, client) = connectedClient()
        client.connect()
        runCurrent()

        val deferred = async { client.routing.traceRoute(NodeId(0xbeef)) }
        runCurrent()
        advanceTimeBy(70.seconds)
        runCurrent()

        val result = deferred.await()
        assertEquals(AdminResult.Timeout, result)
        client.disconnect()
    }

    @Test
    fun requestNeighborInfoResolvesOnReply() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.routing.requestNeighborInfo() }
        runCurrent()

        val req = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.NEIGHBORINFO_APP }
        val expected = ProtoNeighborInfo(
            node_id = 1,
            last_sent_by_id = 1,
            node_broadcast_interval_secs = 600,
            neighbors = listOf(org.meshtastic.proto.Neighbor(node_id = 2, snr = 7.5f)),
        )
        transport.injectNeighborInfoResponse(requestId = req.id, info = expected)
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<ProtoNeighborInfo>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }
}
