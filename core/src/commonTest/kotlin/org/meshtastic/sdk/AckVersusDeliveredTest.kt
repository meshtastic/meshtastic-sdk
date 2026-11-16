/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.Routing
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    @Test
    fun broadcastWithoutWantAck_endsInSent() = runTest {
        withClient(sendTimeout = 5.seconds) { client, transport ->
            val handle = client.send(noAckPacket(to = NodeId.BROADCAST.raw))
            runCurrent()
            assertEquals(SendState.Queued, handle.state.value)

            transport.injectFrame(queueStatus(handle.id.raw, res = 0))
            runCurrent()

            assertEquals(SendState.Sent, handle.state.value)
            assertEquals(SendOutcome.Success, handle.await())

            advanceTimeBy(6_000)
            runCurrent()

            assertEquals(SendState.Sent, handle.state.value)
        }
    }

    @Test
    fun unicastWithoutWantAck_endsInSent() = runTest {
        withClient(sendTimeout = 5.seconds) { client, transport ->
            val handle = client.send(noAckPacket(to = destination.raw))
            runCurrent()
            assertEquals(SendState.Queued, handle.state.value)

            transport.injectFrame(queueStatus(handle.id.raw, res = 0))
            runCurrent()

            assertEquals(SendState.Sent, handle.state.value)
            assertEquals(SendOutcome.Success, handle.await())

            advanceTimeBy(6_000)
            runCurrent()

            assertEquals(SendState.Sent, handle.state.value)
        }
    }

    @Test
    fun unicastWithoutWantAck_deviceAcceptingItKeepsSent() = runTest {
        withClient { client, transport ->
            val handle = client.send(noAckPacket(to = destination.raw))
            runCurrent()

            transport.injectFrame(queueStatus(handle.id.raw, res = 0))
            runCurrent()

            assertEquals(SendState.Sent, handle.state.value)
        }
    }

    @Test
    fun unicastWithoutWantAck_queueRejectionFailsIt() = runTest {
        withClient { client, transport ->
            val handle = client.send(noAckPacket(to = destination.raw))
            runCurrent()

            transport.injectFrame(queueStatus(handle.id.raw, res = 100))
            runCurrent()

            assertEquals(SendState.Failed(SendFailure.QueueRejected(res = 100)), handle.state.value)
        }
    }

    @Test
    fun sendWithoutWantAck_noQueueStatus_isSentAfterGrace() = runTest {
        withClient { client, _ ->
            val handle = client.send(noAckPacket(to = destination.raw))
            runCurrent()
            assertEquals(SendState.Queued, handle.state.value)

            advanceTimeBy(6_000)
            runCurrent()

            assertEquals(SendState.Sent, handle.state.value)
            assertEquals(SendOutcome.Success, handle.await())
        }
    }

    @Test
    fun sendWithoutWantAck_awaitDoesNotSucceedBeforeTheDeviceAccepts() = runTest {
        withClient { client, transport ->
            val handle = client.send(noAckPacket(to = destination.raw))
            runCurrent()
            val outcome = backgroundScope.async { handle.await() }
            runCurrent()
            assertFalse(outcome.isCompleted, "await must wait for the device's verdict")

            transport.injectFrame(queueStatus(handle.id.raw, res = 39))
            runCurrent()

            assertEquals(SendOutcome.Failure(SendFailure.QueueRejected(res = 39)), outcome.await())
        }
    }

    @Test
    fun sendWithoutWantAck_disconnectBeforeAcceptanceFailsIt() = runTest {
        withClient { client, _ ->
            val handle = client.send(noAckPacket(to = destination.raw))
            runCurrent()

            client.disconnect()
            runCurrent()

            assertEquals(SendState.Failed(SendFailure.Disconnected), handle.state.value)
        }
    }

    @Test
    fun sendWithoutWantAck_isNotFailedByDisconnect() = runTest {
        withClient { client, transport ->
            val handle = client.send(noAckPacket(to = destination.raw))
            runCurrent()
            transport.injectFrame(queueStatus(handle.id.raw, res = 0))
            runCurrent()

            client.disconnect()
            runCurrent()

            assertEquals(SendState.Sent, handle.state.value)
        }
    }

    @Test
    fun dm_lateQueueStatusDoesNotPullDeliveredBackToSent() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()
            transport.injectRoutingAck(handle.id.raw, fromNode = transport.nodeNum)
            runCurrent()
            assertEquals(SendState.Delivered, handle.state.value)

            transport.injectFrame(queueStatus(handle.id.raw, res = 0))
            runCurrent()

            assertEquals(SendState.Delivered, handle.state.value)
        }
    }

    @Test
    fun sendWithoutWantAck_cancelAfterDispatchDoesNotReportCancelled() = runTest {
        withClient { client, transport ->
            val handle = client.send(noAckPacket(to = destination.raw))
            runCurrent()
            assertEquals(SendState.Queued, handle.state.value)

            handle.cancel()
            runCurrent()
            assertEquals(SendState.Queued, handle.state.value)

            transport.injectFrame(queueStatus(handle.id.raw, res = 0))
            runCurrent()
            assertEquals(SendState.Sent, handle.state.value)
        }
    }

    @Test
    fun dm_relayAckedThenDisconnect_staysDelivered() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()
            transport.injectRoutingAck(handle.id.raw, fromNode = transport.nodeNum)
            runCurrent()
            assertEquals(SendState.Delivered, handle.state.value)

            client.disconnect()
            runCurrent()

            assertEquals(SendState.Delivered, handle.state.value)
        }
    }

    @Test
    fun sendWithWantAck_isNotTerminalAtSent() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()
            assertEquals(SendState.Sent, handle.state.value)

            val outcome = backgroundScope.async { handle.await() }
            runCurrent()
            assertFalse(outcome.isCompleted, "await must not return at Sent when an ack was requested")

            transport.injectRoutingAck(handle.id.raw, fromNode = destination.raw)
            runCurrent()

            assertTrue(outcome.isCompleted)
            assertEquals(SendOutcome.Success, outcome.await())
        }
    }

    private fun noAckPacket(to: Int) = MeshPacket.Builder().also { wb ->
        wb.to = to
        wb.channel = 0
        wb.want_ack = false
        wb.decoded = Data.Builder().also { wb ->
            wb.portnum = PortNum.TEXT_MESSAGE_APP
            wb.payload = "hi".encodeUtf8()
        }.build()
    }.build()

    private fun queueStatus(meshPacketId: Int, res: Int) = FromRadio.Builder().also { wb ->
        wb.queueStatus = QueueStatus.Builder().also { wb ->
            wb.res = res
            wb.mesh_packet_id = meshPacketId
        }.build()
    }.build().toFrame()

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
