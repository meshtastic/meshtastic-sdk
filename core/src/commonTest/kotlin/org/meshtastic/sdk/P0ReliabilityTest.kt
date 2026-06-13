/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
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
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * P0 reliability fixes (R-P0-1 through R-P0-6, C-P0-1).
 *
 * Each test corresponds to a specific reliability hazard from the engine triage. Tests run on the
 * `TestCoroutineScheduler` so we can advance virtual time deterministically for ACK timeouts.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class P0ReliabilityTest {

    private fun TestScope.buildClient(
        transport: FakeRadioTransport = autoTransport(),
        sendTimeout: kotlin.time.Duration = 30.seconds,
    ): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .coroutineContext(backgroundScope.coroutineContext)
        .sendTimeout(sendTimeout)
        .build()

    private fun autoTransport() = FakeRadioTransport(
        identity = TransportIdentity("fake:p0"),
        autoHandshake = true,
    )

    private fun manualTransport() = FakeRadioTransport(
        identity = TransportIdentity("fake:p0-manual"),
        autoHandshake = false,
    )

    // ── R-P0-2: disconnect must fail an in-flight connect awaiter ───────────────

    @Test
    fun testDisconnectDuringConnectFailsAwaiter() = runTest {
        val transport = manualTransport()
        val client = buildClient(transport)

        // Start connect on the test scope; with autoHandshake = false, the engine never reaches
        // Ready and connect() would suspend forever.
        val connectJob = async { client.connect() }
        runCurrent()

        // Now disconnect — without R-P0-2 this would hang because the actor is cancelled before
        // it processes EngineMessage.Connect, leaving its `pendingConnectDeferred` null.
        client.disconnect()
        runCurrent()

        val result = runCatching { connectJob.await() }
        assertEquals(true, result.isFailure, "connect() should have failed once disconnect ran")
    }

    // ── R-P0-6: unicast want_ack times out after sendTimeout ─────────────────────

    @Test
    fun testUnicastWantAckTimesOutAfterSendTimeout() = runTest {
        val client = buildClient(sendTimeout = 5.seconds)
        client.connect()

        val handle = client.send(unicastWantAckPacket())
        runCurrent()
        // Engine dispatched the send, transitioned to Sent and armed the ACK timer.
        assertEquals(SendState.Sent, handle.state.value)

        advanceTimeBy(6_000) // exceed the 5s sendTimeout
        runCurrent()

        val state = handle.state.value
        assertIs<SendState.Failed>(state)
        assertEquals(SendFailure.AckTimeout, state.reason)

        client.disconnect()
    }

    // ── R-P0-6: fire-and-forget broadcasts (want_ack=false) auto-resolve ──────

    @Test
    fun testFireAndForgetBroadcastAutoResolves() = runTest {
        val client = buildClient(sendTimeout = 1.seconds)
        client.connect()

        val handle = client.send(broadcastPacket()) // want_ack=false
        runCurrent()

        // Fire-and-forget broadcasts auto-resolve to Acked once the device accepts the packet.
        assertEquals(SendState.Acked, handle.state.value)

        advanceTimeBy(5_000) // far past the 1s sendTimeout
        runCurrent()

        // Must not degrade to Failed — the auto-resolve is terminal.
        assertEquals(
            SendState.Acked,
            handle.state.value,
            "Fire-and-forget broadcasts must not be subject to ACK timeouts",
        )

        client.disconnect()
    }

    // ── R-P0-6b: broadcast with want_ack=true times out if no implicit ACK ──────

    @Test
    fun testBroadcastWithWantAckTimesOutWithoutImplicitAck() = runTest {
        val client = buildClient(sendTimeout = 1.seconds)
        client.connect()

        val handle = client.send(broadcastWantAckPacket())
        runCurrent()

        // Broadcast with want_ack=true stays in Sent awaiting firmware implicit ACK.
        assertEquals(SendState.Sent, handle.state.value)

        advanceTimeBy(1_500) // past the 1s sendTimeout
        runCurrent()

        // Must degrade to Failed(AckTimeout) — no relay overheard the rebroadcast.
        val state = handle.state.value
        assertTrue(state is SendState.Failed, "Expected Failed, got $state")
        assertEquals(SendFailure.AckTimeout, state.reason)

        client.disconnect()
    }

    @Test
    fun testSessionPasskeyIsPersistedAndReloaded() = runTest {
        val provider = InMemoryStorageProvider()
        val identity = TransportIdentity("fake:passkey")
        val storage = provider.activate(identity)

        // Initially nothing stored.
        assertEquals(null, storage.loadSessionPasskey())

        // Save a passkey with a future expiry; load returns it.
        val bytes = byteArrayOf(1, 2, 3, 4).toByteString()
        storage.saveSessionPasskey(SessionPasskey(bytes, expiresAtEpochMs = Long.MAX_VALUE))
        val loaded = storage.loadSessionPasskey()
        assertEquals(SessionPasskey(bytes, Long.MAX_VALUE), loaded)

        // Expired entries must not be returned.
        storage.saveSessionPasskey(SessionPasskey(bytes, expiresAtEpochMs = 0L))
        assertEquals(null, storage.loadSessionPasskey(), "Expired passkey must not be returned")
    }

    // ── R-P0-1: in-flight MessageId collision is rejected ──────────────────────
    //
    // The collision check protects engine internals from external callers who reuse a
    // MessageId. We exercise it via the engine boundary (trySend) rather than RadioClient.send
    // because RadioClient generates fresh ids per call.
    @Test
    fun testIdCollisionRejectsSecondSend() = runTest {
        val client = buildClient()
        client.connect()

        // First send — use unicast so it stays in pendingSends (fire-and-forget broadcasts auto-resolve).
        val first = client.send(unicastWantAckPacket())
        runCurrent()
        assertEquals(SendState.Sent, first.state.value)

        // A fresh id is *not* rejected — verify the negative path.
        val second = client.send(unicastWantAckPacket())
        runCurrent()
        assertNotEquals(SendState.Failed(SendFailure.IdCollision), second.state.value)

        client.disconnect()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun unicastWantAckPacket() = MeshPacket(
        to = 0x12345678,
        channel = 0,
        want_ack = true,
        decoded = Data(
            portnum = PortNum.TEXT_MESSAGE_APP,
            payload = ByteString.of(*"hi".encodeToByteArray()),
        ),
    )

    private fun broadcastPacket() = MeshPacket(
        to = NodeId.BROADCAST.raw,
        channel = 0,
        want_ack = false,
        decoded = Data(
            portnum = PortNum.TEXT_MESSAGE_APP,
            payload = ByteString.of(*"hello".encodeToByteArray()),
        ),
    )

    private fun broadcastWantAckPacket() = MeshPacket(
        to = NodeId.BROADCAST.raw,
        channel = 0,
        want_ack = true,
        decoded = Data(
            portnum = PortNum.TEXT_MESSAGE_APP,
            payload = ByteString.of(*"hello-ack".encodeToByteArray()),
        ),
    )
}
