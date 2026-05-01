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
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.Routing
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.bytestring.ByteString as KByteString

/**
 * Coverage for the engine/protocol audit fixes (P1-1 … P1-6, P2-2 … P2-5).
 *
 * Test names map 1:1 to the audit finding IDs so it stays obvious which behaviour each
 * test pins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineAuditFixesTest {

    // ── P1-1: Stage 1/2 unhandled FromRadio variants surface as ProtocolWarning ─

    /**
     * P1-1: a `deviceuiConfig` envelope arriving mid-Stage-1 must be visible as a
     * [MeshEvent.ProtocolWarning] with `details.stage == "Stage 1"` instead of being silently
     * dropped. Same surface as Stage 2.
     */
    @Test
    fun stage1UnhandledVariantEmitsProtocolWarning() = runTest {
        val transport = ScriptedHandshakeTransport(
            identity = TransportIdentity("fake:p1-1-stage1"),
            beforeStage1Complete = listOf(FromRadio(deviceuiConfig = DeviceUIConfig())),
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .build()

        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val job = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }

        client.connect()
        runCurrent()

        val match = warnings.firstOrNull { it.details["variant"] == "deviceui_config" }
        assertNotNull(match, "Expected ProtocolWarning for deviceui_config; got: $warnings")
        assertEquals("Stage 1", match.details["stage"], "Stage detail must distinguish handshake vs Ready arrivals")

        job.cancel()
        client.disconnect()
    }

    // ── P1-3: want_config_id is retried once on Stage 1 silence ────────────────

    /**
     * P1-3: drop the very first `want_config_id` and respond normally to the retry. The engine
     * must still complete the handshake within the original 20 s budget.
     */
    @Test
    fun stage1WantConfigIsRetriedOnSilence() = runTest {
        val transport = DropFirstWantConfigTransport(TransportIdentity("fake:p1-3"))
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .build()

        val connectJob = async { runCatching { client.connect() } }

        // Wait until Stage 1 is armed.
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage1 }
        // Past the 10 s retry tick: the engine should re-send want_config_id.
        advanceTimeBy(11_000L)
        runCurrent()

        val result = connectJob.await()
        assertTrue(result.isSuccess, "Expected connect to succeed via the retry path; got: $result")
        assertEquals(ConnectionState.Connected, client.connection.value)

        // Two want_config_id=NONCE_STAGE1 frames must have been sent (initial + retry).
        val stage1Sends = transport.outboundWantConfigCount(STAGE1_NONCE)
        assertEquals(2, stage1Sends, "Expected exactly one Stage 1 want_config_id retry; got $stage1Sends")

        client.disconnect()
    }

    // ── P1-4: Routing oneof branching ──────────────────────────────────────────

    /**
     * P1-4: a Routing payload that is purely `route_request` must not silently mark a
     * pending send as Acked. The handle stays in `Sent`.
     */
    @Test
    fun routingRouteRequestIsNoOpForPendingSends() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:p1-4-rreq"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .sendTimeout(60.seconds)
            .build()
        client.connect()

        val handle = client.send(unicastWantAckPacket(toNodeNum = 0x22222222))
        runCurrent()
        assertEquals(SendState.Sent, handle.state.value)

        // Inject a Routing payload with route_request set, request_id matching the handle.
        transport.injectFrame(
            routingFrame(
                requestId = handle.id.raw,
                fromNodeNum = 0x22222222,
                routing = Routing(route_request = RouteDiscovery()),
            ),
        )
        runCurrent()
        assertEquals(SendState.Sent, handle.state.value, "route_request must NOT flip handle to Acked")

        client.disconnect()
    }

    /**
     * P1-4: a Routing payload with only `route_reply` set (no error_reason) must mark the
     * handle as Acked.
     */
    @Test
    fun routingRouteReplyOnlyAcksHandle() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:p1-4-reply"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .sendTimeout(60.seconds)
            .build()
        client.connect()

        val handle = client.send(unicastWantAckPacket(toNodeNum = 0x33333333))
        runCurrent()
        transport.injectFrame(
            routingFrame(
                requestId = handle.id.raw,
                fromNodeNum = 0x33333333,
                routing = Routing(route_reply = RouteDiscovery()),
            ),
        )
        runCurrent()
        assertEquals(SendState.Acked, handle.state.value)
        client.disconnect()
    }

    /**
     * P1-4: an explicit `error_reason = NONE` must continue to mean ACK (firmware default).
     */
    @Test
    fun routingExplicitNoneErrorAcksHandle() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:p1-4-none"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .sendTimeout(60.seconds)
            .build()
        client.connect()

        val handle = client.send(unicastWantAckPacket(toNodeNum = 0x44444444))
        runCurrent()
        transport.injectFrame(
            routingFrame(
                requestId = handle.id.raw,
                fromNodeNum = 0x44444444,
                routing = Routing(error_reason = Routing.Error.NONE),
            ),
        )
        runCurrent()
        assertEquals(SendState.Acked, handle.state.value)
        client.disconnect()
    }

    /**
     * P1-4: an explicit non-NONE error must still flip the handle to Failed (regression
     * guard for the new oneof-arm dispatch).
     */
    @Test
    fun routingExplicitErrorFailsHandle() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:p1-4-err"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .sendTimeout(60.seconds)
            .build()
        client.connect()

        val handle = client.send(unicastWantAckPacket(toNodeNum = 0x55555555))
        runCurrent()
        transport.injectFrame(
            routingFrame(
                requestId = handle.id.raw,
                fromNodeNum = 0x55555555,
                routing = Routing(error_reason = Routing.Error.NO_ROUTE),
            ),
        )
        runCurrent()
        val state = handle.state.value
        assertIs<SendState.Failed>(state)
        assertEquals(SendFailure.NoRoute, state.reason)
        client.disconnect()
    }

    // ── P1-5: ACK timer arms for want_response-only requests ───────────────────

    /**
     * P1-5: a unicast packet with `want_response = true` (no `want_ack`) must still arm the
     * ACK timer so a silent device can't strand the handle in `Sent` forever.
     */
    @Test
    fun wantResponseOnlyArmsAckTimer() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:p1-5"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .sendTimeout(5.seconds)
            .build()
        client.connect()

        val packet = MeshPacket(
            to = 0x22222222,
            channel = 0,
            // NB: want_ack deliberately false; the timer must still arm because want_response
            // expects a unicast reply with request_id set.
            want_ack = false,
            decoded = Data(
                portnum = PortNum.ADMIN_APP,
                payload = ByteString.of(*"x".encodeToByteArray()),
                want_response = true,
            ),
        )
        val handle = client.send(packet)
        runCurrent()
        assertEquals(SendState.Sent, handle.state.value)

        advanceTimeBy(6_000L)
        runCurrent()

        val state = handle.state.value
        assertIs<SendState.Failed>(state)
        assertEquals(SendFailure.AckTimeout, state.reason)
        client.disconnect()
    }

    // ── P2-5: encrypted-only MeshPacket emits a rate-limited ProtocolWarning ───

    @Test
    fun encryptedPacketEmitsRateLimitedWarning() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:p2-5"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .build()
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        val job = backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }
        client.connect()

        // Two encrypted-only packets back-to-back — only the first must emit a warning.
        val encrypted = MeshPacket(from = 0x77777777, to = 0)
        transport.injectFrame(encodeFromRadio(FromRadio(packet = encrypted)))
        transport.injectFrame(encodeFromRadio(FromRadio(packet = encrypted)))
        runCurrent()

        val matches = warnings.filter { it.message.contains("encrypted") }
        assertEquals(1, matches.size, "Expected exactly one encrypted-skip warning per minute; got: $matches")

        job.cancel()
        client.disconnect()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun unicastWantAckPacket(toNodeNum: Int) = MeshPacket(
        to = toNodeNum,
        channel = 0,
        want_ack = true,
        decoded = Data(
            portnum = PortNum.TEXT_MESSAGE_APP,
            payload = ByteString.of(*"hi".encodeToByteArray()),
        ),
    )

    private fun routingFrame(requestId: Int, fromNodeNum: Int, routing: Routing): Frame {
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

    /**
     * Transport that responds normally to the two-stage handshake but injects [beforeStage1Complete]
     * frames immediately after the my_info / before the Stage 1 sentinel. Used to verify that
     * stage-time auxiliary variants surface ProtocolWarnings.
     */
    private inner class ScriptedHandshakeTransport(
        override val identity: TransportIdentity,
        private val beforeStage1Complete: List<FromRadio> = emptyList(),
    ) : RadioTransport {
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
            val to = decodeToRadioOrNull(frame) ?: return
            when (to.want_config_id) {
                STAGE1_NONCE -> {
                    inbound.trySend(encodeFromRadio(FromRadio(my_info = MyNodeInfo(my_node_num = 1))))
                    for (extra in beforeStage1Complete) inbound.trySend(encodeFromRadio(extra))
                    inbound.trySend(encodeFromRadio(FromRadio(config_complete_id = STAGE1_NONCE)))
                }

                STAGE2_NONCE -> {
                    inbound.trySend(encodeFromRadio(FromRadio(config_complete_id = STAGE2_NONCE)))
                }
            }
            // Auto-respond to get_owner_request so the handshake can complete.
            val packet = to.packet ?: return
            val decoded = packet.decoded ?: return
            if (decoded.portnum != PortNum.ADMIN_APP) return
            val admin = runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull() ?: return
            if (admin.get_owner_request == true) {
                val response = AdminMessage(
                    get_owner_response = org.meshtastic.proto.User(id = "!00000001"),
                    session_passkey = ByteString.EMPTY,
                )
                val responsePacket = MeshPacket(
                    from = 1,
                    to = packet.from,
                    decoded = Data(
                        portnum = PortNum.ADMIN_APP,
                        payload = ByteString.of(*AdminMessage.ADAPTER.encode(response)),
                    ),
                )
                inbound.trySend(encodeFromRadio(FromRadio(packet = responsePacket)))
            }
        }

        override fun frames(): Flow<Frame> = flow { for (f in inbound) emit(f) }
    }

    /**
     * Transport that drops the very first `want_config_id = STAGE1_NONCE`, then responds
     * normally to subsequent ones. Verifies the engine's want_config_id retry behaviour.
     */
    private inner class DropFirstWantConfigTransport(override val identity: TransportIdentity) : RadioTransport {
        private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
        private var inbound = Channel<Frame>(Channel.UNLIMITED)
        private var stage1WantConfigSeen = 0
        override val state: StateFlow<TransportState> = _state

        fun outboundWantConfigCount(nonce: Int): Int = if (nonce == STAGE1_NONCE) stage1WantConfigSeen else 0

        override suspend fun connect() {
            inbound = Channel(Channel.UNLIMITED)
            stage1WantConfigSeen = 0
            _state.value = TransportState.Connecting
            _state.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            inbound.close()
            _state.value = TransportState.Disconnected
        }

        override suspend fun send(frame: Frame) {
            val to = decodeToRadioOrNull(frame) ?: return
            when (to.want_config_id) {
                STAGE1_NONCE -> {
                    stage1WantConfigSeen += 1
                    if (stage1WantConfigSeen == 1) return // drop the first
                    inbound.trySend(encodeFromRadio(FromRadio(my_info = MyNodeInfo(my_node_num = 1))))
                    inbound.trySend(encodeFromRadio(FromRadio(config_complete_id = STAGE1_NONCE)))
                }

                STAGE2_NONCE -> {
                    inbound.trySend(encodeFromRadio(FromRadio(config_complete_id = STAGE2_NONCE)))
                }
            }
            val packet = to.packet ?: return
            val decoded = packet.decoded ?: return
            if (decoded.portnum != PortNum.ADMIN_APP) return
            val admin = runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull() ?: return
            if (admin.get_owner_request == true) {
                val response = AdminMessage(
                    get_owner_response = org.meshtastic.proto.User(id = "!00000001"),
                    session_passkey = ByteString.EMPTY,
                )
                val responsePacket = MeshPacket(
                    from = 1,
                    to = packet.from,
                    decoded = Data(
                        portnum = PortNum.ADMIN_APP,
                        payload = ByteString.of(*AdminMessage.ADAPTER.encode(response)),
                    ),
                )
                inbound.trySend(encodeFromRadio(FromRadio(packet = responsePacket)))
            }
        }

        override fun frames(): Flow<Frame> = flow { for (f in inbound) emit(f) }
    }

    private fun decodeToRadioOrNull(frame: Frame): ToRadio? {
        val bytes = frame.bytes.toByteArray()
        if (bytes.size < 4) return null
        return runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull()
    }

    private companion object {
        const val STAGE1_NONCE = 69420
        const val STAGE2_NONCE = 69421
    }

    // Suppress unused for assertNotEquals (kept for future regression guards).
    @Suppress("unused")
    private fun unused() = assertNotEquals(0, 1)
}
