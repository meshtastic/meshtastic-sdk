/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `queueWhileReconnecting(capacity)` holds sends made while the session is not yet usable. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QueueWhileReconnectingTest {

    private val destination = NodeId(0x22222222)

    @Test
    fun defaultOff_sendWhileReconnectingThrows() = runTest {
        withClient(capacity = 0) { client, transport ->
            dropLink(client, transport)

            assertFailsWith<MeshtasticException.NotConnected> { client.sendText("x", to = destination) }
        }
    }

    @Test
    fun sendWhileReconnecting_isQueuedAndFlushesInOrderOnceReady() = runTest {
        withClient(capacity = 5) { client, transport ->
            dropLink(client, transport)

            val handles = List(3) { client.sendText("m$it", to = destination) }
            runCurrent()
            assertTrue(handles.all { it.state.value == SendState.Queued })
            val before = transport.textIds().size

            reconnect(client)

            assertEquals(ConnectionState.Connected, client.connection.value)
            assertEquals(handles.map { it.id.raw }, transport.textIds().drop(before))
            assertTrue(handles.all { it.state.value == SendState.Sent })
        }
    }

    @Test
    fun sendBeforeTheFirstConnect_stillThrows() = runTest {
        val transport = LinkTransport(TransportIdentity("fake:q-first"))
        val client = build(transport, capacity = 5)

        assertFailsWith<MeshtasticException.NotConnected> { client.sendText("x", to = destination) }
    }

    @Test
    fun sendWhileConfiguring_isQueued() = runTest {
        val transport = LinkTransport(TransportIdentity("fake:q-config"), answerHandshake = false)
        val client = build(transport, capacity = 5)
        val connecting = backgroundScope.async { runCatching { client.connect() } }
        runCurrent()
        assertIs<ConnectionState.Configuring>(client.connection.value)

        val handle = client.sendText("x", to = destination)
        runCurrent()
        assertEquals(SendState.Queued, handle.state.value)

        client.disconnect()
        runCurrent()
        assertEquals(SendState.Failed(SendFailure.Disconnected), handle.state.value)
        connecting.await()
    }

    @Test
    fun overCapacity_theNewHandleFailsQueueRejected() = runTest {
        withClient(capacity = 2) { client, transport ->
            dropLink(client, transport)

            val handles = List(3) { client.sendText("m$it", to = destination) }
            runCurrent()

            assertEquals(SendState.Queued, handles[0].state.value)
            assertEquals(SendState.Queued, handles[1].state.value)
            assertEquals(SendState.Failed(SendFailure.QueueRejected(32)), handles[2].state.value)

            reconnect(client)
            assertEquals(handles.take(2).map { it.id.raw }, transport.textIds().takeLast(2))
        }
    }

    @Test
    fun cancelOfAQueuedSend_removesIt() = runTest {
        withClient(capacity = 3) { client, transport ->
            dropLink(client, transport)
            val handles = List(3) { client.sendText("m$it", to = destination) }
            runCurrent()

            handles[1].cancel()
            runCurrent()
            assertEquals(SendState.Failed(SendFailure.Cancelled), handles[1].state.value)

            reconnect(client)
            assertEquals(listOf(handles[0], handles[2]).map { it.id.raw }, transport.textIds().takeLast(2))
        }
    }

    @Test
    fun explicitDisconnect_failsTheQueueAndSendThrowsAgain() = runTest {
        withClient(capacity = 5) { client, transport ->
            dropLink(client, transport)
            val handles = List(2) { client.sendText("m$it", to = destination) }
            runCurrent()

            client.disconnect()
            runCurrent()

            assertTrue(handles.all { it.state.value == SendState.Failed(SendFailure.Disconnected) })
            assertFailsWith<MeshtasticException.NotConnected> { client.sendText("x", to = destination) }
        }
    }

    @Test
    fun reconnectGivingUp_failsTheQueue() = runTest {
        withClient(capacity = 5) { client, transport ->
            dropLink(client, transport)
            val handle = client.sendText("m", to = destination)
            runCurrent()

            transport.fail(recoverable = false)
            runCurrent()
            advanceTimeBy(1)
            runCurrent()

            assertEquals(ConnectionState.Disconnected, client.connection.value)
            assertEquals(SendState.Failed(SendFailure.Disconnected), handle.state.value)
            assertFailsWith<MeshtasticException.NotConnected> { client.sendText("x", to = destination) }
        }
    }

    @Test
    fun sendsHeldForTheDeviceQueue_surviveTheDropAndFlushInOrder() = runTest {
        withClient(capacity = 5) { client, transport ->
            val handles = List(20) { client.sendText("m$it", to = destination) }
            runCurrent()
            assertEquals(16, transport.textIds().size)

            dropLink(client, transport)
            assertTrue(handles.take(16).all { it.state.value == SendState.Failed(SendFailure.Disconnected) })
            assertTrue(handles.drop(16).all { it.state.value == SendState.Queued })
            val before = transport.textIds().size

            reconnect(client)

            assertEquals(handles.drop(16).map { it.id.raw }, transport.textIds().drop(before))
        }
    }

    private fun LinkTransport.textIds(): List<Int> =
        outboundPackets().filter { it.decoded?.portnum == PortNum.TEXT_MESSAGE_APP }.map { it.id }

    private fun TestScope.dropLink(client: RadioClient, transport: LinkTransport) {
        transport.fail(recoverable = true)
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(client.connection.value)
    }

    private fun TestScope.reconnect(client: RadioClient) {
        advanceTimeBy(BACKOFF_MS + 1)
        repeat(20) { runCurrent() }
        repeat(10) {
            if (client.connection.value == ConnectionState.Connected) return
            advanceTimeBy(100)
            repeat(20) { runCurrent() }
        }
    }

    private fun TestScope.build(transport: LinkTransport, capacity: Int): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .coroutineContext(backgroundScope.coroutineContext)
        .sendTimeout(60.seconds)
        .autoSyncTimeOnConnect(false)
        .autoReconnect(
            AutoReconnectConfig(
                enabled = true,
                initialBackoff = BACKOFF_MS.milliseconds,
                maxBackoff = BACKOFF_MS.milliseconds,
                jitter = 0.0,
            ),
        )
        .queueWhileReconnecting(capacity)
        .build()

    private suspend fun TestScope.withClient(capacity: Int, block: suspend (RadioClient, LinkTransport) -> Unit) {
        val transport = LinkTransport(TransportIdentity("fake:queue-reconnect"))
        val client = build(transport, capacity)
        client.connect()
        runCurrent()
        try {
            block(client, transport)
        } finally {
            client.disconnect()
            runCurrent()
        }
    }

    private companion object {
        const val BACKOFF_MS = 10_000L
    }
}

