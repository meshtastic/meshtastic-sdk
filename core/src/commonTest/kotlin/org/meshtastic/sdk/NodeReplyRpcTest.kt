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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Routing
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Firmware answers a `want_response` request through `setReplyTo` (`firmware:src/mesh/MeshModule.cpp`): the reply
 * carries `request_id` = the request's id and comes from the node the request went to, on the module's own port.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeReplyRpcTest {

    private val dest = NodeId(0x0a0b0c0d)
    private val other = NodeId(0x01020304)
    private var nextInboundId = 0x5000

    private fun TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(identity = TransportIdentity("fake:node-reply"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .rpcTimeout(30.seconds)
            .build()
        return transport to client
    }

    private fun FakeRadioTransport.reply(requestId: Int, from: NodeId, port: PortNum, payload: ByteString) =
        injectPacket(
            MeshPacket.Builder().also { wb ->
                wb.id = nextInboundId++
                wb.from = from.raw
                wb.to = nodeNum
                wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = port
                    wb.payload = payload
                    wb.request_id = requestId
                }.build()
            }.build(),
        )

    private fun FakeRadioTransport.lastRequest(port: PortNum) = outboundPackets().last { it.decoded?.portnum == port }

    @Test
    fun requestPositionSendsOnThePositionPortWithTheChosenIdAndReturnsTheTargetsReply() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val result = async { client.routing.requestPosition(dest, requestId = 0x4242) }
        runCurrent()
        val request = transport.lastRequest(PortNum.POSITION_APP)
        assertEquals(0x4242, request.id)
        assertEquals(dest.raw, request.to)
        assertEquals(true, request.decoded?.want_response)

        val position = Position.Builder().also { wb ->
            wb.latitude_i = 377_749_000
            wb.longitude_i = -1_224_194_000
        }.build()
        transport.reply(request.id, dest, PortNum.POSITION_APP, Position.ADAPTER.encode(position).toByteString())
        runCurrent()

        assertEquals(AdminResult.Success(position), result.await())
        client.disconnect()
    }

    @Test
    fun aPositionFromAnotherNodeOrARoutingAckWithTheRequestIdDoesNotCompleteTheRequest() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val result = async { client.routing.requestPosition(dest) }
        runCurrent()
        val request = transport.lastRequest(PortNum.POSITION_APP)
        val wrong = Position.Builder().also { it.latitude_i = 1 }.build()
        val right = Position.Builder().also { it.latitude_i = 2 }.build()

        // Another node's position carrying the same request_id is not the target's answer.
        transport.reply(request.id, other, PortNum.POSITION_APP, Position.ADAPTER.encode(wrong).toByteString())
        // An empty Routing (error NONE) decodes as an empty Position; only POSITION_APP answers a position request.
        transport.reply(request.id, dest, PortNum.ROUTING_APP, ByteString.EMPTY)
        runCurrent()
        assertFalse(result.isCompleted)

        transport.reply(request.id, dest, PortNum.POSITION_APP, Position.ADAPTER.encode(right).toByteString())
        runCurrent()
        assertEquals(AdminResult.Success(right), result.await())
        client.disconnect()
    }

    @Test
    fun aNoResponseNakFromTheTargetFailsThePositionRequest() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val result = async { client.routing.requestPosition(dest) }
        runCurrent()
        val request = transport.lastRequest(PortNum.POSITION_APP)
        // MeshModule NAKs NO_RESPONSE when PositionModule has no fix or the channel's precision is 0.
        transport.injectRoutingError(request.id, Routing.Error.NO_RESPONSE, fromNode = dest.raw)
        runCurrent()

        assertEquals(AdminResult.Failed(Routing.Error.NO_RESPONSE), result.await())
        client.disconnect()
    }

    @Test
    fun requestNodeInfoSendsOnTheNodeInfoPortAndReturnsTheTargetsUser() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val result = async { client.routing.requestNodeInfo(dest) }
        runCurrent()
        val request = transport.lastRequest(PortNum.NODEINFO_APP)
        assertEquals(dest.raw, request.to)
        assertEquals(true, request.decoded?.want_response)

        val user = User.Builder().also { wb ->
            wb.id = "!0a0b0c0d"
            wb.long_name = "Target"
            wb.short_name = "TGT"
        }.build()
        transport.reply(request.id, other, PortNum.NODEINFO_APP, User.ADAPTER.encode(user).toByteString())
        runCurrent()
        assertFalse(result.isCompleted)
        transport.reply(request.id, dest, PortNum.NODEINFO_APP, User.ADAPTER.encode(user).toByteString())
        runCurrent()

        assertEquals(AdminResult.Success(user), result.await())
        client.disconnect()
    }

    @Test
    fun aRequestToOurOwnNodeIsRefused() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        assertFailsWith<IllegalArgumentException> { client.routing.requestPosition(NodeId(transport.nodeNum)) }
        assertFailsWith<IllegalArgumentException> { client.routing.requestNodeInfo(NodeId.LOCAL) }
        val requestPorts = setOf(PortNum.POSITION_APP, PortNum.NODEINFO_APP)
        assertTrue(transport.outboundPackets().none { it.decoded?.portnum in requestPorts })
        client.disconnect()
    }
}
