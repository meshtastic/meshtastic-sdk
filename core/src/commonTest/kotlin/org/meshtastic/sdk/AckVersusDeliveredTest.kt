/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.Routing
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * `Acked` means the addressed node confirmed receipt; `Delivered` means a neighbor relayed the
 * packet (the local node's implicit ack, or any ack not sent by the destination).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AckVersusDeliveredTest {

    private val destination = NodeId(0x22222222)
    private val otherNode = 0x33333333

    @Test
    fun dm_ackedByDestination_isAcked() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()

            transport.injectRoutingAck(handle.id.raw, fromNode = destination.raw)
            runCurrent()

            assertEquals(SendState.Acked, handle.state.value)
            assertEquals(SendOutcome.Success, handle.await())
        }
    }

    @Test
    fun dm_implicitAckFromLocalNode_isDelivered() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()

            transport.injectRoutingAck(handle.id.raw, fromNode = transport.nodeNum)
            runCurrent()

            assertEquals(SendState.Delivered, handle.state.value)
            assertEquals(SendOutcome.Success, handle.await())
        }
    }

    @Test
    fun dm_ackFromThirdNode_isDelivered() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()

            transport.injectRoutingAck(handle.id.raw, fromNode = otherNode)
            runCurrent()

            assertEquals(SendState.Delivered, handle.state.value)
        }
    }

    @Test
    fun broadcast_implicitAck_isDelivered() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("all", to = NodeId.BROADCAST)
            runCurrent()

            transport.injectRoutingAck(handle.id.raw, fromNode = transport.nodeNum)
            runCurrent()

            assertEquals(SendState.Delivered, handle.state.value)
            assertEquals(SendOutcome.Success, handle.await())
        }
    }

    @Test
    fun broadcast_ackFromAnyNode_neverReachesAcked() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("all", to = NodeId.BROADCAST)
            runCurrent()

            transport.injectRoutingAck(handle.id.raw, fromNode = NodeId.BROADCAST.raw)
            runCurrent()

            assertEquals(SendState.Delivered, handle.state.value)
        }
    }

    @Test
    fun dm_implicitAckThenDestinationAck_upgradesToAcked() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()

            transport.injectRoutingAck(handle.id.raw, fromNode = transport.nodeNum)
            runCurrent()
            assertEquals(SendState.Delivered, handle.state.value)
            assertEquals(SendOutcome.Success, handle.await())

            transport.injectRoutingAck(handle.id.raw, fromNode = destination.raw)
            runCurrent()
            assertEquals(SendState.Acked, handle.state.value)
        }
    }

    @Test
    fun dm_destinationAckThenImplicitAck_staysAcked() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()

            transport.injectRoutingAck(handle.id.raw, fromNode = destination.raw)
            runCurrent()
            transport.injectRoutingAck(handle.id.raw, fromNode = transport.nodeNum)
            runCurrent()

            assertEquals(SendState.Acked, handle.state.value)
        }
    }

    @Test
    fun dm_implicitAckThenSilence_staysDeliveredPastAckTimeout() = runTest {
        withClient(sendTimeout = 5.seconds) { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()

            transport.injectRoutingAck(handle.id.raw, fromNode = transport.nodeNum)
            runCurrent()

            advanceTimeBy(6_000)
            runCurrent()

            assertEquals(SendState.Delivered, handle.state.value)
        }
    }

    @Test
    fun dm_nakFromDestination_stillFails() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()

            transport.injectRoutingError(handle.id.raw, Routing.Error.NO_ROUTE, fromNode = destination.raw)
            runCurrent()

            assertEquals(SendState.Failed(SendFailure.NoRoute), handle.state.value)
            assertEquals(SendOutcome.Failure(SendFailure.NoRoute), handle.await())
        }
    }

    @Test
    fun dm_nakAfterImplicitAck_doesNotMoveStateBackward() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()

            transport.injectRoutingAck(handle.id.raw, fromNode = transport.nodeNum)
            runCurrent()
            transport.injectRoutingError(handle.id.raw, Routing.Error.MAX_RETRANSMIT, fromNode = transport.nodeNum)
            runCurrent()

            assertEquals(SendState.Delivered, handle.state.value)
        }
    }

    @Test
    fun dm_destinationNakAfterImplicitAck_fails() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()

            transport.injectRoutingAck(handle.id.raw, fromNode = transport.nodeNum)
            runCurrent()
            assertEquals(SendState.Delivered, handle.state.value)
            transport.injectRoutingError(handle.id.raw, Routing.Error.NO_CHANNEL, fromNode = destination.raw)
            runCurrent()

            assertEquals(SendState.Failed(SendFailure.Other(Routing.Error.NO_CHANNEL)), handle.state.value)
        }
    }

    @Test
    fun dm_noAckAtAll_timesOut() = runTest {
        withClient(sendTimeout = 5.seconds) { client, _ ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()

            advanceTimeBy(6_000)
            runCurrent()

            assertEquals(SendState.Failed(SendFailure.AckTimeout), handle.state.value)
        }
    }

    private suspend fun TestScope.withClient(
        sendTimeout: kotlin.time.Duration = 30.seconds,
        block: suspend (RadioClient, FakeRadioTransport) -> Unit,
    ) {
        val transport = FakeRadioTransport(identity = TransportIdentity("fake:ack-vs-delivered"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .sendTimeout(sendTimeout)
            .autoSyncTimeOnConnect(false)
            .build()
        client.connect()
        runCurrent()
        try {
            block(client, transport)
        } finally {
            client.disconnect()
            runCurrent()
        }
    }
}