/** A transport that answers the handshake and can be dropped and reconnected. */
private class LinkTransport(override val identity: TransportIdentity, private val answerHandshake: Boolean = true) :
    RadioTransport {
    private val stateFlow = MutableStateFlow<TransportState>(TransportState.Disconnected)
    private val inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 128)
    private val written = mutableListOf<MeshPacket>()
    val nodeNum = 1

    override val state: StateFlow<TransportState> = stateFlow

    override suspend fun connect() {
        stateFlow.value = TransportState.Connected
    }

    override suspend fun disconnect() {
        stateFlow.value = TransportState.Disconnected
    }

    override fun frames(): Flow<Frame> = inbound

    fun outboundPackets(): List<MeshPacket> = written.toList()

    fun fail(recoverable: Boolean) {
        stateFlow.value = TransportState.Error(MeshtasticException.Transport("drop"), recoverable)
    }

    override suspend fun send(frame: Frame) {
        val bytes = frame.bytes.toByteArray()
        if (bytes.size < 4) return
        val toRadio = runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull() ?: return
        toRadio.packet?.let { written += it }
        if (!answerHandshake) return
        when (toRadio.want_config_id) {
            NONCE_STAGE1 -> {
                emit(
                    FromRadio.Builder().also { wb ->
                        wb.my_info =
                            MyNodeInfo.Builder().also { it.my_node_num = nodeNum }.build()
                    }.build(),
                )
                emit(FromRadio.Builder().also { wb -> wb.config_complete_id = NONCE_STAGE1 }.build())
            }

            NONCE_STAGE2 -> emit(FromRadio.Builder().also { wb -> wb.config_complete_id = NONCE_STAGE2 }.build())
        }
        val packet = toRadio.packet ?: return
        val admin =
            packet.decoded?.payload?.let { runCatching { AdminMessage.ADAPTER.decode(it) }.getOrNull() } ?: return
        if (packet.to != nodeNum || admin.get_owner_request != true) return
        val response = AdminMessage.Builder().also { wb ->
            wb.get_owner_response = User.Builder().also { u ->
                u.id = "!00000001"
                u.long_name = "Link"
                u.short_name = "LK"
                u.hw_model = HardwareModel.UNSET
            }.build()
            wb.session_passkey = byteArrayOf(1, 2, 3, 4).toByteString()
        }.build()
        emit(
            FromRadio.Builder().also { wb ->
                wb.packet = MeshPacket.Builder().also { p ->
                    p.from = nodeNum
                    p.to = 0
                    p.decoded = Data.Builder().also { d ->
                        d.portnum = PortNum.ADMIN_APP
                        d.payload = AdminMessage.ADAPTER.encode(response).toByteString()
                        d.request_id = packet.id
                    }.build()
                }.build()
            }.build(),
        )
    }

    private fun emit(fromRadio: FromRadio) {
        inbound.tryEmit(fromRadio.toFrame())
    }

    private companion object {
        const val NONCE_STAGE1 = 69420
        const val NONCE_STAGE2 = 69421
    }
}
