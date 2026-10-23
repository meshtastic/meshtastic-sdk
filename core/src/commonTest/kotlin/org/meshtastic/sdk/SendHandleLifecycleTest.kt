/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Every MessageHandle reaches a terminal state, and RPCs never hang at teardown. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendHandleLifecycleTest {

    private val peer = NodeId(0x22222222)

    @Test
    fun aUnicastSendWithoutAckSettlesOnceHandedToTheRadio() = runTest {
        val (_, client) = connected()
        val handle = client.send(
            MeshPacket.Builder().also { wb ->
                wb.to = peer.raw
                wb.want_ack = false
                wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.PRIVATE_APP
                    wb.payload = "x".encodeToByteArray().toByteString()
                }.build()
            }.build(),
        )
        runCurrent()

        assertEquals(SendState.Acked, handle.state.value)
        assertEquals(SendOutcome.Success, handle.await())
        client.disconnect()
    }

    @Test
    fun theReplyToAPositionRequestSettlesIt() = runTest {
        val (transport, client) = connected()
        val handle = client.requestPosition(peer)
        runCurrent()
        assertEquals(SendState.Sent, handle.state.value)

        val position = Position.Builder().also { wb ->
            wb.latitude_i = 417_000_000
            wb.longitude_i = 447_000_000
        }.build()
        transport.injectPacket(
            MeshPacket.Builder().also { wb ->
                wb.from = peer.raw
                wb.to = 1
                wb.id = 777
                wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.POSITION_APP
                    wb.payload = Position.ADAPTER.encode(position).toByteString()
                    wb.request_id = handle.id.raw
                }.build()
            }.build(),
        )
        runCurrent()

        assertEquals(SendState.Acked, handle.state.value)
        client.disconnect()
    }

    @Test
    fun cancelAfterSentStillLetsTheHandleSettle() = runTest {
        val (_, client) = connected()
        val handle = client.sendText("dm", to = peer)
        runCurrent()
        assertEquals(SendState.Sent, handle.state.value)

        handle.cancel()
        runCurrent()
        val outcome = async { handle.await() }
        advanceTimeBy(10.minutes)
        runCurrent()

        assertTrue(outcome.isCompleted, "await() must not hang after cancel() on a Sent handle")
        assertEquals(SendState.Failed(SendFailure.AckTimeout), handle.state.value)
        client.disconnect()
    }

    @Test
    fun anRpcQueuedWhileConnectingCompletesWhenTheEngineStops() = runTest {
        val gate = CompletableDeferred<Unit>()
        val inner = FakeRadioTransport(TransportIdentity("fake:rpc-teardown"), autoHandshake = true)
        val stuck = object : RadioTransport by inner {
            override suspend fun connect() {
                gate.await() // e.g. a BLE connect or bond that never finishes
                inner.connect()
            }
        }
        val client = buildClient(stuck)
        backgroundScope.launch { runCatching { client.connect() } }
        runCurrent()

        // The actor is busy in transport.connect(), so this RPC sits in the inbox.
        val owner = async { client.admin.getOwner() }
        runCurrent()

        client.disconnect()
        runCurrent()

        assertTrue(owner.isCompleted, "a queued RPC must not hang after teardown")
        assertEquals(AdminResult.NodeUnreachable, owner.await())
    }

    private suspend fun TestScope.connected(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(TransportIdentity("fake:handle-lifecycle"), autoHandshake = true)
        val client = buildClient(transport)
        client.connect()
        runCurrent()
        return transport to client
    }

    private fun TestScope.buildClient(transport: RadioTransport): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .autoSyncTimeOnConnect(false)
        .sendTimeout(60.seconds)
        .coroutineContext(backgroundScope.coroutineContext)
        .build()
}
