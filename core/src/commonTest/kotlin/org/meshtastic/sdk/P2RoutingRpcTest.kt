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
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
            .sendAckTimeout(60.seconds)
            .build()
        return transport to client
    }

    @Test
    fun traceRouteGoesOutOnTheTraceroutePortAndResolvesOnItsReply() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val dest = NodeId(0xa1b2c3d4.toInt())
        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.routing.traceRoute(dest, hopLimit = 5, requestId = 4242) }
        runCurrent()

        // TraceRouteModule listens on TRACEROUTE_APP (firmware TraceRouteModule.cpp); RoutingModule never replies.
        val req = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.TRACEROUTE_APP }
        assertEquals(dest.raw, req.to)
        assertEquals(5, req.hop_limit)
        assertEquals(4242, req.id)
        assertEquals(true, req.want_ack)
        assertEquals(dest.raw, req.decoded?.dest)
        assertEquals(true, req.decoded?.want_response)

        val expected = RouteDiscovery.Builder().also { wb ->
            wb.route = listOf(0x111, 0x222)
            wb.snr_towards = listOf(40, 32)
            wb.route_back = listOf(0x222, 0x111)
            wb.snr_back = listOf(36, 40)
        }.build()
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
            .last { it.decoded?.portnum == PortNum.TRACEROUTE_APP }
        transport.injectRoutingError(requestId = req.id, error = Routing.Error.NO_ROUTE)
        runCurrent()

        val result = deferred.await()
        assertEquals(AdminResult.Failed(Routing.Error.NO_ROUTE), result)
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
    fun aRoutingErrorAfterTheClientTimeoutStillSettlesALongerTimeoutTraceRoute() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.routing.withRpcTimeout(150.seconds).traceRoute(NodeId(0xdead)) }
        runCurrent()
        val req = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.TRACEROUTE_APP }
        // Past the client's 60 s rpcTimeout, inside the 150 s override; radio traffic keeps the link live.
        repeat(5) {
            advanceTimeBy(20.seconds)
            transport.injectFrame(
                org.meshtastic.proto.FromRadio.Builder().also { wb ->
                    wb.queueStatus = org.meshtastic.proto.QueueStatus.Builder().also { it.free = 8 }.build()
                }.build().toFrame(),
            )
            runCurrent()
        }
        transport.injectRoutingError(requestId = req.id, error = Routing.Error.NO_ROUTE)
        runCurrent()

        assertEquals(AdminResult.Failed(Routing.Error.NO_ROUTE), deferred.await())
        client.disconnect()
    }

    @Test
    fun aChosenIdAnotherRequestHoldsIsRefusedAndTheFirstStillGetsItsReply() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val dest = NodeId(0xdead)
        val first = async { client.routing.traceRoute(dest) }
        runCurrent()
        val held = transport.outboundPackets().last { it.decoded?.portnum == PortNum.TRACEROUTE_APP }

        val second = async { client.routing.requestNeighborInfo(NodeId(0xbeef), requestId = held.id) }
        runCurrent()
        assertEquals(AdminResult.NodeUnreachable, second.await())

        transport.injectRouteReply(requestId = held.id, reply = ROUTE, fromNode = dest.raw)
        runCurrent()
        assertEquals(AdminResult.Success(ROUTE), first.await())
        client.disconnect()
    }

    @Test
    fun anAutomaticIdStepsOverAChosenIdAheadOfTheCounter() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        fun lastTrace() = transport.outboundPackets().last { it.decoded?.portnum == PortNum.TRACEROUTE_APP }
        val warmup = async { client.routing.traceRoute(NodeId(0xa1)) }
        runCurrent()
        // The next id the shared counter hands out.
        val ahead = lastTrace().id + 1
        val chosenDest = NodeId(0xb2)
        val chosen = async { client.routing.traceRoute(chosenDest, requestId = ahead) }
        runCurrent()

        val autoDest = NodeId(0xc3)
        val automatic = async { client.routing.traceRoute(autoDest) }
        runCurrent()
        val autoRequest = lastTrace()
        assertEquals(autoDest.raw, autoRequest.to)
        assertNotEquals(ahead, autoRequest.id)

        transport.injectRouteReply(requestId = ahead, reply = ROUTE, fromNode = chosenDest.raw)
        transport.injectRouteReply(requestId = autoRequest.id, reply = ROUTE_BACK, fromNode = autoDest.raw)
        runCurrent()
        assertEquals(AdminResult.Success(ROUTE), chosen.await())
        assertEquals(AdminResult.Success(ROUTE_BACK), automatic.await())
        warmup.cancel()
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
        val expected = ProtoNeighborInfo.Builder().also { wb ->
            wb.node_id = 1
            wb.last_sent_by_id = 1
            wb.node_broadcast_interval_secs = 600
            wb.neighbors = listOf(
                org.meshtastic.proto.Neighbor.Builder().also { wb ->
                    wb.node_id = 2
                    wb.snr = 7.5f
                }.build(),
            )
        }.build()
        transport.injectNeighborInfoResponse(requestId = req.id, info = expected)
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<ProtoNeighborInfo>>(result)
        assertEquals(expected, result.value)
        client.disconnect()
    }

    @Test
    fun aRemoteNeighborInfoRequestCarriesAckDestAndTheHeardChannel() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        val peer = 0x2468
        transport.injectFrame(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.node_info = org.meshtastic.proto.NodeInfo.Builder().also { n ->
                    n.num = peer
                    n.channel = 2
                }.build()
            }.build().toFrame(),
        )
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val deferred = async { client.routing.requestNeighborInfo(NodeId(peer), requestId = 777) }
        runCurrent()

        // Without want_ack, firmware keeps a zero hop limit (Router.cpp send) and the request stops at a neighbor.
        val req = transport.outboundPackets().drop(outboundBefore)
            .last { it.decoded?.portnum == PortNum.NEIGHBORINFO_APP }
        assertEquals(777, req.id)
        assertEquals(true, req.want_ack)
        assertEquals(peer, req.decoded?.dest)
        assertEquals(2, req.channel)
        assertEquals(3, req.hop_limit)
        deferred.cancel()
        client.disconnect()
    }

    private companion object {
        val ROUTE: RouteDiscovery = RouteDiscovery.Builder().also { it.route = listOf(0x111) }.build()
        val ROUTE_BACK: RouteDiscovery = RouteDiscovery.Builder().also { it.route = listOf(0x222) }.build()
    }
}
