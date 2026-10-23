/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Auto-reconnect against a transport that, like the real BLE/TCP/serial transports, gives each
 * connect cycle its own frame stream: the engine must start a new frame reader after reconnecting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoReconnectFrameReaderTest {

    private val reconnect = AutoReconnectConfig(
        enabled = true,
        initialBackoff = 1.seconds,
        maxBackoff = 1.seconds,
        maxAttempts = 3,
        jitter = 0.0,
    )

    @Test
    fun reconnectReachesConnectedAndReadsTheNewLink() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:reconnect-reader"), autoHandshake = true)
        val client = buildClient(transport)
        val texts = mutableListOf<String>()
        backgroundScope.launch { client.packets.collect { p -> p.asText()?.let { texts += it } } }

        client.connect()
        runCurrent()
        assertEquals(ConnectionState.Connected, client.connection.value)

        transport.simulateError(IllegalStateException("link lost"), recoverable = true)
        runCurrent()
        awaitConnected(client)
        assertEquals(ConnectionState.Connected, client.connection.value, "handshake on the new link must complete")

        transport.injectPacket(textPacket("after reconnect"))
        runCurrent()
        assertEquals(listOf("after reconnect"), texts)
        client.disconnect()
    }

    @Test
    fun aFrameStreamThatEndsTriggersAReconnect() = runTest {
        val inner = FakeRadioTransport(TransportIdentity("fake:stream-ends"), autoHandshake = true)
        val transport = EndableTransport(inner)
        val client = buildClient(transport)

        client.connect()
        runCurrent()
        assertEquals(ConnectionState.Connected, client.connection.value)

        // The link goes away without the transport publishing an Error: the stream just ends.
        transport.endStream()
        runCurrent()
        assertTrue(client.connection.value is ConnectionState.Reconnecting, "was ${client.connection.value}")

        awaitConnected(client)
        assertEquals(ConnectionState.Connected, client.connection.value)
        client.disconnect()
    }

    @Test
    fun aReconnectHandshakeThatTimesOutIsRetried() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:reconnect-timeout"), autoHandshake = true)
        val silent = SilentAfterFirstConnect(transport)
        val client = buildClient(silent)

        client.connect()
        runCurrent()
        silent.silent = true
        transport.simulateError(IllegalStateException("link lost"), recoverable = true)
        runCurrent()

        // The first reconnect handshake gets no answer and times out (20 s Stage 1 budget)...
        advanceTimeBy(25.seconds)
        runCurrent()
        // ...and is retried instead of ending the session.
        assertTrue(client.connection.value != ConnectionState.Disconnected, "was ${client.connection.value}")

        // ...the radio comes back, and the next attempt connects.
        silent.silent = false
        awaitConnected(client, rounds = 60)
        assertEquals(ConnectionState.Connected, client.connection.value)
        client.disconnect()
    }

    private suspend fun TestScope.awaitConnected(client: RadioClient, rounds: Int = 20) {
        repeat(rounds) {
            if (client.connection.value == ConnectionState.Connected) return
            advanceTimeBy(500L)
            runCurrent()
        }
    }

    private fun TestScope.buildClient(transport: RadioTransport): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .autoReconnect(reconnect)
        .autoSyncTimeOnConnect(false)
        .coroutineContext(backgroundScope.coroutineContext)
        .build()

    private fun textPacket(text: String) = MeshPacket.Builder().also { wb ->
        wb.from = 0x22222222
        wb.to = -1
        wb.id = 4242
        wb.decoded = Data.Builder().also { wb ->
            wb.portnum = PortNum.TEXT_MESSAGE_APP
            wb.payload = text.encodeToByteArray().toByteString()
        }.build()
    }.build()

    /** Wraps a fake so the current cycle's frame stream can end without any state change. */
    private class EndableTransport(private val inner: FakeRadioTransport) : RadioTransport by inner {
        private var ended = kotlinx.coroutines.CompletableDeferred<Unit>()

        fun endStream() = ended.complete(Unit)

        override suspend fun connect() {
            ended = kotlinx.coroutines.CompletableDeferred()
            inner.connect()
        }

        override fun frames(): Flow<Frame> {
            val stop = ended
            val source = inner.frames()
            return kotlinx.coroutines.flow.channelFlow {
                val forward = launch { source.collect { send(it) } }
                stop.await()
                forward.cancel()
            }
        }
    }

    /** After the first connect, drops every frame the radio would send while [silent]. */
    private class SilentAfterFirstConnect(private val inner: FakeRadioTransport) : RadioTransport by inner {
        var silent = false

        override suspend fun send(frame: Frame) {
            if (!silent) inner.send(frame)
        }
    }
}
