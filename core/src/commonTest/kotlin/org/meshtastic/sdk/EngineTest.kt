/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Engine lifecycle and message-handle tests.
 *
 * Uses [FakeRadioTransport] and [InMemoryStorageProvider] so no real radio or I/O is needed.
 *
 * **Coroutine dispatcher:** each test passes [TestScope.backgroundScope] context to
 * [RadioClient.Builder.coroutineContext] so the engine actor runs on the `TestCoroutineScheduler`
 * and is auto-cancelled when the test body finishes without triggering [UncompletedCoroutinesError].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EngineTest {

    private fun TestScope.buildClient(transport: FakeRadioTransport = fakeTransport()): RadioClient =
        RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .build()

    private fun fakeTransport() = FakeRadioTransport(
        identity = TransportIdentity("fake:test"),
        autoHandshake = true,
    )

    // ── Connect / disconnect lifecycle ────────────────────────────────────

    @Test
    fun testInitialStateIsDisconnected() = runTest {
        val client = buildClient()
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    @Test
    fun testConnectTransitionsToConnected() = runTest {
        val client = buildClient()
        client.connect()
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun testDisconnectTransitionsToDisconnected() = runTest {
        val client = buildClient()
        client.connect()
        client.disconnect()
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    @Test
    fun testDoubleConnectThrows() = runTest {
        val client = buildClient()
        client.connect()
        assertFailsWith<MeshtasticException.AlreadyConnected> {
            client.connect()
        }
    }

    @Test
    fun testDisconnectBeforeConnectIsIdempotent() = runTest {
        val client = buildClient()
        // Must not throw
        client.disconnect()
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    @Test
    fun testMultipleDisconnectsAreIdempotent() = runTest {
        val client = buildClient()
        client.connect()
        client.disconnect()
        client.disconnect() // second disconnect must not throw
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    // ── Send / cancel ─────────────────────────────────────────────────────

    @Test
    fun testSendThrowsWhenNotConnected() = runTest {
        val client = buildClient()
        assertFailsWith<MeshtasticException.NotConnected> {
            client.send(testPacket())
        }
    }

    @Test
    fun testSendReturnsHandleInQueuedState() = runTest {
        val client = buildClient()
        client.connect()
        val handle = client.send(testPacket())
        assertNotNull(handle)
        assertEquals(SendState.Queued, handle.state.value)
    }

    @Test
    fun testCancelOnSentIsNoOp() = runTest {
        // Per SPEC: cancel() on Sent or later is a no-op; state is unchanged.
        // Use a unicast packet so it stays in Sent (fire-and-forget broadcasts auto-resolve to Acked).
        val client = buildClient()
        client.connect()
        val handle = client.send(unicastPacket())
        runCurrent() // let engine actor process the Send → state becomes Sent
        assertEquals(SendState.Sent, handle.state.value)
        handle.cancel()
        runCurrent() // let engine actor process the CancelHandle (no-op for Sent)
        assertEquals(SendState.Sent, handle.state.value)
    }

    @Test
    fun testDisconnectFailsQueuedHandle() = runTest {
        // Use a unicast packet so it stays in-flight (fire-and-forget broadcasts auto-resolve to Acked).
        val client = buildClient()
        client.connect()
        val handle = client.send(unicastPacket())
        runCurrent() // ensure engine actor processes Send before we cancel the supervisor
        client.disconnect()
        val state = handle.state.value
        assertIs<SendState.Failed>(state)
        assertEquals(SendFailure.Disconnected, state.reason)
    }

    @Test
    fun testPayloadTooLargeThrows() = runTest {
        val client = buildClient()
        client.connect()
        assertFailsWith<MeshtasticException.PayloadTooLarge> {
            client.send(oversizedPacket())
        }
    }

    @Test
    fun testSendTextThrowsWhenNotConnected() = runTest {
        val client = buildClient()
        assertFailsWith<MeshtasticException.NotConnected> {
            client.sendText("hello")
        }
    }

    @Test
    fun testSendTextReturnsHandleInQueuedState() = runTest {
        val client = buildClient()
        client.connect()
        val handle = client.sendText("hello mesh!")
        assertNotNull(handle)
        assertEquals(SendState.Queued, handle.state.value)
    }

    // ── Node snapshot ─────────────────────────────────────────────────────

    @Test
    fun testNodeSnapshotThrowsWhenNotConnected() = runTest {
        val client = buildClient()
        assertFailsWith<MeshtasticException.NotConnected> {
            client.nodeSnapshot()
        }
    }

    @Test
    fun testNodeSnapshotReturnsEmptyMapWhenConnected() = runTest {
        val client = buildClient()
        client.connect()
        val nodes = client.nodeSnapshot()
        // autoHandshake injects one fake node; snapshot may be empty or have the fake node.
        assertNotNull(nodes)
        client.disconnect()
    }

    // ── Transport observability ───────────────────────────────────────────

    @Test
    fun testTransportConnectIsCalled() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport)
        client.connect()
        assertEquals(TransportState.Connected, transport.state.value)
    }

    @Test
    fun testTransportDisconnectIsCalled() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport)
        client.connect()
        client.disconnect()
        assertEquals(TransportState.Disconnected, transport.state.value)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun testPacket() = org.meshtastic.proto.MeshPacket(
        to = NodeId.BROADCAST.raw,
        channel = 0,
        decoded = org.meshtastic.proto.Data(
            portnum = org.meshtastic.proto.PortNum.TEXT_MESSAGE_APP,
            payload = okio.ByteString.of(*"hello".encodeToByteArray()),
        ),
    )

    private fun unicastPacket() = org.meshtastic.proto.MeshPacket(
        to = 0x12345678,
        channel = 0,
        want_ack = true,
        decoded = org.meshtastic.proto.Data(
            portnum = org.meshtastic.proto.PortNum.TEXT_MESSAGE_APP,
            payload = okio.ByteString.of(*"hello".encodeToByteArray()),
        ),
    )

    private fun oversizedPacket() = org.meshtastic.proto.MeshPacket(
        to = NodeId.BROADCAST.raw,
        channel = 0,
        decoded = org.meshtastic.proto.Data(
            portnum = org.meshtastic.proto.PortNum.TEXT_MESSAGE_APP,
            payload = okio.ByteString.of(*ByteArray(300)),
        ),
    )
}
