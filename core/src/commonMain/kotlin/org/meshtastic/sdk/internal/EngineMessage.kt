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
import org.meshtastic.proto.LockdownAuth
import org.meshtastic.proto.MeshPacket
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.MessageId
import org.meshtastic.sdk.QueueReceipt
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
    AwaitingLockdown,
    Stage2Draining,
    SeedingSession,
    Ready,
    ;

    fun toDisplayName(): String = when (this) {
        Idle -> "Idle"
        Stage1Draining -> "Stage 1"
        Stage1Settling -> "Stage 1 (settling)"
        AwaitingLockdown -> "Awaiting lockdown authorization"
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
 * The inbox is unlimited, so no message is dropped while the engine runs; a post after teardown
 * fails because the inbox is closed (docs/architecture/engine-actor.md, Backpressure).
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

    data class RefreshNodeDb(val generation: Long) : EngineMessage

    data class NodeDbRefreshTimeout(val request: Long) : EngineMessage

    // ── Inbound ────────────────────────────────────────────────────────────

    /** A framed packet received from the transport. */
    data class FrameRx(val frame: Frame, val generation: Long) : EngineMessage

    /** A read or write failure on a particular transport connection. */
    data class LinkFailed(val cause: MeshtasticException, val generation: Long) : EngineMessage

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

    data class PacedSend(
        val packet: MeshPacket,
        val id: MessageId,
        val generation: Long?,
        val state: MutableStateFlow<SendState>,
        val admission: CompletableDeferred<Boolean>,
        val dispatch: CompletableDeferred<Boolean>,
        val receipt: CompletableDeferred<QueueReceipt>,
        /** Completed by the outbound writer once `transport.send` has returned for this frame. */
        val written: CompletableDeferred<Unit>? = null,
    ) : EngineMessage

    data class PacedWriteCompleted(val send: PacedSend) : EngineMessage

    data class PacedQueueTimeout(val send: PacedSend) : EngineMessage

    // ── State writes from outside the actor ─────────────────────────────────

    /**
     * Apply acknowledged local writes in the order the device applied them; [done] completes once
     * applied. Skipped when [generation] is no longer the current connection's.
     */
    data class ApplyLocalWrites(
        val writes: List<LocalWrite>,
        val generation: Long,
        val done: CompletableDeferred<Unit>,
    ) : EngineMessage

    /** Channels loaded from storage for a session whose handshake reported none. */
    data class ChannelsRestored(val channels: List<org.meshtastic.proto.Channel>, val generation: Long) : EngineMessage

    /** Restart the handshake on the current link after a live renumber settled its write. */
    data object RenumberRestart : EngineMessage

    /** A first region set got QueueStatus 0; check whether an ack or my_info followed. */
    data class SilentRenumberProbe(val send: PacedSend) : EngineMessage

    /** A storage write made outside the actor failed. */
    data class StorageDegraded(val reason: String, val cause: Throwable?) : EngineMessage

    /** Cancel a previously queued send before it leaves the host. Idempotent. */
    data class CancelHandle(val id: MessageId, val expected: MutableStateFlow<SendState>? = null) : EngineMessage

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

    data class Lockdown(val auth: LockdownAuth, val generation: Long, val nodeNum: Int) : EngineMessage

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
    data class HandshakeTimeout(val stage: HandshakeStage, val attempt: Long) : EngineMessage

    /**
     * The 100 ms inter-stage settle timer (after Stage 1 sentinel received) has elapsed.
     * The engine should now send the inter-stage heartbeat.
     */
    data class HandshakeStage1SettleComplete(val attempt: Long) : EngineMessage

    /**
     * The 100 ms post-heartbeat settle timer has elapsed.
     * The engine should now send `want_config_id = 69421` to begin Stage 2.
     */
    data class HandshakeHeartbeatSettleComplete(val attempt: Long) : EngineMessage

    /** An in-flight admin RPC did not receive a response within its deadline. */
    data class AdminTimeout(val requestId: Int) : EngineMessage

    // ── RPC dispatch (admin / telemetry / routing) ──────────────────

    /**
     * Submit a typed RPC request whose response is correlated by its wire packet `id`. A zero
     * `packet.id` asks the actor to allocate one that no in-flight request holds; a caller-chosen
     * id already in flight fails the request without touching the first. The actor:
     *  1. Registers (id, [kind]) in [CommandDispatcher].
     *  2. Sends [packet] via the outbound writer.
     *  3. Arms a [timeout] timer that posts [PendingResponseTimeout] on expiry.
     *
     * The [deferred] is completed by the dispatcher when a matching response arrives, the timer
     * fires, or the engine winds down. Type erased here ([AdminResult] of [Any]) so the
     * EngineMessage hierarchy stays simple; the dispatcher preserves type safety internally.
     */
    data class PostRpc(
        val packet: MeshPacket,
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
    data class Stage1RetryWantConfig(val attempt: Long) : EngineMessage

    /**
     * Per-send ACK timeout fired (R-P0-6). Posted by the engine after the configured
     * `sendTimeout` elapses with no `Routing` ACK observed for [id]. The handler transitions
     * the in-flight handle to `SendState.Failed(SendFailure.AckTimeout)` if it is still
     * pending.
     */
    data class SendAckTimeout(val id: MessageId, val state: MutableStateFlow<SendState>) : EngineMessage

    // ── Auto-reconnect (DX-7 / COV-4) ──────────────────────────────────────

    /** Backoff window expired; supervisor should attempt the next reconnect cycle. */
    data class ReconnectTick(val cause: MeshtasticException, val attempt: Int) : EngineMessage

    /** Outcome of a reconnect attempt initiated by [ReconnectTick]. */
    data class ReconnectAttemptResult(val success: Boolean, val cause: MeshtasticException?) : EngineMessage
}

/** An acknowledged admin write to the connected node, mirrored on the SDK's session state. */
internal sealed interface LocalWrite {
    data class Config(val config: org.meshtastic.proto.Config) : LocalWrite

    data class ModuleConfig(val config: org.meshtastic.proto.ModuleConfig) : LocalWrite

    data class Channel(val channel: org.meshtastic.proto.Channel) : LocalWrite

    data class Node(val write: NodeWrite) : LocalWrite
}

/** An acknowledged admin write to the connected node that changes its node database, mirrored on the SDK map. */
internal sealed interface NodeWrite {
    data class Remove(val node: Int) : NodeWrite

    data class Flags(val node: Int, val favorite: Boolean? = null, val ignored: Boolean? = null) : NodeWrite

    data class Owner(val user: org.meshtastic.proto.User) : NodeWrite

    data class ToggleMuted(val node: Int) : NodeWrite

    /** A fixed position for our own node; `null` removes it. */
    data class FixedPosition(val position: org.meshtastic.proto.Position?) : NodeWrite
}
