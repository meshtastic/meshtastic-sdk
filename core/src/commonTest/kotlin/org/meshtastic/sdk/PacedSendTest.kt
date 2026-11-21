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
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PacedSendTest {

    @Test
    fun receiptReleasesNextWriteWhileUnicastWaitsForItsDestination() = runTest {
        val radio = fakeRadio()
        val client = client(radio)
        val first = client.sendPaced(packet(11))
        val second = client.sendPaced(packet(12, destination = radio.nodeNum))
        runCurrent()
        assertTrue(first.awaitAdmission())
        assertTrue(first.awaitDispatch())
        assertTrue(second.awaitAdmission())
        assertEquals(MessageId(11), first.message.id)
        assertEquals(listOf(11), radio.outboundIds())

        radio.queueStatus(id = 0, result = 0)
        runCurrent()
        assertEquals(listOf(11), radio.outboundIds())

        radio.queueStatus(id = 11, result = 0)
        runCurrent()
        assertEquals(QueueReceipt.Accepted, first.awaitQueueReceipt())
        assertEquals(listOf(11, 12), radio.outboundIds())

        radio.injectRoutingAck(11, fromNode = radio.nodeNum)
        runCurrent()
        assertEquals(SendState.Sent, first.message.state.value)

        radio.queueStatus(id = 12, result = 35)
        radio.injectRoutingAck(11, fromNode = PEER)
        runCurrent()
        assertEquals(QueueReceipt.LocalDelivery, second.awaitQueueReceipt())
        assertEquals(SendOutcome.Success, second.message.await())
        assertEquals(SendState.Acked, first.message.state.value)
        client.disconnect()
    }

    @Test
    fun queueTimeoutPreservesRoutingProofAndLateRejectionCannotReleaseTheNextWrite() = runTest {
        val radio = fakeRadio()
        val client = client(radio)
        val first = client.sendPaced(packet(21))
        val second = client.sendPaced(packet(22))
        val third = client.sendPaced(packet(23))
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(QueueReceipt.Unconfirmed, first.awaitQueueReceipt())
        assertEquals(listOf(21, 22), radio.outboundIds())

        radio.injectRoutingAck(21, fromNode = PEER)
        radio.queueStatus(id = 21, result = 33)
        runCurrent()
        assertEquals(SendOutcome.Success, first.message.await())
        assertEquals(listOf(21, 22), radio.outboundIds())

        radio.queueStatus(id = 22, result = 32)
        runCurrent()
        assertEquals(QueueReceipt.Rejected, second.awaitQueueReceipt())
        assertEquals(SendOutcome.Failure(SendFailure.QueueRejected(32)), second.message.await())
        assertEquals(listOf(21, 22, 23), radio.outboundIds())
        client.disconnect()
        assertEquals(QueueReceipt.Unconfirmed, third.awaitQueueReceipt())
        assertEquals(SendOutcome.Failure(SendFailure.Disconnected), third.message.await())
    }

    @Test
    fun queueDeadlineStartsAfterTheTransportWriteAndDoesNotBlockInboundAcks() = runTest {
        val radio = fakeRadio()
        val completeWrite = CompletableDeferred<Unit>()
        val transport = object : RadioTransport by radio {
            override suspend fun send(frame: Frame) {
                val request = runCatching { ToRadio.ADAPTER.decode(frame.bytes.substring(4)) }.getOrNull()
                if (request?.packet?.id == 32) completeWrite.await()
                radio.send(frame)
            }
        }
        val client = client(transport)
        val first = client.sendPaced(packet(31))
        client.sendPaced(packet(32))
        client.sendPaced(packet(33))
        runCurrent()

        radio.queueStatus(id = 31, result = 0)
        runCurrent()
        assertEquals(QueueReceipt.Accepted, first.awaitQueueReceipt())
        advanceTimeBy(6.seconds)
        radio.injectRoutingAck(31, fromNode = PEER)
        runCurrent()
        assertEquals(SendOutcome.Success, first.message.await())
        assertEquals(listOf(31), radio.outboundIds())

        completeWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf(31, 32), radio.outboundIds())
        radio.queueStatus(id = 32, result = 0)
        runCurrent()
        assertEquals(listOf(31, 32, 33), radio.outboundIds())
        client.disconnect()
    }

    @Test
    fun cancellationAndAnIdCollisionCannotCancelAnotherOwnersPacket() = runTest {
        val radio = fakeRadio()
        val client = client(radio)
        val first = client.sendPaced(packet(41))
        val queued = client.sendPaced(packet(42))
        val duplicate = client.sendPaced(packet(42))
        duplicate.message.cancel()
        val cancelled = client.sendPaced(packet(43))
        cancelled.message.cancel()
        runCurrent()
        assertFalse(duplicate.awaitAdmission())
        assertEquals(QueueReceipt.NotDispatched, duplicate.awaitQueueReceipt())
        assertEquals(SendOutcome.Failure(SendFailure.IdCollision), duplicate.message.await())
        assertFalse(cancelled.awaitDispatch())
        assertEquals(QueueReceipt.NotDispatched, cancelled.awaitQueueReceipt())
        assertEquals(SendOutcome.Failure(SendFailure.Cancelled), cancelled.message.await())
        assertEquals(SendState.Queued, queued.message.state.value)
        first.message.cancel()
        runCurrent()
        assertEquals(SendState.Sent, first.message.state.value)
        client.disconnect()
        assertFalse(queued.awaitDispatch())
        assertEquals(QueueReceipt.NotDispatched, queued.awaitQueueReceipt())
        assertEquals(listOf(41), radio.outboundIds())
    }

    @Test
    fun boundedAdmissionCoversTheActorInboxAndReturnsCapacityOnCancellation() = runTest {
        val radio = fakeRadio()
        val client = client(radio)
        val sends = (101..228).map { client.sendPaced(packet(it)) }
        val overflow = client.sendPaced(packet(229))
        assertFalse(overflow.awaitAdmission())
        assertEquals(SendOutcome.Failure(SendFailure.QueueFull), overflow.message.await())

        sends.last().message.cancel()
        runCurrent()
        assertFalse(sends.last().awaitDispatch())
        val replacement = client.sendPaced(packet(229))
        runCurrent()
        assertTrue(replacement.awaitAdmission())
        assertEquals(listOf(101), radio.outboundIds())
        client.disconnect()
    }

    private fun fakeRadio(): FakeRadioTransport = FakeRadioTransport(
        TransportIdentity("fake:paced"),
        autoHandshake = true,
    )

    private suspend fun TestScope.client(transport: RadioTransport): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .coroutineContext(backgroundScope.coroutineContext)
        .autoSyncTimeOnConnect(false)
        .build()
        .also { it.connect() }

    private fun packet(id: Int, destination: Int = PEER): MeshPacket = MeshPacket.Builder().also {
        it.id = id
        it.to = destination
        it.want_ack = true
        it.decoded = Data.Builder().also { data ->
            data.portnum = PortNum.TEXT_MESSAGE_APP
            data.payload = "hello".encodeUtf8()
        }.build()
    }.build()

    private fun FakeRadioTransport.outboundIds(): List<Int> =
        outboundPackets().filter { it.decoded?.portnum == PortNum.TEXT_MESSAGE_APP }.map { it.id }

    private fun FakeRadioTransport.queueStatus(id: Int, result: Int) {
        val status = QueueStatus.Builder().also {
            it.mesh_packet_id = id
            it.res = result
            it.free = 4
        }.build()
        injectFrame(FromRadio.Builder().also { it.queueStatus = status }.build().toFrame())
    }

    private companion object {
        const val PEER = 0x1234
    }
}
