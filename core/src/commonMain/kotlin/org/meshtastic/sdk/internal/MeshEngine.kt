/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.AutoReconnectConfig
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.ConfigPhase
import org.meshtastic.sdk.CongestionLevel
import org.meshtastic.sdk.CongestionMetrics
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.DeviceStorage
import org.meshtastic.sdk.DroppedFlow
import org.meshtastic.sdk.ExternalChangeKind
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.LogSink
import org.meshtastic.sdk.MeshEvent
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.MessageId
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.NodeField
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.SendFailure
import org.meshtastic.sdk.SendState
import org.meshtastic.sdk.SessionPasskey
import org.meshtastic.sdk.StorageProvider
import org.meshtastic.sdk.TransportState
import org.meshtastic.sdk.WireCodec
import org.meshtastic.sdk.WireFraming
import org.meshtastic.sdk.debug
import org.meshtastic.sdk.error
import org.meshtastic.sdk.info
import org.meshtastic.sdk.verbose
import org.meshtastic.sdk.warn
import kotlin.coroutines.CoroutineContext
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Single-writer actor that owns all mutable SDK state.
 *
 * All state is written exclusively from the engine coroutine. External coroutines interact by
 * posting [EngineMessage]s to [inbox] — never by writing to state directly.
 */
internal class MeshEngine(
    private val transport: RadioTransport,
    private val storageProvider: StorageProvider,
    private val logger: LogSink,
    private val bleHeartbeatEnabled: Boolean,
    private val parentContext: CoroutineContext,
    private val clock: Clock = Clock.System,
    private val sendTimeout: Duration = 30.seconds,
    private val presenceTimeout: Duration = 2.hours,
    private val autoReconnectConfig: AutoReconnectConfig = AutoReconnectConfig.Disabled,
) {

    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val ownNode = MutableStateFlow<NodeInfo?>(null)

    /**
     * Most recently committed [ConfigBundle] for the active session.
     * Cleared on cleanup so a stale bundle never leaks into a new session.
     */
    val configBundleState = MutableStateFlow<ConfigBundle?>(null)

    /**
     * Channel list for the active session, seeded from the handshake or from storage on
     * reconnect. `null` until [ConnectionState.Connected] is reached.
     *
     * Unlike [configBundleState], this survives auto-reconnect resets so the UI does not
     * flash back to null between reconnect cycles.
     */
    val channelsState = MutableStateFlow<List<org.meshtastic.proto.Channel>?>(null)

    val nodes = MutableSharedFlow<NodeChange>(
        replay = 1,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val packets = MutableSharedFlow<MeshPacket>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    val events = MutableSharedFlow<MeshEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val inbox = Channel<EngineMessage>(Channel.UNLIMITED)

    private val outbound = Channel<Frame>(Channel.UNLIMITED)

    /**
     * Supervisor job hosting the engine and its sibling coroutines.
     *
     * **ADR-002 exception:** this atomic is the boundary between caller threads
     * (which may invoke [connect]/[disconnect] concurrently) and the actor. It
     * guards only the lifecycle handle, not engine state.
     */
    private val supervisorJobRef = atomic<Job?>(null)

    /**
     * Awaiter for the in-flight [connect] call.
     *
     * **ADR-002 exception:** boundary atomic between caller threads and the actor.
     * Only used to let [disconnect] unblock a pending [connect] awaiter.
     */
    private val activeConnectDeferred = atomic<CompletableDeferred<Unit>?>(null)

    private val pendingSends = mutableMapOf<MessageId, MutableStateFlow<SendState>>()
    private var storage: DeviceStorage? = null
    private var meshState = MeshState()

    private var handshakeStage = HandshakeStage.Idle
    private var engineScope: CoroutineScope? = null

    // Inter-stage and periodic keep-alive heartbeats are sent with nonce = 0. Firmware
    // (PhoneAPI.cpp:202-209) interprets nonce == 1 as "broadcast our nodeinfo over LoRa";
    // nonces > 0 are reserved for explicit "ping our nodeinfo" semantics that we don't
    // expose today. Sending 0 is the safe keep-alive value and matches Android's reference
    // client.
    private val keepaliveHeartbeat = Heartbeat(nonce = 0)
    private var myNodeNum = 0

    // R-9: snapshot of the previously-persisted my_node_num, captured at connect time so
    // we can synchronously detect identity rebinds on Stage 2 commit (before storage is cleared).
    private var previousMyNodeNum: Int? = null

    /**
     * Per-node admin session passkeys, keyed by the node num that issued them. Each node
     * (local device and every remote admin target) hands out its **own** passkey in its admin
     * responses; remote admin writes must echo the *target's* passkey back, so a single shared
     * slot would cross-contaminate concurrent admin sessions against different nodes.
     * Entries expire after [SESSION_PASSKEY_TTL]; only the local node's seed passkey is
     * persisted to storage.
     */
    private val sessionPasskeys = mutableMapOf<Int, SessionPasskeyEntry>()

    // Node accumulator during Stage 2 draining.
    private val pendingNodes = mutableMapOf<NodeId, NodeInfo>()

    // Config accumulator during Stage 1 draining.
    private val pendingConfigs = mutableListOf<org.meshtastic.proto.Config>()
    private val pendingModuleConfigs = mutableListOf<org.meshtastic.proto.ModuleConfig>()
    private val pendingChannels = mutableListOf<org.meshtastic.proto.Channel>()
    private var pendingMyInfo: org.meshtastic.proto.MyNodeInfo? = null
    private var pendingMetadata: org.meshtastic.proto.DeviceMetadata? = null
    private var pendingDeviceUIConfig: org.meshtastic.proto.DeviceUIConfig? = null

    // Sends that arrived while still handshaking — dispatched in flushQueuedSends().
    private val preSendQueue = mutableListOf<EngineMessage.Send>()

    /**
     * engine-observed last-heartbeat timestamp (epoch ms) per node. Updated on every
     * inbound frame (periodic heartbeats, telemetry, forwarded packets). Hydrated from
     * [DeviceStorage.loadHeartbeats] on connect; flushed via [DeviceStorage.saveHeartbeat] on
     * periodic heartbeat ticks, at Ready transition, and during cleanup so presence survives
     * process death.
     */
    private val lastHeartbeatAt = mutableMapOf<NodeId, Long>()
    private val dirtyHeartbeats = mutableSetOf<NodeId>()
    private val offlineNodes = mutableSetOf<NodeId>()
    private val lastCongestionLevel = mutableMapOf<NodeId, CongestionLevel>()

    /**
     * once a storage write fails we flip this flag, emit a single
     * [MeshEvent.StorageDegraded] event and skip all subsequent writes for the remainder of this
     * session. In-memory state (packets/nodes/events flows) continues uninterrupted. Cleared on
     * the next [cleanup] so a fresh [RadioClient.connect] can retry the backend.
     */
    private var storageDegraded = false

    /**
     * Liveness watchdog. Counts down on each tick; reset on every decoded FromRadio
     * (raw bytes don't count — a stuck framer could deliver garbage). Virtual-time
     * compatible via `delay()` + tick counter.
     */
    private var livenessBudgetMs: Long = 0L
    private var livenessWatchdogJob: Job? = null
    private var disconnecting = false

    /**
     * Frames received while in [HandshakeStage.Stage1Settling]. They are decoded and
     * buffered here, then replayed through [processStage1Envelope] in
     * [handleStage1SettleComplete] before the inter-stage heartbeat goes out. Drop-oldest at
     * [SETTLE_BUFFER_CAP].
     */
    private val settleBuffer = ArrayDeque<FromRadio>(SETTLE_BUFFER_CAP)

    /**
     * `FromRadio.packet` frames (text messages, telemetry, waypoints, …) that arrive while the
     * handshake is still in flight. Devices forward live mesh traffic interleaved with the
     * config/NodeDB drain; dropping it loses user-visible messages. Buffered drop-oldest at
     * [HANDSHAKE_PACKET_BUFFER_CAP] (with a [MeshEvent.PacketsDropped] signal) and flushed
     * through the normal Ready packet pipeline in [transitionToReady].
     */
    private val handshakePacketBuffer = ArrayDeque<MeshPacket>(HANDSHAKE_PACKET_BUFFER_CAP)

    /**
     * Per-send ACK timeout jobs. Keyed by the wire packet `id`. Touched only by the
     * actor — no atomic needed.
     */
    private val ackTimeoutJobs = mutableMapOf<MessageId, Job>()

    /**
     * Stage 2 sliding-timeout watchdog. The engine no longer relies on a fixed Stage 2
     * deadline; instead, every frame received during [HandshakeStage.Stage2Draining] increments
     * [stage2ProgressCounter], and the watchdog checks every [STAGE2_PROGRESS_TICK_MS] whether
     * the counter advanced. Two failure modes:
     *  1. **Idle stall** — counter unchanged across one tick → fail with HandshakeTimeout(Stage2).
     *  2. **Hard cap** — total elapsed time exceeds [STAGE2_HARD_CAP_MS] regardless of progress
     *     (guards against pathological devices that keep streaming garbage forever).
     */
    private var stage2ProgressCounter: Long = 0L
    private var stage2WatchdogJob: Job? = null

    /** rate-limit clock for the encrypted-packet ACK-skip warning. */
    private var lastEncryptedSkipWarningAtMs: Long = 0L

    /** recent inbound packet ids to suppress duplicate packet delivery. */
    private val recentInboundPacketKeys = ArrayDeque<InboundPacketKey>(INBOUND_PACKET_DEDUP_CAP)
    private val recentInboundPacketKeySet = mutableSetOf<InboundPacketKey>()

    /**
     * Connect-phase deferred. Stored so [cleanup] can fail it if the engine is cancelled before
     * the handshake completes.
     */
    private var pendingConnectDeferred: CompletableDeferred<Unit>? = null

    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0
    private var autoReconnectInProgress: Boolean = false

    // Dispatcher is engine-actor-owned; mutated only inside processMessage. RPC wire ids share
    // the same allocator as user-facing sends so a getConfig can never collide with a sendText.
    private val dispatcher = CommandDispatcher(logger)
    private val nextWireId = atomic(1)

    suspend fun connect() {
        val sup = SupervisorJob(parentContext[Job])

        // the job we just created and throw.
        if (!supervisorJobRef.compareAndSet(null, sup)) {
            sup.cancel()
            throw MeshtasticException.AlreadyConnected()
        }

        val connectDeferred = CompletableDeferred<Unit>()
        // publish the awaiter on the boundary atomic *before* posting the message so a
        // concurrent disconnect() can complete it exceptionally even if the actor is cancelled
        // before it ever processes EngineMessage.Connect.
        activeConnectDeferred.value = connectDeferred
        val scope = CoroutineScope(parentContext + sup)
        engineScope = scope

        scope.launch(CoroutineName("meshtastic-engine-actor")) {
            runEngine()
        }

        // frames(), then forwards each inbound transport frame as a FrameRx message.
        // Gating on TransportState avoids racing with handleConnect() (the transport's
        // readChannel is null until connect() completes, and TcpTransport.frames() throws
        // if collected before then). Failures are surfaced as Disconnect messages so the
        // coroutine doesn't die silently.
        scope.launch(CoroutineName("meshtastic-frame-reader")) {
            try {
                transport.state.first { it is org.meshtastic.sdk.TransportState.Connected }
                transport.frames().collect { frame ->
                    inbox.send(EngineMessage.FrameRx(frame))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, e) { "Frame reader failed: ${e.message}" }
                inbox.trySend(
                    EngineMessage.Disconnect(
                        MeshtasticException.Transport(e.message ?: "frame reader failed", e),
                    ),
                )
            }
        }

        scope.launch(CoroutineName("meshtastic-outbound-writer")) {
            for (frame in outbound) {
                try {
                    transport.send(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(TAG, e) { "Transport send failed: ${e.message}" }
                    inbox.trySend(
                        EngineMessage.Disconnect(
                            MeshtasticException.Transport(e.message ?: "send failed", e),
                        ),
                    )
                }
            }
        }

        scope.launch(CoroutineName("meshtastic-transport-observer")) {
            transport.state.collect { state ->
                inbox.send(EngineMessage.TransportStateChanged(state))
            }
        }

        inbox.send(EngineMessage.Connect(connectDeferred))

        try {
            connectDeferred.await()
        } catch (e: Exception) {
            // Clean up on failure so a subsequent connect() can start fresh
            supervisorJobRef.getAndSet(null)?.cancel()
            throw e
        } finally {
            // clear the awaiter slot so a subsequent disconnect() doesn't try to fail
            // an already-completed deferred.
            activeConnectDeferred.compareAndSet(connectDeferred, null)
        }
    }

    suspend fun disconnect() {
        // complete any in-flight connect() awaiter BEFORE we cancel the supervisor.
        // was cancelled first), cleanup() runs but its `pendingConnectDeferred` was never
        // assigned, so the awaiter would otherwise hang forever.
        activeConnectDeferred.getAndSet(null)?.completeExceptionally(
            CancellationException("disconnect requested"),
        )
        val job = supervisorJobRef.getAndSet(null)
        if (job == null) {
            // Already disconnected; just ensure state is consistent
            connectionState.value = ConnectionState.Disconnected
            return
        }
        job.cancel()
        connectionState.first { it == ConnectionState.Disconnected }
    }

    fun trySend(packet: MeshPacket, id: MessageId, stateFlow: MutableStateFlow<SendState>) {
        val result = inbox.trySend(EngineMessage.Send(packet, id, stateFlow))
        if (!result.isSuccess) {
            stateFlow.value = SendState.Failed(SendFailure.Disconnected)
        }
    }

    fun tryCancelSend(id: MessageId) {
        inbox.trySend(EngineMessage.CancelHandle(id))
    }

    /**
     * Allocate the next monotonic wire id. Shared between user-facing sends ([RadioClient.send])
     * and RPCs so a `getConfig`'s request_id can never collide with a concurrent
     * `sendText`'s packet id. Wraps at `Int.MAX_VALUE`; the firmware uses fixed32 so we just
     * keep climbing.
     */
    fun nextMessageId(): MessageId = MessageId(nextWireId.incrementAndGet())

    /**
     * Submit a typed RPC and suspend until the response arrives, the timer fires, or the engine
     * winds down.
     *
     * The caller must:
     *  1. Have already populated `packet.id` with [requestId] (the value returned by
     *     [nextMessageId].raw).
     *  2. Set `decoded.want_response = true` (or `want_ack = true` for ack-only flows; not used
     *     by this path — those flow through [trySend]/[MessageHandle]).
     *
     * Returns [AdminResult.Timeout] / [AdminResult.SessionKeyExpired] / etc. as classified by
     * [CommandDispatcher]. Throws [MeshtasticException.NotConnected] if the engine is closed
     * before the request reaches the actor.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> submitRpc(
        packet: MeshPacket,
        requestId: Int,
        kind: ResponseKind<T>,
        timeout: Duration,
    ): AdminResult<T> {
        val deferred = CompletableDeferred<AdminResult<Any?>>()
        val sent = inbox.trySend(
            EngineMessage.PostRpc(
                packet = packet,
                requestId = requestId,
                kind = kind,
                deferred = deferred,
                timeout = timeout,
            ),
        )
        if (!sent.isSuccess) return AdminResult.Timeout
        return deferred.await() as AdminResult<T>
    }

    fun nodeSnapshot(): Map<NodeId, NodeInfo> = meshState.nodes

    /**
     * The local node's NodeNum once the handshake has populated it, otherwise `null`.
     *
     * Read-after-Ready by RPC impls so they can default `to = LOCAL` to the actual
     * device address instead of `0`. Single-writer access from the actor; cross-thread reads see
     * the last value committed before [ConnectionState.Connected] was emitted.
     */
    fun myNodeNumOrNull(): Int? = if (myNodeNum != 0) myNodeNum else null

    private suspend fun runEngine() {
        try {
            for (msg in inbox) {
                processMessage(msg)
            }
        } finally {
            withContext(NonCancellable) {
                cleanup()
            }
        }
    }

    /**
     * Teardown sequence. Always runs inside `withContext(NonCancellable)` to ensure completion
     * even if the coroutine was cancelled.
     *
     * Order matters:
     * 1. Fail pending connect deferred (unblocks `connect()` callers).
     * 2. Drain inbox for any unprocessed [EngineMessage.Send] — prevents handles stuck in Queued.
     * 3. Fail all tracked pending sends.
     * 4. Close inbox — makes future `trySend` calls fail immediately.
     * 5. Close outbound channel — stops the outbound-writer coroutine.
     * 6. Close storage.
     * 7. Send `ToRadio { disconnect = true }` if the transport is still Connected — the
     *    proto-level "polite goodbye" (mesh.proto: `ToRadio.disconnect`). Best-effort,
     *    bounded by [GOODBYE_TIMEOUT_MS]; failures are swallowed.
     * 8. Disconnect transport.
     * 9. Emit [ConnectionState.Disconnected] — unblocks `disconnect()` callers.
     */
    private suspend fun cleanup() {
        // stop the liveness watchdog before any other work so it cannot race with the
        // already disconnecting).
        disconnecting = true
        livenessWatchdogJob?.cancel()
        livenessWatchdogJob = null

        //    disconnect(); see ).
        pendingConnectDeferred?.completeExceptionally(
            MeshtasticException.Transport("Engine cancelled before connection completed"),
        )
        pendingConnectDeferred = null
        activeConnectDeferred.getAndSet(null)?.completeExceptionally(
            CancellationException("Engine cancelled before connection completed"),
        )

        var drained = inbox.tryReceive()
        while (drained.isSuccess) {
            val msg = drained.getOrNull()
            if (msg is EngineMessage.Send) {
                msg.stateFlow.value = SendState.Failed(SendFailure.Disconnected)
            }
            drained = inbox.tryReceive()
        }

        // ②ʹ fail any pre-Ready queued sends with Disconnected (semantically equivalent
        //         to the pendingSends sweep below, but explicit so the intent is documented).
        failPreSendQueueWith(SendFailure.Disconnected)

        for ((_, stateFlow) in pendingSends) {
            stateFlow.value = SendState.Failed(SendFailure.Disconnected)
        }
        pendingSends.clear()

        inbox.close()
        outbound.close()

        // Final flush of any dirty heartbeats so presence data survives a clean disconnect.
        try {
            if (!storageDegraded && dirtyHeartbeats.isNotEmpty()) {
                val s = storage
                if (s != null) {
                    for (nodeId in dirtyHeartbeats) {
                        lastHeartbeatAt[nodeId]?.let { ts -> s.saveHeartbeat(nodeId, ts) }
                    }
                    dirtyHeartbeats.clear()
                }
            }
        } catch (_: Exception) {
            // best-effort — don't fail cleanup
        }

        try {
            storage?.close()
        } catch (_: Exception) {
        }
        storage = null

        //    Per mesh.proto `ToRadio.disconnect`: "useful for serial links where there is no
        //    hardware/protocol based notification that the client has dropped the link."
        //    Without this, firmware waits for its own idle timeout (tens of seconds on
        //    serial; 15 min on TCP) before considering the streamed-API client gone.
        //    Best-effort: only attempt if the transport reached Connected, bound by a short
        //    timeout, and swallow all errors so teardown always completes.
        if (transport.state.value is TransportState.Connected) {
            try {
                withTimeoutOrNull(GOODBYE_TIMEOUT_MS) {
                    val encoded = WireCodec.encodeToRadio(ToRadio(disconnect = true))
                    transport.send(Frame(ByteString(encoded)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug(TAG, e) { "Polite disconnect send failed: ${e.message}" }
            }
        }

        try {
            transport.disconnect()
        } catch (_: Exception) {
        }

        supervisorJobRef.value = null
        engineScope = null
        handshakeStage = HandshakeStage.Idle
        myNodeNum = 0
        previousMyNodeNum = null
        sessionPasskeys.clear()
        handshakePacketBuffer.clear()
        pendingNodes.clear()
        pendingConfigs.clear()
        pendingModuleConfigs.clear()
        pendingChannels.clear()
        pendingMyInfo = null
        pendingMetadata = null
        pendingDeviceUIConfig = null
        preSendQueue.clear()
        // reset the degraded flag so the next connect attempt gets a fresh storage shot.
        storageDegraded = false
        // clear in-memory heartbeat bookkeeping (hydrated fresh on next connect).
        lastHeartbeatAt.clear()
        dirtyHeartbeats.clear()
        offlineNodes.clear()
        lastCongestionLevel.clear()
        // clear any leftover settle-buffer entries so a reconnect starts clean.
        settleBuffer.clear()
        // cancel any in-flight per-send ACK timers so they don't post stale messages.
        for ((_, job) in ackTimeoutJobs) job.cancel()
        ackTimeoutJobs.clear()
        // cancel the Stage 2 sliding-timeout watchdog so it doesn't post stale timeouts.
        stage2WatchdogJob?.cancel()
        stage2WatchdogJob = null
        stage2ProgressCounter = 0L
        lastEncryptedSkipWarningAtMs = 0L
        recentInboundPacketKeys.clear()
        recentInboundPacketKeySet.clear()

        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        autoReconnectInProgress = false
        configBundleState.value = null
        channelsState.value = null

        dispatcher.cancelAll(AdminResult.NodeUnreachable)

        connectionState.value = ConnectionState.Disconnected
        logger.debug(TAG) { "Cleanup complete — session torn down" }
    }

    private suspend fun processMessage(msg: EngineMessage) {
        when (msg) {
            is EngineMessage.Connect -> handleConnect(msg)
            is EngineMessage.Disconnect -> handleDisconnect(msg)
            is EngineMessage.FrameRx -> handleFrameRx(msg)
            is EngineMessage.TransportStateChanged -> handleTransportStateChanged(msg)
            is EngineMessage.Send -> handleSend(msg)
            is EngineMessage.CancelHandle -> handleCancelHandle(msg)
            EngineMessage.HeartbeatTick -> handleHeartbeatTick()
            EngineMessage.LivenessTick -> handleLivenessTick()
            EngineMessage.PresenceCheckTick -> handlePresenceCheckTick()
            is EngineMessage.HandshakeTimeout -> handleHandshakeTimeout(msg)
            EngineMessage.HandshakeStage1SettleComplete -> handleStage1SettleComplete()
            EngineMessage.HandshakeHeartbeatSettleComplete -> handleHeartbeatSettleComplete()
            EngineMessage.Stage1RetryWantConfig -> handleStage1RetryWantConfig()
            is EngineMessage.AdminTimeout -> { /* legacy slot, superseded by PendingResponseTimeout */ }
            is EngineMessage.PostRpc -> handlePostRpc(msg)
            is EngineMessage.PendingResponseTimeout -> handlePendingResponseTimeout(msg)
            is EngineMessage.SendAckTimeout -> handleSendAckTimeout(msg)
            is EngineMessage.ReconnectTick -> handleReconnectTick(msg)
            is EngineMessage.ReconnectAttemptResult -> handleReconnectAttemptResult(msg)
        }
    }

    private fun handlePostRpc(msg: EngineMessage.PostRpc) {
        // Engine must be Ready: RPCs require an established session passkey + nodenum.
        if (handshakeStage != HandshakeStage.Ready) {
            msg.deferred.complete(AdminResult.NodeUnreachable)
            return
        }
        // Register before sending so a fast device reply can never race the registration.
        dispatcher.register(msg.requestId, msg.kind, msg.deferred)
        // Outbound: stamp the target's session passkey + PKC routing for remote-admin, then enqueue.
        val outboundPacket = prepareOutboundAdminPacket(msg.packet)
        val encoded = WireCodec.encodeToRadio(ToRadio(packet = outboundPacket))
        outbound.trySend(Frame(ByteString(encoded)))
        // Arm the timeout last so the timer fires even if dispatch path errors above.
        val scope = engineScope ?: return
        val job = scope.launch {
            delay(msg.timeout)
            inbox.trySend(EngineMessage.PendingResponseTimeout(msg.requestId))
        }
        dispatcher.attachTimeoutJob(msg.requestId, job)
    }

    private fun handlePendingResponseTimeout(msg: EngineMessage.PendingResponseTimeout) {
        dispatcher.timeout(msg.requestId)
    }

    /**
     * Prepare an outbound ADMIN_APP packet targeting a **remote** node. One choke point shared
     * by the RPC path ([handlePostRpc]), the ACK'd-send path ([dispatchSend]), and the
     * fire-and-forget path ([sendAdminPacket]). Three concerns, mirroring the Android
     * reference client's remote-admin behaviour:
     *
     *  1. **Session passkey** — stamp the *target node's* cached passkey (each node issues its
     *     own; a passkey from node A is rejected by node B). Missing/expired passkey emits a
     *     ProtocolWarning and sends anyway — the firmware NAKs with ADMIN_BAD_SESSION_KEY,
     *     which [AdminApiImpl]'s single-shot reseed-and-retry handles.
     *  2. **PKC routing** — firmware 2.5+ requires remote admin to be PKI-encrypted when both
     *     nodes have published keys: `pki_encrypted = true`, `public_key = <target key>`,
     *     channel 0. Without mutual keys, fall back to a channel named "admin" (legacy
     *     secondary-channel admin).
     *  3. **Priority** — remote admin defaults to RELIABLE (matches the reference client).
     *
     * Local admin (`to == myNodeNum`) is returned untouched: the PhoneAPI accepts local admin
     * without a passkey or PKC.
     */
    private fun prepareOutboundAdminPacket(packet: MeshPacket): MeshPacket {
        val decoded = packet.decoded ?: return packet
        if (decoded.portnum != PortNum.ADMIN_APP) return packet
        if (packet.to == 0 || packet.to == myNodeNum || packet.to == BROADCAST_ADDR) return packet

        // 1. Session passkey re-stamp (skip when the caller already provided one).
        var outDecoded = decoded
        val passkey = validSessionPasskeyFor(packet.to)
        if (passkey != null) {
            val originalAdmin = runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull()
            if (originalAdmin != null && originalAdmin.session_passkey.size == 0) {
                val stamped = originalAdmin.copy(session_passkey = passkey.toByteString())
                outDecoded = decoded.copy(payload = AdminMessage.ADAPTER.encode(stamped).toByteString())
            }
        } else {
            events.tryEmit(
                MeshEvent.ProtocolWarning(
                    "admin remote without session passkey",
                    details = mapOf("to" to packet.to),
                ),
            )
        }

        val priority = if (packet.priority == MeshPacket.Priority.UNSET) {
            MeshPacket.Priority.RELIABLE
        } else {
            packet.priority
        }

        // Caller already routed the packet itself (custom PKC build) — passkey/priority only.
        if (packet.pki_encrypted) {
            return packet.copy(decoded = outDecoded, priority = priority)
        }

        // 2. PKC when both sides have published keys; legacy "admin" channel otherwise.
        val targetKey = meshState.nodes[NodeId(packet.to)]?.user?.public_key
        val ownKey = meshState.nodes[NodeId(myNodeNum)]?.user?.public_key
        return if (targetKey != null && targetKey.size > 0 && ownKey != null && ownKey.size > 0) {
            packet.copy(
                decoded = outDecoded,
                channel = 0,
                pki_encrypted = true,
                public_key = targetKey,
                priority = priority,
            )
        } else {
            val adminIndex = channelsState.value
                ?.indexOfFirst { it.settings?.name?.equals(ADMIN_CHANNEL_NAME, ignoreCase = true) == true }
                ?.takeIf { it >= 0 } ?: 0
            packet.copy(decoded = outDecoded, channel = adminIndex, priority = priority)
        }
    }

    private suspend fun handleConnect(msg: EngineMessage.Connect) {
        pendingConnectDeferred = msg.deferred
        connectionState.value = ConnectionState.Connecting(1)
        // reset watchdog gate so a prior session's `disconnecting = true` doesn't stick.
        disconnecting = false
        logger.info(TAG) { "Connect requested (transport=${transport.identity})" }

        try {
            storage = storageProvider.activate(transport.identity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MeshtasticException) {
            connectionState.value = ConnectionState.Disconnected
            msg.deferred.completeExceptionally(e)
            pendingConnectDeferred = null
            return
        } catch (e: Exception) {
            val ex = MeshtasticException.StorageUnavailable(e.message ?: "storage activation failed", e)
            connectionState.value = ConnectionState.Disconnected
            msg.deferred.completeExceptionally(ex)
            pendingConnectDeferred = null
            return
        }

        // R-9: capture previously-persisted identity (if any) synchronously on the actor so we
        // can compare against the device's reported NodeNum at Stage 2 commit time.
        previousMyNodeNum = try {
            storage?.loadConfig()?.myInfo?.my_node_num?.takeIf { it != 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, e) { "Failed to load previous identity for rebind detection" }
            null
        }

        // restore the persisted session passkey so admin RPCs work immediately, instead
        // of waiting for the next get_owner_request response. Failures are non-fatal — we can
        // always re-seed via the handshake's get_owner round-trip. The persisted passkey is the
        // *local* device's seed passkey, so it is keyed under the previously-persisted identity
        // (remote-node passkeys are ephemeral and never persisted).
        val persisted = try {
            storage?.loadSessionPasskey()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, e) { "Failed to load persisted session passkey" }
            null
        }
        val restoredOwner = previousMyNodeNum
        if (persisted != null && restoredOwner != null) {
            sessionPasskeys[restoredOwner] =
                SessionPasskeyEntry(persisted.bytes.toByteArray(), persisted.expiresAtEpochMs)
        }

        // hydrate in-memory heartbeat map so subscribers don't see every previously-known
        // node as silent on reconnect. Failures are non-fatal; we just start fresh.
        try {
            val loaded = storage?.loadHeartbeats().orEmpty()
            lastHeartbeatAt.clear()
            offlineNodes.clear()
            lastHeartbeatAt.putAll(loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, e) { "Failed to load persisted heartbeats" }
            lastHeartbeatAt.clear()
            offlineNodes.clear()
        }

        try {
            transport.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MeshtasticException) {
            connectionState.value = ConnectionState.Disconnected
            msg.deferred.completeExceptionally(e)
            pendingConnectDeferred = null
            return
        } catch (e: Exception) {
            val ex = MeshtasticException.Transport(e.message ?: "transport connect failed", e)
            connectionState.value = ConnectionState.Disconnected
            msg.deferred.completeExceptionally(ex)
            pendingConnectDeferred = null
            return
        }

        startStage1Handshake()
    }

    /**
     * Begin Stage 1 of the handshake. Extracted from [handleConnect] so the auto-reconnect
     * supervisor can re-enter the handshake without re-running storage activation.
     */
    private fun startStage1Handshake() {
        connectionState.value = ConnectionState.Configuring(ConfigPhase.Stage1, 0f)

        // Stream-based transports (TCP, Serial) require 4 wake bytes (0x94) before the first
        // want_config_id to resync the firmware's frame decoder after an unclean disconnect.
        // BLE doesn't need this since each GATT write is self-framing.
        if (!transport.identity.raw.startsWith("ble:")) {
            val wakeBytes = byteArrayOf(0x94.toByte(), 0x94.toByte(), 0x94.toByte(), 0x94.toByte())
            outbound.trySend(Frame(ByteString(wakeBytes)))
        }

        sendToRadio(ToRadio(want_config_id = NONCE_STAGE1))
        handshakeStage = HandshakeStage.Stage1Draining

        engineScope?.launch {
            delay(STAGE1_RETRY_MS)
            inbox.trySend(EngineMessage.Stage1RetryWantConfig)
        }
        engineScope?.launch {
            delay(STAGE1_TIMEOUT_MS)
            inbox.trySend(EngineMessage.HandshakeTimeout(HandshakeStage.Stage1Draining))
        }

        logger.info(TAG) { "Handshake Stage 1 started (want_config_id=$NONCE_STAGE1)" }
    }

    private fun handleDisconnect(msg: EngineMessage.Disconnect) {
        // mark `disconnecting` so the liveness watchdog can't re-emit a TransportError
        // while we're already tearing down.
        disconnecting = true
        logger.info(TAG) { "Disconnect initiated (cause=${msg.cause?.message ?: "user-requested"})" }
        livenessWatchdogJob?.cancel()
        livenessWatchdogJob = null

        // cancel any pending reconnect timer up-front so a queued ReconnectTick from
        // the pre-disconnect cycle can't re-enter the handshake after teardown begins.
        reconnectJob?.cancel()
        reconnectJob = null
        autoReconnectInProgress = false

        if (msg.cause != null) {
            val transportError = msg.cause.let {
                if (it is MeshtasticException.Transport) {
                    it
                } else {
                    MeshtasticException.tag(
                        MeshtasticException.Transport(it.message ?: "disconnect", it),
                        transportIdentity = transport.identity,
                        operation = "engine.disconnect",
                    )
                }
            }
            events.tryEmit(MeshEvent.TransportError(transportError))
        }
        supervisorJobRef.value?.cancel()
    }

    private fun handleFrameRx(msg: EngineMessage.FrameRx) {
        val rawBytes = msg.frame.bytes.toByteArray()
        when {
            rawBytes.size < WireFraming.HEADER_SIZE -> {
                reportMalformedFrame(
                    message = "Frame shorter than wire header",
                    details = mapOf("bytes" to rawBytes.size),
                )
                return
            }

            rawBytes[0] != WireFraming.MAGIC_0 || rawBytes[1] != WireFraming.MAGIC_1 -> {
                reportMalformedFrame(
                    message = "Frame has invalid wire header",
                    details = mapOf(
                        "magic0" to (rawBytes[0].toInt() and 0xFF),
                        "magic1" to (rawBytes[1].toInt() and 0xFF),
                    ),
                )
                return
            }
        }

        val declaredPayloadSize = ((rawBytes[2].toInt() and 0xFF) shl 8) or (rawBytes[3].toInt() and 0xFF)
        if (declaredPayloadSize == 0) {
            reportMalformedFrame(
                message = "Frame declares empty payload",
                details = emptyMap(),
            )
            return
        }
        if (declaredPayloadSize > WireFraming.MAX_PAYLOAD_SIZE) {
            reportMalformedFrame(
                message = "Frame exceeds max payload size",
                details = mapOf(
                    "declared_payload_bytes" to declaredPayloadSize,
                    "max_payload_bytes" to WireFraming.MAX_PAYLOAD_SIZE,
                ),
            )
            return
        }

        val actualPayloadSize = rawBytes.size - WireFraming.HEADER_SIZE
        if (declaredPayloadSize != actualPayloadSize) {
            reportMalformedFrame(
                message = "Frame payload length mismatch",
                details = mapOf(
                    "declared_payload_bytes" to declaredPayloadSize,
                    "actual_payload_bytes" to actualPayloadSize,
                ),
            )
            return
        }

        val protoBytes = rawBytes.copyOfRange(WireFraming.HEADER_SIZE, rawBytes.size)
        val fromRadio = try {
            WireCodec.decodeFromRadio(protoBytes)
        } catch (e: Exception) {
            // decode failures are a protocol-level anomaly, not a silent
            // drop. Emit a ProtocolWarning so hosts can surface an "incompatible firmware?" /
            // "wire corruption" UI and ship logs. Keep the exception message + type in details
            // so downstream tooling can classify (e.g. MismatchedTag vs truncated payload).
            logger.warn(TAG, e) { "Failed to decode FromRadio: ${e.message}" }
            events.tryEmit(
                MeshEvent.ProtocolWarning(
                    "FromRadio decode failed",
                    details = mapOf(
                        "exception" to (e::class.simpleName ?: "Exception"),
                        "message" to (e.message ?: ""),
                        "payload_bytes" to protoBytes.size,
                    ),
                ),
            )
            return
        }

        // liveness watchdog only resets on successfully decoded envelopes,
        // not raw-byte arrivals. A stuck framer on a half-open socket could still deliver
        // resync garbage; that must not look like "the link is healthy" to the watchdog.
        livenessBudgetMs = LIVENESS_TIMEOUT_MS

        // `FromRadio.rebooted = true` is a forced-disconnect signal — the
        // radio has restarted and all prior session state (session passkey, node DB, pending
        // sends) is now stale. Previously we only cancelled the supervisor, which meant pending
        // sends resolved as generic `Disconnected`. Now we:
        //   1. Reset handshake stage so any stale settle/stage timers become no-ops.
        //   2. Emit `MeshEvent.DeviceRebooted` so subscribers can distinguish this from a
        //      transport-level drop.
        //   3. Fail the pending-connect deferred (if any) with Protocol so mid-handshake
        //      reboots surface a clear error.
        //   4. Cancel the supervisor → cleanup() runs, polite-goodbye is sent, transport
        //      disconnects, and `ConnectionState.Disconnected` is emitted.
        if (fromRadio.rebooted == true) {
            logger.warn(TAG) { "Device rebooted — forcing disconnect" }
            events.tryEmit(MeshEvent.DeviceRebooted())
            handshakeStage = HandshakeStage.Idle
            stage2WatchdogJob?.cancel()
            stage2WatchdogJob = null
            val deferred = pendingConnectDeferred
            if (deferred != null) {
                pendingConnectDeferred = null
                failPreSendQueueWith(SendFailure.HandshakeFailed)
                deferred.completeExceptionally(MeshtasticException.Protocol("Device rebooted mid-handshake"))
            }
            supervisorJobRef.value?.cancel()
            return
        }

        when (handshakeStage) {
            HandshakeStage.Idle -> {
            }

            HandshakeStage.Stage1Settling -> {
                // instead of dropping, buffer frames for replay after settle. Drop-oldest
                // at SETTLE_BUFFER_CAP and surface a PacketsDropped event so subscribers can
                // spot pathological backlogs — silent loss is forbidden per
                // ADR-005 public-surface rules).
                if (settleBuffer.size >= SETTLE_BUFFER_CAP) {
                    val dropped = settleBuffer.removeFirst()
                    logger.warn(TAG) {
                        "Stage1 settle buffer full (cap=$SETTLE_BUFFER_CAP) — dropping oldest frame ($dropped)"
                    }
                    events.tryEmit(MeshEvent.PacketsDropped(DroppedFlow.Packets, count = 1))
                }
                settleBuffer.addLast(fromRadio)
                return
            }

            HandshakeStage.Stage1Draining -> processStage1Envelope(fromRadio)

            HandshakeStage.Stage2Draining -> processStage2Envelope(fromRadio)

            HandshakeStage.SeedingSession -> processSeedingEnvelope(fromRadio)

            HandshakeStage.Ready -> routeNormalEnvelope(fromRadio)
        }
    }

    private fun processStage1Envelope(fromRadio: org.meshtastic.proto.FromRadio) {
        val myInfo = fromRadio.my_info
        val metadata = fromRadio.metadata
        val channel = fromRadio.channel
        val config = fromRadio.config
        val modConfig = fromRadio.moduleConfig
        val nodeInfo = fromRadio.node_info
        val deviceUIConf = fromRadio.deviceuiConfig
        val completeId = fromRadio.config_complete_id

        // Accumulators must be idempotent: a `want_config_id` retry (Stage1RetryWantConfig) or a
        // device-side hiccup restarts the firmware's config drain from scratch (PhoneAPI
        // handleStartConfig resets its read index), so the same channels/config sections can
        // stream twice. Replace-by-key instead of append so the committed bundle has no dupes.
        when {
            myInfo != null -> pendingMyInfo = myInfo

            metadata != null -> pendingMetadata = metadata

            channel != null -> {
                pendingChannels.removeAll { it.index == channel.index }
                pendingChannels.add(channel)
            }

            config != null -> {
                val merged = mergeConfigs(pendingConfigs, listOf(config))
                pendingConfigs.clear()
                pendingConfigs.addAll(merged)
            }

            modConfig != null -> {
                val merged = mergeModuleConfigs(pendingModuleConfigs, listOf(modConfig))
                pendingModuleConfigs.clear()
                pendingModuleConfigs.addAll(merged)
            }

            deviceUIConf != null -> pendingDeviceUIConfig = deviceUIConf

            nodeInfo != null -> {
                val nodeId = NodeId(nodeInfo.num)
                pendingNodes[nodeId] = nodeInfo
            }

            // live mesh traffic interleaved with the Stage 1 config drain — buffer for
            // delivery through the normal packet pipeline once the session reaches Ready.
            fromRadio.packet != null -> bufferHandshakePacket(fromRadio.packet!!)

            // route auxiliary FromRadio variants (clientNotification,
            // mqttClientProxyMessage, xmodemPacket, deviceuiConfig, fileInfo, queueStatus,
            // log_record) through the shared helper so they're not silently dropped during
            // the handshake. clientNotification → SecurityWarning translation is identical
            // to the Ready path; the rest emit a structured ProtocolWarning.
            handleAuxiliaryFromRadioVariant(fromRadio, HandshakeStage.Stage1Draining) -> Unit

            completeId == NONCE_STAGE1 -> {
                logger.info(TAG) { "Stage 1 complete; entering 100 ms settle" }
                handshakeStage = HandshakeStage.Stage1Settling
                connectionState.value = ConnectionState.Configuring(ConfigPhase.Settling, 0.5f)
                engineScope?.launch {
                    delay(INTER_STAGE_SETTLE_MS)
                    inbox.trySend(EngineMessage.HandshakeStage1SettleComplete)
                }
            }

            // a `config_complete_id` that matches neither the Stage 1 nor
            // Stage 2 nonce is a protocol violation — previously we silently dropped it.
            completeId != 0 -> failHandshakeWithProtocol(
                "Stage 1 config_complete_id mismatch: expected=$NONCE_STAGE1 got=$completeId",
            )
        }
    }

    private fun handleStage1SettleComplete() {
        if (handshakeStage != HandshakeStage.Stage1Settling) return

        // replay any frames that arrived during the settle window through the normal
        // Stage1 envelope handler. We temporarily flip back to Stage1Draining so the handler
        // accepts the buffered frames; if a replayed frame contains another `config_complete_id`
        // matching NONCE_STAGE1, processStage1Envelope will re-arm Stage1Settling and we bail
        // out without sending the inter-stage heartbeat.
        if (settleBuffer.isNotEmpty()) {
            val pending = settleBuffer.toList()
            settleBuffer.clear()
            handshakeStage = HandshakeStage.Stage1Draining
            for (env in pending) {
                processStage1Envelope(env)
                if (handshakeStage == HandshakeStage.Stage1Settling) {
                    // A replayed frame re-armed the settle timer — abort this handler and let
                    // the new HandshakeStage1SettleComplete fire.
                    return
                }
            }
            // No re-arm; resume the normal post-settle flow.
            handshakeStage = HandshakeStage.Stage1Settling
        }

        // keep-alive heartbeats use nonce=0 (see [keepaliveHeartbeat] for rationale).
        sendToRadio(ToRadio(heartbeat = keepaliveHeartbeat))
        logger.debug(TAG) { "Sent inter-stage heartbeat nonce=0" }
        engineScope?.launch {
            delay(INTER_STAGE_SETTLE_MS)
            inbox.trySend(EngineMessage.HandshakeHeartbeatSettleComplete)
        }
    }

    private fun handleHeartbeatSettleComplete() {
        connectionState.value = ConnectionState.Configuring(ConfigPhase.Stage2, 0.5f)
        sendToRadio(ToRadio(want_config_id = NONCE_STAGE2))
        handshakeStage = HandshakeStage.Stage2Draining

        // install the sliding Stage 2 watchdog. Frame arrival in processStage2Envelope
        // increments stage2ProgressCounter; the watchdog fails the handshake only if no progress
        // is made for one tick window, or the hard cap is reached. Replaces the old fixed
        // delay(STAGE2_TIMEOUT_MS) which would penalize slow-but-progressing devices.
        stage2ProgressCounter = 0L
        stage2WatchdogJob?.cancel()
        stage2WatchdogJob = engineScope?.launch {
            var lastCounter = stage2ProgressCounter
            var elapsedMs = 0L
            while (elapsedMs < STAGE2_HARD_CAP_MS) {
                delay(STAGE2_PROGRESS_TICK_MS)
                elapsedMs += STAGE2_PROGRESS_TICK_MS
                val current = stage2ProgressCounter
                if (current == lastCounter) {
                    inbox.trySend(EngineMessage.HandshakeTimeout(HandshakeStage.Stage2Draining))
                    return@launch
                }
                lastCounter = current
            }
            // Hard cap exceeded — even if frames are still trickling, abort.
            inbox.trySend(EngineMessage.HandshakeTimeout(HandshakeStage.Stage2Draining))
        }

        logger.info(TAG) { "Handshake Stage 2 started (want_config_id=$NONCE_STAGE2)" }
    }

    private fun processStage2Envelope(fromRadio: org.meshtastic.proto.FromRadio) {
        // any frame received during Stage 2 counts as forward progress for the sliding
        // watchdog. Increment unconditionally — the cost is one Long add per frame.
        stage2ProgressCounter++

        val myInfo = fromRadio.my_info
        val nodeInfo = fromRadio.node_info
        val completeId = fromRadio.config_complete_id

        when {
            myInfo != null -> pendingMyInfo = myInfo

            nodeInfo != null -> {
                val nodeId = NodeId(nodeInfo.num)
                pendingNodes[nodeId] = nodeInfo
            }

            // live mesh traffic interleaved with the Stage 2 NodeDB drain (see Stage 1).
            fromRadio.packet != null -> bufferHandshakePacket(fromRadio.packet!!)

            // shared auxiliary-variant handling for Stage 2 (see Stage 1).
            handleAuxiliaryFromRadioVariant(fromRadio, HandshakeStage.Stage2Draining) -> Unit

            completeId == NONCE_STAGE2 -> {
                logger.info(TAG) { "Stage 2 complete; seeding session passkey" }
                handshakeStage = HandshakeStage.SeedingSession
                // leaving Stage2Draining — cancel the sliding watchdog so it doesn't
                // post a stale HandshakeTimeout message after we've already advanced.
                stage2WatchdogJob?.cancel()
                stage2WatchdogJob = null

                // Commit accumulated handshake state.
                val myInfo = pendingMyInfo
                if (myInfo != null) {
                    // R-9: detect identity rebind synchronously on the actor, BEFORE we mutate
                    // any consumer-observable state (NodeChange.Snapshot below) and BEFORE the
                    // async storage.recordOwnNode → clear() runs.
                    val prev = previousMyNodeNum
                    if (prev != null && prev != myInfo.my_node_num) {
                        logger.warn(TAG) {
                            "Identity rebind detected — previous=0x${prev.toString(
                                16,
                            )} new=0x${myInfo.my_node_num.toString(16)}"
                        }
                        // passkeys issued under the previous identity are meaningless now.
                        sessionPasskeys.clear()
                        events.tryEmit(
                            MeshEvent.IdentityRebound(
                                previousNodeNum = NodeId(prev),
                                newNodeNum = NodeId(myInfo.my_node_num),
                                // populate reason so host UX can choose
                                // copy. Without the firmware specifically signalling "factory
                                // reset", we report "identity_mismatch" — the NodeNum changed
                                // under an identity that the host persisted.
                                reason = "identity_mismatch",
                            ),
                        )
                    }
                    previousMyNodeNum = myInfo.my_node_num.takeIf { it != 0 }

                    myNodeNum = myInfo.my_node_num
                    meshState = meshState.withMyInfo(myInfo)
                    ownNode.value = pendingNodes[NodeId(myNodeNum)]
                }
                if (pendingMetadata != null || pendingConfigs.isNotEmpty() || pendingModuleConfigs.isNotEmpty()) {
                    val bundle = ConfigBundle(
                        myInfo = myInfo ?: org.meshtastic.proto.MyNodeInfo(),
                        metadata = pendingMetadata ?: org.meshtastic.proto.DeviceMetadata(),
                        configs = pendingConfigs.toList(),
                        moduleConfigs = pendingModuleConfigs.toList(),
                        deviceUIConfig = pendingDeviceUIConfig,
                    )
                    meshState = meshState.withConfig(bundle)
                }
                if (pendingChannels.isNotEmpty()) {
                    meshState = meshState.withChannels(pendingChannels.toList())
                }
                if (pendingNodes.isNotEmpty()) {
                    meshState = meshState.withNodes(pendingNodes.toMap())
                    emitNodeChangeOrLog(NodeChange.Snapshot(pendingNodes.toMap()))
                }

                // Persist to storage.
                engineScope?.launch {
                    if (storageDegraded) return@launch
                    val s = storage ?: return@launch
                    try {
                        if (myInfo != null) {
                            s.recordOwnNode(NodeId(myInfo.my_node_num), pendingMetadata?.firmware_version ?: "")
                        }
                        for ((nodeId, node) in pendingNodes) {
                            s.saveNode(node)
                            // every node in the handshake snapshot was just heard, so
                            // seed their heartbeat row as well.
                            lastHeartbeatAt[nodeId]?.let { ts -> s.saveHeartbeat(nodeId, ts) }
                        }
                        if (pendingChannels.isNotEmpty()) s.saveChannels(pendingChannels)
                        // Populate channelsState from handshake payload; fall back to storage
                        // for reconnect sessions where the device skips re-sending channels.
                        channelsState.value = if (pendingChannels.isNotEmpty()) {
                            pendingChannels.toList()
                        } else {
                            s.loadChannels().ifEmpty { null }
                        }
                        meshState.configBundle?.let {
                            s.saveConfig(it)
                            configBundleState.value = it
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportStorageDegraded(
                            "persisting handshake snapshot failed: ${e.message ?: e::class.simpleName}",
                            e,
                        )
                    }
                }

                // mark every pending node as heard now (before the async block above flushes).
                run {
                    val now = clock.now().toEpochMilliseconds()
                    for ((nodeId, _) in pendingNodes) {
                        lastHeartbeatAt[nodeId] = now
                    }
                }

                // Send get_owner_request to seed the session passkey.
                if (myNodeNum != 0) {
                    sendAdminPacket(AdminMessage(get_owner_request = true), wantResponse = true)
                    engineScope?.launch {
                        delay(SEED_TIMEOUT_MS)
                        inbox.trySend(EngineMessage.HandshakeTimeout(HandshakeStage.SeedingSession))
                    }
                } else {
                    // No myInfo: skip seeding and go Ready.
                    transitionToReady()
                }
            }

            // see Stage 1 comment above — mismatched completion nonce is
            // a protocol violation, not a silently-droppable frame.
            completeId != 0 -> failHandshakeWithProtocol(
                "Stage 2 config_complete_id mismatch: expected=$NONCE_STAGE2 got=$completeId",
            )
        }
    }

    private fun processSeedingEnvelope(fromRadio: org.meshtastic.proto.FromRadio) {
        val packet = fromRadio.packet ?: return
        val decoded = packet.decoded ?: return
        if (decoded.portnum != PortNum.ADMIN_APP) {
            // live mesh traffic during the seeding window — same treatment as Stage 1/2.
            bufferHandshakePacket(packet)
            return
        }

        val adminMsg = try {
            AdminMessage.ADAPTER.decode(decoded.payload)
        } catch (_: Exception) {
            return
        }

        if (adminMsg.get_owner_response != null) {
            latchSessionPasskey(packet.from.takeIf { it != 0 } ?: myNodeNum, adminMsg)
            transitionToReady()
        } else {
            // unsolicited admin traffic (e.g. external config change) — deliver post-Ready.
            bufferHandshakePacket(packet)
        }
    }

    /**
     * Cache the session passkey carried in [adminMsg], keyed by the node that issued it.
     * Firmware refreshes the passkey in **every** admin response, so any admin packet from
     * [fromNum] keeps that node's admin session alive. Only the local node's seed passkey is
     * persisted — remote passkeys are ephemeral by design (4-minute TTL).
     */
    private fun latchSessionPasskey(fromNum: Int, adminMsg: AdminMessage) {
        if (adminMsg.session_passkey.size <= 0) return
        if (fromNum == 0) return
        val bytes = adminMsg.session_passkey.toByteArray()
        val expiresAtMs = clock.now().toEpochMilliseconds() + SESSION_PASSKEY_TTL.inWholeMilliseconds
        sessionPasskeys[fromNum] = SessionPasskeyEntry(bytes, expiresAtMs)
        logger.debug(TAG) { "Session passkey latched for 0x${fromNum.toString(16)} (${bytes.size} bytes)" }
        if (fromNum != myNodeNum) return
        engineScope?.launch {
            if (storageDegraded) return@launch
            try {
                storage?.saveSessionPasskey(SessionPasskey(ByteString(bytes), expiresAtMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportStorageDegraded(
                    "saveSessionPasskey failed: ${e.message ?: e::class.simpleName}",
                    e,
                )
            }
        }
    }

    /** Returns the unexpired session passkey for [nodeNum], pruning a stale entry if present. */
    private fun validSessionPasskeyFor(nodeNum: Int): ByteArray? {
        val entry = sessionPasskeys[nodeNum] ?: return null
        if (clock.now().toEpochMilliseconds() >= entry.expiresAtMs) {
            sessionPasskeys.remove(nodeNum)
            return null
        }
        return entry.bytes
    }

    /** Buffer a mid-handshake `FromRadio.packet` for delivery once the session reaches Ready. */
    private fun bufferHandshakePacket(packet: MeshPacket) {
        if (handshakePacketBuffer.size >= HANDSHAKE_PACKET_BUFFER_CAP) {
            handshakePacketBuffer.removeFirst()
            logger.warn(TAG) {
                "Handshake packet buffer full (cap=$HANDSHAKE_PACKET_BUFFER_CAP) — dropping oldest"
            }
            events.tryEmit(MeshEvent.PacketsDropped(DroppedFlow.Packets, count = 1))
        }
        handshakePacketBuffer.addLast(packet)
    }

    private fun transitionToReady() {
        handshakeStage = HandshakeStage.Ready

        // failure starts a fresh backoff schedule.
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        autoReconnectInProgress = false

        // C1: snapshot + clear preSendQueue BEFORE we publish ConnectionState.Connected.
        // Subscribers that react to Connected by calling RadioClient.send post a fresh
        // EngineMessage.Send into the inbox, which the actor processes on a later iteration —
        // their packets must NOT be folded into this drain (they belong to the post-Ready flow,
        // and dispatchSend below would mis-handle them as if they were part of the handshake
        // backlog).
        val snapshot = preSendQueue.toList()
        preSendQueue.clear()

        connectionState.value = ConnectionState.Connected

        // Start the 30 s periodic heartbeat scheduler.
        engineScope?.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                inbox.trySend(EngineMessage.HeartbeatTick)
            }
        }

        // liveness watchdog. Prime the budget and spawn a ticker coroutine
        // that posts `LivenessTick` messages into the engine inbox every
        // `LIVENESS_CHECK_INTERVAL_MS`. The budget is decremented on each tick (on the actor)
        // and reset in `handleFrameRx` when a decoded envelope arrives — keeping all mutations
        // single-writer per ADR-002. Using `delay()` instead of wall-clock arithmetic means the
        // watchdog is correct under both real time and `runTest`'s virtual time.
        livenessBudgetMs = LIVENESS_TIMEOUT_MS
        livenessWatchdogJob?.cancel()
        livenessWatchdogJob = engineScope?.launch {
            while (true) {
                delay(LIVENESS_CHECK_INTERVAL_MS)
                inbox.trySend(EngineMessage.LivenessTick)
            }
        }

        // Presence check: scan nodes for offline transitions every 30s (piggybacks on heartbeat interval).
        if (presenceTimeout.isPositive() && presenceTimeout.isFinite()) {
            engineScope?.launch {
                while (true) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    inbox.trySend(EngineMessage.PresenceCheckTick)
                }
            }
        }

        // Flush mesh packets that arrived mid-handshake through the normal Ready pipeline
        // (presence, dedup, packets flow, RPC/ACK correlation) before draining queued sends.
        if (handshakePacketBuffer.isNotEmpty()) {
            val backlog = handshakePacketBuffer.toList()
            handshakePacketBuffer.clear()
            logger.info(TAG) { "Flushing ${backlog.size} packets received during handshake" }
            for (packet in backlog) {
                processInboundMeshPacket(packet)
            }
        }

        // Drain the snapshot: dispatch every send that wasn't cancelled while waiting.
        for (msg in snapshot) {
            if (msg.stateFlow.value == SendState.Queued) {
                dispatchSend(msg)
            }
        }

        val deferred = pendingConnectDeferred
        pendingConnectDeferred = null
        deferred?.complete(Unit)

        logger.info(TAG) { "Session Ready — myNodeNum=0x${myNodeNum.toString(16)}" }
    }

    private fun routeNormalEnvelope(fromRadio: org.meshtastic.proto.FromRadio) {
        val packet = fromRadio.packet
        val nodeInfo = fromRadio.node_info
        val queueStatus = fromRadio.queueStatus

        when {
            packet != null -> processInboundMeshPacket(packet)

            nodeInfo != null -> {
                val nodeId = NodeId(nodeInfo.num)
                markNodeHeard(nodeId)
                val existing = meshState.nodes[nodeId]
                meshState = meshState.withNodes(meshState.nodes + (nodeId to nodeInfo))
                if (nodeId == NodeId(myNodeNum)) ownNode.value = nodeInfo
                if (existing == null) {
                    emitNodeChangeOrLog(NodeChange.Added(nodeInfo))
                } else {
                    val changed = diffNodeFields(existing, nodeInfo)
                    if (changed.isNotEmpty()) {
                        emitNodeChangeOrLog(NodeChange.Updated(nodeInfo, changed))
                    }
                }
            }

            queueStatus != null -> {
                // firmware emits QueueStatus with mesh_packet_id == 0 as a
                // "queue is empty / no tracked packet" heartbeat-ish signal. Looking that up in
                // [pendingSends] is harmless but meaningless, and incorrectly treating it as a
                // successful send for a dropped packet would corrupt user-visible state. Guard.
                if (queueStatus.mesh_packet_id == 0) return
                val packetId = MessageId(queueStatus.mesh_packet_id)
                if (queueStatus.res == 0) {
                    pendingSends[packetId]?.value = SendState.Sent
                } else {
                    // the firmware rejected the packet at its transmit queue (typically
                    // queue-full). Fast-fail the handle / pending RPC instead of letting the
                    // caller wait out the full ACK timer for a packet that never left the device.
                    Routing.Error.fromValue(queueStatus.res)?.let { err ->
                        dispatcher.tryFailFromRouting(packetId.raw, err)
                    }
                    val flow = pendingSends.remove(packetId)
                    ackTimeoutJobs.remove(packetId)?.cancel()
                    if (flow != null) {
                        val current = flow.value
                        val terminal = current is SendState.Failed ||
                            current == SendState.Acked ||
                            current == SendState.Delivered
                        if (!terminal) {
                            logger.warn(TAG) {
                                "Queue rejected id=${packetId.raw} res=${queueStatus.res}"
                            }
                            flow.value = SendState.Failed(SendFailure.QueueRejected(queueStatus.res))
                        }
                    }
                }
            }

            // / route auxiliary FromRadio variants through the shared helper
            // so we get the same behaviour during Ready and during the handshake stages.
            // log_record / clientNotification / mqttClientProxyMessage / xmodemPacket /
            // deviceuiConfig / fileInfo are handled here. queueStatus is handled above (it has
            // session-specific bookkeeping in pendingSends).
            handleAuxiliaryFromRadioVariant(fromRadio, HandshakeStage.Ready) -> Unit
        }
    }

    /**
     * The Ready-stage pipeline for a single inbound [MeshPacket]: presence, dedup, public
     * `packets` emission, session-passkey latching, RPC/ACK correlation, telemetry merge, and
     * external-config-change detection. Called for live packets in [routeNormalEnvelope] and
     * for the mid-handshake backlog flushed in [transitionToReady].
     */
    private fun processInboundMeshPacket(packet: MeshPacket) {
        logger.verbose(TAG) {
            "Rx packet id=${packet.id} from=0x${packet.from.toString(16)} " +
                "port=${packet.decoded?.portnum ?: "encrypted"}"
        }
        // any frame from a node counts as presence activity.
        if (packet.from != 0) markNodeHeard(NodeId(packet.from))
        if (shouldDropDuplicateInboundPacket(packet)) return
        val decoded = packet.decoded
        if (decoded == null) {
            maybeWarnEncryptedPacketSkipped()
            return
        }
        if (decoded.portnum == PortNum.UNKNOWN_APP) {
            logger.warn(TAG) {
                "Dropping packet id=${packet.id} with unknown port from=0x${packet.from.toString(16)}"
            }
            events.tryEmit(
                MeshEvent.ProtocolWarning(
                    "packet dropped for unknown port",
                    details = mapOf(
                        "id" to packet.id,
                        "from" to packet.from,
                        "port" to decoded.portnum.name,
                    ),
                ),
            )
            return
        }
        emitPacketOrLog(packet)
        if (decoded.portnum == PortNum.ADMIN_APP) {
            // every admin response refreshes the responder's session passkey — latch them all
            // (keyed per node) so remote admin sessions stay alive without re-seeding.
            runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }
                .getOrNull()
                ?.let { latchSessionPasskey(packet.from.takeIf { n -> n != 0 } ?: myNodeNum, it) }
        }
        // RPC dispatch: complete any pending getter / traceRoute / neighborInfo
        // request matching this packet's request_id. Must run before processRoutingAck
        // so a route_reply doesn't get mistakenly classified as a generic Acked send.
        dispatcher.tryComplete(packet)
        processRoutingAck(packet)
        // Telemetry → node update: merge device_metrics into the node DB so that
        // NodeChange subscribers see battery/telemetry changes without calling TelemetryApi.
        maybeMergeDeviceMetrics(packet)
        maybeEmitCongestionWarning(packet)
        // External config change detection: unsolicited admin messages from firmware
        // indicating another client modified channels/config on this device.
        maybeProcessExternalAdminChange(packet)
    }

    /**
     * Shared dispatch for `FromRadio` variants that are not part of the strict handshake state
     * machine but can arrive at any time (clientNotification, mqttClientProxyMessage,
     * xmodemPacket, deviceuiConfig, fileInfo, log_record). Returns `true` if the frame was
     * handled (or explicitly warned about); `false` if no auxiliary variant matched so the
     * caller can keep walking its own arms.
     *
     * Variants we cannot yet act on are surfaced as [MeshEvent.ProtocolWarning] with structured
     * `details` so subscribers can spot "we received it but ignored it" instead of silent loss.
     * The optional `stage` detail lets hosts distinguish handshake-time arrivals from Ready
     * arrivals without inspecting `connection`.
     */
    private fun handleAuxiliaryFromRadioVariant(
        fromRadio: org.meshtastic.proto.FromRadio,
        stage: HandshakeStage,
    ): Boolean = when {
        fromRadio.log_record != null -> {
            // Legacy text log. Drop explicitly — the firmware has a richer log channel via
            // ClientNotification; we don't persist these.
            true
        }

        fromRadio.clientNotification != null -> {
            // surface the raw notification, then
            // translate the security-relevant payload variants into typed SecurityWarning
            // sub-events so hosts can react without string-matching the message field.
            val cn = fromRadio.clientNotification!!
            events.tryEmit(MeshEvent.Notification(cn))
            when {
                cn.duplicated_public_key != null ->
                    events.tryEmit(MeshEvent.SecurityWarning.DuplicatedPublicKey)

                cn.low_entropy_key != null ->
                    events.tryEmit(MeshEvent.SecurityWarning.LowEntropyKey)
            }
            true
        }

        fromRadio.mqttClientProxyMessage != null -> {
            // MQTT client proxy: the device tunnels its MQTT traffic through the host.
            // Surfaced as a typed event; the host relays to the broker and answers via
            // RadioClient.sendRaw(ToRadio(mqttClientProxyMessage = …)).
            events.tryEmit(MeshEvent.MqttProxyMessage(fromRadio.mqttClientProxyMessage!!))
            true
        }

        fromRadio.xmodemPacket != null -> {
            // XModem file-transfer frame (firmware update / file transfer flows).
            events.tryEmit(MeshEvent.XmodemPacket(fromRadio.xmodemPacket!!))
            true
        }

        fromRadio.deviceuiConfig != null -> {
            // Capture deviceuiConfig if it arrives post-handshake (e.g., after storeUIConfig).
            pendingDeviceUIConfig = fromRadio.deviceuiConfig
            true
        }

        fromRadio.fileInfo != null -> {
            events.tryEmit(MeshEvent.FileInfo(fromRadio.fileInfo!!))
            true
        }

        // Lockdown (protobufs 2.7.25+, firmware support in flight): surfaced as a structured
        // warning until a typed event lands alongside AdminMessage.lockdown_auth support.
        fromRadio.lockdown_status != null -> {
            warnUnhandledVariant("lockdown_status", stage)
            true
        }

        // queueStatus arriving mid-handshake is not actionable (we have no
        // pendingSends yet) — surface it explicitly so it's not silently dropped.
        stage != HandshakeStage.Ready && fromRadio.queueStatus != null -> {
            warnUnhandledVariant("queue_status", stage)
            true
        }

        else -> false
    }

    private fun warnUnhandledVariant(variant: String, stage: HandshakeStage) {
        val details = mutableMapOf<String, Any?>("variant" to variant)
        if (stage != HandshakeStage.Ready) details["stage"] = stage.toDisplayName()
        events.tryEmit(
            MeshEvent.ProtocolWarning("FromRadio variant not yet surfaced", details = details),
        )
    }

    private fun processRoutingAck(packet: MeshPacket) {
        val decoded = packet.decoded
        if (decoded == null) {
            // an encrypted-only MeshPacket has no decoded payload, so we
            // cannot correlate it to a pending send. Surface this as a rate-limited
            // ProtocolWarning rather than dropping silently — repeated occurrences signal an
            // encryption / channel-key mismatch that hosts should investigate.
            maybeWarnEncryptedPacketSkipped()
            return
        }
        if (decoded.portnum != PortNum.ROUTING_APP) return

        val routing = try {
            Routing.ADAPTER.decode(decoded.payload)
        } catch (_: Exception) {
            return
        }
        val messageId = MessageId(decoded.request_id)

        // RPC failure: a Routing.Error variant matching a pending getter / traceRoute
        // is the failure surface for that call. Try the dispatcher BEFORE checking pendingSends
        // requests don't allocate a MessageHandle, so they wouldn't be in pendingSends.
        val errorReason = routing.error_reason
        if (errorReason != null && errorReason != Routing.Error.NONE &&
            dispatcher.tryFailFromRouting(messageId.raw, errorReason)
        ) {
            // Defensive cleanup if a stale pendingSends entry shared the wire id.
            pendingSends.remove(messageId)
            ackTimeoutJobs.remove(messageId)?.cancel()
            return
        }

        val flow = pendingSends[messageId] ?: return

        // ignore duplicate routing ACKs. Once a handle reaches a terminal state
        // (Acked/Delivered/Failed) the engine has already settled the user-visible outcome;
        // a second device-emitted Routing reply (legitimate retry, mesh echo, or relayed ack)
        // must not flip the state again. Defensive even though terminal entries are removed
        // from pendingSends below — guards against any future caller that re-inserts.
        val current = flow.value
        if (current is SendState.Failed || current == SendState.Acked || current == SendState.Delivered) {
            pendingSends.remove(messageId)
            ackTimeoutJobs.remove(messageId)?.cancel()
            return
        }

        // branch on the Routing oneof variant rather than null-checking the
        // error_reason field. Wire generates `error_reason: Error?` and the on-wire encoding
        // can either omit the tag (null) or include `NONE` explicitly — both mean "ACK". A
        // route_reply payload with no error_reason must NOT be misclassified as a NONE-acked
        // request. The `route_request` arm is informational (we are a phone-class client; we
        // don't participate in route discovery) and is treated as a no-op.
        val routeRequest = routing.route_request
        val routeReply = routing.route_reply
        // errorReason is consumed below; kept local rather than re-reading the field on each arm.

        when {
            routeRequest != null -> {
                // phone-class client; we don't participate in route
                // discovery. Explicit no-op so the Routing.Error fall-through doesn't mark
                // an unrelated handle as Acked.
                logger.debug(TAG) { "Ignoring Routing.route_request for id=$messageId" }
            }

            errorReason != null && errorReason != Routing.Error.NONE -> {
                // translate wire-level Routing.Error enum into the public
                // SendFailure sealed type promised by error-taxonomy.md. Specific enum values
                // map to specific sealed variants so exhaustive-when consumers can branch on
                // the common failure modes; everything else falls through to SendFailure.Other
                // (which still carries the raw enum so diagnostics aren't lost).
                val failure: SendFailure = when (errorReason) {
                    Routing.Error.NO_ROUTE,
                    Routing.Error.GOT_NAK,
                    -> SendFailure.NoRoute

                    Routing.Error.MAX_RETRANSMIT -> SendFailure.MaxRetransmit

                    Routing.Error.TIMEOUT -> SendFailure.Timeout

                    Routing.Error.DUTY_CYCLE_LIMIT -> SendFailure.DutyCycleLimit

                    else -> SendFailure.Other(errorReason)
                }
                flow.value = SendState.Failed(failure)
                pendingSends.remove(messageId)
                ackTimeoutJobs.remove(messageId)?.cancel()
            }

            routeReply != null || errorReason == Routing.Error.NONE -> {
                flow.value = SendState.Acked
                pendingSends.remove(messageId)
                ackTimeoutJobs.remove(messageId)?.cancel()
            }

            else -> {
                // No oneof arm set — informational, not actionable.
                logger.debug(TAG) { "Routing payload with no oneof variant for id=$messageId" }
            }
        }
    }

    /**
     * When a TELEMETRY_APP packet arrives with device_metrics, merge them into the corresponding
     * node in the node DB and emit [NodeChange.Updated] so node-list subscribers see telemetry
     * changes (battery, channel utilization, etc.) without separate TelemetryApi subscription.
     */
    private fun maybeMergeDeviceMetrics(packet: MeshPacket) {
        val decoded = packet.decoded ?: return
        if (decoded.portnum != PortNum.TELEMETRY_APP) return
        if (packet.from == 0) return

        val telemetry = try {
            Telemetry.ADAPTER.decode(decoded.payload)
        } catch (_: Exception) {
            return
        }

        val deviceMetrics = telemetry.device_metrics ?: return
        val nodeId = NodeId(packet.from)
        val existing = meshState.nodes[nodeId] ?: return

        val updated = existing.copy(device_metrics = deviceMetrics)
        meshState = meshState.withNodes(meshState.nodes + (nodeId to updated))
        if (nodeId == NodeId(myNodeNum)) ownNode.value = updated

        val changed = diffNodeFields(existing, updated)
        if (changed.isNotEmpty()) {
            emitNodeChangeOrLog(NodeChange.Updated(updated, changed))
        }
    }

    private fun maybeEmitCongestionWarning(packet: MeshPacket) {
        val decoded = packet.decoded ?: return
        if (decoded.portnum != PortNum.TELEMETRY_APP) return
        if (packet.from == 0) return

        val telemetry = try {
            Telemetry.ADAPTER.decode(decoded.payload)
        } catch (_: Exception) {
            return
        }

        val deviceMetrics = telemetry.device_metrics ?: return
        val airUtilTx: Float = deviceMetrics.air_util_tx ?: 0f
        val channelUtil: Float = deviceMetrics.channel_utilization ?: 0f

        if (airUtilTx == 0f && channelUtil == 0f) return

        val metrics = CongestionMetrics(airUtilTx = airUtilTx, channelUtil = channelUtil)
        val nodeId = NodeId(packet.from)
        val prevLevel = lastCongestionLevel[nodeId]

        if (prevLevel != metrics.level) {
            lastCongestionLevel[nodeId] = metrics.level
            events.tryEmit(MeshEvent.CongestionWarning(metrics))
        }
    }

    /**
     * Detect unsolicited admin messages from the firmware that indicate an external client
     * changed channels, config, or module config on the connected device. Updates local
     * state (channelsState / configBundleState) and emits [MeshEvent.ExternalConfigChange].
     *
     * Only processes packets addressed to us (from our own node) with request_id == 0
     * (unsolicited push from firmware, not a response to our own RPC).
     */
    private fun maybeProcessExternalAdminChange(packet: MeshPacket) {
        val decoded = packet.decoded ?: return
        if (decoded.portnum != PortNum.ADMIN_APP) return
        // Only consider packets from our own node (firmware pushing changes locally)
        if (packet.from != myNodeNum && packet.from != 0) return
        // Solicited responses have a non-zero request_id matching a pending RPC
        if (decoded.request_id != 0) return

        val adminMsg = try {
            AdminMessage.ADAPTER.decode(decoded.payload)
        } catch (_: Exception) {
            return
        }

        adminMsg.get_channel_response?.let { channel ->
            logger.info(TAG) { "External channel change detected (index=${channel.index})" }
            updateChannelAndPersist(channel)
            events.tryEmit(MeshEvent.ExternalConfigChange(ExternalChangeKind.CHANNEL))
            return
        }

        adminMsg.get_config_response?.let { config ->
            logger.info(TAG) { "External config change detected" }
            val current = configBundleState.value ?: return
            val merged = mergeConfigs(current.configs, listOf(config))
            val updated = current.copy(configs = merged)
            configBundleState.value = updated
            // Persist the merged config bundle
            engineScope?.launch {
                if (storageDegraded) return@launch
                try {
                    storage?.saveConfig(updated)
                } catch (_: Exception) { /* non-fatal */ }
            }
            events.tryEmit(MeshEvent.ExternalConfigChange(ExternalChangeKind.CONFIG))
            return
        }

        adminMsg.get_module_config_response?.let { moduleConfig ->
            logger.info(TAG) { "External module config change detected" }
            val current = configBundleState.value ?: return
            val merged = mergeModuleConfigs(current.moduleConfigs, listOf(moduleConfig))
            val updated = current.copy(moduleConfigs = merged)
            configBundleState.value = updated
            engineScope?.launch {
                if (storageDegraded) return@launch
                try {
                    storage?.saveConfig(updated)
                } catch (_: Exception) { /* non-fatal */ }
            }
            events.tryEmit(MeshEvent.ExternalConfigChange(ExternalChangeKind.MODULE_CONFIG))
            return
        }
    }

    /**
     * rate-limited (≤ once per minute) ProtocolWarning for encrypted MeshPackets that
     * skip ACK correlation. Avoids log spam on encrypted-only meshes while still surfacing
     * the situation to consumers.
     */
    private fun maybeWarnEncryptedPacketSkipped() {
        val now = clock.now().toEpochMilliseconds()
        if (now - lastEncryptedSkipWarningAtMs < ENCRYPTED_SKIP_WARNING_INTERVAL_MS) return
        lastEncryptedSkipWarningAtMs = now
        logger.warn(TAG) { "Dropping encrypted packet without decoded payload" }
        events.tryEmit(
            MeshEvent.ProtocolWarning(
                "encrypted packet skipped for ACK correlation",
                details = mapOf("rate_limited" to true),
            ),
        )
    }

    private fun reportMalformedFrame(message: String, details: Map<String, Any?>) {
        logger.warn(TAG) { message }
        events.tryEmit(MeshEvent.ProtocolWarning(message, details = details))
    }

    private fun shouldDropDuplicateInboundPacket(packet: MeshPacket): Boolean {
        if (packet.id == 0 || packet.from == 0) return false
        val key = InboundPacketKey(
            from = packet.from,
            to = packet.to,
            channel = packet.channel,
            id = packet.id,
        )
        if (!recentInboundPacketKeySet.add(key)) {
            logger.debug(TAG) {
                "Dropping duplicate inbound packet id=${packet.id} from=0x${packet.from.toString(16)}"
            }
            return true
        }
        recentInboundPacketKeys.addLast(key)
        if (recentInboundPacketKeys.size > INBOUND_PACKET_DEDUP_CAP) {
            recentInboundPacketKeySet.remove(recentInboundPacketKeys.removeFirst())
        }
        return false
    }

    /**
     * drain [preSendQueue] and complete every pending state flow with [failure].
     *
     * Called from two terminal paths:
     *  - [cleanup] with [SendFailure.Disconnected] when the engine winds down for any reason.
     *  - [handleHandshakeTimeout] with [SendFailure.HandshakeFailed] so callers can distinguish
     *    "session never reached Ready" from "transport went away mid-session".
     *
     * Skips entries whose flow has already reached a terminal state (e.g. cancelled while
     * waiting). Removes from [pendingSends] so the [cleanup] sweep doesn't double-write.
     */
    private fun failPreSendQueueWith(failure: SendFailure) {
        if (preSendQueue.isEmpty()) return
        for (msg in preSendQueue) {
            val state = msg.stateFlow.value
            val terminal = state is SendState.Failed || state == SendState.Acked || state == SendState.Delivered
            if (!terminal) {
                msg.stateFlow.value = SendState.Failed(failure)
            }
            pendingSends.remove(msg.id)
        }
        preSendQueue.clear()
    }

    private fun handleTransportStateChanged(msg: EngineMessage.TransportStateChanged) {
        when (val s = msg.state) {
            is TransportState.Error -> {
                logger.warn(TAG, s.cause) { "Transport error: ${s.cause.message}" }
                val cause = MeshtasticException.tag(
                    MeshtasticException.Transport(s.cause.message ?: "transport error", s.cause),
                    transportIdentity = transport.identity,
                    operation = "transport.observe",
                )
                events.tryEmit(MeshEvent.TransportError(cause))

                // branch on whether the engine should attempt reconnect.
                val retriable = autoReconnectConfig.enabled &&
                    s.recoverable &&
                    s.cause !is MeshtasticException.FirmwareTooOld &&
                    !disconnecting
                if (retriable) {
                    scheduleReconnect(cause)
                } else {
                    if (handshakeStage == HandshakeStage.Ready) {
                        connectionState.value = ConnectionState.Reconnecting(cause, 1)
                    }
                    supervisorJobRef.value?.cancel()
                }
            }

            else -> logger.verbose(TAG) { "Transport state → ${msg.state}" }
        }
    }

    private fun scheduleReconnect(cause: MeshtasticException) {
        val cap = autoReconnectConfig.maxAttempts
        if (cap != null && reconnectAttempt >= cap) {
            logger.warn(TAG, cause) {
                "Auto-reconnect attempt cap ($cap) exhausted; tearing down session"
            }
            connectionState.value = ConnectionState.Disconnected
            supervisorJobRef.value?.cancel()
            return
        }
        reconnectAttempt += 1
        autoReconnectInProgress = true

        // Reset session-level state so the next handshake starts clean. We deliberately keep
        // engineScope, storage, supervisorJobRef and channels live across the cycle.
        handshakeStage = HandshakeStage.Idle
        configBundleState.value = null
        pendingNodes.clear()
        pendingConfigs.clear()
        pendingModuleConfigs.clear()
        pendingChannels.clear()
        pendingMyInfo = null
        pendingMetadata = null
        settleBuffer.clear()
        handshakePacketBuffer.clear()
        for ((_, job) in ackTimeoutJobs) job.cancel()
        ackTimeoutJobs.clear()
        stage2WatchdogJob?.cancel()
        stage2WatchdogJob = null
        stage2ProgressCounter = 0L
        recentInboundPacketKeys.clear()
        recentInboundPacketKeySet.clear()
        livenessWatchdogJob?.cancel()
        livenessWatchdogJob = null

        // Fail in-flight sends — their wire IDs won't survive the device-side session restart.
        for ((_, stateFlow) in pendingSends) {
            stateFlow.value = SendState.Failed(SendFailure.Disconnected)
        }
        pendingSends.clear()
        failPreSendQueueWith(SendFailure.Disconnected)

        connectionState.value = ConnectionState.Reconnecting(cause, reconnectAttempt)

        val backoff = computeBackoff(reconnectAttempt)
        logger.info(TAG, cause) {
            "Auto-reconnect attempt $reconnectAttempt scheduled in ${backoff.inWholeMilliseconds} ms"
        }
        reconnectJob?.cancel()
        reconnectJob = engineScope?.launch {
            delay(backoff)
            inbox.trySend(EngineMessage.ReconnectTick(cause, reconnectAttempt))
        }
    }

    private fun handleReconnectTick(msg: EngineMessage.ReconnectTick) {
        if (!autoReconnectInProgress) return
        val scope = engineScope ?: return
        scope.launch {
            val outcome = try {
                runCatching { transport.disconnect() }
                transport.connect()
                EngineMessage.ReconnectAttemptResult(success = true, cause = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MeshtasticException) {
                EngineMessage.ReconnectAttemptResult(success = false, cause = e)
            } catch (e: Exception) {
                EngineMessage.ReconnectAttemptResult(
                    success = false,
                    cause = MeshtasticException.tag(
                        MeshtasticException.Transport(e.message ?: "reconnect attempt failed", e),
                        transportIdentity = transport.identity,
                        operation = "engine.reconnect",
                    ),
                )
            }
            inbox.trySend(outcome)
        }
    }

    private fun handleReconnectAttemptResult(msg: EngineMessage.ReconnectAttemptResult) {
        if (!autoReconnectInProgress) return
        if (msg.success) {
            logger.info(TAG) { "Auto-reconnect attempt $reconnectAttempt succeeded" }
            livenessBudgetMs = LIVENESS_TIMEOUT_MS
            startStage1Handshake()
        } else {
            val cause = msg.cause ?: MeshtasticException.tag(
                MeshtasticException.Transport("reconnect attempt failed"),
                transportIdentity = transport.identity,
                operation = "engine.reconnect",
            )
            logger.warn(TAG, cause) { "Auto-reconnect attempt $reconnectAttempt failed; rescheduling" }
            scheduleReconnect(cause)
        }
    }

    private fun computeBackoff(attempt: Int): kotlin.time.Duration {
        val base = autoReconnectConfig.initialBackoff.inWholeMilliseconds.toDouble() *
            autoReconnectConfig.backoffMultiplier.pow((attempt - 1).coerceAtLeast(0))
        val capped = base.coerceAtMost(autoReconnectConfig.maxBackoff.inWholeMilliseconds.toDouble())
        val jitter = autoReconnectConfig.jitter
        val multiplier = if (jitter == 0.0) 1.0 else 1.0 + (Random.nextDouble() * 2.0 - 1.0) * jitter
        val finalMs = (capped * multiplier).coerceAtLeast(0.0).toLong()
        return finalMs.milliseconds
    }

    private fun handleSend(msg: EngineMessage.Send) {
        // reject duplicate MessageId for an in-flight (non-terminal) send. Without this
        // check the second handle would silently overwrite the first in `pendingSends`, leaving
        // the original handle stranded forever.
        val existing = pendingSends[msg.id]
        if (existing != null) {
            val state = existing.value
            val terminal = state is SendState.Failed || state == SendState.Acked || state == SendState.Delivered
            if (!terminal) {
                logger.warn(TAG) { "Send id=${msg.id} collides with in-flight handle; rejecting" }
                msg.stateFlow.value = SendState.Failed(SendFailure.IdCollision)
                return
            }
        }
        pendingSends[msg.id] = msg.stateFlow

        if (handshakeStage != HandshakeStage.Ready) {
            preSendQueue.add(msg)
            logger.debug(TAG) { "Send queued id=${msg.id} (pre-Ready)" }
            return
        }

        dispatchSend(msg)
    }

    private fun dispatchSend(msg: EngineMessage.Send) {
        // Ensure the wire packet ID is set to the SDK-level MessageId value so that
        // QueueStatus / Routing ACK correlation via pendingSends[MessageId(x)] works.
        val wireId = if (msg.packet.id != 0) msg.packet.id else msg.id.raw
        // Remote ADMIN_APP packets pick up the target's session passkey + PKC routing here
        // (no-op for everything else).
        val packet = prepareOutboundAdminPacket(
            msg.packet.copy(
                from = if (msg.packet.from == 0 && myNodeNum != 0) myNodeNum else msg.packet.from,
                id = wireId,
            ),
        )
        // Re-key so both QueueStatus and Routing ACK can look up via MessageId(wireId).
        pendingSends.remove(msg.id)
        pendingSends[MessageId(wireId)] = msg.stateFlow

        val encoded = WireCodec.encodeToRadio(ToRadio(packet = packet))
        outbound.trySend(Frame(ByteString(encoded)))
        // Transition Queued → Sent; the device will confirm with QueueStatus.
        msg.stateFlow.value = SendState.Sent
        logger.debug(TAG) { "Send dispatched id=${msg.id}" }

        val wantsResponse = packet.decoded?.want_response == true

        // Fire-and-forget broadcasts (want_ack=false, no want_response): auto-resolve to
        // Acked immediately. No firmware ACK will arrive so without this the
        // MessageHandle.await() would suspend forever.
        if (packet.to == BROADCAST_ADDR && !packet.want_ack && !wantsResponse) {
            msg.stateFlow.value = SendState.Acked
            pendingSends.remove(MessageId(wireId))
            logger.debug(TAG) { "Broadcast (fire-and-forget) auto-acked id=${msg.id}" }
            return
        }

        // Arm an ACK timeout for any send expecting firmware feedback:
        //  • unicast want_ack — waits for recipient's Routing ACK
        //  • broadcast want_ack — waits for firmware's implicit ACK (relay overheard)
        //  • want_response requests (e.g. AdminMessage.get_owner_request)
        if (packet.want_ack || wantsResponse) {
            val key = MessageId(wireId)
            val scope = engineScope ?: return
            ackTimeoutJobs.remove(key)?.cancel()
            ackTimeoutJobs[key] = scope.launch {
                delay(sendTimeout)
                inbox.trySend(EngineMessage.SendAckTimeout(key))
            }
        }
    }

    private fun handleCancelHandle(msg: EngineMessage.CancelHandle) {
        val stateFlow = pendingSends[msg.id] ?: return
        if (stateFlow.value == SendState.Queued) {
            stateFlow.value = SendState.Failed(SendFailure.Cancelled)
            pendingSends.remove(msg.id)
        }
        // regardless of state, drop any pending ACK timer so we don't post a stale
        // SendAckTimeout for a handle the user has explicitly cancelled.
        ackTimeoutJobs.remove(msg.id)?.cancel()
    }

    private fun handleHeartbeatTick() {
        if (handshakeStage != HandshakeStage.Ready) return
        if (!bleHeartbeatEnabled && transport.identity.raw.startsWith("ble:")) return
        // keep-alive heartbeats use nonce=0 (see [keepaliveHeartbeat] for rationale).
        sendToRadio(ToRadio(heartbeat = keepaliveHeartbeat))
        logger.verbose(TAG) { "Heartbeat sent nonce=0" }
        // piggy-back heartbeat persistence on the 30 s heartbeat tick so we write at most
        // a bounded number of rows per session tick, even on busy meshes.
        flushDirtyHeartbeats()
    }

    private fun handlePresenceCheckTick() {
        if (handshakeStage != HandshakeStage.Ready) return
        val now = clock.now().toEpochMilliseconds()
        val timeoutMs = presenceTimeout.inWholeMilliseconds

        for ((nodeId, lastMs) in lastHeartbeatAt) {
            if (nodeId == NodeId(myNodeNum)) continue
            val elapsed = now - lastMs
            if (elapsed > timeoutMs && nodeId !in offlineNodes) {
                offlineNodes.add(nodeId)
                val lastHeardSec = (lastMs / 1000).toInt()
                emitNodeChangeOrLog(NodeChange.WentOffline(nodeId, lastHeardSec))
            }
        }
    }

    /**
     * one-shot Stage 1 retry. If we're still waiting for the device's first
     * `my_info`/config envelope at the half-budget mark, re-send `want_config_id = NONCE_STAGE1`
     * once. Bounded by the original 20 s Stage 1 timeout. Conservative by design — a chatty
     * retry policy can confuse the firmware's want_config_id state machine.
     */
    private fun handleStage1RetryWantConfig() {
        if (handshakeStage != HandshakeStage.Stage1Draining) return
        if (pendingMyInfo != null) return
        logger.warn(TAG) { "Stage 1 silent — retrying want_config_id=$NONCE_STAGE1" }
        sendToRadio(ToRadio(want_config_id = NONCE_STAGE1))
    }

    private fun handleHandshakeTimeout(msg: EngineMessage.HandshakeTimeout) {
        if (handshakeStage == HandshakeStage.Ready) return // already succeeded
        // Stale timer from a stage we've already advanced past — ignore.
        if (handshakeStage != msg.stage) return
        logger.error(TAG) { "Handshake timeout at stage ${msg.stage.toDisplayName()}" }

        // emit a structured ProtocolWarning so consumers can distinguish
        // "seed never landed" (handshake reached SeedingSession but get_owner_response never
        // arrived) from "Stage 1/2 never started". The thrown HandshakeTimeout exception
        // already carries a stage string but only callers awaiting connect() see it; this
        // event surfaces the same signal on the events flow for passive observers.
        events.tryEmit(
            MeshEvent.ProtocolWarning(
                "handshake stage timed out",
                details = mapOf("stage" to msg.stage.toDisplayName()),
            ),
        )

        // fail any sends queued before the handshake completed with HandshakeFailed
        // (instead of Disconnected) so callers can distinguish "session never reached Ready"
        // from "transport went away mid-session". Must happen before we cancel the supervisor —
        // once cleanup() runs it would otherwise mark these as Disconnected.
        failPreSendQueueWith(SendFailure.HandshakeFailed)

        val cause = MeshtasticException.HandshakeTimeout(msg.stage.toDisplayName())
        val deferred = pendingConnectDeferred
        pendingConnectDeferred = null
        deferred?.completeExceptionally(cause)

        // Cancel supervisor — cleanup() will run and emit Disconnected.
        supervisorJobRef.value?.cancel()
    }

    /**
     * liveness watchdog tick. Runs on the engine actor — so sharing
     * [livenessBudgetMs] with [handleFrameRx] is safe per ADR-002 (single-writer). When the
     * budget is exhausted we emit a `TransportError` (via `handleDisconnect`) explaining
     * **why** the session is dropping, then run the normal teardown path. Gated on
     * [disconnecting] and `handshakeStage == Ready` so ticks arriving mid-teardown or pre-Ready
     * can't double-fire with a user-initiated disconnect or the TCP read timeout.
     */
    private fun handleLivenessTick() {
        if (disconnecting) return
        if (handshakeStage != HandshakeStage.Ready) return
        livenessBudgetMs -= LIVENESS_CHECK_INTERVAL_MS
        if (livenessBudgetMs > 0) return
        logger.warn(TAG) { "Liveness timeout — no FromRadio for ${LIVENESS_TIMEOUT_MS}ms" }
        val cause = MeshtasticException.Transport("liveness timeout", cause = null)
        // handleDisconnect emits the TransportError event for us and cancels the supervisor.
        handleDisconnect(EngineMessage.Disconnect(cause))
    }

    private fun failHandshakeWithProtocol(reason: String) {
        logger.error(TAG) { reason }
        failPreSendQueueWith(SendFailure.HandshakeFailed)
        val cause = MeshtasticException.Protocol(reason)
        val deferred = pendingConnectDeferred
        pendingConnectDeferred = null
        deferred?.completeExceptionally(cause)
        supervisorJobRef.value?.cancel()
    }

    internal fun sendToRadio(msg: ToRadio) {
        try {
            val encoded = WireCodec.encodeToRadio(msg)
            outbound.trySend(Frame(ByteString(encoded)))
        } catch (e: Exception) {
            logger.warn(TAG, e) { "Failed to encode outbound ToRadio: ${e.message}" }
        }
    }

    internal fun sendAdmin(adminMsg: AdminMessage, wantResponse: Boolean = false, to: Int = myNodeNum) {
        sendAdminPacket(adminMsg, wantResponse, to)
    }

    private fun sendAdminPacket(adminMsg: AdminMessage, wantResponse: Boolean = false, to: Int = myNodeNum) {
        if (myNodeNum == 0) return
        val payload = AdminMessage.ADAPTER.encode(adminMsg).toByteString()
        val packet = MeshPacket(
            to = to,
            from = myNodeNum,
            decoded = Data(
                portnum = PortNum.ADMIN_APP,
                payload = payload,
                want_response = wantResponse,
            ),
        )
        // Remote targets pick up the session passkey + PKC routing in the shared choke point.
        sendToRadio(ToRadio(packet = prepareOutboundAdminPacket(packet)))
    }

    // ---- C-shared-flow emit helpers ----
    // The hot SharedFlows (`packets`, `nodes`, `events`) all use BufferOverflow.DROP_OLDEST,
    // which means `tryEmit` is always supposed to return `true`. We still wrap each call site
    // through these helpers so:
    //   • a future BufferOverflow change (or a misconfigured custom replay buffer) doesn't
    //     silently drop data without surfacing a `MeshEvent.PacketsDropped` signal, and
    //   • all packet-drop accounting flows through one place that subscribers can observe.

    private fun emitPacketOrLog(packet: MeshPacket) {
        if (!packets.tryEmit(packet)) {
            logger.warn(TAG) { "packets flow rejected emit (id=${packet.id}); reporting drop" }
            events.tryEmit(MeshEvent.PacketsDropped(DroppedFlow.Packets, count = 1))
        }
    }

    private fun emitNodeChangeOrLog(change: NodeChange) {
        if (!nodes.tryEmit(change)) {
            logger.warn(TAG) { "nodes flow rejected emit ($change); reporting drop" }
            events.tryEmit(MeshEvent.PacketsDropped(DroppedFlow.Events, count = 1))
        }
    }

    /**
     * Atomically patches [channelsState] with the updated [channel] and enqueues a
     * best-effort storage flush. Called from [AdminApiImpl] after a successful `setChannel` ACK.
     *
     * Thread-safe: uses `StateFlow.update` for the in-memory atomic update.
     * Storage write is fire-and-forget on [engineScope] — failure is logged but does NOT
     * degrade the session (the next handshake re-syncs channels from the device anyway).
     */
    internal fun updateChannelAndPersist(channel: org.meshtastic.proto.Channel) {
        val idx = channel.index
        if (idx < 0 || idx > ChannelIndex.MAX_CHANNEL_INDEX) {
            logger.warn(TAG) { "setChannel: ignoring out-of-range index $idx" }
            return
        }
        channelsState.update { current: List<org.meshtastic.proto.Channel>? ->
            val list = (current ?: emptyList()).toMutableList()
            when {
                idx < list.size -> list[idx] = channel

                idx == list.size -> list.add(channel)

                // idx > list.size: sparse gap — pad with DISABLED channels to avoid holes
                else -> {
                    while (list.size < idx) {
                        list.add(
                            org.meshtastic.proto.Channel(
                                index = list.size,
                                role = org.meshtastic.proto.Channel.Role.DISABLED,
                            ),
                        )
                    }
                    list.add(channel)
                }
            }
            list.toList()
        }
        val updated = channelsState.value
        if (updated != null) {
            engineScope?.launch {
                try {
                    storage?.saveChannels(updated)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(TAG, e) { "updateChannelAndPersist: saveChannels failed (non-fatal)" }
                }
            }
        }
    }

    /**
     * Optimistically update [configBundleState] after a successful `editSettings` commit.
     *
     * Merges written [Config] and [ModuleConfig] objects into the existing bundle, replacing
     * sections that were written while preserving sections that weren't touched.
     */
    internal fun applyConfigEdits(
        writtenConfigs: List<org.meshtastic.proto.Config>,
        writtenModuleConfigs: List<org.meshtastic.proto.ModuleConfig>,
    ) {
        if (writtenConfigs.isEmpty() && writtenModuleConfigs.isEmpty()) return

        val current = configBundleState.value ?: return

        val mergedConfigs = if (writtenConfigs.isEmpty()) {
            current.configs
        } else {
            mergeConfigs(current.configs, writtenConfigs)
        }
        val mergedModuleConfigs = if (writtenModuleConfigs.isEmpty()) {
            current.moduleConfigs
        } else {
            mergeModuleConfigs(current.moduleConfigs, writtenModuleConfigs)
        }

        val updated = current.copy(configs = mergedConfigs, moduleConfigs = mergedModuleConfigs)
        configBundleState.value = updated
        meshState = meshState.withConfig(updated)

        // Persist the updated bundle to storage.
        engineScope?.launch {
            if (storageDegraded) return@launch
            try {
                storage?.saveConfig(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, e) { "applyConfigEdits: saveConfig failed (non-fatal)" }
            }
        }
    }

    // ---- storage error wrapping ----
    // All write paths funnel through `reportStorageDegraded` on failure so the user-visible
    // events flow carries a single StorageDegraded signal per session. `runStorageWrite`
    // is the actor-local form (blocking suspend); call sites already inside `engineScope.launch`
    // use `runStorageWriteAsync`.

    private fun reportStorageDegraded(reason: String, cause: Throwable?) {
        logger.warn(TAG, cause) { "Storage degraded: $reason" }
        if (storageDegraded) return
        storageDegraded = true
        events.tryEmit(MeshEvent.StorageDegraded(reason))
    }

    // timestamp + dirty-mark a per-node heartbeat observation. Flushed to storage on the
    // next heartbeat tick (handleHeartbeatTick) or at the Ready transition.
    private fun markNodeHeard(nodeId: NodeId) {
        val now = clock.now().toEpochMilliseconds()
        lastHeartbeatAt[nodeId] = now
        dirtyHeartbeats.add(nodeId)
        if (offlineNodes.remove(nodeId)) {
            emitNodeChangeOrLog(NodeChange.CameOnline(nodeId))
        }
    }

    private fun flushDirtyHeartbeats() {
        if (dirtyHeartbeats.isEmpty()) return
        if (storageDegraded) {
            dirtyHeartbeats.clear()
            return
        }
        val s = storage ?: run {
            dirtyHeartbeats.clear()
            return
        }
        val scope = engineScope ?: return
        val snapshot = dirtyHeartbeats.map { it to (lastHeartbeatAt[it] ?: 0L) }
        dirtyHeartbeats.clear()
        scope.launch {
            try {
                for ((nodeId, ts) in snapshot) {
                    s.saveHeartbeat(nodeId, ts)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportStorageDegraded("saveHeartbeat failed: ${e.message ?: e::class.simpleName}", e)
            }
        }
    }

    // a unicast send timed out waiting for a routing ACK. Mark the handle as Failed
    // (only if it's still in a non-terminal state — a late ACK that won the race takes
    // precedence) and prune our bookkeeping.
    private fun handleSendAckTimeout(msg: EngineMessage.SendAckTimeout) {
        val flow = pendingSends[msg.id] ?: return
        ackTimeoutJobs.remove(msg.id)
        when (flow.value) {
            SendState.Queued, SendState.Sent -> {
                logger.debug(TAG) { "ACK timeout for id=${msg.id} (state was ${flow.value})" }
                flow.value = SendState.Failed(SendFailure.AckTimeout)
                pendingSends.remove(msg.id)
            }

            SendState.Acked, SendState.Delivered, is SendState.Failed -> {
                // Race resolved in favor of the real result; nothing to do.
            }
        }
    }

    private companion object {
        private const val TAG = "MeshEngine"

        // Handshake nonces (protocol.md §6).
        const val NONCE_STAGE1 = 69420
        const val NONCE_STAGE2 = 69421

        // Timeouts (ms).
        const val STAGE1_TIMEOUT_MS = 20_000L

        // one-shot retry of `want_config_id` at half the Stage 1 budget.
        const val STAGE1_RETRY_MS = 10_000L
        const val SEED_TIMEOUT_MS = 10_000L
        const val INTER_STAGE_SETTLE_MS = 100L
        const val HEARTBEAT_INTERVAL_MS = 30_000L

        // liveness watchdog. Android's `SharedRadioInterfaceService` uses
        // `LIVENESS_TIMEOUT_MILLIS = HEARTBEAT_INTERVAL_MS * 2` — if no decoded `FromRadio`
        // envelope has arrived for 60 s we treat the link as silently dead. The check interval
        // is a fraction of the timeout so we notice within one heartbeat window of expiry.
        // firmware now exposes `getConfig_max_wait()` so this could be made
        // configurable (e.g. via `RadioClient.Builder.livenessTimeout`). Keeping it hardcoded
        // for now matches Android reference behaviour; revisit when the public Builder gains
        // a knob.
        const val LIVENESS_TIMEOUT_MS = HEARTBEAT_INTERVAL_MS * 2
        const val LIVENESS_CHECK_INTERVAL_MS = HEARTBEAT_INTERVAL_MS

        // Polite-goodbye timeout: how long to wait for the final
        // `ToRadio { disconnect = true }` write to flush before we close the transport
        // anyway. Short by design — teardown latency must stay bounded.
        const val GOODBYE_TIMEOUT_MS = 500L

        // sliding-timeout watchdog parameters for Stage 2. The engine fails the handshake
        // if no Stage 2 frame arrives for one tick window (idle stall), or the total time
        // exceeds the hard cap (regardless of progress). Tick is intentionally < legacy
        // STAGE2_TIMEOUT_MS so the no-progress case still aborts within the same envelope as
        // before.
        const val STAGE2_PROGRESS_TICK_MS = 30_000L

        // hard-cap was 120s — reduce to 60s to match upstream reference
        // clients (Android `Stage2ConnectState` uses ~60s) and fail faster on stalled devices.
        const val STAGE2_HARD_CAP_MS = 60_000L

        // maximum frames buffered during the inter-stage settle window.
        const val SETTLE_BUFFER_CAP = 64

        // maximum mesh packets buffered while the handshake is in flight (flushed at Ready).
        const val HANDSHAKE_PACKET_BUFFER_CAP = 64

        // name of the legacy secondary channel used for remote admin when PKC keys are
        // unavailable. Matched case-insensitively against ChannelSettings.name.
        const val ADMIN_CHANNEL_NAME = "admin"

        // broadcast destination is the unsigned 0xFFFFFFFF address (Int.toLong → -1
        // after wire round-trip). Used so we don't arm an ACK timer for broadcasts.
        const val BROADCAST_ADDR: Int = -1

        // Firmware regenerates session passkeys every ~150s and they remain valid for ~300s.
        // A 4-minute TTL ensures we don't use a stale passkey on reconnect while still
        // avoiding unnecessary re-seeding within a single connected session.
        val SESSION_PASSKEY_TTL: Duration = 4.minutes

        // minimum interval between consecutive encrypted-packet skip warnings.
        const val ENCRYPTED_SKIP_WARNING_INTERVAL_MS: Long = 60_000L

        // bounded recent-history window for inbound packet deduplication.
        const val INBOUND_PACKET_DEDUP_CAP: Int = 256
    }

    private data class InboundPacketKey(val from: Int, val to: Int, val channel: Int, val id: Int)

    /** In-memory per-node session passkey with absolute expiry (epoch ms). */
    private class SessionPasskeyEntry(val bytes: ByteArray, val expiresAtMs: Long)
}
