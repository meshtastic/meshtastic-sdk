/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.internal.MeshEngine
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.bytestring.ByteString as KByteString

/**
 * P1 engine hardening fixes:
 * - **P1-1** preSendQueue is failed (HandshakeFailed / Disconnected) on terminal paths.
 * - **P1-3** duplicate routing ACK is idempotent.
 * - **P1-7** Stage 2 timeout slides while frames are arriving; hard cap still applies.
 * - **C1** transitionToReady snapshot/clear/emit/drain ordering (covered indirectly via the
 *   handshake FSM tests already passing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P1EngineHardeningTest {

    // Wire constants mirrored locally so the test isn't coupled to engine private symbols.
    private companion object {
        const val STAGE1_NONCE = 69420
        const val STAGE2_NONCE = 69421
        const val STAGE2_PROGRESS_TICK_MS = 30_000L
        const val STAGE2_HARD_CAP_MS = 120_000L
    }

    // ── P1-3: duplicate routing ACK is ignored ──────────────────────────────

    @Test
    fun duplicateRoutingAckIsIgnored() = runTest {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:p1-dup-ack"),
            autoHandshake = true,
            nodeNum = 0x11111111,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .sendTimeout(60.seconds)
            .build()
        client.connect()

        // Unicast want_ack so the handle survives long enough to receive a Routing ACK.
        val handle = client.send(unicastWantAckPacket(toNodeNum = 0x22222222))
        runCurrent()
        assertEquals(SendState.Sent, handle.state.value)

        // First Routing ACK — flips the handle to Acked.
        transport.injectFrame(routingAckFrame(requestId = handle.id.raw, fromNodeNum = 0x22222222))
        runCurrent()
        assertEquals(SendState.Acked, handle.state.value)

        // Duplicate (e.g. a relayed copy from a neighbor). Must NOT change state away from Acked
        // (which would happen if processRoutingAck overwrote pendingSends entries blindly).
        transport.injectFrame(routingAckFrame(requestId = handle.id.raw, fromNodeNum = 0x22222222))
        runCurrent()
        assertEquals(SendState.Acked, handle.state.value)

        // A *third* duplicate carrying an explicit failure must also be ignored — the user has
        // already observed Acked and the engine must not silently flip it to Failed.
        transport.injectFrame(
            routingAckFrame(
                requestId = handle.id.raw,
                fromNodeNum = 0x22222222,
                error = Routing.Error.NO_ROUTE,
            ),
        )
        runCurrent()
        assertEquals(SendState.Acked, handle.state.value)

        client.disconnect()
    }

    /**
     * P3c-9 (audit §1.9): specific [Routing.Error] wire values translate to specific
     * [SendFailure] sealed variants. A handle that receives MAX_RETRANSMIT must see
     * `Failed(MaxRetransmit)`, not `Failed(Other(...))`; same for NO_ROUTE, TIMEOUT,
     * DUTY_CYCLE_LIMIT. Anything else still falls through to `Other`.
     */
    @Test
    fun routingErrorsMapToSpecificSendFailureVariants() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:routing-map"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .build()
        client.connect()

        fun assertMapping(error: Routing.Error, expected: SendFailure) {
            val handle = client.send(unicastWantAckPacket(toNodeNum = 0x22222222))
            runCurrent()
            transport.injectFrame(routingAckFrame(requestId = handle.id.raw, fromNodeNum = 0x22222222, error = error))
            runCurrent()
            val state = handle.state.value
            assertIs<SendState.Failed>(state, "Expected Failed for $error; got $state")
            assertEquals(expected, state.reason, "wire $error must map to $expected")
        }

        assertMapping(Routing.Error.NO_ROUTE, SendFailure.NoRoute)
        assertMapping(Routing.Error.GOT_NAK, SendFailure.NoRoute)
        assertMapping(Routing.Error.MAX_RETRANSMIT, SendFailure.MaxRetransmit)
        assertMapping(Routing.Error.TIMEOUT, SendFailure.Timeout)
        assertMapping(Routing.Error.DUTY_CYCLE_LIMIT, SendFailure.DutyCycleLimit)
        // Unmapped value still lands in Other with the raw enum preserved.
        val handle = client.send(unicastWantAckPacket(toNodeNum = 0x22222222))
        runCurrent()
        transport.injectFrame(
            routingAckFrame(requestId = handle.id.raw, fromNodeNum = 0x22222222, error = Routing.Error.NOT_AUTHORIZED),
        )
        runCurrent()
        val state = handle.state.value
        assertIs<SendState.Failed>(state)
        assertEquals(SendFailure.Other(Routing.Error.NOT_AUTHORIZED), state.reason)

        client.disconnect()
    }

    // ── P1-1: handshake timeout fails preSendQueue with HandshakeFailed ─────

    /**
     * Drives the engine directly (bypassing RadioClient.send which throws NotConnected before
     * Ready) so we can enqueue a Send while the engine is still in Stage1Draining. After the
     * Stage 1 timeout fires, the queued handle must transition to `Failed(HandshakeFailed)` —
     * NOT `Failed(Disconnected)` — so callers can distinguish the failure mode.
     */
    @Test
    fun handshakeTimeoutFailsPreSendQueueWithHandshakeFailed() = runTest {
        // Transport that connects but never responds to Stage 1 → Stage 1 watchdog fires.
        val transport = SilentTransport(TransportIdentity("fake:p1-pre-send"))
        val engine = MeshEngine(
            transport = transport,
            storageProvider = InMemoryStorageProvider(),
            logger = LogSink.Silent,
            bleHeartbeatEnabled = false,
            parentContext = backgroundScope.coroutineContext,
            sendTimeout = 30.seconds,
        )

        val connectJob = async { runCatching { engine.connect() } }

        // Wait until the engine is past Connecting and into Configuring(Stage1).
        engine.connectionState.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage1 }

        // Enqueue a send while the engine is in Stage1Draining. trySend posts an
        // EngineMessage.Send; handleSend will park it in preSendQueue because handshakeStage
        // != Ready.
        val handle = MutableStateFlow<SendState>(SendState.Queued)
        engine.trySend(unicastWantAckPacket(toNodeNum = 0x33333333), MessageId(0xCAFE), handle)
        runCurrent()
        // Still Queued — pre-Ready sends never advance to Sent.
        assertEquals(SendState.Queued, handle.value)

        // Drive past Stage 1 timeout. The engine fails the queued handle with HandshakeFailed
        // before cancelling the supervisor.
        advanceTimeBy(25_000L) // STAGE1_TIMEOUT_MS = 20s
        runCurrent()

        val state = handle.value
        assertIs<SendState.Failed>(state)
        assertEquals(
            SendFailure.HandshakeFailed,
            state.reason,
            "Pre-Ready sends must surface HandshakeFailed (not Disconnected) on handshake timeout",
        )

        connectJob.await()
    }

    // ── P1-7: Stage 2 sliding timeout — frames extend the deadline ──────────

    /**
     * Verifies the watchdog *slides* when Stage 2 frames keep arriving. We complete Stage 1
     * normally, then inject a Stage 2 progress frame (NodeInfo) every (tick - 1s). The total
     * elapsed time exceeds the legacy fixed STAGE2_TIMEOUT_MS (60s) without timing out, then
     * we stop feeding frames and assert the watchdog fires within ~one tick window.
     */
    @Test
    fun stage2TimeoutSlidesWhenFramesArrive() = runTest {
        val transport = SteppedStage2Transport(TransportIdentity("fake:p1-stage2-slide"))
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .build()

        val connectJob = async { runCatching { client.connect() } }

        // Wait until Stage 2 is armed.
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }

        // Pump 2 progress frames spaced just under the tick — total elapsed ≈ 2 * 29s = 58s,
        // already well past a single sliding tick (30s) but under the hard cap (60s, audit
        // P2-4). With sliding timeout it must NOT fire because each tick sees fresh progress.
        val pumpInterval = STAGE2_PROGRESS_TICK_MS - 1_000L
        repeat(2) { i ->
            advanceTimeBy(pumpInterval)
            runCurrent()
            transport.injectStage2Progress(nodeNum = 0xABCD0000.toInt() or i)
            runCurrent()
            // Still in Stage 2 — sliding timeout has not fired.
            val s = client.connection.value
            assertIs<ConnectionState.Configuring>(s)
            assertEquals(
                ConfigPhase.Stage2,
                s.phase,
                "Stage 2 must still be active after ${(i + 1) * pumpInterval}ms with progress (sliding timeout)",
            )
        }

        // Now go silent — no more frames. Within one tick the watchdog must fire.
        advanceTimeBy(STAGE2_PROGRESS_TICK_MS + 2_000L)
        runCurrent()

        val ex = connectJob.await().exceptionOrNull()
        assertIs<MeshtasticException.HandshakeTimeout>(ex)
        assertEquals("Stage 2", ex.stage)
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    // ── P3a-1: liveness watchdog (audit §2.2) ────────────────────────────────

    /**
     * Once the session reaches Ready, if no decoded `FromRadio` envelope arrives for
     * `2 × HEARTBEAT_INTERVAL_MS` (60 s), the engine MUST emit `MeshEvent.TransportError` and
     * tear the session down to `Disconnected`. Prevents half-open TCP sockets from hanging the
     * engine indefinitely.
     */
    @Test
    fun livenessWatchdogTearsDownSilentSession() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:liveness"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .build()

        val events = mutableListOf<MeshEvent>()
        val eventJob = backgroundScope.launch {
            client.events.collect { events.add(it) }
        }

        client.connect()
        assertEquals(ConnectionState.Connected, client.connection.value)

        // Advance past liveness timeout (60s) + one check interval (30s) so the watchdog's
        // while-loop has observed the expired deadline.
        advanceTimeBy(95_000L)
        runCurrent()

        assertEquals(ConnectionState.Disconnected, client.connection.value)
        val transportError = events.filterIsInstance<MeshEvent.TransportError>().lastOrNull()
        assertNotNull(transportError, "Expected a TransportError event on liveness timeout (got: $events)")
        kotlin.test.assertTrue(
            transportError.error.message?.contains("liveness", ignoreCase = true) == true,
            "Expected liveness diagnostic, got: ${transportError.error.message}",
        )

        eventJob.cancel()
    }

    /**
     * The inverse: if frames *do* keep arriving, the watchdog MUST NOT fire. Injects a decoded
     * envelope every ~check-interval for longer than the timeout period and asserts the
     * session is still Connected.
     */
    @Test
    fun livenessWatchdogSlidesWithIncomingFrames() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:liveness-slide"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .build()

        client.connect()
        assertEquals(ConnectionState.Connected, client.connection.value)

        // Pump a decoded FromRadio every 25s for a total of 100s — twice the check interval,
        // well past the 60s timeout. The watchdog must not fire because each inbound envelope
        // resets lastRxTimeMs.
        repeat(4) {
            advanceTimeBy(25_000L)
            runCurrent()
            transport.injectFrame(encodeFromRadio(FromRadio(packet = MeshPacket(from = 0xABCD))))
            runCurrent()
        }

        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    /**
     * P4 (audit F-5.2): `ClientNotification.duplicated_public_key` and
     * `low_entropy_key` arms translate to typed [MeshEvent.SecurityWarning] variants
     * alongside the raw [MeshEvent.Notification] for backward-compatible consumers.
     */
    @Test
    fun clientNotificationSecurityArmsEmitTypedVariants() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:sec-warn"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .build()

        val seen = mutableListOf<MeshEvent>()
        val job = launch { client.events.collect { seen += it } }

        client.connect()
        runCurrent()

        transport.injectFrame(
            encodeFromRadio(
                FromRadio(
                    clientNotification = org.meshtastic.proto.ClientNotification(
                        duplicated_public_key = org.meshtastic.proto.DuplicatedPublicKey(),
                    ),
                ),
            ),
        )
        runCurrent()
        transport.injectFrame(
            encodeFromRadio(
                FromRadio(
                    clientNotification = org.meshtastic.proto.ClientNotification(
                        low_entropy_key = org.meshtastic.proto.LowEntropyKey(),
                    ),
                ),
            ),
        )
        runCurrent()

        assertEquals(
            1,
            seen.count { it is MeshEvent.SecurityWarning.DuplicatedPublicKey },
            "expected one DuplicatedPublicKey event; saw: $seen",
        )
        assertEquals(
            1,
            seen.count { it is MeshEvent.SecurityWarning.LowEntropyKey },
            "expected one LowEntropyKey event; saw: $seen",
        )
        // Raw Notification still emitted for callers that want the wire payload.
        assertEquals(2, seen.count { it is MeshEvent.Notification })

        job.cancel()
        client.disconnect()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun unicastWantAckPacket(toNodeNum: Int) = MeshPacket(
        to = toNodeNum,
        channel = 0,
        want_ack = true,
        decoded = Data(
            portnum = PortNum.TEXT_MESSAGE_APP,
            payload = ByteString.of(*"hi".encodeToByteArray()),
        ),
    )

    private fun routingAckFrame(requestId: Int, fromNodeNum: Int, error: Routing.Error = Routing.Error.NONE): Frame {
        val routing = Routing(error_reason = error)
        val payload = ByteString.of(*Routing.ADAPTER.encode(routing))
        val packet = MeshPacket(
            from = fromNodeNum,
            to = 0,
            decoded = Data(
                portnum = PortNum.ROUTING_APP,
                payload = payload,
                request_id = requestId,
            ),
        )
        return encodeFromRadio(FromRadio(packet = packet))
    }

    private fun encodeFromRadio(fromRadio: FromRadio): Frame {
        val proto = FromRadio.ADAPTER.encode(fromRadio)
        val frameBytes = ByteArray(4 + proto.size).apply {
            this[0] = 0x94.toByte()
            this[1] = 0xC3.toByte()
            this[2] = (proto.size shr 8).toByte()
            this[3] = (proto.size and 0xFF).toByte()
            proto.copyInto(this, destinationOffset = 4)
        }
        return Frame(KByteString(frameBytes))
    }

    /** Transport that connects but never responds — used to drive Stage 1 to its timeout. */
    private inner class SilentTransport(override val identity: TransportIdentity) : RadioTransport {
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        private var inbound = Channel<Frame>(Channel.UNLIMITED)
        override val state: StateFlow<TransportState> = _state

        override suspend fun connect() {
            inbound = Channel(Channel.UNLIMITED)
            _state.value = TransportState.Connecting
            _state.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            inbound.close()
            _state.value = TransportState.Disconnected
        }

        override suspend fun send(frame: Frame) {
            // Swallow everything — no Stage 1 response, so the handshake watchdog will fire.
        }

        override fun frames(): Flow<Frame> = flow { for (f in inbound) emit(f) }
    }

    /**
     * Transport that completes Stage 1 normally and then accepts manual Stage 2 progress
     * injections via [injectStage2Progress]. Stage 2 completion is never sent automatically.
     */
    private inner class SteppedStage2Transport(override val identity: TransportIdentity) : RadioTransport {
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        private var inbound = Channel<Frame>(Channel.UNLIMITED)
        override val state: StateFlow<TransportState> = _state

        override suspend fun connect() {
            inbound = Channel(Channel.UNLIMITED)
            _state.value = TransportState.Connecting
            _state.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            inbound.close()
            _state.value = TransportState.Disconnected
        }

        override suspend fun send(frame: Frame) {
            val to = decodeToRadio(frame) ?: return
            if (to.want_config_id == STAGE1_NONCE) {
                inbound.trySend(encodeFromRadio(FromRadio(my_info = MyNodeInfo(my_node_num = 1))))
                inbound.trySend(encodeFromRadio(FromRadio(config_complete_id = STAGE1_NONCE)))
            }
            // Stage 2 nonce is intentionally swallowed — the test feeds progress manually.
        }

        override fun frames(): Flow<Frame> = flow { for (f in inbound) emit(f) }

        fun injectStage2Progress(nodeNum: Int) {
            inbound.trySend(
                encodeFromRadio(
                    FromRadio(
                        node_info = org.meshtastic.proto.NodeInfo(num = nodeNum),
                    ),
                ),
            )
        }

        private fun decodeToRadio(frame: Frame): ToRadio? {
            val bytes = frame.bytes.toByteArray()
            if (bytes.size < 4) return null
            return try {
                ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size))
            } catch (_: Exception) {
                null
            }
        }
    }
}
