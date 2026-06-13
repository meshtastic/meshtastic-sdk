/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Table-driven coverage of the handshake state machine documented in
 * [docs/architecture/handshake-fsm.md](../../../../../../docs/architecture/handshake-fsm.md).
 *
 * Validates:
 * - Pre-handshake byte discipline (frames before Stage 1 nonce posts are dropped).
 * - Stage 1 timeout: no `config_complete_id=69420` within 20 s ⇒
 *   [MeshtasticException.HandshakeTimeout] with stage `"Stage1Draining"`.
 * - Stage 2 timeout: no `config_complete_id=69421` within 60 s ⇒
 *   [MeshtasticException.HandshakeTimeout] with stage `"Stage2Draining"`.
 * - The public projection traverses
 *   `Disconnected → Connecting → Configuring(Stage1) → Configuring(Stage2) → Connected`.
 *
 * All tests use virtual time via `runTest`; none take wall-clock seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeFsmTest {

    private fun TestScope.buildClient(transport: RadioTransport): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .coroutineContext(backgroundScope.coroutineContext)
        .build()

    // ── Public projection traversal ───────────────────────────────────────────

    @Test
    fun connectionStateTraversesAllPhases() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:fsm"), autoHandshake = true)
        val client = buildClient(transport)

        client.connection.test {
            assertEquals(ConnectionState.Disconnected, awaitItem())

            val connectJob = async { client.connect() }

            // Connecting(attempt=1)
            val connecting = awaitItem()
            assertIs<ConnectionState.Connecting>(connecting)
            assertEquals(1, connecting.attempt)

            // Configuring(Stage1, ~0)
            val s1 = awaitItem()
            assertIs<ConnectionState.Configuring>(s1)
            assertEquals(ConfigPhase.Stage1, s1.phase)

            // Settling between stages (Apple/Android precedent: 100 ms)
            val settling = awaitItem()
            assertIs<ConnectionState.Configuring>(settling)
            assertEquals(ConfigPhase.Settling, settling.phase)

            // Configuring(Stage2)
            val s2 = awaitItem()
            assertIs<ConnectionState.Configuring>(s2)
            assertEquals(ConfigPhase.Stage2, s2.phase)

            // Connected
            assertEquals(ConnectionState.Connected, awaitItem())

            connectJob.await()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Pre-handshake byte discipline (protocol.md §6) ────────────────────────

    @Test
    fun preHandshakeFramesAreDiscarded() = runTest {
        // Pre-load a bogus FromRadio with a my_info from a "stale" prior session.
        // Per the FSM spec, the engine MUST drop these frames; if it routed them,
        // they'd corrupt early state. We assert that connect() still completes
        // through the normal autoHandshake flow (i.e. the bogus frames did not
        // break the FSM).
        val staleFrame = encodeFromRadio(
            FromRadio(my_info = MyNodeInfo(my_node_num = 0xDEADBEEF.toInt())),
        )
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:pre-handshake"),
            frames = listOf(staleFrame),
            autoHandshake = true,
        )
        val client = buildClient(transport)
        client.connect()
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    // ── Stage 1 timeout ───────────────────────────────────────────────────────

    @Test
    fun stage1TimeoutThrowsHandshakeTimeout() = runTest {
        // autoHandshake = false ⇒ no Stage 1 response is ever injected.
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:stage1-timeout"),
            autoHandshake = false,
        )
        val client = buildClient(transport)

        val connectResult = backgroundScope.async { runCatching { client.connect() } }

        // Wait until the engine actually enters Stage1Draining before advancing
        // virtual time, so we know the 20 s timer has been armed.
        client.connection.first { it is ConnectionState.Configuring }
        advanceTimeBy(STAGE1_TIMEOUT_MS + 1_000L)
        runCurrent()

        val ex = connectResult.await().exceptionOrNull()
        assertIs<MeshtasticException.HandshakeTimeout>(ex)
        assertEquals("Stage 1", ex.stage)
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    // ── Stage 2 timeout ───────────────────────────────────────────────────────

    @Test
    fun stage2TimeoutThrowsHandshakeTimeout() = runTest {
        val transport = Stage1OnlyTransport()
        val client = buildClient(transport)

        val connectResult = backgroundScope.async { runCatching { client.connect() } }

        // Wait until Stage 2 phase is observed, so we know Stage 1 completed
        // and the Stage 2 timer is now armed.
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }
        advanceTimeBy(STAGE2_TIMEOUT_MS + 1_000L)
        runCurrent()

        val ex = connectResult.await().exceptionOrNull()
        assertIs<MeshtasticException.HandshakeTimeout>(ex)
        assertEquals("Stage 2", ex.stage)
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    /**
     * Minimal transport that completes Stage 1 in real time but never responds
     * to Stage 2 — used to exercise the Stage 2 timeout path.
     */
    private inner class Stage1OnlyTransport : RadioTransport {
        override val identity = TransportIdentity("fake:stage2-timeout")
        private val _state = kotlinx.coroutines.flow.MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: kotlinx.coroutines.flow.StateFlow<TransportState> = _state
        private var inbound = kotlinx.coroutines.channels.Channel<Frame>(kotlinx.coroutines.channels.Channel.UNLIMITED)

        override suspend fun connect() {
            inbound = kotlinx.coroutines.channels.Channel(kotlinx.coroutines.channels.Channel.UNLIMITED)
            _state.value = TransportState.Connecting
            _state.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            inbound.close()
            _state.value = TransportState.Disconnected
        }

        override suspend fun send(frame: Frame) {
            val to = decodeToRadioOrNull(frame) ?: return
            if (to.want_config_id == STAGE1_NONCE) {
                inbound.trySend(encodeFromRadio(FromRadio(my_info = MyNodeInfo(my_node_num = 1))))
                inbound.trySend(encodeFromRadio(FromRadio(config_complete_id = STAGE1_NONCE)))
            }
            // Swallow Stage 2 nonce so the engine times out.
        }

        override fun frames(): kotlinx.coroutines.flow.Flow<Frame> =
            kotlinx.coroutines.flow.flow { for (f in inbound) emit(f) }
    }

    // ── Outbound frame discipline ─────────────────────────────────────────────

    @Test
    fun handshakeSendsBothNonces() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:nonces"), autoHandshake = true)
        val client = buildClient(transport)
        client.connect()

        val nonces = transport.outboundFrames()
            .mapNotNull { decodeToRadioOrNull(it) }
            .map { it.want_config_id }
            .filter { it != 0 }

        assertTrue(STAGE1_NONCE in nonces, "Stage 1 nonce $STAGE1_NONCE must be sent (got: $nonces)")
        assertTrue(STAGE2_NONCE in nonces, "Stage 2 nonce $STAGE2_NONCE must be sent (got: $nonces)")
        // Stage 1 must be sent before Stage 2.
        assertTrue(
            nonces.indexOf(STAGE1_NONCE) < nonces.indexOf(STAGE2_NONCE),
            "Stage 1 nonce must precede Stage 2 nonce (order: $nonces)",
        )
    }

    @Test
    fun cleanupSendsPoliteDisconnect() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:goodbye"), autoHandshake = true)
        val client = buildClient(transport)
        client.connect()
        // Snapshot frames sent during handshake so we can prove the goodbye is *added*
        // by disconnect(), not something the handshake already emitted.
        val handshakeFrames = transport.outboundFrames().size

        client.disconnect()

        val all = transport.outboundFrames()
        assertTrue(
            all.size > handshakeFrames,
            "Expected disconnect() to send at least one additional ToRadio (handshake=$handshakeFrames, total=${all.size})",
        )
        // The polite goodbye must be the *last* outbound frame — sent after all queued
        // traffic and immediately before the transport is closed (mesh.proto:
        // ToRadio.disconnect).
        val last = decodeToRadioOrNull(all.last())
        assertEquals(true, last?.disconnect, "Last outbound ToRadio must have disconnect=true (got: $last)")
    }

    // ── Audit: critical regressions ─────────────────────────────────

    /**
     * Audit P1-2 / F-3.1: firmware (PhoneAPI.cpp:202-209) interprets `Heartbeat(nonce=1)` as
     * "broadcast our nodeinfo over LoRa". Keep-alive heartbeats — both inter-stage and the
     * periodic 30 s tick — MUST use nonce=0 so the SDK never silently spams a NodeInfo
     * broadcast onto the mesh.
     */
    @Test
    fun keepaliveHeartbeatsUseNonceZero() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:heartbeat-nonce"), autoHandshake = true)
        val client = buildClient(transport)
        client.connect()

        val heartbeatNonces = transport.outboundFrames()
            .mapNotNull { decodeToRadioOrNull(it) }
            .mapNotNull { it.heartbeat }
            .map { it.nonce }

        assertTrue(heartbeatNonces.isNotEmpty(), "Expected at least one heartbeat to be sent")
        assertTrue(
            heartbeatNonces.all { it == 0 },
            "Every keep-alive heartbeat nonce must be 0 (firmware overloads non-zero values; nonce=1 triggers a LoRa NodeInfo broadcast); got: $heartbeatNonces",
        )
    }

    /**
     * Audit §1.5: a `config_complete_id` that matches neither Stage 1 nor Stage 2 must fail
     * the handshake with `MeshtasticException.Protocol`, not be silently dropped.
     */
    @Test
    fun stage1MismatchedCompleteIdFailsProtocol() = runTest {
        val transport = MismatchedStage1Transport()
        val client = buildClient(transport)

        val connectResult = backgroundScope.async { runCatching { client.connect() } }

        client.connection.first { it is ConnectionState.Configuring }
        runCurrent()

        val ex = connectResult.await().exceptionOrNull()
        assertIs<MeshtasticException.Protocol>(ex)
        assertTrue(
            ex.message?.contains("Stage 1 config_complete_id mismatch") == true,
            "Expected Stage 1 mismatch diagnostic, got: ${ex.message}",
        )
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    private inner class MismatchedStage1Transport : RadioTransport {
        override val identity = TransportIdentity("fake:stage1-mismatch")
        private val _state = kotlinx.coroutines.flow.MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: kotlinx.coroutines.flow.StateFlow<TransportState> = _state
        private var inbound = kotlinx.coroutines.channels.Channel<Frame>(kotlinx.coroutines.channels.Channel.UNLIMITED)

        override suspend fun connect() {
            inbound = kotlinx.coroutines.channels.Channel(kotlinx.coroutines.channels.Channel.UNLIMITED)
            _state.value = TransportState.Connecting
            _state.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            inbound.close()
            _state.value = TransportState.Disconnected
        }

        override suspend fun send(frame: Frame) {
            val to = decodeToRadioOrNull(frame) ?: return
            if (to.want_config_id == STAGE1_NONCE) {
                inbound.trySend(encodeFromRadio(FromRadio(my_info = MyNodeInfo(my_node_num = 1))))
                // WRONG nonce echoed back.
                inbound.trySend(encodeFromRadio(FromRadio(config_complete_id = 0xDEAD)))
            }
        }

        override fun frames(): kotlinx.coroutines.flow.Flow<Frame> =
            kotlinx.coroutines.flow.flow { for (f in inbound) emit(f) }
    }

    /**
     * Audit §1.6: `FromRadio.rebooted = true` mid-handshake must fail the pending connect with
     * Protocol, emit `MeshEvent.DeviceRebooted`, and tear the session down to Disconnected.
     */
    @Test
    fun deviceRebootMidHandshakeFailsProtocolAndEmitsEvent() = runTest {
        val transport = RebootingTransport()
        val client = buildClient(transport)

        val events = mutableListOf<MeshEvent>()
        val eventJob = backgroundScope.launch {
            client.events.collect { events.add(it) }
        }

        val connectResult = backgroundScope.async { runCatching { client.connect() } }
        client.connection.first { it is ConnectionState.Configuring }
        runCurrent()

        val ex = connectResult.await().exceptionOrNull()
        assertIs<MeshtasticException.Protocol>(ex)
        assertTrue(
            ex.message?.contains("rebooted") == true,
            "Expected reboot diagnostic, got: ${ex.message}",
        )
        assertEquals(ConnectionState.Disconnected, client.connection.value)
        assertTrue(
            events.any { it is MeshEvent.DeviceRebooted },
            "Expected MeshEvent.DeviceRebooted to be emitted (got: $events)",
        )

        eventJob.cancel()
    }

    private inner class RebootingTransport : RadioTransport {
        override val identity = TransportIdentity("fake:rebooting")
        private val _state = kotlinx.coroutines.flow.MutableStateFlow<TransportState>(TransportState.Disconnected)
        override val state: kotlinx.coroutines.flow.StateFlow<TransportState> = _state
        private var inbound = kotlinx.coroutines.channels.Channel<Frame>(kotlinx.coroutines.channels.Channel.UNLIMITED)

        override suspend fun connect() {
            inbound = kotlinx.coroutines.channels.Channel(kotlinx.coroutines.channels.Channel.UNLIMITED)
            _state.value = TransportState.Connecting
            _state.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            inbound.close()
            _state.value = TransportState.Disconnected
        }

        override suspend fun send(frame: Frame) {
            val to = decodeToRadioOrNull(frame) ?: return
            if (to.want_config_id == STAGE1_NONCE) {
                inbound.trySend(encodeFromRadio(FromRadio(rebooted = true)))
            }
        }

        override fun frames(): kotlinx.coroutines.flow.Flow<Frame> =
            kotlinx.coroutines.flow.flow { for (f in inbound) emit(f) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun encodeFromRadio(fromRadio: FromRadio): Frame = fromRadio.toFrame()

    private fun decodeToRadioOrNull(frame: Frame): ToRadio? {
        val bytes = frame.bytes.toByteArray()
        if (bytes.size < 4) return null
        return runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull()
    }

    private companion object {
        const val STAGE1_NONCE = 69420
        const val STAGE2_NONCE = 69421
        const val STAGE1_TIMEOUT_MS = 20_000L
        const val STAGE2_TIMEOUT_MS = 60_000L
    }
}
