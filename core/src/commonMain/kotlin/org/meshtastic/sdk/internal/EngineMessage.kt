/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.meshtastic.proto.MeshPacket
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.MessageId
import org.meshtastic.sdk.SendState
import org.meshtastic.sdk.TransportState
import kotlin.time.Duration

/**
 * Handshake stage names (internal state machine).
 */
internal enum class HandshakeStage {
    Idle,
    Stage1Draining,
    Stage1Settling,
    Stage2Draining,
    SeedingSession,
    Ready,
    ;

    fun toDisplayName(): String = when (this) {
        Idle -> "Idle"
        Stage1Draining -> "Stage 1"
        Stage1Settling -> "Stage 1 (settling)"
        Stage2Draining -> "Stage 2"
        SeedingSession -> "Seeding Session"
        Ready -> "Ready"
    }
}

/**
 * Messages that the engine actor processes from its inbox.
 *
 * All state mutation happens on the engine coroutine; these messages are the only entry points
 * that mutate state. No other code path writes to shared state.
 *
 * Per SPEC engine-actor.md: lifecycle messages ([Connect], [Disconnect]) and outbound [Send] /
 * admin messages are **never dropped**. [FrameRx] may be dropped under extreme inbox pressure
 * once backpressure triage is implemented.
 */
internal sealed interface EngineMessage {

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Initiate connection. The [deferred] is completed on success or failed on error.
     *
     * Posted by [RadioClient.connect]; engine completes it when [ConnectionState.Connected] is
     * reached (or fails it on terminal error).
     */
    data class Connect(val deferred: CompletableDeferred<Unit>) : EngineMessage

    /**
     * Graceful or error-triggered disconnection request.
     *
     * @param cause if non-null, the engine transitions to [ConnectionState.Reconnecting] with
     *   this cause and attempts exponential-backoff reconnect. For `null`, the engine
     *   transitions directly to [ConnectionState.Disconnected] with no reconnect.
     */
    data class Disconnect(val cause: MeshtasticException? = null) : EngineMessage

    // ── Inbound ────────────────────────────────────────────────────────────

    /** A framed packet received from the transport. */
    data class FrameRx(val frame: Frame) : EngineMessage

    /** Transport connection state changed (posted by the transport-state observer coroutine). */
    data class TransportStateChanged(val state: TransportState) : EngineMessage

    // ── Outbound ───────────────────────────────────────────────────────────

    /**
     * User requested to enqueue a packet for transmission.
     *
     * The [stateFlow] is owned by the [MessageHandle] returned to the caller; the engine
     * updates it as the packet progresses through its lifecycle.
     */
    data class Send(val packet: MeshPacket, val id: MessageId, val stateFlow: MutableStateFlow<SendState>) :
        EngineMessage

    /** Cancel a previously queued send before it leaves the host. Idempotent. */
    data class CancelHandle(val id: MessageId) : EngineMessage

    /**
     * Fire-and-forget admin message (no RPC correlation, no MessageHandle). Posted by
     * [MeshEngine.sendAdmin] so that packet preparation — which reads and prunes the
     * actor-owned per-node session-passkey map — always runs on the engine actor (ADR-002),
     * never on the caller's coroutine.
     */
    data class AdminFireAndForget(
        val adminMsg: org.meshtastic.proto.AdminMessage,
        val wantResponse: Boolean,
        val to: Int,
    ) : EngineMessage

    // ── Timers ─────────────────────────────────────────────────────────────

    /** Periodic heartbeat tick (every 30 s per protocol.md §16). */
    data object HeartbeatTick : EngineMessage

    /**
     * Liveness watchdog tick (audit §2.2): decrements [MeshEngine.livenessBudgetMs]; when the
     * budget reaches 0 the engine treats the link as silently dead (e.g. half-open TCP socket,
     * NAT timeout) and tears the session down to `Disconnected` via a recoverable transport
     * error. The budget is reset in `handleFrameRx` on every decoded `FromRadio`.
     */
    data object LivenessTick : EngineMessage

    /** Periodic presence check: scans nodes and emits WentOffline/CameOnline events. */
    data object PresenceCheckTick : EngineMessage

    /**
     * Handshake stage timed out.
     *
     * @param stage the handshake stage that timed out.
     */
    data class HandshakeTimeout(val stage: HandshakeStage) : EngineMessage

    /**
     * The 100 ms inter-stage settle timer (after Stage 1 sentinel received) has elapsed.
     * The engine should now send the inter-stage heartbeat.
     */
    data object HandshakeStage1SettleComplete : EngineMessage

    /**
     * The 100 ms post-heartbeat settle timer has elapsed.
     * The engine should now send `want_config_id = 69421` to begin Stage 2.
     */
    data object HandshakeHeartbeatSettleComplete : EngineMessage

    /** An in-flight admin RPC did not receive a response within its deadline. */
    data class AdminTimeout(val requestId: Int) : EngineMessage

    // ── RPC dispatch (admin / telemetry / routing) ──────────────────

    /**
     * Submit a typed RPC request whose response is correlated by [requestId] (the wire packet
     * `id`). The actor:
     *  1. Registers ([requestId], [kind]) in [CommandDispatcher].
     *  2. Sends [packet] via the outbound writer.
     *  3. Arms a [timeout] timer that posts [PendingResponseTimeout] on expiry.
     *
     * The [deferred] is completed by the dispatcher when a matching response arrives, the timer
     * fires, or the engine winds down. Type erased here ([AdminResult] of [Any]) so the
     * EngineMessage hierarchy stays simple; the dispatcher preserves type safety internally.
     */
    data class PostRpc(
        val packet: MeshPacket,
        val requestId: Int,
        val kind: ResponseKind<*>,
        val deferred: CompletableDeferred<AdminResult<Any?>>,
        val timeout: Duration,
    ) : EngineMessage

    /** Timeout fired for an in-flight RPC registered via [PostRpc]. */
    data class PendingResponseTimeout(val requestId: Int) : EngineMessage

    /**
     * P1-3: Stage 1 want_config_id retry tick. Fires once at `STAGE1_RETRY_MS` after the
     * initial want_config_id was sent. If the engine is still in
     * [HandshakeStage.Stage1Draining] with no `pendingMyInfo` observed, the engine re-sends
     * `want_config_id = NONCE_STAGE1` once before the original 20 s budget runs out.
     */
    data object Stage1RetryWantConfig : EngineMessage

    /**
     * Per-send ACK timeout fired (R-P0-6). Posted by the engine after the configured
     * `sendTimeout` elapses with no `Routing` ACK observed for [id]. The handler transitions
     * the in-flight handle to `SendState.Failed(SendFailure.AckTimeout)` if it is still
     * pending.
     */
    data class SendAckTimeout(val id: MessageId) : EngineMessage

    // ── Auto-reconnect (DX-7 / COV-4) ──────────────────────────────────────

    /** Backoff window expired; supervisor should attempt the next reconnect cycle. */
    data class ReconnectTick(val cause: MeshtasticException, val attempt: Int) : EngineMessage

    /** Outcome of a reconnect attempt initiated by [ReconnectTick]. */
    data class ReconnectAttemptResult(val success: Boolean, val cause: MeshtasticException?) : EngineMessage
}
