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
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.internal.MeshEngine
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The engine holds user sends and releases them only while the device's transmit queue has room:
 * the freshest `QueueStatus.free` minus the packets written but not yet answered. A send held
 * back stays `Queued`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SendPacingTest {

    private val destination = NodeId(0x22222222)

    @Test
    fun singleSendOnIdleLink_isWrittenImmediately() = runTest {
        withClient { client, transport ->
            val handle = client.sendText("hi", to = destination)
            runCurrent()

            assertEquals(SendState.Sent, handle.state.value)
            assertEquals(listOf(handle.id.raw), transport.textIds())
        }
    }

    @Test
    fun burst_isReleasedAsQueueStatusFreesSlots() = runTest {
        withClient(warmFree = 2) { client, transport ->
            val handles = List(6) { client.sendText("m$it", to = destination) }
            runCurrent()

            assertEquals(handles.take(2).ids(), transport.textIds(), "two slots are free")
            assertEquals(SendState.Sent, handles[1].state.value)
            assertEquals(SendState.Queued, handles[2].state.value)

            transport.injectFrame(queueStatus(handles[0].id.raw, free = 1))
            runCurrent()
            assertEquals(handles.take(2).ids(), transport.textIds(), "one packet still unanswered and free is 1")

            transport.injectFrame(queueStatus(handles[1].id.raw, free = 2))
            runCurrent()
            assertEquals(handles.take(4).ids(), transport.textIds())

            transport.injectFrame(queueStatus(handles[2].id.raw, free = 3))
            runCurrent()
            assertEquals(handles.ids(), transport.textIds(), "written in submission order")
        }
    }

    @Test
    fun writtenButUnanswered_neverExceedsFree() = runTest {
        withClient(warmFree = 4) { client, transport ->
            val handles = List(20) { client.sendText("m$it", to = destination) }
            runCurrent()

            var answered = 0
            while (answered < handles.size) {
                val unanswered = transport.textIds().size - answered
                assertTrue(unanswered <= 4, "unanswered=$unanswered exceeds free=4")
                transport.injectFrame(queueStatus(handles[answered++].id.raw, free = 4))
                runCurrent()
            }
            assertEquals(handles.ids(), transport.textIds())
        }
    }

    @Test
    fun deviceFull_holdsUntilAnUnsolicitedQueueStatusReportsRoom() = runTest {
        withClient(warmFree = 0) { client, transport ->
            val handles = List(3) { client.sendText("m$it", to = destination) }
            runCurrent()
            assertEquals(emptyList(), transport.textIds())
            assertEquals(SendState.Queued, handles[0].state.value)

            transport.injectFrame(queueStatus(meshPacketId = 0, free = 2))
            runCurrent()
            assertEquals(handles.take(2).ids(), transport.textIds())
        }
    }

    @Test
    fun queueRejection_failsTheSendAndReleasesTheNext() = runTest {
        withClient(warmFree = 1) { client, transport ->
            val handles = List(2) { client.sendText("m$it", to = destination) }
            runCurrent()
            assertEquals(handles.take(1).ids(), transport.textIds())

            transport.injectFrame(queueStatus(handles[0].id.raw, res = 32, free = 3))
            runCurrent()

            assertEquals(SendState.Failed(SendFailure.QueueRejected(32)), handles[0].state.value)
            assertEquals(handles.ids(), transport.textIds())
        }
    }

    @Test
    fun deviceThatNeverSendsQueueStatus_drainsAfterTheTimeout() = runTest {
        withClient { client, transport ->
            val handles = List(20) { client.sendText("m$it", to = destination) }
            runCurrent()
            assertEquals(handles.take(16).ids(), transport.textIds(), "an unreported queue is assumed to hold 16")

            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(handles.take(16).ids(), transport.textIds(), "the bounded wait has not elapsed")

            advanceTimeBy(2)
            runCurrent()
            assertEquals(handles.ids(), transport.textIds())
            assertEquals(SendState.Sent, handles.last().state.value)
        }
    }

    @Test
    fun afterTheTimeout_laterSendsAreNotHeld() = runTest {
        withClient { client, transport ->
            val burst = List(17) { client.sendText("m$it", to = destination) }
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(burst.ids(), transport.textIds())

            val later = List(20) { client.sendText("l$it", to = destination) }
            runCurrent()

            assertEquals((burst + later).ids(), transport.textIds())
        }
    }

    @Test
    fun cancelOfAHeldSend_failsItAndNeverWritesIt() = runTest {
        withClient(warmFree = 1) { client, transport ->
            val handles = List(3) { client.sendText("m$it", to = destination) }
            runCurrent()

            handles[1].cancel()
            runCurrent()
            assertEquals(SendState.Failed(SendFailure.Cancelled), handles[1].state.value)

            transport.injectFrame(queueStatus(handles[0].id.raw, free = 8))
            runCurrent()

            assertEquals(listOf(handles[0], handles[2]).ids(), transport.textIds())
            assertEquals(SendState.Sent, handles[2].state.value)
        }
    }

    @Test
    fun cancelOfAPacketAlreadyWritten_doesNotCancelIt() = runTest {
        withClient(warmFree = 1) { client, transport ->
            val handles = List(2) { client.sendText("m$it", to = destination) }
            runCurrent()

            handles[0].cancel()
            runCurrent()
            assertEquals(SendState.Sent, handles[0].state.value)

            transport.injectFrame(queueStatus(handles[0].id.raw, free = 8))
            runCurrent()
            assertEquals(handles.ids(), transport.textIds())
        }
    }

    @Test
    fun heldQueueIsBounded_theNewestOverflowFails() = runTest {
        withClient(warmFree = 1) { client, transport ->
            val capacity = MeshEngine.MAX_HELD_SENDS
            val handles = List(1 + capacity + 1) { client.sendText("m$it", to = destination) }
            runCurrent()

            assertEquals(SendState.Sent, handles[0].state.value)
            assertTrue(handles.subList(1, 1 + capacity).all { it.state.value == SendState.Queued })
            assertEquals(SendState.Failed(SendFailure.QueueRejected(32)), handles.last().state.value)

            transport.injectFrame(queueStatus(handles[0].id.raw, free = 16))
            runCurrent()
            assertEquals(handles.take(17).ids(), transport.textIds())
        }
    }

    @Test
    fun sendToTheLocalNode_isNotHeldBehindABacklog() = runTest {
        withClient(warmFree = 1) { client, transport ->
            val backlog = List(4) { client.sendText("m$it", to = destination) }
            runCurrent()
            assertEquals(backlog.take(1).ids(), transport.textIds())

            val local = client.send(adminPacketTo(transport.nodeNum))
            runCurrent()

            assertEquals(SendState.Sent, local.state.value)
            assertTrue(transport.outboundPackets().any { it.id == local.id.raw })
            assertEquals(backlog.take(1).ids(), transport.textIds(), "the backlog is still held")
        }
    }

    @Test
    fun rpc_isNotHeldBehindABacklog() = runTest {
        withClient(warmFree = 1) { client, transport ->
            val backlog = List(4) { client.sendText("m$it", to = destination) }
            runCurrent()
            val adminBefore = transport.adminIds().size

            val owner = backgroundScope.async { client.admin.getOwner() }
            runCurrent()

            assertEquals(adminBefore + 1, transport.adminIds().size)
            assertEquals(backlog.take(1).ids(), transport.textIds())
            owner.cancel()
        }
    }

    @Test
    fun disconnect_failsEveryHeldSend() = runTest {
        withClient(warmFree = 1) { client, _ ->
            val handles = List(3) { client.sendText("m$it", to = destination) }
            runCurrent()

            client.disconnect()
            runCurrent()

            assertEquals(SendState.Failed(SendFailure.Disconnected), handles[1].state.value)
            assertEquals(SendState.Failed(SendFailure.Disconnected), handles[2].state.value)
        }
    }

    @Test
    fun fullQueue_pollsWithAHeartbeatAndReleasesOnTheReply() = runTest {
        withClient(warmFree = 0) { client, transport ->
            val handles = List(3) { client.sendText("m$it", to = destination) }
            runCurrent()
            val before = transport.heartbeats()

            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(before + 1, transport.heartbeats(), "one poll per second")
            assertEquals(emptyList(), transport.textIds())

            transport.injectFrame(queueStatus(meshPacketId = 0, free = 2))
            runCurrent()
            assertEquals(handles.take(2).ids(), transport.textIds(), "released by the reply, not the 5 s timer")
        }
    }

    @Test
    fun nothingHeld_sendsNoPoll() = runTest {
        withClient(warmFree = 0) { _, transport ->
            val before = transport.heartbeats()
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(before, transport.heartbeats())
        }
    }

    @Test
    fun pollingStopsWhenTheFifoDrains() = runTest {
        withClient(warmFree = 0) { client, transport ->
            val handle = client.sendText("m", to = destination)
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            val polled = transport.heartbeats()

            transport.injectFrame(queueStatus(meshPacketId = 0, free = 4))
            runCurrent()
            assertEquals(listOf(handle.id.raw), transport.textIds())

            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(polled, transport.heartbeats())
        }
    }

    @Test
    fun pollingStopsOnDisconnect() = runTest {
        withClient(warmFree = 0) { client, transport ->
            client.sendText("m", to = destination)
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()

            client.disconnect()
            runCurrent()
            val polled = transport.heartbeats()

            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(polled, transport.heartbeats())
        }
    }

    @Test
    fun deviceThatIgnoresPolls_stillDrainsAfterTheTimeout() = runTest {
        withClient(warmFree = 0) { client, transport ->
            val handles = List(2) { client.sendText("m$it", to = destination) }
            runCurrent()

            advanceTimeBy(4_900)
            runCurrent()
            assertEquals(emptyList(), transport.textIds())
            assertTrue(transport.heartbeats() > 0, "polls were sent")

            advanceTimeBy(200)
            runCurrent()
            assertEquals(handles.ids(), transport.textIds())
        }
    }

    @Test
    fun bleWithHeartbeatsDisabled_doesNotPoll() = runTest {
        withClient(warmFree = 0, identity = "ble:send-pacing", bleHeartbeat = false) { client, transport ->
            val handle = client.sendText("m", to = destination)
            runCurrent()
            val before = transport.heartbeats()

            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(before, transport.heartbeats())
            assertEquals(SendState.Queued, handle.state.value)

            advanceTimeBy(1_100)
            runCurrent()
            assertEquals(SendState.Sent, handle.state.value, "the 5 s fallback still releases it")
        }
    }

    private fun List<MessageHandle>.ids(): List<Int> = map { it.id.raw }

    private var warmId = 0

    private fun FakeRadioTransport.textIds(): List<Int> =
        outboundPackets().filter { it.decoded?.portnum == PortNum.TEXT_MESSAGE_APP && it.id != warmId }.map { it.id }

    private fun FakeRadioTransport.heartbeats(): Int = outboundFrames().count { frame ->
        val bytes = frame.bytes.toByteArray()
        bytes.size >= 4 &&
            runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)).heartbeat }.getOrNull() != null
    }

    private fun FakeRadioTransport.adminIds(): List<Int> =
        outboundPackets().filter { it.decoded?.portnum == PortNum.ADMIN_APP }.map { it.id }

    private fun adminPacketTo(node: Int) = MeshPacket.Builder().also { wb ->
        wb.to = node
        wb.want_ack = true
        wb.decoded = Data.Builder().also { wb ->
            wb.portnum = PortNum.ADMIN_APP
            wb.payload = "x".encodeUtf8()
        }.build()
    }.build()

    private fun queueStatus(meshPacketId: Int, free: Int, res: Int = 0) = FromRadio.Builder().also { wb ->
        wb.queueStatus = QueueStatus.Builder().also { wb ->
            wb.res = res
            wb.mesh_packet_id = meshPacketId
            wb.free = free
            wb.maxlen = 16
        }.build()
    }.build().toFrame()

    private suspend fun TestScope.withClient(
        warmFree: Int? = null,
        identity: String = "fake:send-pacing",
        bleHeartbeat: Boolean = true,
        block: suspend (RadioClient, FakeRadioTransport) -> Unit,
    ) {
        val transport = FakeRadioTransport(identity = TransportIdentity(identity), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .sendTimeout(60.seconds)
            .autoSyncTimeOnConnect(false)
            .apply { if (!bleHeartbeat) disableBleHeartbeat() }
            .build()
        client.connect()
        runCurrent()
        if (warmFree != null) {
            val warm = client.sendText("warm", to = destination)
            warmId = warm.id.raw
            runCurrent()
            transport.injectFrame(queueStatus(warm.id.raw, free = warmFree))
            runCurrent()
        }
        try {
            block(client, transport)
        } finally {
            client.disconnect()
            runCurrent()
        }
    }
}
