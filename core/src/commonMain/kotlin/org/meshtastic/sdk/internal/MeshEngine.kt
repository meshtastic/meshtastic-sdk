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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceConnectionStatus
import org.meshtastic.proto.DisplayPalette
import org.meshtastic.proto.FileInfo
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.LoRaRegionPresetMap
import org.meshtastic.proto.LockdownAuth
import org.meshtastic.proto.LockdownStatus
import org.meshtastic.proto.LogRecord
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.AdminSessionStatus
import org.meshtastic.sdk.AutoReconnectConfig
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.ConfigPhase
import org.meshtastic.sdk.CongestionLevel
import org.meshtastic.sdk.CongestionMetrics
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.DeviceStorage
import org.meshtastic.sdk.DeviceVersion
import org.meshtastic.sdk.DisplayMirrorFrame
import org.meshtastic.sdk.DroppedFlow
import org.meshtastic.sdk.ExternalChangeKind
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.KeyVerificationPrompt
import org.meshtastic.sdk.LogLevel
import org.meshtastic.sdk.LogSink
import org.meshtastic.sdk.MeshEvent
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.MessageHandle
import org.meshtastic.sdk.MessageId
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.NodeField
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.PacedMessageHandle
import org.meshtastic.sdk.PayloadRedactor
import org.meshtastic.sdk.QueueReceipt
import org.meshtastic.sdk.RadioSessionEvent
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.RadioTransportProvider
import org.meshtastic.sdk.SendFailure
import org.meshtastic.sdk.SendState
import org.meshtastic.sdk.SessionPasskey
import org.meshtastic.sdk.StorageProvider
import org.meshtastic.sdk.TransportIdentity
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Single-writer actor that owns all mutable SDK state.
 *
 * All state is written exclusively from the engine coroutine. External coroutines interact by
 * posting [EngineMessage]s to [inbox] — never by writing to state directly.
 */
internal class MeshEngine(
    private val identity: TransportIdentity,
    private val transportProvider: RadioTransportProvider,
    private val storageProvider: StorageProvider,
    private val logger: LogSink,
    private val bleHeartbeatEnabled: Boolean,
    private val parentContext: CoroutineContext,
    private val clock: Clock = Clock.System,
    private val sendTimeout: Duration = 30.seconds,
    private val presenceTimeout: Duration = 2.hours,
    private val autoReconnectConfig: AutoReconnectConfig = AutoReconnectConfig.Disabled,
    private val skipNodeDb: Boolean = false, // config-only connect (see handleHeartbeatSettleComplete)
    private val syncTimeDuringHandshake: Boolean = false,
    private val protocolLogLevel: LogLevel = LogLevel.NONE,
    private val payloadRedactor: PayloadRedactor = PayloadRedactor.Default,
    sessionReceiveCapacity: Int? = null,
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

    // No replay: RadioClient.nodes seeds every subscription with a fresh Snapshot via
    // onSubscription (see nodeMapSnapshot()). A replay slot would hand late subscribers
    // whatever delta happened to be emitted last — NOT the Snapshot — leaving them with a
    // near-empty fold until the next reconnect.
    val nodes = MutableSharedFlow<NodeChange>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val packets = MutableSharedFlow<MeshPacket>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    // With auto-reconnect on, an overflow restarts the session so the consumer resumes on a fresh stream.
    private val sessionReceive = sessionReceiveCapacity?.let { capacity ->
        SessionReceive(capacity, restartOnOverflow = autoReconnectConfig.enabled) { sessionId ->
            inbox.trySend(EngineMessage.SessionOverflow(sessionId))
        }
    }
    val sessionEvents = sessionReceive?.events

    val events = MutableSharedFlow<MeshEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Device logs can arrive at any rate, so a slow collector loses the oldest records instead of
    // slowing the engine, and nothing is replayed.
    val deviceLogs = MutableSharedFlow<LogRecord>(
        extraBufferCapacity = DEVICE_LOG_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Live mirror: no replay, and a slow collector loses the oldest undelivered frame rather than
    // stalling the engine. With no collector a frame is dropped on emit, so none is retained.
    val displayFrames = MutableSharedFlow<DisplayMirrorFrame>(
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Latest complete display palette for the session, `null` until the device sends one. */
    val displayPalette = MutableStateFlow<DisplayPalette?>(null)

    private val displayFrameReassembler = DisplayFrameReassembler()
    private val displayPaletteReassembler = DisplayPaletteReassembler()

    /** Every decoded inbound envelope, unfiltered. Lossy by design so a slow tap never stalls the engine. */
    val rawFromRadio = MutableSharedFlow<FromRadio>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every mesh packet queued for the radio, as written. Lossy for the same reason as [rawFromRadio]. */
    val outboundPackets = MutableSharedFlow<MeshPacket>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // ADR-002 exception: cleanup() closes both channels, and connect() opens fresh ones for the next run.
    private val inboxRef = atomic(Channel<EngineMessage>(Channel.UNLIMITED))
    private val inbox: Channel<EngineMessage> get() = inboxRef.value

    private data class OutboundFrame(
        val frame: Frame,
        val lockdownLink: LockdownLink? = null,
        val pacedSend: EngineMessage.PacedSend? = null,
        // The link that enqueued the frame, so a failed write cannot tear down its replacement.
        val generation: Long = 0L,
    )

    private val outboundRef = atomic(Channel<OutboundFrame>(Channel.UNLIMITED))
    private val outbound: Channel<OutboundFrame> get() = outboundRef.value
    private val lockdownWriteMutex = Mutex()

    /**
     * Supervisor job hosting the engine and its sibling coroutines.
     *
     * **ADR-002 exception:** this atomic is the boundary between caller threads
     * (which may invoke [connect]/[disconnect] concurrently) and the actor. It
     * guards only the lifecycle handle, not engine state.
     */
    private val supervisorJobRef = atomic<Job?>(null)

    // ADR-002 exception: the last run's supervisor, which connect() joins so the next run starts after its cleanup.
    private val lastRun = atomic<Job?>(null)

    // ADR-002 exception: lifecycle idempotency; set while one connect() decides whether and when its run starts.
    private val admitting = atomic(false)

    // ADR-002 exception: lifecycle idempotency; set by disconnect(), which waits for Disconnected, so cleanup
    // must not settle on a terminal state.
    private val disconnectRequested = atomic(false)

    // The boundary handles one run owns, so its cleanup cannot close or clear a later run's.
    private class RunHandles(
        val job: Job,
        val connected: CompletableDeferred<Unit>,
        val inbox: Channel<EngineMessage>,
        val outbound: Channel<OutboundFrame>,
    )

    /**
     * Awaiter for the in-flight [connect] call.
     *
     * **ADR-002 exception:** boundary atomic between caller threads and the actor.
     * Only used to let [disconnect] unblock a pending [connect] awaiter.
     */
    private val activeConnectDeferred = atomic<CompletableDeferred<Unit>?>(null)

    private val connectionGeneration = atomic(0L)

    private val pendingSends = mutableMapOf<MessageId, MutableStateFlow<SendState>>()
    private val readySendGeneration = atomic<Long?>(null)

    // Bounds reservations from caller admission through terminal completion, including the actor inbox.
    private val pacedReservations = atomic(0)
    private val pacedSends = mutableMapOf<MessageId, EngineMessage.PacedSend>()
    private val pacedQueue = ArrayDeque<EngineMessage.PacedSend>()
    private var queueWrite: EngineMessage.PacedSend? = null
    private var queueWriteTimeout: Job? = null
    private var storage: DeviceStorage? = null
    private var meshState = MeshState()

    private var handshakeStage = HandshakeStage.Idle
    private var engineScope: CoroutineScope? = null

    // Config snapshots for storage, saved one at a time by a single consumer; conflated, since each bundle is
    // whole and only the newest has to land.
    private var configSaves: Channel<ConfigBundle>? = null
    private var frameReaderJob: Job? = null
    private var receiveGeneration = 0L

    // The link the outbound writer sends on: receiveGeneration and the transport it was opened on.
    private class WriteLink(val generation: Long, val transport: RadioTransport)

    // ADR-002 exception: the actor publishes each new link here for the outbound writer and RadioClient.
    private val writeLink = atomic<WriteLink?>(null)

    // The transport of the current or last attempt; actor-owned, replaced only between attempts.
    private var transport: RadioTransport? = null
    private var transportObserver: Job? = null

    // A connect() on [transport] returned and no drop has been handled since.
    private var linkUp = false
    private var stage1DumpStarted = false
    private var nodeDbRefreshDumpStarted = false

    // Live renumber: firmware moves my_node_num to crc32(public_key) on the first region set and
    // resends my_info. The ACK for that write carries the new number and may arrive first.
    private var renumberedTo: Int? = null
    private var heldRenumberAck: EngineMessage.PacedSend? = null
    private var heldRenumberAckFrom = 0
    private var renumberRestartJob: Job? = null

    // Firmware before 2.8.1 renumbers without resending my_info. A write to our old number then fails
    // PKI_SEND_FAIL_PUBLIC_KEY and is held for the NAK that names the new one.
    private var staleNumberWrite: EngineMessage.PacedSend? = null

    // The first region set, run by the device (QueueStatus 0) and never acked: the number moved under it.
    private var regionWrite: EngineMessage.PacedSend? = null
    private var silentRenumberProbe: Job? = null

    // Command admission captures the exact physical link and node id before posting to the actor.
    private data class LockdownLink(val generation: Long, val nodeNum: Int)

    private val lockdownLink = atomic<LockdownLink?>(null)
    private var handshakeAttempt = 0L
    private var nodeDbRefresh: MutableMap<NodeId, NodeInfo>? = null
    private var nodeDbRefreshQueued = false
    private var nodeDbRefreshRequest = 0L
    private var nodeDbRefreshWatchdog: Job? = null

    // Firmware drops a ToRadio write byte-identical to the previous one and reads heartbeat
    // nonce 1 as "broadcast NodeInfo", so keep-alive nonces count up from 2 and never reach 0 or 1.
    private var heartbeatNonce = FIRST_HEARTBEAT_NONCE
    private var myNodeNum = 0

    // R-9: snapshot of the previously-persisted my_node_num, captured at connect time so
    // we can synchronously detect identity rebinds on Stage 2 commit (before storage is cleared).
    private var previousMyNodeNum: Int? = null

    // Hardware id persisted with previousMyNodeNum; null when storage holds none (never logged).
    private var previousDeviceId: okio.ByteString? = null

    /**
     * Per-node admin session passkeys, keyed by the node num that issued them. Each node
     * (local device and every remote admin target) hands out its **own** passkey in its admin
     * responses; remote admin writes must echo the *target's* passkey back, so a single shared
     * slot would cross-contaminate concurrent admin sessions against different nodes.
     * Entries expire after [SESSION_PASSKEY_TTL], the firmware's validity window; they are
     * never persisted.
     */
    private val sessionPasskeys = mutableMapOf<Int, SessionPasskeyEntry>()
    private var nextPasskeyGeneration = 0

    /** Wire id of an outbound admin request to the key generation it was stamped with; bounded. */
    private data class ExpectedAdminReply(val sender: Int, val kind: ResponseKind<*>)

    private val adminReplyTargets = linkedMapOf<Int, ExpectedAdminReply>()

    private val requestPasskeyGenerations = mutableMapOf<Int, Int>()

    /** Public view of [sessionPasskeys] (no key bytes); recomputed by [publishAdminSessions]. */
    val adminSessions = MutableStateFlow<Map<NodeId, AdminSessionStatus>>(emptyMap())

    // Node accumulator during Stage 2 draining.
    private val pendingNodes = mutableMapOf<NodeId, NodeInfo>()

    // Config accumulator during Stage 1 draining.
    private var pendingConfigs = emptyList<org.meshtastic.proto.Config>()
    private var pendingModuleConfigs = emptyList<org.meshtastic.proto.ModuleConfig>()
    private val pendingChannels = mutableListOf<org.meshtastic.proto.Channel>()
    private var pendingMyInfo: org.meshtastic.proto.MyNodeInfo? = null
    private var pendingMetadata: org.meshtastic.proto.DeviceMetadata? = null
    private var pendingDeviceUIConfig: org.meshtastic.proto.DeviceUIConfig? = null
    private val pendingFiles = linkedMapOf<String, FileInfo>()
    private var pendingRegionPresets: LoRaRegionPresetMap? = null

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

    /** MQTT state from the last local `DeviceConnectionStatus`; null until one reports it. */
    private var lastMqttConnected: Boolean? = null

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

    /**
     * The link's firmware reported a lockdown status, so it was built with PhoneAPI access control and
     * authorizes local admin per link, `is_managed` or not (AdminModule.cpp, MESHTASTIC_PHONEAPI_ACCESS_CONTROL).
     */
    val localAdminGatedByLink = MutableStateFlow(false)

    /** This link's firmware has answered a heartbeat, so the liveness watchdog may act on silence. */
    private var heartbeatAnswered = false

    // One heartbeat and one presence loop per Ready session.
    private var heartbeatJob: Job? = null
    private var presenceJob: Job? = null
    private var disconnecting = false

    /**
     * Frames received while in [HandshakeStage.Stage1Settling]. They are decoded and
     * buffered here, then replayed through [processStage1Envelope] at the end of each settle
     * window, before the inter-stage heartbeat and before the Stage 2 request. Drop-oldest at
     * [SETTLE_BUFFER_CAP].
     */
    private val settleBuffer = ArrayDeque<FromRadio>(SETTLE_BUFFER_CAP)
    private var firmwareUpdateAdvised = false

    /**
     * `FromRadio.packet` frames (text messages, telemetry, waypoints, …) that arrive while the
     * handshake is still in flight. Devices forward live mesh traffic interleaved with the
     * config/NodeDB drain; dropping it loses user-visible messages. Buffered drop-oldest at
     * [HANDSHAKE_PACKET_BUFFER_CAP] (with a [MeshEvent.PacketsDropped] signal) and flushed
     * through the normal Ready packet pipeline in [transitionToReady].
     */
    private val handshakePacketBuffer = ArrayDeque<HeldItem>(HANDSHAKE_PACKET_BUFFER_CAP)

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

    // Stage 1 inactivity and total timers, and the wait for a dump after a boot-time `rebooted`.
    private var stage1WatchdogJob: Job? = null
    private var stage1CapJob: Job? = null
    private var stage1RebootGraceJob: Job? = null

    /** The device's channels in storage when [connect] started, for a handshake that sends none. */
    private var storedChannels: List<org.meshtastic.proto.Channel> = emptyList()

    /** Packet id of the unanswered `get_owner_request` that seeds the local passkey after Ready; 0 when none. */
    private var seedRequestId = 0
    private var seedTimeoutJob: Job? = null

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

    // The tick the backoff wait will deliver; retryNow() delivers it early.
    private var pendingTick: EngineMessage.ReconnectTick? = null
        set(value) {
            field = value
            retryWaiting.value = value != null
        }

    // ADR-002 exception: pendingTick != null, readable by retryNow() on the caller's thread.
    private val retryWaiting = atomic(false)
    private var attemptToken = 0L
    private var attemptJob: Job? = null

    // ADR-002 exception: the link the running attempt opened, set off the actor, so cleanup can close it
    // when the attempt's result never reaches the actor.
    private val attemptLink = atomic<RadioTransport?>(null)

    // Sessions restarted in a row after a handshake or liveness timeout, a handshake protocol error or a
    // receive overflow. A session that reached Ready and then ended for any other reason resets it.
    private var handshakeRecoveries = 0
    private var readyThisSession = false
    private var stableJob: Job? = null

    // A drop judged a by-design sleep: attempts before its wake bound has passed go to
    // `sleepAttempts`, outside maxAttempts. `terminalState` is the state cleanup settles on instead
    // of Disconnected: a sleep with auto-reconnect off, or Failed.
    private var sleeping: ConnectionState.DeviceSleeping? = null
    private var sleepAttempts: Int = 0
    private var terminalState: ConnectionState? = null

    // Dispatcher is engine-actor-owned; mutated only inside processMessage. RPC wire ids share
    // the same allocator as user-facing sends so a getConfig can never collide with a sendText.
    private val dispatcher = CommandDispatcher(logger)

    // Randomly seeded per engine instance: firmware dedups packets by (sender, id) with a
    // ~10-minute history window, so a deterministic seed (e.g. always starting at 1) makes
    // every session re-issue the same wire ids and the radio silently drops the repeats —
    // RPCs then time out with no response. Matches the reference clients, which both
    // randomize the starting packet id. Caught on hardware by MeshtasticBleConformanceTest.
    private val nextWireId = atomic(Random.nextInt())

    /** Test seam: runs once a connect() is admitted, before it installs its run. */
    internal var runStartProbe: (() -> Unit)? = null

    /**
     * Admission is held from the check below until this run's scope, config-save channel and children
     * are installed, so a disconnect() in that window cancels this run alone. A concurrent connect()
     * throws [MeshtasticException.AlreadyConnected] while admission is held or a run is live; after a
     * disconnect() it waits for the previous run's cleanup before starting its own.
     */
    suspend fun connect() {
        // One connect() at a time is admitted, so lastRun read below is the run current at admission.
        if (!admitting.compareAndSet(false, true)) throw MeshtasticException.AlreadyConnected()
        val connectDeferred = CompletableDeferred<Unit>()
        val sup = SupervisorJob(parentContext[Job])
        var admitted = false
        try {
            supervisorJobRef.value?.let { running ->
                // Failed is published just before its run is cancelled; wait for that run's cleanup.
                if (connectionState.value !is ConnectionState.Failed) throw MeshtasticException.AlreadyConnected()
                running.join()
            }
            // Published before the wait below, so a disconnect() during it cancels this attempt. It also lets a
            // disconnect() complete the awaiter if the actor is cancelled before it processes EngineMessage.Connect.
            activeConnectDeferred.value = connectDeferred
            val previous = lastRun.value
            // A failed or cancelled connect() returns before its run's cleanup has closed the channels and reset state.
            previous?.join()
            if (!supervisorJobRef.compareAndSet(null, sup)) throw MeshtasticException.AlreadyConnected()
            // disconnect() fails the awaiter before it takes the supervisor, so one of the two sees the other.
            if (connectDeferred.isCompleted) {
                supervisorJobRef.compareAndSet(sup, null)
                connectDeferred.await() // rethrows disconnect()'s cancellation
                throw CancellationException("disconnect requested")
            }
            lastRun.value = sup
            if (previous != null) {
                inboxRef.value = Channel(Channel.UNLIMITED)
                outboundRef.value = Channel(Channel.UNLIMITED)
            }
            runStartProbe?.invoke()
            // After the previous run's cleanup, which a disconnect() may still have been waiting on.
            disconnectRequested.value = false
            startRun(RunHandles(sup, connectDeferred, inboxRef.value, outboundRef.value))
            admitted = true
        } finally {
            if (!admitted) {
                supervisorJobRef.compareAndSet(sup, null)
                sup.cancel()
                activeConnectDeferred.compareAndSet(connectDeferred, null)
            }
            admitting.value = false
        }

        try {
            connectDeferred.await()
        } catch (e: Exception) {
            // Clean up on failure so a subsequent connect() can start fresh
            supervisorJobRef.compareAndSet(sup, null)
            sup.cancel()
            throw e
        } finally {
            // clear the awaiter slot so a subsequent disconnect() doesn't try to fail
            // an already-completed deferred.
            activeConnectDeferred.compareAndSet(connectDeferred, null)
        }
    }

    /** Installs [run]'s scope and config-save channel and launches its children; called under admission. */
    private fun startRun(run: RunHandles) {
        val scope = CoroutineScope(parentContext + run.job)
        engineScope = scope
        val saves = Channel<ConfigBundle>(Channel.CONFLATED)
        configSaves = saves
        scope.launch(CoroutineName("meshtastic-config-saves")) {
            for (bundle in saves) persistConfig(bundle)
        }

        scope.launch(CoroutineName("meshtastic-engine-actor")) {
            runEngine(run)
        }

        scope.launch(CoroutineName("meshtastic-outbound-writer")) {
            for ((frame, link, paced, generation) in outbound) {
                if (paced != null && paced.generation != readySendGeneration.value) continue
                // A frame queued for a replaced link must not go out on its successor.
                val target = writeLink.value?.takeIf { it.generation == generation }?.transport ?: continue
                try {
                    if (link == null) {
                        logProtocolTx(frame)
                        // A stalled write would hold the paced queue while inbound frames keep the watchdog quiet.
                        withTimeoutOrNull(TRANSPORT_WRITE_TIMEOUT) { target.send(frame) }
                            ?: throw MeshtasticException.Transport(
                                "Transport write timed out after $TRANSPORT_WRITE_TIMEOUT",
                            )
                    } else {
                        writeLockdownFrame(frame, link, target)
                    }
                    // Completed here, not on the actor, so the evidence exists before a link drop is processed.
                    paced?.written?.complete(Unit)
                    if (paced != null) inbox.trySend(EngineMessage.PacedWriteCompleted(paced))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(TAG, e) { "Transport send failed: ${e.message}" }
                    val cause =
                        e as? MeshtasticException.Transport
                            ?: MeshtasticException.Transport(e.message ?: "send failed", e)
                    inbox.trySend(EngineMessage.LinkFailed(cause, generation))
                }
            }
        }

        // A disconnect() that already ran closed or abandoned this inbox; it also failed the awaiter.
        run.inbox.trySend(EngineMessage.Connect(run.connected))
    }

    suspend fun disconnect() {
        disconnectRequested.value = true
        // Engine writes stop here (publishState), so teardown shows Disconnecting until cleanup ends it.
        if (connectionState.value != ConnectionState.Disconnected) connectionState.value = ConnectionState.Disconnecting
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
        // Cleanup runs as the run's child, so this returns after it. A run cancelled before connect()
        // launched it has no cleanup to end Disconnecting.
        job.join()
        connectionState.compareAndSet(ConnectionState.Disconnecting, ConnectionState.Disconnected)
    }

    // Once disconnect() has published Disconnecting, a message the actor is still draining must not replace it.
    private fun publishState(state: ConnectionState) {
        if (!disconnectRequested.value) connectionState.value = state
    }

    // Only a backoff wait can be cut short; an attempt in flight, Reconnecting or DeviceSleeping, cannot.
    fun retryNow(): Boolean = retryWaiting.value && inbox.trySend(EngineMessage.RetryNow).isSuccess

    fun trySend(packet: MeshPacket, id: MessageId, stateFlow: MutableStateFlow<SendState>) {
        val result = inbox.trySend(EngineMessage.Send(packet, id, stateFlow))
        if (!result.isSuccess) {
            stateFlow.value = SendState.Failed(SendFailure.Disconnected)
        }
    }

    fun enqueuePaced(
        packet: MeshPacket,
        resend: ((MeshPacket) -> MessageHandle)?,
        written: CompletableDeferred<Unit>? = null,
    ): PacedMessageHandle {
        val id = if (packet.id == 0) nextMessageId() else MessageId(packet.id)
        val state = MutableStateFlow<SendState>(SendState.Queued)
        val receipt = CompletableDeferred<QueueReceipt>()
        val admission = CompletableDeferred<Boolean>()
        val dispatch = CompletableDeferred<Boolean>()
        val generation = readySendGeneration.value
        val submission = EngineMessage.PacedSend(packet, id, generation, state, admission, dispatch, receipt, written)
        when {
            generation == null -> completeNotDispatched(submission, SendFailure.Disconnected)
            !reservePacedSend() -> completeNotDispatched(submission, SendFailure.QueueFull)
            !inbox.trySend(submission).isSuccess -> rejectPaced(submission, SendFailure.Disconnected)
        }
        val message = MessageHandle(
            id,
            state,
            { inbox.trySend(EngineMessage.CancelHandle(it, state)) },
            packet,
            resend,
        )
        return PacedMessageHandle(message, admission, dispatch, receipt)
    }

    private fun reservePacedSend(): Boolean {
        while (true) {
            val reservations = pacedReservations.value
            if (reservations >= MAX_PACED_SENDS) return false
            if (pacedReservations.compareAndSet(reservations, reservations + 1)) return true
        }
    }

    // [expected] binds the cancel to this handle, so a later send that reuses the id is untouched.
    fun tryCancelSend(id: MessageId, expected: MutableStateFlow<SendState>) {
        inbox.trySend(EngineMessage.CancelHandle(id, expected))
    }

    fun requestNodeDbRefresh(): Boolean {
        // The link at admission: a request must not start a download on a link that replaced it.
        val link = readySendGeneration.value ?: return false
        return sessionReceive != null && inbox.trySend(EngineMessage.RefreshNodeDb(link)).isSuccess
    }

    /**
     * Allocate the next monotonic wire id. Shared between user-facing sends ([RadioClient.send])
     * and RPCs so a `getConfig`'s request_id can never collide with a concurrent
     * `sendText`'s packet id. Wraps at `Int.MAX_VALUE`; the firmware uses fixed32 so we just
     * keep climbing.
     */
    fun nextMessageId(): MessageId {
        // id=0 means "unset" on the wire (firmware would assign its own); skip it so
        // QueueStatus / Routing ACK correlation always has a real key.
        while (true) {
            val id = nextWireId.incrementAndGet()
            if (id != 0) return MessageId(id)
        }
    }

    /**
     * Submit a typed RPC and suspend until the response arrives, the timer fires, or the engine
     * winds down.
     *
     * The caller must:
     *  1. Leave `packet.id` zero for the actor to allocate an id no in-flight request holds, or set
     *     a caller-chosen id. A chosen id already in flight returns [AdminResult.NodeUnreachable]
     *     and leaves the request holding it untouched.
     *  2. Set `decoded.want_response = true` (or `want_ack = true` for ack-only flows; not used
     *     by this path — those flow through [trySend]/[MessageHandle]).
     *
     * Returns [AdminResult.Timeout] / [AdminResult.SessionKeyExpired] / etc. as classified by
     * [CommandDispatcher]. Returns [AdminResult.Timeout] without waiting if the engine is closed
     * before the request reaches the actor.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> submitRpc(packet: MeshPacket, kind: ResponseKind<T>, timeout: Duration): AdminResult<T> {
        val deferred = CompletableDeferred<AdminResult<Any?>>()
        val sent = inbox.trySend(
            EngineMessage.PostRpc(
                packet = packet,
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

    /** Whether [num] is the connected node, under its number or the one a live renumber is moving it to. */
    fun isConnectedNodeNum(num: Int): Boolean = num != 0 && (num == myNodeNum || num == renumberedTo)

    fun connectionGeneration(): Long = connectionGeneration.value

    val linkDropsOnRestartCommands: Boolean
        get() = writeLink.value?.transport?.linkDropsOnRestartCommands ?: false

    fun isConnectedAtGeneration(generation: Long): Boolean =
        connectionGeneration.value == generation && connectionState.value == ConnectionState.Connected

    private suspend fun runEngine(run: RunHandles) {
        try {
            for (msg in run.inbox) {
                processMessage(msg)
            }
        } finally {
            withContext(NonCancellable) {
                cleanup(run)
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
    private suspend fun cleanup(run: RunHandles) {
        clearNodeDbRefresh()
        sessionReceive?.finish()
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
        // Only this run's handles: a later connect() owns whatever replaced them.
        if (activeConnectDeferred.compareAndSet(run.connected, null)) {
            run.connected.completeExceptionally(CancellationException("Engine cancelled before connection completed"))
        }

        readySendGeneration.value = null
        run.inbox.close()
        var drained = run.inbox.tryReceive()
        while (drained.isSuccess) {
            val msg = drained.getOrNull()
            when (msg) {
                is EngineMessage.Send -> msg.stateFlow.value = SendState.Failed(SendFailure.Disconnected)

                is EngineMessage.PacedSend -> rejectPaced(msg, SendFailure.Disconnected)

                // Its caller awaits it; the session is gone, so there is nothing left to apply.
                is EngineMessage.ApplyLocalWrites -> msg.done.complete(Unit)

                else -> Unit
            }
            drained = run.inbox.tryReceive()
        }

        // ②ʹ fail any pre-Ready queued sends with Disconnected (semantically equivalent
        //         to the pendingSends sweep below, but explicit so the intent is documented).
        failPreSendQueueWith(SendFailure.Disconnected)

        clearPacedSends()
        for ((_, stateFlow) in pendingSends) {
            stateFlow.value = SendState.Failed(SendFailure.Disconnected)
        }
        pendingSends.clear()

        run.outbound.close()

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
        val link = transport
        if (link?.state?.value is TransportState.Connected) {
            try {
                withTimeoutOrNull(GOODBYE_TIMEOUT_MS) {
                    val encoded = WireCodec.encodeToRadio(
                        ToRadio.Builder().also { wb ->
                            wb.disconnect = true
                        }.build(),
                    )
                    val goodbye = Frame(encoded.toByteString())
                    logProtocolTx(goodbye)
                    link.send(goodbye)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug(TAG, e) { "Polite disconnect send failed: ${e.message}" }
            }
        }

        try {
            link?.disconnect()
        } catch (_: Exception) {
        }
        attemptLink.getAndSet(null)?.takeIf { it !== link }?.let { disconnectQuietly(it) }
        transport = null
        transportObserver = null
        writeLink.value = null
        linkUp = false

        supervisorJobRef.compareAndSet(run.job, null)
        configSaves?.close()
        configSaves = null
        engineScope = null
        handshakeStage = HandshakeStage.Idle
        lockdownLink.value = null
        myNodeNum = 0
        previousMyNodeNum = null
        previousDeviceId = null
        storedChannels = emptyList()
        clearSessionPasskeys()
        clearPendingHandshake()
        preSendQueue.clear()
        // reset the degraded flag so the next connect attempt gets a fresh storage shot.
        storageDegraded = false
        // clear in-memory heartbeat bookkeeping (hydrated fresh on next connect).
        lastHeartbeatAt.clear()
        dirtyHeartbeats.clear()
        offlineNodes.clear()
        lastCongestionLevel.clear()
        lastMqttConnected = null
        firmwareUpdateAdvised = false
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
        resetDisplayMirror()

        reconnectJob?.cancel()
        reconnectJob = null
        pendingTick = null
        attemptJob = null
        stableJob = null
        handshakeRecoveries = 0
        readyThisSession = false
        reconnectAttempt = 0
        sleepAttempts = 0
        sleeping = null
        autoReconnectInProgress = false
        renumberedTo = null
        heldRenumberAck = null
        staleNumberWrite = null
        regionWrite = null
        configBundleState.value = null
        channelsState.value = null

        dispatcher.cancelAll(AdminResult.NodeUnreachable)

        // A disconnect() waits for Disconnected; the compare keeps a terminal state only when nothing wrote over it.
        val terminal = terminalState
        terminalState = null
        if (terminal == null || disconnectRequested.value || !connectionState.compareAndSet(terminal, terminal)) {
            connectionState.value = ConnectionState.Disconnected
        }
        logger.debug(TAG) { "Cleanup complete — session torn down" }
    }

    private suspend fun processMessage(msg: EngineMessage) {
        when (msg) {
            is EngineMessage.Connect -> handleConnect(msg)

            is EngineMessage.Disconnect -> handleDisconnect(msg)

            is EngineMessage.RefreshNodeDb -> {
                if (handshakeStage == HandshakeStage.Ready &&
                    msg.generation == readySendGeneration.value
                ) {
                    startNodeDbRefresh()
                }
            }

            is EngineMessage.NodeDbRefreshTimeout -> {
                if (nodeDbRefresh != null && msg.request == nodeDbRefreshRequest) {
                    val cause = MeshtasticException.Transport("Node database refresh timed out")
                    if (!recoverSession(cause)) handleDisconnect(EngineMessage.Disconnect(cause))
                }
            }

            is EngineMessage.FrameRx -> {
                if (acceptsFramesFrom(msg.generation)) handleFrameRx(msg)
            }

            is EngineMessage.LinkFailed -> {
                if (acceptsFramesFrom(msg.generation)) handleLinkFailure(msg.cause)
            }

            is EngineMessage.TransportStateChanged -> handleTransportStateChanged(msg)

            is EngineMessage.Send -> handleSend(msg)

            is EngineMessage.PacedSend -> handlePacedSend(msg)

            is EngineMessage.PacedWriteCompleted -> handlePacedWriteCompleted(msg.send)

            is EngineMessage.PacedQueueTimeout -> handlePacedQueueTimeout(msg.send)

            is EngineMessage.CancelHandle -> handleCancelHandle(msg)

            is EngineMessage.ApplyLocalWrites -> {
                // A write acked on a link since replaced must not land on the node that replaced it.
                if (msg.generation == connectionGeneration.value) mergeLocalWrites(msg.writes)
                msg.done.complete(Unit)
            }

            is EngineMessage.ChannelsRestored -> {
                if (msg.generation == receiveGeneration && channelsState.value == null) {
                    channelsState.value = msg.channels
                    meshState = meshState.withChannels(msg.channels)
                    if (handshakeStage == HandshakeStage.Ready) sessionReceive?.channelsChanged(msg.channels)
                }
            }

            is EngineMessage.StorageDegraded -> markStorageDegraded(msg.reason, msg.cause)

            is EngineMessage.SilentRenumberProbe -> handleSilentRenumberProbe(msg.send)

            EngineMessage.RenumberRestart -> if (
                handshakeStage == HandshakeStage.Ready && renumberedTo != null && pacedSends.isEmpty()
            ) {
                restartSessionForRenumber()
            }

            is EngineMessage.AdminFireAndForget -> sendAdminPacket(msg.adminMsg, msg.wantResponse, msg.to)

            is EngineMessage.Lockdown -> handleLockdownCommand(msg)

            EngineMessage.HeartbeatTick -> handleHeartbeatTick()

            EngineMessage.LivenessTick -> handleLivenessTick()

            EngineMessage.PresenceCheckTick -> handlePresenceCheckTick()

            is EngineMessage.HandshakeTimeout -> handleHandshakeTimeout(msg)

            is EngineMessage.SessionSeedTimeout -> handleSessionSeedTimeout(msg)

            is EngineMessage.Stage1RebootGrace -> handleStage1RebootGrace(msg)

            is EngineMessage.HandshakeStage1SettleComplete -> if (msg.attempt ==
                handshakeAttempt
            ) {
                handleStage1SettleComplete()
            }

            is EngineMessage.HandshakeHeartbeatSettleComplete -> if (msg.attempt ==
                handshakeAttempt
            ) {
                handleHeartbeatSettleComplete()
            }

            is EngineMessage.AdminTimeout -> { /* legacy slot, superseded by PendingResponseTimeout */ }

            is EngineMessage.PostRpc -> handlePostRpc(msg)

            is EngineMessage.PendingResponseTimeout -> handlePendingResponseTimeout(msg)

            is EngineMessage.SendAckTimeout -> handleSendAckTimeout(msg)

            is EngineMessage.ReconnectTick -> handleReconnectTick(msg)

            is EngineMessage.ReconnectAttemptResult -> handleReconnectAttemptResult(msg)

            EngineMessage.RetryNow -> handleRetryNow()

            is EngineMessage.LinkStable -> handleLinkStable(msg)

            is EngineMessage.SessionOverflow -> handleSessionOverflow(msg)
        }
    }

    private fun handleRetryNow() {
        val tick = pendingTick ?: return
        reconnectJob?.cancel()
        handleReconnectTick(tick)
    }

    private fun handleSessionOverflow(msg: EngineMessage.SessionOverflow) {
        // A session that has already ended, or is ending, is restarting anyway.
        if (!linkUp || handshakeStage == HandshakeStage.Idle || msg.sessionId != sessionReceive?.sessionId) return
        logger.warn(TAG) { "Session receive overflowed in session ${msg.sessionId}; restarting the session" }
        recoverSession(
            MeshtasticException.Transport("Session receive overflowed in session ${msg.sessionId}"),
            overflow = true,
        )
    }

    private fun handleLinkStable(msg: EngineMessage.LinkStable) {
        if (handshakeStage == HandshakeStage.Ready && msg.generation == receiveGeneration) resetFailureStreak()
    }

    private fun handlePostRpc(msg: EngineMessage.PostRpc) {
        // Engine must be Ready: RPCs require an established session passkey + nodenum.
        // A caller-chosen id already in flight is refused, never re-registered over the request holding it.
        val chosenId = msg.packet.id
        if (handshakeStage != HandshakeStage.Ready || (chosenId != 0 && wireIdInFlight(chosenId))) {
            msg.deferred.complete(AdminResult.NodeUnreachable)
            return
        }
        val requestId = if (chosenId != 0) chosenId else allocateFreeWireId()
        val packet = if (chosenId != 0) msg.packet else msg.packet.newBuilder().also { it.id = requestId }.build()
        // Between a renumber and the restart, the device answers only to its new number.
        val sent = retargetRenumbered(packet)
        // Register before sending so a fast device reply can never race the registration.
        val expectedSender = when {
            sent.decoded?.portnum == PortNum.ADMIN_APP -> sent.to.takeIf { it != 0 } ?: myNodeNum
            msg.kind.answeredByTarget -> sent.to
            else -> null
        }
        dispatcher.register(requestId, msg.kind, msg.deferred, expectedSender = expectedSender)
        rememberAdminRequest(sent)
        // Outbound: stamp the target's session passkey + PKC routing for remote-admin, then enqueue.
        val outboundPacket = retargetRenumbered(prepareOutboundAdminPacket(packet))
        trySendToRadio(ToRadio.Builder().also { wb -> wb.packet = outboundPacket }.build())
        // Arm the timeout last so the timer fires even if dispatch path errors above.
        val scope = engineScope ?: return
        val job = scope.launch {
            delay(msg.timeout)
            inbox.trySend(EngineMessage.PendingResponseTimeout(requestId))
        }
        dispatcher.attachTimeoutJob(requestId, job)
    }

    // Actor only: a pending RPC or an unsettled send holds the id until its reply or terminal state.
    private fun wireIdInFlight(id: Int): Boolean {
        if (dispatcher.contains(id) || pacedSends.containsKey(MessageId(id))) return true
        val state = pendingSends[MessageId(id)]?.value ?: return false
        return state !is SendState.Failed && state != SendState.Acked && state != SendState.Delivered
    }

    // A caller-chosen id can sit ahead of the shared counter, so automatic RPC ids step over held ones.
    private fun allocateFreeWireId(): Int {
        while (true) {
            val id = nextMessageId().raw
            if (!wireIdInFlight(id)) return id
        }
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
        val keyEntry = validSessionEntryFor(packet.to)
        val passkey = keyEntry?.bytes
        if (keyEntry != null && packet.id != 0) {
            if (requestPasskeyGenerations.size >= REQUEST_GENERATION_CAP) {
                requestPasskeyGenerations.remove(requestPasskeyGenerations.keys.first())
            }
            requestPasskeyGenerations[packet.id] = keyEntry.generation
        }
        if (passkey != null) {
            val originalAdmin = runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull()
            if (originalAdmin != null && originalAdmin.session_passkey.size == 0) {
                val stamped = originalAdmin.newBuilder().also { wb ->
                    wb.session_passkey = passkey.toByteString()
                }.build()
                outDecoded = decoded.newBuilder().also { wb ->
                    wb.payload = AdminMessage.ADAPTER.encode(stamped).toByteString()
                }.build()
            }
        } else {
            emitEvent(
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
            return packet.newBuilder().also { wb ->
                wb.decoded = outDecoded
                wb.priority = priority
            }.build()
        }

        // 2. PKC when both sides have published keys; legacy "admin" channel otherwise.
        val targetKey = meshState.nodes[NodeId(packet.to)]?.user?.public_key
        val ownKey = meshState.nodes[NodeId(myNodeNum)]?.user?.public_key
        return if (targetKey != null && targetKey.size > 0 && ownKey != null && ownKey.size > 0) {
            packet.newBuilder().also { wb ->
                wb.decoded = outDecoded
                wb.channel = 0
                wb.pki_encrypted = true
                wb.public_key = targetKey
                wb.priority = priority
            }.build()
        } else {
            val adminIndex = adminChannelIndex() ?: 0
            packet.newBuilder().also { wb ->
                wb.decoded = outDecoded
                wb.channel = adminIndex
                wb.priority = priority
            }.build()
        }
    }

    private suspend fun handleConnect(msg: EngineMessage.Connect) {
        connectionGeneration.incrementAndGet()
        pendingConnectDeferred = msg.deferred
        publishState(ConnectionState.Connecting(1))
        // reset watchdog gate so a prior session's `disconnecting = true` doesn't stick.
        disconnecting = false
        logger.info(TAG) { "Connect requested (transport=${identity.forLog()})" }

        try {
            storage = storageProvider.activate(identity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MeshtasticException) {
            publishState(ConnectionState.Disconnected)
            msg.deferred.completeExceptionally(e)
            pendingConnectDeferred = null
            return
        } catch (e: Exception) {
            val ex = MeshtasticException.StorageUnavailable(e.message ?: "storage activation failed", e)
            publishState(ConnectionState.Disconnected)
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
        previousDeviceId = try {
            trustedDeviceIdOrNull(storage?.loadOwnDeviceId())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, e) { "Failed to load previous device id for rebind detection" }
            null
        }
        storedChannels = try {
            storage?.loadChannels().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, e) { "Failed to load stored channels" }
            emptyList()
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

        if (!openFirstTransport(msg.deferred)) return
        linkUp = true
        startStage1Handshake()
    }

    /** Opens and connects the transport for the attempt [connect] starts; false when it failed and was handled. */
    private suspend fun openFirstTransport(deferred: CompletableDeferred<Unit>): Boolean {
        var opened: RadioTransport? = null
        try {
            opened = transportProvider.open(0, null)
            installTransport(opened)
            opened.connect()
            return true
        } catch (e: Exception) {
            // Only the actor's own cancellation ends the run; a provider's timeout fails the attempt.
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            val ex =
                e as? MeshtasticException ?: MeshtasticException.Transport(e.message ?: "transport connect failed", e)
            val tagged = MeshtasticException.tag(ex, transportIdentity = identity, operation = "connect")
            when {
                isTerminal(opened) -> failSession(tagged)

                // The first attempt is retried like a later drop; connect() waits for Ready or Failed.
                autoReconnectConfig.enabled -> scheduleReconnect(tagged)

                else -> {
                    publishState(ConnectionState.Disconnected)
                    deferred.completeExceptionally(ex)
                    pendingConnectDeferred = null
                }
            }
            return false
        }
    }

    // A failed connect on a transport that also reports a non-recoverable error cannot succeed by retrying.
    private fun isTerminal(link: RadioTransport?): Boolean =
        (link?.state?.value as? TransportState.Error)?.recoverable == false

    /** Makes [link] the engine's transport and observes its state, replacing the previous observer. */
    private fun installTransport(link: RadioTransport) {
        if (link === transport && transportObserver != null) return
        transportObserver?.cancel()
        transport = link
        transportObserver = engineScope?.launch(CoroutineName("meshtastic-transport-observer")) {
            link.state.collect { state ->
                // A state update already in flight can reach the observer after the actor closes its inbox.
                inbox.trySend(EngineMessage.TransportStateChanged(state, link))
            }
        }
    }

    /**
     * Begin Stage 1 of the handshake. Extracted from [handleConnect] so the auto-reconnect
     * supervisor can re-enter the handshake without re-running storage activation.
     */
    private fun startStage1Handshake() {
        val generation = ++receiveGeneration
        // Each session, a reconnect included, warns once about firmware below the recommended version.
        firmwareUpdateAdvised = false
        heartbeatAnswered = false
        localAdminGatedByLink.value = false
        writeLink.value = transport?.let { WriteLink(generation, it) }
        sessionReceive?.begin()
        startFrameReader(generation)

        // Stream-based transports (TCP, Serial) require 4 wake bytes (0x94) before the first
        // want_config_id to resync the firmware's frame decoder after an unclean disconnect.
        // BLE doesn't need this since each GATT write is self-framing.
        if (!identity.raw.startsWith("ble:")) {
            val wakeBytes = byteArrayOf(0x94.toByte(), 0x94.toByte(), 0x94.toByte(), 0x94.toByte())
            outbound.trySend(OutboundFrame(Frame(wakeBytes.toByteString()), generation = generation))
        }

        requestStage1Config()
    }

    private fun requestStage1Config() {
        val attempt = ++handshakeAttempt
        stage1DumpStarted = false
        lockdownLink.value = null
        handshakeStage = HandshakeStage.Stage1Draining
        publishState(ConnectionState.Configuring(ConfigPhase.Stage1, 0f))
        // Sent once per session: firmware's handleStartConfig has no re-entry guard (protocol.md §6),
        // so a silent Stage 1 ends in HandshakeTimeout and a fresh session, never an in-session resend.
        sendToRadio(ToRadio.Builder().also { wb -> wb.want_config_id = NONCE_STAGE1 }.build())
        armStage1Watchdog()
        stage1CapJob?.cancel()
        stage1CapJob = engineScope?.launch {
            delay(STAGE1_HARD_CAP_MS)
            inbox.trySend(EngineMessage.HandshakeTimeout(HandshakeStage.Stage1Draining, attempt))
        }

        logger.info(TAG) { "Handshake Stage 1 started (want_config_id=$NONCE_STAGE1)" }
    }

    // Stage 1 fails after STAGE1_TIMEOUT_MS without a frame of its dump, the way Android re-armed its stage guard
    // on progress (MeshConnectionManagerImpl), and at STAGE1_HARD_CAP_MS however much arrives.
    private fun armStage1Watchdog() {
        val attempt = handshakeAttempt
        stage1WatchdogJob?.cancel()
        stage1WatchdogJob = engineScope?.launch {
            delay(STAGE1_TIMEOUT_MS)
            inbox.trySend(EngineMessage.HandshakeTimeout(HandshakeStage.Stage1Draining, attempt))
        }
    }

    private fun clearStage1Timers() {
        stage1WatchdogJob?.cancel()
        stage1WatchdogJob = null
        stage1CapJob?.cancel()
        stage1CapJob = null
        stage1RebootGraceJob?.cancel()
        stage1RebootGraceJob = null
    }

    private fun acceptsFramesFrom(generation: Long): Boolean =
        generation == receiveGeneration && handshakeStage != HandshakeStage.Idle

    private fun startFrameReader(generation: Long) {
        val link = transport ?: return
        // Subscribe before sending Stage 1 so transports with a hot flow can deliver an
        // immediate response. Yield after the first frame so a preloaded transport returns
        // control to the actor even while it still has frames to read.
        frameReaderJob =
            engineScope?.launch(CoroutineName("meshtastic-frame-reader"), start = CoroutineStart.UNDISPATCHED) {
                var firstFrame = true
                try {
                    link.frames().collect { frame ->
                        inbox.send(EngineMessage.FrameRx(frame, generation))
                        if (firstFrame) {
                            firstFrame = false
                            yield()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(TAG, e) { "Frame reader failed: ${e.message}" }
                    val cause = e as? MeshtasticException.Transport
                        ?: MeshtasticException.Transport(e.message ?: "frame reader failed", e)
                    inbox.trySend(EngineMessage.LinkFailed(cause, generation))
                }
            }
    }

    // A transport that throws from frames() or send() never publishes TransportState.Error, so
    // this is the only place its failure meets the reconnect policy.
    private fun handleLinkFailure(cause: MeshtasticException) {
        if (!linkUp) return
        val sleep = judgeSleepOnDrop(cause, recoverable = true)
        if (!autoReconnectConfig.enabled || disconnecting) {
            handleDisconnect(EngineMessage.Disconnect(cause))
            sleep?.let(::settleTerminalSleep)
            return
        }
        val tagged = MeshtasticException.tag(cause, transportIdentity = identity, operation = "engine.link")
        emitEvent(MeshEvent.TransportError(tagged))
        scheduleReconnect(tagged)
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
        pendingTick = null
        autoReconnectInProgress = false
        sleeping = null
        terminalState = null

        if (msg.cause != null) {
            // Typed causes (HandshakeTimeout, StorageUnavailable) reach observers as themselves.
            emitEvent(
                MeshEvent.TransportError(
                    MeshtasticException.tag(
                        msg.cause,
                        transportIdentity = identity,
                        operation = "engine.disconnect",
                    ),
                ),
            )
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
            emitEvent(
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

        logProtocol { "rx ${payloadRedactor.describe(fromRadio)}" }

        rawFromRadio.tryEmit(fromRadio)

        // liveness watchdog only resets on successfully decoded envelopes,
        // not raw-byte arrivals. A stuck framer on a half-open socket could still deliver
        // resync garbage; that must not look like "the link is healthy" to the watchdog.
        livenessBudgetMs = LIVENESS_TIMEOUT_MS
        // Firmware 2.7.4 and later answer a heartbeat with a QueueStatus for packet id 0 (PhoneAPI::getFromRadio).
        if (fromRadio.queueStatus?.mesh_packet_id == 0) heartbeatAnswered = true

        // Firmware answers a 69420 want_config with my_info first (PhoneAPI handleStartConfig). A completion
        // seen before it in Stage 1 was queued before the request and would end the new handshake, so it is
        // dropped. Firmware before 2.8 writes `rebooted` at boot before it reads input (SerialConsole), so the
        // request went to the previous boot: the attempt ends unless my_info follows within the grace.
        if (handshakeStage == HandshakeStage.Stage1Draining && !stage1DumpStarted) {
            if (fromRadio.my_info != null) {
                stage1DumpStarted = true
            } else if (fromRadio.rebooted == true) {
                armStage1RebootGrace()
                return
            } else if (fromRadio.config_complete_id != null) {
                logger.debug(TAG) { "Dropping a completion from before the Stage 1 dump" }
                return
            }
        }

        // `FromRadio.rebooted = true` is a forced-disconnect signal — the
        // radio has restarted and all prior session state (session passkey, node DB, pending
        // sends) is now stale. Previously we only cancelled the supervisor, which meant pending
        // sends resolved as generic `Disconnected`. Now we:
        //   1. Reset handshake stage so any stale settle/stage timers become no-ops.
        //   2. Emit `MeshEvent.DeviceRebooted` so subscribers can distinguish this from a
        //      transport-level drop.
        //   3. Fail the pending-connect deferred (if any) with Transport so the host can retry
        //      after the device comes back.
        //   4. Cancel the supervisor → cleanup() runs, polite-goodbye is sent, transport
        //      disconnects, and `ConnectionState.Disconnected` is emitted.
        if (fromRadio.rebooted == true) {
            handleDeviceRebooted()
            return
        }

        val lockdownStatus = fromRadio.lockdown_status
        if (lockdownStatus != null) {
            localAdminGatedByLink.value = true
            if (handshakeStage != HandshakeStage.Ready) handleHandshakeLockdown(lockdownStatus)
            emitEvent(MeshEvent.LockdownStatusChanged(lockdownStatus))
            sessionReceive?.lockdown(lockdownStatus)
            return
        }

        // Log records bypass the settle and handshake buffers, so a log burst cannot evict a held frame.
        fromRadio.log_record?.let { record ->
            deviceLogs.tryEmit(record)
            return
        }

        when (handshakeStage) {
            HandshakeStage.AwaitingLockdown,
            HandshakeStage.Idle,
            -> {
            }

            HandshakeStage.Stage1Settling -> {
                // instead of dropping, buffer frames for replay after settle. Drop-oldest
                // at SETTLE_BUFFER_CAP and surface a PacketsDropped event so subscribers can
                // spot pathological backlogs — silent loss is forbidden per
                // ADR-005 public-surface rules).
                if (settleBuffer.size >= SETTLE_BUFFER_CAP) {
                    sessionReceive?.handshakeOverflow()
                    val dropped = settleBuffer.removeFirst()
                    logger.warn(TAG) {
                        "Stage1 settle buffer full (cap=$SETTLE_BUFFER_CAP) — dropping oldest frame " +
                            "(${summarizeFromRadio(dropped)})"
                    }
                    emitEvent(MeshEvent.PacketsDropped(DroppedFlow.Packets, count = 1))
                }
                settleBuffer.addLast(fromRadio)
                return
            }

            HandshakeStage.Stage1Draining -> {
                if (stage1DumpStarted) armStage1Watchdog()
                processStage1Envelope(fromRadio)
            }

            HandshakeStage.Stage2Draining -> processStage2Envelope(fromRadio)

            HandshakeStage.Ready -> {
                handleNodeDbRefreshFrame(fromRadio)
                routeNormalEnvelope(fromRadio)
            }
        }
    }

    private fun handleDeviceRebooted() {
        emitEvent(MeshEvent.DeviceRebooted())
        if (autoReconnectConfig.enabled && !disconnecting) {
            logger.warn(TAG) { "Device rebooted; reconnecting" }
            val cause = MeshtasticException.tag(
                MeshtasticException.Transport("Device rebooted"),
                transportIdentity = identity,
                operation = "engine.reboot",
            )
            if (handshakeStage != HandshakeStage.Ready) failPreSendQueueWith(SendFailure.HandshakeFailed)
            scheduleReconnect(cause)
            return
        }
        logger.warn(TAG) { "Device rebooted — forcing disconnect" }
        handshakeStage = HandshakeStage.Idle
        lockdownLink.value = null
        readySendGeneration.value = null
        stage2WatchdogJob?.cancel()
        stage2WatchdogJob = null
        val deferred = pendingConnectDeferred
        if (deferred != null) {
            pendingConnectDeferred = null
            failPreSendQueueWith(SendFailure.HandshakeFailed)
            deferred.completeExceptionally(MeshtasticException.Transport("Device rebooted mid-handshake"))
        }
        supervisorJobRef.value?.cancel()
    }

    private fun armStage1RebootGrace() {
        if (stage1RebootGraceJob?.isActive == true) return
        val attempt = handshakeAttempt
        stage1RebootGraceJob = engineScope?.launch {
            delay(STAGE1_REBOOT_GRACE_MS)
            inbox.trySend(EngineMessage.Stage1RebootGrace(attempt))
        }
    }

    private fun handleStage1RebootGrace(msg: EngineMessage.Stage1RebootGrace) {
        if (msg.attempt != handshakeAttempt || handshakeStage != HandshakeStage.Stage1Draining || stage1DumpStarted) {
            return
        }
        handleDeviceRebooted()
    }

    private fun handleHandshakeLockdown(status: LockdownStatus) {
        when (status.state) {
            LockdownStatus.State.LOCKED, LockdownStatus.State.NEEDS_PROVISION -> {
                val entering = handshakeStage != HandshakeStage.AwaitingLockdown
                handshakeStage = HandshakeStage.AwaitingLockdown
                lockdownLink.value = pendingMyInfo?.my_node_num?.takeIf { it != 0 }?.let {
                    LockdownLink(receiveGeneration, it)
                }
                stage2WatchdogJob?.cancel()
                stage2WatchdogJob = null
                // Firmware answers a heartbeat on a link it has not authorized (PhoneAPI::handleToRadio), so the
                // watchdog runs from here for the wait, however long the unlock takes.
                if (entering && sendsPeriodicHeartbeats()) {
                    sendKeepaliveHeartbeat()
                    startKeepalive()
                }
            }

            LockdownStatus.State.UNLOCKED, LockdownStatus.State.DISABLED -> if (
                handshakeStage == HandshakeStage.AwaitingLockdown
            ) {
                // Firmware authorizes this link only once the unlocked configuration is loaded.
                // Discard the redacted snapshot and keep the reader on the authorized link. Mesh packets
                // held for Ready are real traffic on this same link, so they stay.
                clearPendingHandshake(keepHeldPackets = true)
                requestStage1Config()
            }

            else -> Unit
        }
    }

    private fun clearPendingHandshake(keepHeldPackets: Boolean = false) {
        pendingNodes.clear()
        pendingConfigs = emptyList()
        pendingModuleConfigs = emptyList()
        pendingChannels.clear()
        pendingMyInfo = null
        pendingMetadata = null
        pendingDeviceUIConfig = null
        pendingFiles.clear()
        pendingRegionPresets = null
        settleBuffer.clear()
        if (!keepHeldPackets) handshakePacketBuffer.clear()
        clearSessionSeed()
        clearStage1Timers()
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

        // Accumulators must be idempotent: a device-side hiccup restarts the firmware's config drain from scratch (PhoneAPI
        // handleStartConfig resets its read index), so the same channels/config sections can
        // stream twice. Replace-by-key instead of append so the committed bundle has no dupes.
        when {
            myInfo != null -> {
                if (syncTimeDuringHandshake && pendingMyInfo == null) sendHandshakeTimeSync(myInfo.my_node_num)
                pendingMyInfo = myInfo
            }

            metadata != null -> {
                pendingMetadata = metadata
                enforceFirmwareFloor(metadata.firmware_version)
            }

            channel != null -> {
                pendingChannels.removeAll { it.index == channel.index }
                pendingChannels.add(channel)
            }

            config != null -> pendingConfigs = mergeConfigs(pendingConfigs, listOf(config))

            modConfig != null -> pendingModuleConfigs = mergeModuleConfigs(pendingModuleConfigs, listOf(modConfig))

            deviceUIConf != null -> pendingDeviceUIConfig = deviceUIConf

            fromRadio.region_presets != null -> pendingRegionPresets = fromRadio.region_presets

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
                clearStage1Timers()
                handshakeStage = HandshakeStage.Stage1Settling
                publishState(ConnectionState.Configuring(ConfigPhase.Settling, 0.5f))
                val attempt = handshakeAttempt
                engineScope?.launch {
                    delay(INTER_STAGE_SETTLE_MS)
                    inbox.trySend(EngineMessage.HandshakeStage1SettleComplete(attempt))
                }
            }

            // A *set* config_complete_id matching neither nonce is a protocol
            // violation. (config_complete_id is nullable; `null != 0` is true in
            // Kotlin, so the null check is required or unrecognized frames fatal here.)
            completeId != null && completeId != 0 -> failHandshakeWithProtocol(
                "Stage 1 config_complete_id mismatch: expected=$NONCE_STAGE1 got=$completeId",
            )

            // Unrecognized FromRadio during the config drain — most commonly the device's
            // serial debug console interleaving plain-text log lines with the protobuf frame
            // stream (which can resync the framer onto a spurious boundary), or a
            // newer-firmware FromRadio variant this build doesn't model. Tolerate it instead
            // of aborting: the real config_complete_id still arrives. Mirrors the reference
            // Python client, which skips bytes it can't frame/parse.
            else -> logger.debug(TAG) { "Stage 1: ignoring unrecognized FromRadio" }
        }
    }

    /**
     * Ends the connect with [MeshtasticException.FirmwareTooOld] when [reported] parses below
     * [DeviceVersion.ABS_MIN_SUPPORTED]. Below [DeviceVersion.MIN_SUPPORTED] it connects and emits
     * one [MeshEvent.ProtocolWarning] per session. A blank or unparseable version only warns: a
     * missing version is not an old one.
     */
    private fun enforceFirmwareFloor(reported: String) {
        val present = DeviceVersion(reported).asInt
        if (present == 0) {
            logger.warn(TAG) { "Device reported no parseable firmware version ('$reported'); skipping the floor check" }
            return
        }
        val required = DeviceVersion.ABS_MIN_SUPPORTED.asInt
        if (present >= required) {
            val recommended = DeviceVersion.MIN_SUPPORTED
            if (present < recommended.asInt && !firmwareUpdateAdvised) {
                firmwareUpdateAdvised = true
                logger.warn(TAG) { "Firmware $reported is below the recommended ${recommended.versionString}" }
                emitEvent(
                    MeshEvent.ProtocolWarning(
                        "firmware should be updated",
                        details = mapOf(
                            "present" to reported,
                            "recommended" to recommended.versionString,
                        ),
                    ),
                )
            }
            return
        }

        logger.error(TAG) {
            "Firmware $reported is below the supported floor ${DeviceVersion.ABS_MIN_SUPPORTED.versionString}"
        }
        val cause = MeshtasticException.tag(
            MeshtasticException.FirmwareTooOld(required = required, present = present),
            transportIdentity = identity,
            operation = "handshake",
        )
        emitEvent(MeshEvent.TransportError(cause))
        failSession(cause)
    }

    /**
     * Replays [settleBuffer] through [processStage1Envelope]. Returns false when a replayed
     * frame re-armed the settle (a duplicate Stage 1 completion, whose unprocessed successors are
     * re-buffered for the next replay) or ended the handshake; the caller must then stop.
     */
    private fun replaySettleBuffer(): Boolean {
        if (settleBuffer.isEmpty()) return true
        val pending = settleBuffer.toList()
        settleBuffer.clear()
        handshakeStage = HandshakeStage.Stage1Draining
        for ((index, env) in pending.withIndex()) {
            processStage1Envelope(env)
            if (handshakeStage != HandshakeStage.Stage1Draining) {
                if (handshakeStage == HandshakeStage.Stage1Settling) {
                    settleBuffer.addAll(pending.subList(index + 1, pending.size))
                }
                return false
            }
        }
        handshakeStage = HandshakeStage.Stage1Settling
        return true
    }

    private fun handleStage1SettleComplete() {
        if (handshakeStage != HandshakeStage.Stage1Settling) return
        if (!replaySettleBuffer()) return

        val nonce = sendKeepaliveHeartbeat()
        logger.debug(TAG) { "Sent inter-stage heartbeat nonce=$nonce" }
        val attempt = handshakeAttempt
        engineScope?.launch {
            delay(INTER_STAGE_SETTLE_MS)
            inbox.trySend(EngineMessage.HandshakeHeartbeatSettleComplete(attempt))
        }
    }

    private fun handleHeartbeatSettleComplete() {
        if (handshakeStage != HandshakeStage.Stage1Settling) return
        // Frames from the window after the heartbeat sit in settleBuffer too; a reconnecting
        // radio delivers its queued phone packets there.
        if (!replaySettleBuffer()) return
        val attempt = handshakeAttempt
        // Config-only: finish after Stage 1 via a synthetic Stage-2 completion; no NodeDB request (§6).
        if (skipNodeDb) {
            handshakeStage = HandshakeStage.Stage2Draining
            processStage2Envelope(
                org.meshtastic.proto.FromRadio.Builder().also { wb ->
                    wb.config_complete_id = NONCE_STAGE2
                }.build(),
            )
            logger.info(TAG) { "Handshake ended after Stage 1 (config-only; NodeDB skipped)" }
            return
        }
        publishState(ConnectionState.Configuring(ConfigPhase.Stage2, 0.5f))
        sendToRadio(ToRadio.Builder().also { wb -> wb.want_config_id = NONCE_STAGE2 }.build())
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
                    inbox.trySend(EngineMessage.HandshakeTimeout(HandshakeStage.Stage2Draining, attempt))
                    return@launch
                }
                lastCounter = current
            }
            // Hard cap exceeded — even if frames are still trickling, abort.
            inbox.trySend(EngineMessage.HandshakeTimeout(HandshakeStage.Stage2Draining, attempt))
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
                logger.info(TAG) { "Stage 2 complete" }
                // leaving Stage2Draining — cancel the sliding watchdog so it doesn't
                // post a stale HandshakeTimeout message after we've already advanced.
                stage2WatchdogJob?.cancel()
                stage2WatchdogJob = null

                // Commit accumulated handshake state.
                val myInfo = pendingMyInfo
                var keepsStoredState = true
                if (myInfo != null) {
                    // R-9: detect identity rebind synchronously on the actor, BEFORE we mutate
                    // any consumer-observable state (NodeChange.Snapshot below) and BEFORE the
                    // async storage.recordOwnNode → clear() runs.
                    val prev = previousMyNodeNum
                    val incomingDeviceId = trustedDeviceIdOrNull(myInfo.device_id)
                    if (prev != null && prev != myInfo.my_node_num) {
                        val renumbered = incomingDeviceId != null && incomingDeviceId == previousDeviceId
                        keepsStoredState = renumbered
                        if (!renumbered) {
                            // Another device: the cached channels are the old one's, and a reconnect skips handleConnect.
                            storedChannels = emptyList()
                            channelsState.value = null
                            meshState = meshState.withChannels(emptyList())
                        }
                        logger.warn(TAG) {
                            "Identity rebind detected — previous=0x${prev.toString(
                                16,
                            )} new=0x${myInfo.my_node_num.toString(16)} renumbered=$renumbered"
                        }
                        // passkeys issued under the previous identity are meaningless now.
                        clearSessionPasskeys()
                        emitEvent(
                            MeshEvent.IdentityRebound(
                                previousNodeNum = NodeId(prev),
                                newNodeNum = NodeId(myInfo.my_node_num),
                                reason = if (renumbered) REASON_NODE_RENUMBERED else REASON_IDENTITY_MISMATCH,
                            ),
                        )
                    }
                    previousMyNodeNum = myInfo.my_node_num.takeIf { it != 0 }
                    previousDeviceId = incomingDeviceId ?: previousDeviceId.takeIf { prev == myInfo.my_node_num }

                    myNodeNum = myInfo.my_node_num
                    meshState = meshState.withMyInfo(myInfo)
                    ownNode.value = pendingNodes[NodeId(myNodeNum)]
                }
                if (pendingMetadata != null || pendingConfigs.isNotEmpty() || pendingModuleConfigs.isNotEmpty() ||
                    pendingDeviceUIConfig != null
                ) {
                    val bundle = ConfigBundle(
                        myInfo = myInfo ?: org.meshtastic.proto.MyNodeInfo.Builder().build(),
                        metadata = pendingMetadata ?: org.meshtastic.proto.DeviceMetadata.Builder().build(),
                        configs = pendingConfigs,
                        moduleConfigs = pendingModuleConfigs,
                        deviceUIConfig = pendingDeviceUIConfig,
                        regionPresets = pendingRegionPresets,
                    )
                    meshState = meshState.withConfig(bundle)
                }
                // A handshake without channels falls back to the device's stored ones, read before Ready
                // because the Ready snapshot is published here.
                if (pendingChannels.isEmpty() && keepsStoredState) {
                    pendingChannels.addAll(channelsState.value ?: storedChannels)
                }
                if (pendingChannels.isNotEmpty()) {
                    meshState = meshState.withChannels(pendingChannels.toList())
                }
                // Publish handshake state to the public flows synchronously on the actor:
                // connect() resumes at Ready, and consumers legitimately read
                // configBundle/channels immediately after — the async persistence block
                // below must not gate visibility (real-device storage latency loses that
                // race; caught by MeshtasticBleConformanceTest).
                meshState.configBundle?.let { configBundleState.value = it }
                if (pendingChannels.isNotEmpty()) {
                    channelsState.value = pendingChannels.toList()
                }
                if (pendingNodes.isNotEmpty()) {
                    meshState = meshState.withNodes(pendingNodes.toMap())
                    emitNodeChangeOrLog(NodeChange.Snapshot(pendingNodes.toMap()))
                }

                // Persist to storage. Snapshot every actor-owned collection BEFORE launching:
                // the async block runs concurrently with the actor, which keeps mutating
                // pendingNodes / pendingChannels / lastHeartbeatAt (markNodeHeard on every
                // inbound frame, scheduleReconnect clears) — iterating the live collections
                // off-actor risks ConcurrentModificationException mis-reported as
                // StorageDegraded.
                run {
                    val nodesSnapshot = pendingNodes.toMap()
                    val channelsSnapshot = pendingChannels.toList()
                    val heartbeatSnapshot = nodesSnapshot.keys.associateWith { lastHeartbeatAt[it] }
                    val firmwareVersion = pendingMetadata?.firmware_version ?: ""
                    val bundleSnapshot = meshState.configBundle
                    val sessionGeneration = receiveGeneration
                    engineScope?.launch {
                        if (storageDegraded) return@launch
                        val s = storage ?: return@launch
                        try {
                            if (myInfo != null) {
                                s.recordOwnNode(
                                    NodeId(myInfo.my_node_num),
                                    firmwareVersion,
                                    trustedDeviceIdOrNull(myInfo.device_id),
                                )
                            }
                            for ((nodeId, node) in nodesSnapshot) {
                                s.saveNode(node)
                                // every node in the handshake snapshot was just heard, so
                                // seed their heartbeat row as well.
                                heartbeatSnapshot[nodeId]?.let { ts -> s.saveHeartbeat(nodeId, ts) }
                            }
                            if (channelsSnapshot.isNotEmpty()) {
                                s.saveChannels(channelsSnapshot)
                            } else {
                                // Reconnect sessions where the device skips re-sending channels fall back to
                                // the persisted set; the actor applies it only if the session still has none.
                                val restored = s.loadChannels()
                                if (restored.isNotEmpty()) {
                                    inbox.trySend(EngineMessage.ChannelsRestored(restored, sessionGeneration))
                                }
                            }
                            bundleSnapshot?.let { s.saveConfig(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            reportStorageDegraded(
                                "persisting handshake snapshot failed: ${e.message ?: e::class.simpleName}",
                                e,
                            )
                        }
                    }
                }

                // mark every pending node as heard now (before the async block above flushes).
                run {
                    val now = clock.now().toEpochMilliseconds()
                    for ((nodeId, _) in pendingNodes) {
                        lastHeartbeatAt[nodeId] = now
                    }
                }

                // Ready here, before the seed reply: firmware starts its stored-packet replay right after this
                // completion (PhoneAPI::sendConfigComplete), more packets than the handshake buffer holds.
                if (myNodeNum != 0) sendSessionSeed()
                transitionToReady()
            }

            // see Stage 1 comment above — only a *set* mismatching completion nonce is a
            // protocol violation; a null/unrecognized FromRadio is tolerated.
            completeId != null && completeId != 0 -> failHandshakeWithProtocol(
                "Stage 2 config_complete_id mismatch: expected=$NONCE_STAGE2 got=$completeId",
            )

            else -> logger.debug(TAG) { "Stage 2: ignoring unrecognized FromRadio" }
        }
    }

    /** Local admin needs no passkey (firmware checks it only when `from != 0`); remote keys are seeded on demand. */
    private fun sendSessionSeed() {
        val id = allocateFreeWireId()
        sendAdminPacket(
            AdminMessage.Builder().also { wb -> wb.get_owner_request = true }.build(),
            wantResponse = true,
            id = id,
        )
        seedRequestId = id
        seedTimeoutJob?.cancel()
        seedTimeoutJob = engineScope?.launch {
            delay(SEED_TIMEOUT_MS)
            inbox.trySend(EngineMessage.SessionSeedTimeout(id))
        }
    }

    // Firmware answers with request_id = the seed's packet id (MeshModule::setReplyTo).
    private fun consumeSessionSeedReply(packet: MeshPacket): Boolean {
        val decoded = packet.decoded ?: return false
        if (seedRequestId == 0 || decoded.portnum != PortNum.ADMIN_APP || decoded.request_id != seedRequestId) {
            return false
        }
        if (packet.from != 0 && packet.from != myNodeNum) return false
        val adminMsg = runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull() ?: return false
        if (adminMsg.get_owner_response == null) return false
        clearSessionSeed()
        latchSessionPasskey(myNodeNum, adminMsg)
        return true
    }

    private fun handleSessionSeedTimeout(msg: EngineMessage.SessionSeedTimeout) {
        if (seedRequestId == 0 || msg.requestId != seedRequestId) return
        // Only the warning is due: the reply still latches the passkey if it comes later.
        seedTimeoutJob = null
        logger.warn(TAG) { "No get_owner_response within ${SEED_TIMEOUT_MS}ms; the session has no local passkey" }
        emitEvent(MeshEvent.ProtocolWarning("session passkey seed timed out"))
    }

    private fun clearSessionSeed() {
        seedRequestId = 0
        seedTimeoutJob?.cancel()
        seedTimeoutJob = null
    }

    /**
     * Cache the session passkey carried in [adminMsg], keyed by the node that issued it.
     * Firmware refreshes the passkey in **every** admin response, so any admin packet from
     * [fromNum] keeps that node's admin session alive. Only the local node's seed passkey is
     * persisted — remote passkeys are ephemeral by design (firmware validity window).
     */
    private fun latchSessionPasskey(fromNum: Int, adminMsg: AdminMessage, receivedAt: Instant = clock.now()) {
        if (adminMsg.session_passkey.size <= 0) return
        if (fromNum == 0) return
        val bytes = adminMsg.session_passkey.toByteArray()
        val previous = sessionPasskeys[fromNum]
        if (previous != null && receivedAt < previous.refreshedAt) return
        val generation = if (previous != null && previous.bytes.contentEquals(bytes)) {
            previous.generation
        } else {
            nextPasskeyGeneration++
        }
        sessionPasskeys[fromNum] = SessionPasskeyEntry(bytes, receivedAt, generation)
        publishAdminSessions()
        logger.debug(TAG) { "Session passkey latched for 0x${fromNum.toString(16)} (${bytes.size} bytes)" }
    }

    private fun clearSessionPasskeys() {
        sessionPasskeys.clear()
        requestPasskeyGenerations.clear()
        adminReplyTargets.clear()
        adminSessions.value = emptyMap()
    }

    /** Drop expired entries and republish [adminSessions]; the StateFlow ignores unchanged maps. */
    private fun publishAdminSessions() {
        val now = clock.now()
        sessionPasskeys.entries.removeAll { now - it.value.refreshedAt >= SESSION_PASSKEY_TTL }
        adminSessions.value = sessionPasskeys.entries.associate { (num, entry) ->
            val refreshedAt = entry.refreshedAt
            NodeId(num) to if (now - refreshedAt < SESSION_PASSKEY_ACTIVE_FOR) {
                AdminSessionStatus.Active(refreshedAt, refreshedAt + SESSION_PASSKEY_ACTIVE_FOR)
            } else {
                AdminSessionStatus.Stale(refreshedAt, refreshedAt + SESSION_PASSKEY_TTL)
            }
        }
    }

    private fun AdminMessage.adminRequestKind(): ResponseKind<*>? = when {
        get_channel_request != null -> ResponseKind.AdminChannel
        get_owner_request != null -> ResponseKind.AdminOwner
        get_config_request != null -> ResponseKind.AdminConfig
        get_module_config_request != null -> ResponseKind.AdminModuleConfig
        get_canned_message_module_messages_request != null -> ResponseKind.AdminCannedMessages
        get_device_metadata_request != null -> ResponseKind.AdminDeviceMetadata
        get_ringtone_request != null -> ResponseKind.AdminRingtone
        get_device_connection_status_request != null -> ResponseKind.AdminDeviceConnectionStatus
        get_node_remote_hardware_pins_request != null -> ResponseKind.AdminRemoteHardwarePins
        get_ui_config_request != null -> ResponseKind.AdminDeviceUIConfig
        else -> null
    }

    private fun AdminMessage.adminResponseKind(): ResponseKind<*>? = when {
        get_channel_response != null -> ResponseKind.AdminChannel
        get_owner_response != null -> ResponseKind.AdminOwner
        get_config_response != null -> ResponseKind.AdminConfig
        get_module_config_response != null -> ResponseKind.AdminModuleConfig
        get_canned_message_module_messages_response != null -> ResponseKind.AdminCannedMessages
        get_device_metadata_response != null -> ResponseKind.AdminDeviceMetadata
        get_ringtone_response != null -> ResponseKind.AdminRingtone
        get_device_connection_status_response != null -> ResponseKind.AdminDeviceConnectionStatus
        get_node_remote_hardware_pins_response != null -> ResponseKind.AdminRemoteHardwarePins
        get_ui_config_response != null -> ResponseKind.AdminDeviceUIConfig
        else -> null
    }

    private fun rememberAdminRequest(packet: MeshPacket) {
        val decoded = packet.decoded ?: return
        if (packet.id == 0 || packet.to == BROADCAST_ADDR || decoded.portnum != PortNum.ADMIN_APP) return
        val admin = runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull() ?: return
        val kind = admin.adminRequestKind() ?: return
        adminReplyTargets.remove(packet.id)
        if (adminReplyTargets.size >= ADMIN_REPLY_CAP) adminReplyTargets.remove(adminReplyTargets.keys.first())
        adminReplyTargets[packet.id] = ExpectedAdminReply(packet.to.takeIf { it != 0 } ?: myNodeNum, kind)
    }

    /**
     * `from` and `request_id` travel in the clear, so an admin reply from another node is only as trustworthy as its
     * encryption: PKI, or the legacy admin channel's PSK, the two paths firmware accepts remote admin on.
     */
    private fun isAuthenticatedAdminReply(packet: MeshPacket): Boolean {
        if (packet.from == 0 || packet.from == myNodeNum || packet.from == renumberedTo || packet.pki_encrypted) {
            return true
        }
        val adminIndex = adminChannelIndex()
        return adminIndex != null && packet.channel == adminIndex
    }

    // The wire index, not the list position: the handshake list is in arrival order and may be sparse.
    private fun adminChannelIndex(): Int? = channelsState.value
        ?.firstOrNull { it.settings?.name?.equals(ADMIN_CHANNEL_NAME, ignoreCase = true) == true }
        ?.index

    private fun recognizesAdminReply(packet: MeshPacket, requestId: Int, admin: AdminMessage): Boolean {
        val kind = admin.adminResponseKind() ?: return false
        // A live RPC is recognized by its registration; the bounded cache covers other sends and late replies.
        if (dispatcher.expects(requestId, kind, packet.from, myNodeNum)) return true
        val expected = adminReplyTargets[requestId] ?: return false
        return expected.kind == kind &&
            (packet.from == expected.sender || (packet.from == 0 && expected.sender == myNodeNum))
    }

    /** Returns the unexpired session passkey for [nodeNum], pruning a stale entry if present. */
    private fun validSessionEntryFor(nodeNum: Int): SessionPasskeyEntry? {
        val entry = sessionPasskeys[nodeNum] ?: return null
        if (clock.now() - entry.refreshedAt >= SESSION_PASSKEY_TTL) {
            publishAdminSessions()
            return null
        }
        return entry
    }

    /** Buffer a mid-handshake `FromRadio.packet` for delivery once the session reaches Ready. */
    private fun bufferHandshakePacket(packet: MeshPacket) = holdUntilReady(HeldItem.Packet(packet, clock.now()))

    private fun holdUntilReady(item: HeldItem) {
        if (handshakePacketBuffer.size >= HANDSHAKE_PACKET_BUFFER_CAP) {
            sessionReceive?.handshakeOverflow()
            val dropped = handshakePacketBuffer.removeFirst()
            logger.warn(TAG) {
                "Handshake packet buffer full (cap=$HANDSHAKE_PACKET_BUFFER_CAP) — dropping oldest"
            }
            // A held event already reached RadioClient.events; only the session stream loses it.
            if (dropped is HeldItem.Packet) emitEvent(MeshEvent.PacketsDropped(DroppedFlow.Packets, count = 1))
        }
        handshakePacketBuffer.addLast(item)
    }

    private fun transitionToReady() {
        handshakeStage = HandshakeStage.Ready
        lockdownLink.value = myNodeNum.takeIf { it != 0 }?.let { LockdownLink(receiveGeneration, it) }

        // The backoff starts over once the session proves stable; the restart count when it ends without an overflow.
        readyThisSession = true
        // A sleeping radio may stay awake for less than stableAfter; its next sleep starts the backoff over.
        sleepAttempts = 0
        sleeping = null
        reconnectJob?.cancel()
        reconnectJob = null
        pendingTick = null
        autoReconnectInProgress = false
        stableJob?.cancel()
        stableJob = null
        val stableAfter = autoReconnectConfig.stableAfter
        if (stableAfter == Duration.ZERO) {
            resetFailureStreak()
        } else {
            val generation = receiveGeneration
            stableJob = engineScope?.launch {
                delay(stableAfter)
                inbox.trySend(EngineMessage.LinkStable(generation))
            }
        }

        // C1: snapshot + clear preSendQueue BEFORE we publish ConnectionState.Connected.
        // Subscribers that react to Connected by calling RadioClient.send post a fresh
        // EngineMessage.Send into the inbox, which the actor processes on a later iteration —
        // their packets must NOT be folded into this drain (they belong to the post-Ready flow,
        // and dispatchSend below would mis-handle them as if they were part of the handshake
        // backlog).
        val snapshot = preSendQueue.toList()
        preSendQueue.clear()

        readySendGeneration.value = receiveGeneration
        publishState(ConnectionState.Connected)
        sessionReceive?.let { receive ->
            receive.ready(
                RadioSessionEvent.Ready(
                    sessionId = receive.sessionId,
                    myInfo = pendingMyInfo,
                    configBundle = configBundleState.value.takeIf {
                        pendingMetadata != null || pendingConfigs.isNotEmpty() || pendingModuleConfigs.isNotEmpty() ||
                            pendingDeviceUIConfig != null
                    },
                    channels = pendingChannels.takeIf { it.isNotEmpty() }?.toList(),
                    nodes = pendingNodes.toMap(),
                    files = pendingFiles.values.toList(),
                    regionPresets = pendingRegionPresets,
                ),
            )
        }

        startKeepalive()

        // Presence check: scan nodes for offline transitions every 30s (piggybacks on heartbeat interval).
        presenceJob?.cancel()
        presenceJob = null
        if (presenceTimeout.isPositive() && presenceTimeout.isFinite()) {
            presenceJob = engineScope?.launch {
                while (true) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    inbox.trySend(EngineMessage.PresenceCheckTick)
                }
            }
        }

        // Flush mesh packets that arrived mid-handshake through the normal Ready pipeline
        // (presence, dedup, packets flow, RPC/ACK correlation) before draining queued sends,
        // with the session copies of handshake events in receive order between them.
        if (handshakePacketBuffer.isNotEmpty()) {
            logger.info(TAG) { "Flushing ${handshakePacketBuffer.size} frames received during handshake" }
            while (handshakePacketBuffer.isNotEmpty()) {
                when (val held = handshakePacketBuffer.removeFirst()) {
                    is HeldItem.Packet -> processInboundMeshPacket(held.packet, held.receivedAt)
                    is HeldItem.Event -> sessionReceive?.event(held.event)
                }
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

    /** Starts the 30 s heartbeat and the liveness watchdog, for a Ready session or one awaiting lockdown. */
    private fun startKeepalive() {
        heartbeatJob?.cancel()
        heartbeatJob = engineScope?.launch {
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
    }

    // A session awaiting lockdown has a live link but no Ready deadline, so it keeps the Ready keepalive.
    private fun keepsLinkAlive(): Boolean =
        handshakeStage == HandshakeStage.Ready || handshakeStage == HandshakeStage.AwaitingLockdown

    private fun startNodeDbRefresh() {
        if (nodeDbRefresh != null) {
            nodeDbRefreshQueued = true
            return
        }
        // The radio ignores byte-identical consecutive writes. A heartbeat separates the fixed
        // nodes-only nonces even when the follow-up starts immediately after completion.
        sendKeepaliveHeartbeat()
        val sent = trySendToRadio(ToRadio.Builder().also { it.want_config_id = NONCE_STAGE2 }.build())
        if (!sent) {
            handleDisconnect(
                EngineMessage.Disconnect(MeshtasticException.Transport("Could not queue node database refresh")),
            )
            return
        }
        nodeDbRefreshDumpStarted = false
        nodeDbRefresh = linkedMapOf()
        val request = ++nodeDbRefreshRequest
        nodeDbRefreshWatchdog = engineScope?.launch {
            delay(NODE_DB_REFRESH_TIMEOUT_MS)
            inbox.trySend(EngineMessage.NodeDbRefreshTimeout(request))
        }
    }

    private fun handleNodeDbRefreshFrame(fromRadio: org.meshtastic.proto.FromRadio): Boolean {
        val refresh = nodeDbRefresh ?: return false
        val node = fromRadio.node_info
        // A nodes-only dump opens with the node's own node_info (PhoneAPI handleStartConfig starts 69421 at
        // STATE_SEND_OWN_NODEINFO). Earlier frames were queued before the request reached the radio, so a
        // node_info is live traffic and a completion belongs to an earlier dump.
        if (!nodeDbRefreshDumpStarted) {
            if (node == null || !isOwnNodeInfo(node)) return fromRadio.config_complete_id != null
            nodeDbRefreshDumpStarted = true
        }
        if (node != null) {
            refresh[NodeId(node.num)] = node
            return true
        }
        if (fromRadio.config_complete_id != NONCE_STAGE2) {
            return fromRadio.deviceuiConfig != null || fromRadio.config_complete_id != null
        }
        // The collector sees the completed dump at its position in the receive stream.
        // The actor still processes mesh packets and routes ACKs while collecting the dump.
        val followUp = nodeDbRefreshQueued
        clearNodeDbRefresh()
        sessionReceive?.nodeDbRefreshed(refresh)
        if (followUp) startNodeDbRefresh()
        return true
    }

    // A held ACK from another number for our own write marks a renumber firmware may not announce: it
    // sends no my_info during a nodes-only dump, which then opens with our node_info under the new number.
    private fun isOwnNodeInfo(node: NodeInfo): Boolean = node.num == myNodeNum || node.num == renumberedTo ||
        (heldRenumberAck != null && node.num == heldRenumberAckFrom)

    private fun clearNodeDbRefresh() {
        nodeDbRefreshDumpStarted = false
        nodeDbRefreshWatchdog?.cancel()
        nodeDbRefreshWatchdog = null
        nodeDbRefresh = null
        nodeDbRefreshQueued = false
    }

    private fun routeNormalEnvelope(fromRadio: org.meshtastic.proto.FromRadio) {
        val packet = fromRadio.packet
        val nodeInfo = fromRadio.node_info
        val queueStatus = fromRadio.queueStatus

        when {
            packet != null -> if (!consumeSessionSeedReply(packet)) processInboundMeshPacket(packet)

            nodeInfo != null -> {
                if (heldRenumberAck != null && nodeInfo.num == heldRenumberAckFrom) beginRenumber(nodeInfo.num)
                processInboundNodeInfo(nodeInfo)
            }

            fromRadio.my_info != null -> handleReadyMyInfo(fromRadio.my_info!!)

            queueStatus != null -> {
                emitEvent(MeshEvent.QueueStatusChanged(queueStatus))
                // Heartbeat replies also use ID zero. They report available capacity, so
                // they cannot prove that the outstanding paced write was admitted.
                if (queueStatus.mesh_packet_id == 0) return
                val packetId = MessageId(queueStatus.mesh_packet_id)
                val paced = pacedSends[packetId]
                if (paced != null) {
                    if (paced.state.value == SendState.Sent) handlePacedQueueStatus(paced, queueStatus.res)
                    return
                }
                // `res` is the firmware's enqueue result in the ERRNO namespace
                // (MeshTypes.h), NOT Routing.Error: 0 = OK, 32 = queue full / unknown,
                // 33 = no radio interface, 34 = radio disabled, and 35
                // (ERRNO_SHOULD_RELEASE) means "no error, packet handled without LoRa TX" —
                // a SUCCESS. Values 1..31 are genuine Routing.Error codes that
                // Router::send returns directly (e.g. DUTY_CYCLE_LIMIT = 9). The two
                // namespaces collide at 32..35 (BAD_REQUEST = 32, NOT_AUTHORIZED = 33, …),
                // so those are read as ERRNO; above 35 only Routing.Error codes remain
                // (PKI_SEND_FAIL_PUBLIC_KEY = 39 from perhapsEncode).
                if (queueStatus.res == 0 || queueStatus.res == ERRNO_SHOULD_RELEASE) {
                    pendingSends[packetId]?.value = SendState.Sent
                } else {
                    // The firmware rejected the packet at its transmit queue. Fast-fail
                    // the handle / pending RPC instead of letting the caller wait out the
                    // full ACK timer for a packet that never left the device.
                    val rpcResult: AdminResult<Nothing> =
                        if (queueStatus.res < ERRNO_BASE || queueStatus.res > ERRNO_SHOULD_RELEASE) {
                            Routing.Error.fromValue(queueStatus.res)
                                ?.let(CommandDispatcher::mapRoutingError)
                                ?: AdminResult.NodeUnreachable
                        } else {
                            AdminResult.NodeUnreachable
                        }
                    dispatcher.tryFail(packetId.raw, rpcResult)
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
     * Merge an unsolicited `FromRadio.node_info` into the node DB and emit the matching
     * delta. Called for live frames in [routeNormalEnvelope].
     */
    private fun processInboundNodeInfo(nodeInfo: NodeInfo) {
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

    /**
     * Fold a decoded mesh packet into the sender's node entry, creating it when unknown, and
     * emit one [NodeChange.Added] or [NodeChange.Updated]. Mirrors firmware `NodeDB::updateFrom`,
     * `updateUser` and `updatePosition`; own-node packets skip the link metrics.
     */
    private fun mergeLivePacketIntoNodeDb(packet: MeshPacket, decoded: Data) {
        if (packet.from == 0) return
        val nodeId = NodeId(packet.from)
        val isOwn = packet.from == myNodeNum
        val existing = meshState.nodes[nodeId]
        val wb = (existing ?: NodeInfo.Builder().also { b -> b.num = packet.from }.build()).newBuilder()

        val rxTime = packet.rx_time?.takeIf { it != 0 }
        when {
            rxTime != null -> wb.last_heard = rxTime

            // A replay with no rx_time is history from a node never heard on a trusted clock.
            !packet.isStoredReplay() -> wb.last_heard = clock.now().epochSeconds.toInt()
        }
        if (!isOwn) {
            // An SNR is ours only when heard over our own radio; firmware replays carry no rx_rssi.
            if (packet.transport_mechanism == MeshPacket.TransportMechanism.TRANSPORT_LORA && packet.rx_rssi != null) {
                wb.snr = packet.rx_snr
            }
            wb.via_mqtt = packet.via_mqtt
            // NodeDB.cpp getHopsAway: hop_start 0 is trustworthy only from a sender stamping the 2.5+ bitfield.
            val hopStartKnown = packet.hop_start != 0 || decoded.bitfield != null
            if (hopStartKnown && packet.hop_start >= packet.hop_limit) {
                wb.hops_away = packet.hop_start - packet.hop_limit
            }
        }

        when (decoded.portnum) {
            PortNum.NODEINFO_APP -> {
                val user = runCatching { User.ADAPTER.decode(decoded.payload) }.getOrNull()
                val knownKey = existing?.user?.public_key
                val placeholder = user != null && user.long_name.isEmpty() && user.short_name.isEmpty() &&
                    user.public_key.size == 0
                val keyMismatch = user != null && knownKey != null && knownKey.size == 32 && user.public_key != knownKey
                if (user != null && keyMismatch && !placeholder) {
                    announceKeyMismatch(nodeId, user.public_key)
                }
                if (user != null && !placeholder && !keyMismatch) {
                    val id = "!" + packet.from.toUInt().toString(16).padStart(8, '0')
                    wb.user = user.newBuilder().also { ub -> ub.id = id }.build()
                    if (!isOwn) wb.channel = packet.channel
                }
            }

            PortNum.POSITION_APP -> {
                val pos = runCatching { Position.ADAPTER.decode(decoded.payload) }.getOrNull()
                if (pos != null) mergePosition(wb, existing?.position, pos)
            }

            else -> Unit
        }

        val updated = wb.build()
        meshState = meshState.withNodes(meshState.nodes + (nodeId to updated))
        if (isOwn) ownNode.value = updated
        if (existing == null) {
            emitNodeChangeOrLog(NodeChange.Added(updated))
        } else {
            val changed = diffNodeFields(existing, updated)
            if (changed.isNotEmpty()) emitNodeChangeOrLog(NodeChange.Updated(updated, changed))
        }
    }

    // Firmware replays stored satellite data after config_complete_id marked TRANSPORT_LORA, but only a
    // radio receive sets rx_rssi (PhoneAPI::makeReplay*Packet, NodeDB::updateFrom), so this is history.
    private fun MeshPacket.isStoredReplay(): Boolean =
        transport_mechanism == MeshPacket.TransportMechanism.TRANSPORT_LORA && rx_rssi == null

    // Bounded, insertion-ordered: the oldest (node, key) pair is forgotten first.
    private val reportedKeyMismatches = LinkedHashSet<Pair<Int, ByteString>>()

    private fun announceKeyMismatch(nodeId: NodeId, offered: ByteString) {
        if (!reportedKeyMismatches.add(nodeId.raw to offered)) return
        if (reportedKeyMismatches.size > MAX_REPORTED_KEY_MISMATCHES) {
            reportedKeyMismatches.remove(reportedKeyMismatches.first())
        }
        emitEvent(MeshEvent.SecurityWarning.PublicKeyMismatch(nodeId, offered))
    }

    private fun mergePosition(wb: NodeInfo.Builder, previous: Position?, pos: Position) {
        val noFix = (pos.latitude_i ?: 0) == 0 && (pos.longitude_i ?: 0) == 0
        when {
            // A request, or a position with nothing in it.
            noFix && pos.time == 0 && pos.timestamp == 0 -> Unit

            // Time-only update (clock sync from a client): keeps the stored fix.
            noFix && pos.timestamp == 0 && pos.location_source == Position.LocSource.LOC_UNSET ->
                wb.position =
                    (previous ?: Position.Builder().build()).newBuilder().also { pb -> pb.time = pos.time }.build()

            else -> wb.position = if (pos.time == 0 && previous != null) {
                pos.newBuilder().also { pb -> pb.time = previous.time }.build()
            } else {
                pos
            }
        }
    }

    /**
     * The Ready-stage pipeline for a single inbound [MeshPacket]: presence, dedup, public
     * `packets` emission, session-passkey latching, RPC/ACK correlation, telemetry merge, and
     * external-config-change detection. Called for live packets in [routeNormalEnvelope] and
     * for the mid-handshake backlog flushed in [transitionToReady].
     */
    private fun processInboundMeshPacket(packet: MeshPacket, receivedAt: Instant = clock.now()) {
        logger.verbose(TAG) {
            "Rx packet id=${packet.id} from=0x${packet.from.toString(16)} " +
                "port=${packet.decoded?.portnum ?: "encrypted"}"
        }
        // Any frame from a node counts as presence activity. A packet the session sees is offered before
        // the CameOnline it may cause.
        val heard = { if (packet.from != 0 && !packet.isStoredReplay()) markNodeHeard(NodeId(packet.from)) }
        if (shouldDropDuplicateInboundPacket(packet)) return heard()
        val decoded = packet.decoded
        if (decoded == null) {
            heard()
            maybeWarnEncryptedPacketSkipped()
            return
        }
        if (decoded.portnum == PortNum.UNKNOWN_APP) {
            heard()
            logger.warn(TAG) {
                "Dropping packet id=${packet.id} with unknown port from=0x${packet.from.toString(16)}"
            }
            emitEvent(
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
        // Decode the admin payload once; both the passkey latch and external-change detection
        // need it, and inbound ADMIN_APP traffic is hot during config syncs.
        val adminMsg = if (decoded.portnum == PortNum.ADMIN_APP) {
            runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull()
        } else {
            null
        }
        val unauthenticatedAdminReply = decoded.portnum == PortNum.ADMIN_APP && !isAuthenticatedAdminReply(packet)
        if (unauthenticatedAdminReply && decoded.request_id != 0) {
            logger.warn(TAG) { "Ignored unauthenticated admin reply from ${packet.from.toUInt()}" }
        }
        val correlatedAdminResponse = adminMsg != null && !unauthenticatedAdminReply &&
            recognizesAdminReply(packet, decoded.request_id, adminMsg)
        // The session stream gets the packet before the node, config and event changes it causes.
        sessionReceive?.packet(packet, correlatedAdminResponse)
        heard()
        mergeLivePacketIntoNodeDb(packet, decoded)
        emitPacketOrLog(packet)
        if (correlatedAdminResponse) {
            // Install the key before completing the RPC, so its caller can immediately use the session.
            latchSessionPasskey(packet.from.takeIf { it != 0 } ?: myNodeNum, adminMsg, receivedAt)
            adminReplyTargets.remove(decoded.request_id)
        }
        val connectionStatus = adminMsg?.get_device_connection_status_response
        if (connectionStatus != null && (packet.from == 0 || packet.from == myNodeNum)) {
            trackLocalMqttState(connectionStatus)
        }
        // RPC dispatch: complete any pending getter / traceRoute / neighborInfo
        // request matching this packet's request_id. Must run before processRoutingAck
        // so a route_reply doesn't get mistakenly classified as a generic Acked send.
        val response = if (correlatedAdminResponse && packet.from == 0) {
            packet.newBuilder().also { it.from = myNodeNum }.build()
        } else {
            packet
        }
        if (!unauthenticatedAdminReply) dispatcher.tryComplete(response)
        processRoutingAck(packet)
        emitRoutingReport(packet)
        // Telemetry → node update: merge device_metrics into the node DB so that
        // NodeChange subscribers see battery/telemetry changes without calling TelemetryApi.
        maybeMergeDeviceMetrics(packet)
        maybeEmitCongestionWarning(packet)
        // External config change detection: unsolicited admin messages from firmware
        // indicating another client modified channels/config on this device.
        if (adminMsg != null) maybeProcessExternalAdminChange(packet, adminMsg)
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
            deviceLogs.tryEmit(fromRadio.log_record!!)
            true
        }

        fromRadio.clientNotification != null -> {
            // surface the raw notification, then
            // translate the security-relevant payload variants into typed SecurityWarning
            // sub-events so hosts can react without string-matching the message field.
            val cn = fromRadio.clientNotification!!
            emitEvent(MeshEvent.Notification(cn))
            when {
                cn.duplicated_public_key != null ->
                    emitEvent(MeshEvent.SecurityWarning.DuplicatedPublicKey)

                cn.low_entropy_key != null ->
                    emitEvent(MeshEvent.SecurityWarning.LowEntropyKey)

                cn.key_verification_number_request != null -> cn.key_verification_number_request!!.let { r ->
                    emitEvent(
                        MeshEvent.KeyVerification(
                            KeyVerificationPrompt.EnterSecurityNumber(r.nonce, r.remote_longname),
                        ),
                    )
                }

                cn.key_verification_number_inform != null -> cn.key_verification_number_inform!!.let { i ->
                    emitEvent(
                        MeshEvent.KeyVerification(
                            KeyVerificationPrompt.ShowSecurityNumber(
                                i.nonce,
                                i.remote_longname,
                                i.security_number,
                            ),
                        ),
                    )
                }

                cn.key_verification_final != null -> cn.key_verification_final!!.let { f ->
                    emitEvent(
                        MeshEvent.KeyVerification(
                            KeyVerificationPrompt.ConfirmVerification(
                                f.nonce,
                                f.remote_longname,
                                f.isSender,
                                f.verification_characters,
                            ),
                        ),
                    )
                }
            }
            true
        }

        fromRadio.mqttClientProxyMessage != null -> {
            // MQTT client proxy: the device tunnels its MQTT traffic through the host.
            // Surfaced as a typed event; the host relays to the broker and answers via
            // RadioClient.sendRaw(ToRadio(mqttClientProxyMessage = …)).
            emitEvent(MeshEvent.MqttProxyMessage(fromRadio.mqttClientProxyMessage!!))
            true
        }

        fromRadio.xmodemPacket != null -> {
            // XModem file-transfer frame (firmware update / file transfer flows).
            emitEvent(MeshEvent.XmodemPacket(fromRadio.xmodemPacket!!))
            true
        }

        fromRadio.deviceuiConfig != null -> {
            val uiConfig = fromRadio.deviceuiConfig
            val bundle = configBundleState.value
            if (stage == HandshakeStage.Ready && bundle != null) {
                commitConfigBundle(bundle.copy(deviceUIConfig = uiConfig))
            } else {
                pendingDeviceUIConfig = uiConfig
            }
            true
        }

        fromRadio.fileInfo != null -> {
            val file = fromRadio.fileInfo!!
            if (stage != HandshakeStage.Ready) pendingFiles[file.file_name] = file
            emitEvent(MeshEvent.FileInfoReceived(file))
            true
        }

        fromRadio.region_presets != null -> {
            if (stage != HandshakeStage.Ready) pendingRegionPresets = fromRadio.region_presets
            true
        }

        // Display mirror chunks: reassembled into whole frames / palettes, never rendered here.
        fromRadio.display_frame != null -> {
            handleDisplayFrameChunk(fromRadio.display_frame!!)
            true
        }

        fromRadio.display_palette != null -> {
            displayPaletteReassembler.accept(fromRadio.display_palette!!)?.let { displayPalette.value = it }
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

    /**
     * Emit [MeshEvent.MqttConnected] / [MeshEvent.MqttDisconnected] when a local
     * `DeviceConnectionStatus` shows the broker link changed since the last one this connection
     * saw. A first observation of disconnected only records the state.
     */
    private fun trackLocalMqttState(status: DeviceConnectionStatus) {
        // Firmware reports MQTT only inside a WiFi or Ethernet status (AdminModule
        // handleGetDeviceConnectionStatus); a device with neither reports nothing about it.
        val networks = listOfNotNull(status.wifi?.status, status.ethernet?.status)
        if (networks.isEmpty()) return
        val connected = networks.any { it.is_mqtt_connected }
        val previous = lastMqttConnected
        lastMqttConnected = connected
        if (connected && previous != true) {
            emitEvent(MeshEvent.MqttConnected)
        } else if (!connected && previous == true) {
            emitEvent(MeshEvent.MqttDisconnected())
        }
    }

    private fun handleDisplayFrameChunk(chunk: org.meshtastic.proto.DisplayFrame) {
        when (val outcome = displayFrameReassembler.accept(chunk)) {
            DisplayFrameReassembler.Outcome.Incomplete -> Unit

            is DisplayFrameReassembler.Outcome.Complete -> displayFrames.tryEmit(outcome.frame)

            is DisplayFrameReassembler.Outcome.Dropped ->
                logger.warn(TAG) { "Dropped display frame chunk: ${outcome.reason}" }
        }
    }

    private fun resetDisplayMirror() {
        displayFrameReassembler.reset()
        displayPaletteReassembler.reset()
        displayPalette.value = null
    }

    private fun warnUnhandledVariant(variant: String, stage: HandshakeStage) {
        val details = mutableMapOf<String, Any?>("variant" to variant)
        if (stage != HandshakeStage.Ready) details["stage"] = stage.toDisplayName()
        emitEvent(
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

        if (routing.error_reason == Routing.Error.ADMIN_BAD_SESSION_KEY) {
            // Drop the key only if the rejected request used the current one; a newer key
            // installed since must survive a late rejection of an older one.
            val node = packet.from.takeIf { it != 0 } ?: myNodeNum
            val usedGeneration = requestPasskeyGenerations.remove(decoded.request_id)
            if (usedGeneration != null && sessionPasskeys[node]?.generation == usedGeneration) {
                sessionPasskeys.remove(node)
            }
            publishAdminSessions()
        }

        // RPC failure: a Routing.Error variant matching a pending getter / traceRoute
        // is the failure surface for that call. Try the dispatcher BEFORE checking pendingSends
        // requests don't allocate a MessageHandle, so they wouldn't be in pendingSends.
        val errorReason = routing.error_reason
        if (errorReason != null && errorReason != Routing.Error.NONE &&
            dispatcher.ownsRoutingFailure(messageId.raw, packet.from, myNodeNum) &&
            dispatcher.tryFailFromRouting(messageId.raw, errorReason, packet.from, myNodeNum)
        ) {
            // Defensive cleanup if a stale pendingSends entry shared the wire id.
            pendingSends.remove(messageId)
            ackTimeoutJobs.remove(messageId)?.cancel()
            return
        }

        val paced = pacedSends[messageId]
        if (paced != null) {
            if (paced.state.value == SendState.Sent) handlePacedRouting(paced, packet, routing)
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
                val failure = routingFailure(errorReason)
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

    // Firmware stamps a first transmission with the sender's own relay byte (FloodingRouter::send), so one
    // heard with no hops used, or bridged from MQTT, names no relay. An implicit ACK copies the relayer's
    // byte from the overheard rebroadcast (MeshModule::allocAckNak); 0 is NO_RELAY_NODE (MeshTypes.h).
    private fun emitRoutingReport(packet: MeshPacket) {
        val decoded = packet.decoded ?: return
        if (decoded.portnum != PortNum.ROUTING_APP || decoded.request_id == 0) return
        if (packet.to != 0 && !isConnectedNodeNum(packet.to)) return
        val routing = runCatching { Routing.ADAPTER.decode(decoded.payload) }.getOrNull() ?: return
        val error = routing.error_reason ?: return
        val local = packet.from == 0 || isConnectedNodeNum(packet.from)
        val heardDirect = packet.hop_start != 0 && packet.hop_limit == packet.hop_start
        emitEvent(
            MeshEvent.RoutingReport(
                requestId = MessageId(decoded.request_id),
                from = NodeId(packet.from.takeIf { it != 0 } ?: myNodeNum),
                error = error,
                fromLocalRadio = local,
                relayNode = packet.relay_node.takeUnless { it == 0 || heardDirect || packet.via_mqtt },
                ackProof = packet.ack_proof_status,
            ),
        )
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

        val updated = existing.newBuilder().also { wb -> wb.device_metrics = deviceMetrics }.build()
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
            emitEvent(MeshEvent.CongestionWarning(metrics))
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
    private fun maybeProcessExternalAdminChange(packet: MeshPacket, adminMsg: AdminMessage) {
        val decoded = packet.decoded ?: return
        // Only consider packets from our own node (firmware pushing changes locally)
        if (packet.from != myNodeNum && packet.from != 0) return
        // Solicited responses have a non-zero request_id matching a pending RPC
        if (decoded.request_id != 0) return

        adminMsg.get_channel_response?.let { channel ->
            logger.info(TAG) { "External channel change detected (index=${channel.index})" }
            updateChannelAndPersist(channel)
            emitEvent(MeshEvent.ExternalConfigChange(ExternalChangeKind.CHANNEL))
            return
        }

        adminMsg.get_config_response?.let { config ->
            logger.info(TAG) { "External config change detected" }
            val current = configBundleState.value ?: return
            val merged = mergeConfigs(current.configs, listOf(config))
            val updated = current.copy(configs = merged)
            commitConfigBundle(updated)
            // Persist the merged config bundle
            configSaves?.trySend(updated)
            emitEvent(MeshEvent.ExternalConfigChange(ExternalChangeKind.CONFIG))
            return
        }

        adminMsg.get_module_config_response?.let { moduleConfig ->
            logger.info(TAG) { "External module config change detected" }
            val current = configBundleState.value ?: return
            val merged = mergeModuleConfigs(current.moduleConfigs, listOf(moduleConfig))
            val updated = current.copy(moduleConfigs = merged)
            commitConfigBundle(updated)
            configSaves?.trySend(updated)
            emitEvent(MeshEvent.ExternalConfigChange(ExternalChangeKind.MODULE_CONFIG))
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
        emitEvent(
            MeshEvent.ProtocolWarning(
                "encrypted packet skipped for ACK correlation",
                details = mapOf("rate_limited" to true),
            ),
        )
    }

    private fun reportMalformedFrame(message: String, details: Map<String, Any?>) {
        logger.warn(TAG) { message }
        emitEvent(MeshEvent.ProtocolWarning(message, details = details))
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
        val s = msg.state
        // Only the live link's errors end a session: a failed attempt reports through its own outcome,
        // and a replaced transport's late updates are stale.
        if (s !is TransportState.Error || msg.source !== transport || !linkUp) {
            logger.verbose(TAG) { "Transport state → $s" }
            return
        }
        logger.warn(TAG, s.cause) { "Transport error: ${s.cause.message}" }
        val cause = MeshtasticException.tag(
            s.cause as? MeshtasticException
                ?: MeshtasticException.Transport(s.cause.message ?: "transport error", s.cause),
            transportIdentity = identity,
            operation = "transport.observe",
        )
        emitEvent(MeshEvent.TransportError(cause))

        // Judged before scheduleReconnect resets the handshake stage and the config bundle.
        val sleep = judgeSleepOnDrop(s.cause, s.recoverable)
        when {
            !s.recoverable || cause is MeshtasticException.FirmwareTooOld -> failSession(cause)

            autoReconnectConfig.enabled && !disconnecting -> scheduleReconnect(cause)

            else -> {
                linkUp = false
                sessionReceive?.end()
                sleep?.let(::settleTerminalSleep)
                supervisorJobRef.value?.cancel()
            }
        }
    }

    /**
     * Ends the session for good with [ConnectionState.Failed]: no Reconnecting is published, a pending
     * [connect] throws [cause], and cleanup keeps Failed as the final state.
     */
    private fun failSession(cause: MeshtasticException) {
        logger.warn(TAG, cause) { "Connection failed; not retrying" }
        linkUp = false
        writeLink.value = null
        clearNodeDbRefresh()
        sessionReceive?.end()
        reconnectJob?.cancel()
        reconnectJob = null
        pendingTick = null
        autoReconnectInProgress = false
        sleeping = null
        if (handshakeStage != HandshakeStage.Ready) failPreSendQueueWith(SendFailure.HandshakeFailed)
        handshakeStage = HandshakeStage.Idle
        readySendGeneration.value = null
        lockdownLink.value = null
        val failed = ConnectionState.Failed(cause)
        terminalState = failed
        // Published before connect() is released, so a caller that catches its throw already reads Failed.
        publishState(failed)
        val deferred = pendingConnectDeferred
        pendingConnectDeferred = null
        deferred?.completeExceptionally(cause)
        supervisorJobRef.value?.cancel()
    }

    /**
     * Restarts the session after an engine-detected stall ([cause]: a handshake or liveness timeout, a
     * handshake protocol error or a session receive overflow) when auto-reconnect is on. Returns false
     * when it is off, leaving the teardown to the caller.
     */
    private fun recoverSession(cause: MeshtasticException, overflow: Boolean = false): Boolean {
        if (!autoReconnectConfig.enabled || disconnecting) return false
        // An overflow can follow Ready at once, while the device replays its history, so it does not end the run.
        if (readyThisSession && !overflow) handshakeRecoveries = 0
        readyThisSession = false
        val tagged = MeshtasticException.tag(cause, transportIdentity = identity, operation = "engine.recover")
        // A collector that keeps falling behind would otherwise restart the session forever.
        val cap = autoReconnectConfig.maxHandshakeRecoveries ?: if (overflow) DEFAULT_OVERFLOW_RESTARTS else null
        // A radio that kept its link through light sleep accepts the connect but stays silent until it wakes.
        if (!inSleepWindow()) {
            if (cap != null && handshakeRecoveries >= cap) {
                failSession(tagged)
                return true
            }
            handshakeRecoveries += 1
        }
        if (handshakeStage != HandshakeStage.Ready) failPreSendQueueWith(SendFailure.HandshakeFailed)
        scheduleReconnect(tagged)
        return true
    }

    // Every drop out of a Ready session is judged here, whichever way it was noticed: a transport error, a failed
    // write or read, or the liveness watchdog. A reboot has already reset the stage.
    private fun judgeSleepOnDrop(cause: Throwable, recoverable: Boolean): ConnectionState.DeviceSleeping? {
        if (handshakeStage != HandshakeStage.Ready || !recoverable || disconnecting) return null
        if (cause is MeshtasticException.FirmwareTooOld) return null
        val expectation = sleepExpectation(identity, configBundleState.value) ?: return null
        return ConnectionState.DeviceSleeping(clock.now(), expectation.expectedWakeSeconds).also { sleeping = it }
    }

    // With no reconnect to follow, cleanup settles on this instead of Disconnected.
    private fun settleTerminalSleep(sleep: ConnectionState.DeviceSleeping) {
        publishState(sleep)
        terminalState = sleep
    }

    // Session state a new handshake must not inherit. Engine scope, storage, the supervisor and
    // channels survive it.
    private fun resetSessionState() {
        handshakeStage = HandshakeStage.Idle
        lockdownLink.value = null
        readySendGeneration.value = null
        clearPacedSends()
        clearSessionPasskeys()
        dispatcher.cancelAll(AdminResult.NodeUnreachable)
        configBundleState.value = null
        lastMqttConnected = null
        clearPendingHandshake()
        for ((_, job) in ackTimeoutJobs) job.cancel()
        ackTimeoutJobs.clear()
        stage2WatchdogJob?.cancel()
        stage2WatchdogJob = null
        stage2ProgressCounter = 0L
        recentInboundPacketKeys.clear()
        recentInboundPacketKeySet.clear()
        resetDisplayMirror()
        livenessWatchdogJob?.cancel()
        livenessWatchdogJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        presenceJob?.cancel()
        presenceJob = null
        stableJob?.cancel()
        stableJob = null

        // Fail in-flight sends — their wire IDs won't survive the device-side session restart.
        for ((_, stateFlow) in pendingSends) {
            stateFlow.value = SendState.Failed(SendFailure.Disconnected)
        }
        pendingSends.clear()
        failPreSendQueueWith(SendFailure.Disconnected)
        renumberedTo = null
        heldRenumberAck = null
        staleNumberWrite = null
        regionWrite = null
        renumberRestartJob?.cancel()
        renumberRestartJob = null
        silentRenumberProbe?.cancel()
        silentRenumberProbe = null
    }

    private fun handleReadyMyInfo(info: org.meshtastic.proto.MyNodeInfo) = beginRenumber(info.my_node_num)

    private fun beginRenumber(newNum: Int) {
        if (newNum == 0 || newNum == myNodeNum || newNum == renumberedTo) return
        logger.info(TAG) { "Node renumbered ${myNodeNum.toUInt()} -> ${newNum.toUInt()}; restarting the handshake" }
        renumberedTo = newNum
        val held = heldRenumberAck
        if (held != null && heldRenumberAckFrom == newNum) {
            finishPaced(held, SendState.Acked)
            held.receipt.complete(QueueReceipt.Accepted)
            releaseQueueWrite(held)
        }
        // The device ran the region write before its QueueStatus 0 and, having moved, never acks it.
        regionWrite?.takeIf { pacedSends[it.id] === it }?.let { finishPaced(it, SendState.Acked) }
        regionWrite = null
        // Queued writes, a batch commit among them, still go out, addressed to the new number.
        pumpPacedQueue()
        maybeRestartForRenumber()
    }

    private fun retargetRenumbered(packet: MeshPacket): MeshPacket {
        val newNum = renumberedTo ?: return packet
        return if (packet.to == myNodeNum) packet.newBuilder().also { it.to = newNum }.build() else packet
    }

    // The handshake restarts once the paced queue has stayed empty for a moment, so a batch whose next
    // write is a step away, its commit among them, is not cut off.
    private fun maybeRestartForRenumber() {
        if (renumberedTo == null || pacedSends.isNotEmpty()) return
        renumberRestartJob?.cancel()
        renumberRestartJob = engineScope?.launch {
            delay(RENUMBER_SETTLE_MS)
            inbox.trySend(EngineMessage.RenumberRestart)
        }
    }

    // A fresh handshake on the same link rebinds the identity the way a reconnect does.
    private fun restartSessionForRenumber() {
        // Work begun under the old identity, an open editSettings among it, must not continue.
        connectionGeneration.incrementAndGet()
        clearNodeDbRefresh()
        sessionReceive?.end()
        resetSessionState()
        sessionReceive?.begin()
        requestStage1Config()
    }

    private fun scheduleReconnect(cause: MeshtasticException) {
        if (readyThisSession) handshakeRecoveries = 0
        readyThisSession = false
        linkUp = false
        // A frame still queued for this link must not reach the transport once it is reconnected.
        writeLink.value = null
        clearNodeDbRefresh()
        sessionReceive?.end()
        frameReaderJob?.cancel()
        val cap = autoReconnectConfig.maxAttempts
        // A sleep gets attempts outside the cap until its wake bound plus a grace has passed.
        val freeAttempt = inSleepWindow()
        if (!freeAttempt && cap != null && reconnectAttempt >= cap) {
            logger.warn(TAG, cause) { "Auto-reconnect attempt cap ($cap) exhausted" }
            failSession(cause)
            return
        }
        if (freeAttempt) sleepAttempts += 1 else reconnectAttempt += 1
        autoReconnectInProgress = true
        resetSessionState()

        val attempt = reconnectAttempt + sleepAttempts
        val backoff = computeBackoff(attempt)
        publishState(sleeping ?: ConnectionState.Reconnecting(cause, reconnectAttempt, clock.now() + backoff))
        logger.info(TAG, cause) {
            "Auto-reconnect attempt $attempt scheduled in ${backoff.inWholeMilliseconds} ms"
        }
        val tick = EngineMessage.ReconnectTick(cause, attempt)
        pendingTick = tick
        reconnectJob?.cancel()
        reconnectJob = engineScope?.launch {
            delay(backoff)
            inbox.trySend(tick)
        }
    }

    private fun handleReconnectTick(msg: EngineMessage.ReconnectTick) {
        if (!autoReconnectInProgress || msg !== pendingTick) return
        val scope = engineScope ?: return
        pendingTick = null
        reconnectJob = null
        if (sleeping == null) publishState(ConnectionState.Reconnecting(msg.cause, reconnectAttempt))
        val previous = transport
        val previousReader = frameReaderJob
        val token = ++attemptToken
        attemptJob = scope.launch(CoroutineName("meshtastic-reconnect-attempt")) {
            var opened: RadioTransport? = null
            val outcome = try {
                // Serialized with credential writes, so a queued lockdown command never reaches the next link.
                lockdownWriteMutex.withLock {
                    disconnectQuietly(previous)
                    previousReader?.join()
                    val next = transportProvider.open(msg.attempt, msg.cause)
                    opened = next
                    attemptLink.value = next
                    next.connect()
                }
                EngineMessage.ReconnectAttemptResult(token, opened, cause = null)
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) {
                    // Torn down mid-attempt: a link opened for a session that no longer exists must not stay up.
                    withContext(NonCancellable) { disconnectQuietly(opened) }
                    throw e
                }
                // A provider's own timeout (TimeoutCancellationException) fails the attempt, it doesn't end the engine.
                val cause = e as? MeshtasticException
                    ?: MeshtasticException.Transport(e.message ?: "reconnect attempt failed", e)
                EngineMessage.ReconnectAttemptResult(
                    token,
                    opened,
                    MeshtasticException.tag(cause, transportIdentity = identity, operation = "engine.reconnect"),
                )
            }
            inbox.trySend(outcome)
        }
    }

    private suspend fun disconnectQuietly(link: RadioTransport?) {
        try {
            link?.disconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug(TAG, e) { "Transport disconnect failed: ${e.message}" }
        }
    }

    private fun handleReconnectAttemptResult(msg: EngineMessage.ReconnectAttemptResult) {
        if (!autoReconnectInProgress || msg.token != attemptToken) return
        attemptJob = null
        attemptLink.value = null
        msg.transport?.let(::installTransport)
        val link = msg.transport
        val failure = msg.cause ?: (link?.state?.value as? TransportState.Error)?.let { error ->
            MeshtasticException.tag(
                error.cause as? MeshtasticException
                    ?: MeshtasticException.Transport(error.cause.message ?: "transport error", error.cause),
                transportIdentity = identity,
                operation = "engine.reconnect",
            )
        }
        if (failure == null && link != null) {
            logger.info(TAG) { "Auto-reconnect attempt ${reconnectAttempt + sleepAttempts} succeeded" }
            // The link can come up while the radio still sleeps; only Ready proves it awake.
            linkUp = true
            livenessBudgetMs = LIVENESS_TIMEOUT_MS
            startStage1Handshake()
            return
        }
        val cause = failure ?: MeshtasticException.tag(
            MeshtasticException.Transport("reconnect attempt failed"),
            transportIdentity = identity,
            operation = "engine.reconnect",
        )
        if (isTerminal(link)) {
            failSession(cause)
            return
        }
        logger.warn(TAG, cause) { "Auto-reconnect attempt ${reconnectAttempt + sleepAttempts} failed; rescheduling" }
        scheduleReconnect(cause)
    }

    /**
     * True while [sleeping] is set and its wake bound plus grace has not passed; clears [sleeping] once
     * it has. Attempts and handshake restarts inside the window count against no cap.
     */
    private fun inSleepWindow(): Boolean {
        val sleep = sleeping ?: return false
        if (clock.now() < sleep.since + sleep.expectedWakeSeconds.seconds + autoReconnectConfig.maxBackoff * 2) return true
        sleeping = null
        return false
    }

    // Only a session that stayed up for stableAfter ends the failure streak.
    private fun resetFailureStreak() {
        reconnectAttempt = 0
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

    private fun routingFailure(errorReason: Routing.Error): SendFailure = when (errorReason) {
        Routing.Error.NO_ROUTE,
        Routing.Error.GOT_NAK,
        -> SendFailure.NoRoute

        Routing.Error.MAX_RETRANSMIT -> SendFailure.MaxRetransmit

        Routing.Error.TIMEOUT -> SendFailure.Timeout

        Routing.Error.DUTY_CYCLE_LIMIT -> SendFailure.DutyCycleLimit

        Routing.Error.RATE_LIMIT_EXCEEDED -> SendFailure.RateLimited

        else -> SendFailure.Other(errorReason)
    }

    private fun handlePacedSend(send: EngineMessage.PacedSend) {
        val failure = when {
            handshakeStage != HandshakeStage.Ready || send.generation != receiveGeneration -> SendFailure.Disconnected
            pendingSends.containsKey(send.id) || dispatcher.contains(send.id.raw) -> SendFailure.IdCollision
            else -> null
        }
        if (failure != null) {
            rejectPaced(send, failure)
            return
        }

        pendingSends[send.id] = send.state
        pacedSends[send.id] = send
        pacedQueue.addLast(send)
        send.admission.complete(true)
        pumpPacedQueue()
    }

    private fun rejectPaced(send: EngineMessage.PacedSend, failure: SendFailure) {
        pacedReservations.decrementAndGet()
        completeNotDispatched(send, failure)
    }

    private fun completeNotDispatched(send: EngineMessage.PacedSend, failure: SendFailure) {
        send.state.value = SendState.Failed(failure)
        send.admission.complete(false)
        send.dispatch.complete(false)
        send.receipt.complete(QueueReceipt.NotDispatched)
    }

    private fun pumpPacedQueue() {
        if (queueWrite != null || handshakeStage != HandshakeStage.Ready) return
        while (pacedQueue.isNotEmpty()) {
            val send = pacedQueue.removeFirst()
            val packet = retargetRenumbered(preparePacketForSend(send.packet, send.id.raw))
            if (!trySendToRadio(ToRadio.Builder().also { it.packet = packet }.build(), pacedSend = send)) {
                finishPaced(send, SendState.Failed(SendFailure.Disconnected))
                send.dispatch.complete(false)
                send.receipt.complete(QueueReceipt.NotDispatched)
                continue
            }

            rememberAdminRequest(packet)
            send.state.value = SendState.Sent
            queueWrite = send
            send.dispatch.complete(true)
            return
        }
    }

    private fun handlePacedWriteCompleted(send: EngineMessage.PacedSend) {
        if (pacedSends[send.id] !== send) return
        if (queueWrite === send) {
            queueWriteTimeout = engineScope?.launch {
                delay(PACED_QUEUE_TIMEOUT)
                inbox.trySend(EngineMessage.PacedQueueTimeout(send))
            }
        }
        if (send.expectsRouting()) startSendAckTimeout(send.id, send.state)
    }

    private fun EngineMessage.PacedSend.expectsRouting(): Boolean =
        packet.want_ack || packet.decoded?.want_response == true

    private fun handlePacedQueueTimeout(send: EngineMessage.PacedSend) {
        if (queueWrite !== send) return
        releaseQueueWrite(send)
        if (staleNumberWrite === send) {
            staleNumberWrite = null
            finishPaced(send, SendState.Failed(SendFailure.QueueRejected(PKI_SEND_FAIL_PUBLIC_KEY)))
            send.receipt.complete(QueueReceipt.Rejected)
            pumpPacedQueue()
            return
        }
        if (!send.expectsRouting()) finishPaced(send, SendState.Failed(SendFailure.Timeout))
        send.receipt.complete(QueueReceipt.Unconfirmed)
        pumpPacedQueue()
    }

    private fun handlePacedQueueStatus(send: EngineMessage.PacedSend, result: Int) {
        // Router::send refuses a destination it holds no key for and NAKs from the device's current
        // number (abortSendAndNak). For our own number that means it moved: resend to the new number,
        // or hold the queue until that NAK names it.
        if (result == PKI_SEND_FAIL_PUBLIC_KEY && send.packet.to == myNodeNum) {
            val moved = renumberedTo
            if (moved != null) resendToRenumbered(send, moved) else staleNumberWrite = send
            return
        }
        if (result == 0 && send.expectsRouting() && isFirstRegionSet(send.packet)) {
            if (renumberedTo != null) {
                finishPaced(send, SendState.Acked)
                send.receipt.complete(QueueReceipt.Accepted)
                releaseQueueWrite(send)
                pumpPacedQueue()
                return
            }
            regionWrite = send
            silentRenumberProbe?.cancel()
            silentRenumberProbe = engineScope?.launch {
                delay(SILENT_RENUMBER_MS)
                inbox.trySend(EngineMessage.SilentRenumberProbe(send))
            }
        }
        val receipt = when (result) {
            0 -> QueueReceipt.Accepted
            ERRNO_SHOULD_RELEASE -> QueueReceipt.LocalDelivery
            else -> QueueReceipt.Rejected
        }
        val terminal = when (receipt) {
            QueueReceipt.Accepted -> SendState.Acked.takeUnless { send.expectsRouting() }
            QueueReceipt.LocalDelivery -> SendState.Acked
            QueueReceipt.Rejected -> SendState.Failed(SendFailure.QueueRejected(result))
            else -> null
        }
        if (terminal != null) finishPaced(send, terminal)
        send.receipt.complete(receipt)
        releaseQueueWrite(send)
        pumpPacedQueue()
    }

    private fun handlePacedRouting(send: EngineMessage.PacedSend, packet: MeshPacket, routing: Routing) {
        if (routing.route_request != null) return
        val error = routing.error_reason
        // The device addresses that NAK to itself, under the number it now holds.
        if (error == Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY && send.packet.to == myNodeNum && packet.from != 0 &&
            packet.from != myNodeNum && packet.to == packet.from
        ) {
            resendToRenumbered(send, packet.from)
            return
        }
        val success = error == Routing.Error.NONE || routing.route_reply != null
        val destination = send.packet.to
        val broadcast = destination == BROADCAST_ADDR
        val isLocal = packet.from == myNodeNum || packet.from == 0
        // A positive unicast response must come from the destination. Errors may also originate
        // in the local radio, for example when it exhausts its retransmissions.
        val renumberAck = success && destination == myNodeNum && !isLocal && packet.from == renumberedTo
        if (success && !broadcast && packet.from != destination && !(destination == myNodeNum && isLocal) &&
            !renumberAck
        ) {
            if (destination == myNodeNum && !isLocal) {
                heldRenumberAck = send
                heldRenumberAckFrom = packet.from
            }
            return
        }
        if (!success && (error == null || (!isLocal && packet.from != destination))) return

        val terminal = when {
            !success -> SendState.Failed(routingFailure(checkNotNull(error)))
            broadcast -> SendState.Delivered
            else -> SendState.Acked
        }
        finishPaced(send, terminal)
        send.receipt.complete(if (success) QueueReceipt.Accepted else QueueReceipt.Unconfirmed)
        releaseQueueWrite(send)
        pumpPacedQueue()
    }

    private fun resendToRenumbered(send: EngineMessage.PacedSend, newNum: Int) {
        if (staleNumberWrite === send) staleNumberWrite = null
        releaseQueueWrite(send)
        pacedSends.remove(send.id)
        pendingSends.remove(send.id)
        ackTimeoutJobs.remove(send.id)?.cancel()
        // PhoneAPI::wasSeenRecently drops a phone packet whose id it has seen, so the resend takes a new one.
        val resend = send.copy(id = nextMessageId())
        pendingSends[resend.id] = resend.state
        pacedSends[resend.id] = resend
        pacedQueue.addFirst(resend)
        beginRenumber(newNum)
        pumpPacedQueue()
    }

    // AdminModule::handleSetConfig mints the PKI key, and with it the node number, on a region set from UNSET.
    private fun isFirstRegionSet(packet: MeshPacket): Boolean {
        val decoded = packet.decoded ?: return false
        if (packet.to != myNodeNum || decoded.portnum != PortNum.ADMIN_APP) return false
        val region = runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull()?.set_config?.lora?.region
        val current = meshState.configBundle?.configs?.firstNotNullOfOrNull { it.lora }?.region
        return region != null && region != UNSET_REGION && current == UNSET_REGION
    }

    // The device runs a local write before it reports QueueStatus 0 (Router::sendLocal), and acks it unless
    // the write moved its number (ReliableRouter::sniffReceived checks isToUs). Without an ack or my_info the
    // write applied and the number moved silently.
    private fun handleSilentRenumberProbe(send: EngineMessage.PacedSend) {
        if (regionWrite === send) regionWrite = null
        if (pacedSends[send.id] !== send || send.state.value != SendState.Sent || renumberedTo != null) return
        if (handshakeStage != HandshakeStage.Ready) return
        logger.info(TAG) { "First region set settled without an ack or my_info; restarting the handshake" }
        finishPaced(send, SendState.Acked)
        restartSessionForRenumber()
    }

    private fun releaseQueueWrite(send: EngineMessage.PacedSend) {
        if (queueWrite !== send) return
        queueWrite = null
        queueWriteTimeout?.cancel()
        queueWriteTimeout = null
    }

    private fun finishPaced(send: EngineMessage.PacedSend, state: SendState) {
        if (heldRenumberAck === send) heldRenumberAck = null
        pacedSends.remove(send.id)
        pacedReservations.decrementAndGet()
        pendingSends.remove(send.id)
        ackTimeoutJobs.remove(send.id)?.cancel()
        maybeRestartForRenumber()
        send.state.value = state
    }

    private fun clearPacedSends() {
        queueWriteTimeout?.cancel()
        queueWriteTimeout = null
        queueWrite = null
        pacedQueue.clear()
        pacedSends.values.forEach {
            if (it.state.value == SendState.Queued) {
                rejectPaced(it, SendFailure.Disconnected)
            } else {
                pacedReservations.decrementAndGet()
                it.state.value = SendState.Failed(SendFailure.Disconnected)
                it.receipt.complete(QueueReceipt.Unconfirmed)
            }
            pendingSends.remove(it.id)
        }
        pacedSends.clear()
    }

    private fun preparePacketForSend(packet: MeshPacket, wireId: Int): MeshPacket = prepareOutboundAdminPacket(
        packet.newBuilder().also {
            it.from = if (packet.from == 0 && myNodeNum != 0) myNodeNum else packet.from
            it.id = wireId
        }.build(),
    )

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
        // Guard caller-supplied wire-id collisions at the REAL key. handleSend's guard runs
        // on the engine-allocated MessageId (always unique); without this check a second
        // send carrying the same caller-supplied packet.id would silently overwrite the
        // first handle's pendingSends entry, stranding it un-completable forever (even
        // cleanup() couldn't reach it).
        val wireKey = MessageId(wireId)
        val prior = pendingSends[wireKey]
        if (prior != null && prior !== msg.stateFlow) {
            val state = prior.value
            val terminal = state is SendState.Failed || state == SendState.Acked || state == SendState.Delivered
            if (!terminal) {
                logger.warn(TAG) { "Send id=$wireId collides with in-flight wire id; rejecting" }
                msg.stateFlow.value = SendState.Failed(SendFailure.IdCollision)
                pendingSends.remove(msg.id)
                return
            }
        }
        // Remote ADMIN_APP packets pick up the target's session passkey + PKC routing here
        // (no-op for everything else).
        val packet = preparePacketForSend(msg.packet, wireId)
        rememberAdminRequest(packet)
        // Re-key so both QueueStatus and Routing ACK can look up via MessageId(wireId).
        pendingSends.remove(msg.id)
        pendingSends[wireKey] = msg.stateFlow

        trySendToRadio(ToRadio.Builder().also { wb -> wb.packet = packet }.build())
        // Transition Queued → Sent; the device will confirm with QueueStatus.
        msg.stateFlow.value = SendState.Sent
        logger.debug(TAG) { "Send dispatched id=${msg.id}" }

        val wantsResponse = packet.decoded?.want_response == true

        // Fire-and-forget sends (want_ack=false, no want_response), broadcast or unicast: auto-resolve
        // to Acked immediately. No firmware ACK will arrive and no timeout is armed, so without this the
        // MessageHandle.await() would suspend forever.
        if (!packet.want_ack && !wantsResponse) {
            msg.stateFlow.value = SendState.Acked
            pendingSends.remove(MessageId(wireId))
            logger.debug(TAG) { "Fire-and-forget send auto-acked id=${msg.id}" }
            return
        }

        // Arm an ACK timeout for any send expecting firmware feedback:
        //  • unicast want_ack — waits for recipient's Routing ACK
        //  • broadcast want_ack — waits for firmware's implicit ACK (relay overheard)
        //  • want_response requests (e.g. AdminMessage.get_owner_request)
        if (packet.want_ack || wantsResponse) startSendAckTimeout(wireKey, msg.stateFlow)
    }

    private fun startSendAckTimeout(id: MessageId, state: MutableStateFlow<SendState>) {
        val scope = engineScope ?: return
        ackTimeoutJobs.remove(id)?.cancel()
        ackTimeoutJobs[id] = scope.launch {
            delay(sendTimeout)
            inbox.trySend(EngineMessage.SendAckTimeout(id, state))
        }
    }

    private fun handleCancelHandle(msg: EngineMessage.CancelHandle) {
        val stateFlow = pendingSends[msg.id] ?: return
        if (msg.expected != null && msg.expected !== stateFlow) return
        if (stateFlow.value != SendState.Queued) return
        val paced = pacedSends[msg.id]
        if (paced != null) {
            pacedQueue.remove(paced)
            finishPaced(paced, SendState.Failed(SendFailure.Cancelled))
            paced.dispatch.complete(false)
            paced.receipt.complete(QueueReceipt.NotDispatched)
        } else {
            stateFlow.value = SendState.Failed(SendFailure.Cancelled)
            pendingSends.remove(msg.id)
            ackTimeoutJobs.remove(msg.id)?.cancel()
        }
    }

    private fun handleHeartbeatTick() {
        publishAdminSessions()
        if (!keepsLinkAlive()) return
        if (!sendsPeriodicHeartbeats()) return
        val nonce = sendKeepaliveHeartbeat()
        logger.verbose(TAG) { "Heartbeat sent nonce=$nonce" }
        // piggy-back heartbeat persistence on the 30 s heartbeat tick so we write at most
        // a bounded number of rows per session tick, even on busy meshes.
        flushDirtyHeartbeats()
    }

    private fun sendsPeriodicHeartbeats(): Boolean = bleHeartbeatEnabled || !identity.raw.startsWith("ble:")

    private fun sendKeepaliveHeartbeat(): Int {
        val nonce = heartbeatNonce
        heartbeatNonce = if (nonce == Int.MAX_VALUE) FIRST_HEARTBEAT_NONCE else nonce + 1
        val heartbeat = Heartbeat.Builder().also { hb -> hb.nonce = nonce }.build()
        sendToRadio(ToRadio.Builder().also { wb -> wb.heartbeat = heartbeat }.build())
        return nonce
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

    private fun handleHandshakeTimeout(msg: EngineMessage.HandshakeTimeout) {
        if (handshakeStage == HandshakeStage.Ready) return // already succeeded
        // Stale timer from a stage we've already advanced past — ignore.
        if (msg.attempt != handshakeAttempt || handshakeStage != msg.stage) return
        logger.error(TAG) { "Handshake timeout at stage ${msg.stage.toDisplayName()}" }

        // The thrown HandshakeTimeout carries the stage, but only callers awaiting connect() see it;
        // this event surfaces the same signal on the events flow for passive observers.
        emitEvent(
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
        if (recoverSession(cause)) return
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
     * [disconnecting] and on Ready or AwaitingLockdown, so ticks arriving mid-teardown or during a
     * handshake stage, which has its own timers, can't double-fire. Silence proves
     * nothing until the firmware has answered a heartbeat, or reported a lockdown status, and the
     * engine keeps sending them: firmware before 2.7.4 never replies, so a quiet mesh leaves a
     * healthy link silent.
     */
    private fun handleLivenessTick() {
        if (disconnecting) return
        if (!keepsLinkAlive()) return
        if (!sendsPeriodicHeartbeats()) return
        // A lockdown_status (firmware 2.7.24 and later) proves firmware that answers heartbeats (2.7.4 and later).
        if (!heartbeatAnswered && handshakeStage != HandshakeStage.AwaitingLockdown) return
        livenessBudgetMs -= LIVENESS_CHECK_INTERVAL_MS
        // Fires on the first tick after more than LIVENESS_TIMEOUT_MS of silence, whatever the tick phase.
        if (livenessBudgetMs >= 0) return
        logger.warn(TAG) { "Liveness timeout — no FromRadio for ${LIVENESS_TIMEOUT_MS}ms" }
        val cause = MeshtasticException.Transport("liveness timeout", cause = null)
        val sleep = judgeSleepOnDrop(cause, recoverable = true)
        if (autoReconnectConfig.enabled) {
            val tagged = MeshtasticException.tag(cause, transportIdentity = identity, operation = "engine.liveness")
            emitEvent(MeshEvent.TransportError(tagged))
            // A sleeping device's silence is its sleep, not a stall to count against maxHandshakeRecoveries.
            if (sleep != null) scheduleReconnect(tagged) else recoverSession(tagged)
            return
        }
        // A connect() still waiting on a lockdown unlock fails with the same cause.
        pendingConnectDeferred?.let { deferred ->
            pendingConnectDeferred = null
            failPreSendQueueWith(SendFailure.HandshakeFailed)
            deferred.completeExceptionally(cause)
        }
        // handleDisconnect emits the TransportError event for us and cancels the supervisor.
        handleDisconnect(EngineMessage.Disconnect(cause))
        sleep?.let(::settleTerminalSleep)
    }

    private fun failHandshakeWithProtocol(reason: String) {
        logger.error(TAG) { reason }
        val cause = MeshtasticException.Protocol(reason)
        // scheduleReconnect resets the stage to Idle, so a settle replay stops here too.
        if (recoverSession(cause)) return
        // Terminal before the cancel lands, so a settle replay stops instead of carrying the handshake on.
        handshakeStage = HandshakeStage.Idle
        failPreSendQueueWith(SendFailure.HandshakeFailed)
        val deferred = pendingConnectDeferred
        pendingConnectDeferred = null
        deferred?.completeExceptionally(cause)
        supervisorJobRef.value?.cancel()
    }

    /**
     * Current node map for seeding per-subscription [NodeChange.Snapshot]s.
     *
     * Called from subscriber coroutines (off-actor). Safe without posting to the inbox:
     * [meshState] is a `var` holding an immutable snapshot object, so this is a benign
     * stale-by-at-most-one-message read — references are word-atomic and the deltas that
     * follow on the subscription re-apply idempotently on top of whatever map was read.
     */
    internal fun nodeMapSnapshot(): Map<NodeId, NodeInfo> = meshState.nodes

    internal fun sendToRadio(msg: ToRadio) {
        trySendToRadio(msg)
    }

    internal fun trySendToRadio(msg: ToRadio): Boolean = trySendToRadio(msg, link = null)

    private fun trySendToRadio(
        msg: ToRadio,
        link: LockdownLink? = null,
        pacedSend: EngineMessage.PacedSend? = null,
    ): Boolean = try {
        val encoded = WireCodec.encodeToRadio(msg)
        val queued =
            outbound.trySend(OutboundFrame(Frame(encoded.toByteString()), link, pacedSend, receiveGeneration)).isSuccess
        // A lockdown command carries the radio's credential, so it stays off the tap.
        if (queued && link == null) msg.packet?.let { outboundPackets.tryEmit(it) }
        queued
    } catch (e: Exception) {
        logger.warn(TAG, e) { "Failed to encode outbound ToRadio: ${e.message}" }
        false
    }

    /**
     * Fire-and-forget admin send. Posts to the actor inbox rather than building the packet
     * inline: [prepareOutboundAdminPacket] reads (and prunes) the actor-owned
     * [sessionPasskeys] map, so it must never run on the caller's coroutine (ADR-002).
     */
    internal fun sendAdmin(adminMsg: AdminMessage, wantResponse: Boolean = false, to: Int = myNodeNum) {
        inbox.trySend(EngineMessage.AdminFireAndForget(adminMsg, wantResponse, to))
    }

    fun enqueueLockdown(auth: LockdownAuth, target: Int?): AdminResult<Unit> {
        val link = lockdownLink.value
        if (target != null && target != link?.nodeNum) return AdminResult.Unauthorized
        if (link == null) return AdminResult.NodeUnreachable
        return if (inbox.trySend(EngineMessage.Lockdown(auth, link.generation, link.nodeNum)).isSuccess) {
            AdminResult.Success(Unit)
        } else {
            AdminResult.NodeUnreachable
        }
    }

    private fun handleLockdownCommand(msg: EngineMessage.Lockdown) {
        val link = lockdownLink.value ?: return
        if (msg.generation != link.generation || msg.nodeNum != link.nodeNum) return
        val local = link.nodeNum
        // Firmware consumes this command inline only when it targets the attached radio.
        val admin = AdminMessage.Builder().also { it.lockdown_auth = msg.auth }.build()
        val packet = MeshPacket.Builder().also {
            it.to = local
            it.from = local
            it.id = nextMessageId().raw
            it.decoded = Data.Builder().also { data ->
                data.portnum = PortNum.ADMIN_APP
                data.payload = AdminMessage.ADAPTER.encode(admin).toByteString()
            }.build()
        }.build()
        trySendToRadio(ToRadio.Builder().also { it.packet = packet }.build(), link)
    }

    private suspend fun writeLockdownFrame(frame: Frame, link: LockdownLink, target: RadioTransport) {
        // Serialize credential writes with physical reconnection, so a queued command
        // cannot reach a radio that has replaced its original link.
        lockdownWriteMutex.withLock {
            if (lockdownLink.value != link) return
            logProtocolTx(frame)
            val sent = withTimeoutOrNull(sendTimeout) { target.send(frame) }
            checkNotNull(sent) { "Lockdown write timed out" }
        }
    }

    // my_info is the first Stage 1 frame. Firmware flushes its queued packets once Stage 1 ends and
    // re-dates them when the clock turns valid (MeshService::reconcilePendingRxTimes), so the
    // clock has to be set here. myNodeNum is still 0; firmware fills `from` and the packet id.
    private fun sendHandshakeTimeSync(nodeNum: Int) {
        val admin = AdminMessage.Builder().also { wb -> wb.set_time_only = clock.now().epochSeconds.toInt() }.build()
        val packet = MeshPacket.Builder().also { wb ->
            wb.to = nodeNum
            wb.decoded = Data.Builder().also { d ->
                d.portnum = PortNum.ADMIN_APP
                d.payload = AdminMessage.ADAPTER.encode(admin).toByteString()
            }.build()
        }.build()
        sendToRadio(ToRadio.Builder().also { wb -> wb.packet = packet }.build())
    }

    private fun sendAdminPacket(
        adminMsg: AdminMessage,
        wantResponse: Boolean = false,
        to: Int = myNodeNum,
        id: Int = 0,
    ) {
        if (myNodeNum == 0) return
        val payload = AdminMessage.ADAPTER.encode(adminMsg).toByteString()
        val packet = MeshPacket.Builder().also { wb ->
            wb.to = to
            wb.from = myNodeNum
            wb.id = id
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.ADMIN_APP
                wb.payload = payload
                wb.want_response = wantResponse
            }.build()
        }.build()
        // Remote targets pick up the session passkey + PKC routing in the shared choke point.
        sendToRadio(
            ToRadio.Builder().also { wb ->
                wb.packet = prepareOutboundAdminPacket(packet)
            }.build(),
        )
    }

    // ---- C-shared-flow emit helpers ----
    // `packets` and `nodes` are BufferOverflow.SUSPEND flows, so `tryEmit` returns false when a
    // subscriber leaves the buffer full. The engine never suspends on its own output: the
    // rejected item is dropped and reported as `MeshEvent.PacketsDropped`.

    // Every MeshEvent goes through here so the session stream sees it in order with its packets.
    private fun emitEvent(event: MeshEvent) {
        events.tryEmit(event)
        val sessionVisible = event !is MeshEvent.LockdownStatusChanged &&
            event !is MeshEvent.QueueStatusChanged &&
            event !is MeshEvent.PacketsDropped
        when {
            !sessionVisible -> Unit

            // The session stream starts at Ready, so a handshake event follows the snapshot with the held packets.
            sessionReceive != null && handshakeStage != HandshakeStage.Ready && handshakeStage != HandshakeStage.Idle ->
                holdUntilReady(HeldItem.Event(event))

            else -> sessionReceive?.event(event)
        }
    }

    // Every post-Ready config bundle change goes through here.
    private fun commitConfigBundle(bundle: ConfigBundle) {
        configBundleState.value = bundle
        meshState = meshState.withConfig(bundle)
        sessionReceive?.configChanged(bundle)
    }

    private fun emitPacketOrLog(packet: MeshPacket) {
        if (!packets.tryEmit(packet)) {
            logger.warn(TAG) { "packets flow rejected emit (id=${packet.id}); reporting drop" }
            emitEvent(MeshEvent.PacketsDropped(DroppedFlow.Packets, count = 1))
        }
    }

    private fun emitNodeChangeOrLog(change: NodeChange) {
        if (change !is NodeChange.Snapshot) sessionReceive?.nodeChanged(change)
        if (!nodes.tryEmit(change)) {
            logger.warn(TAG) { "nodes flow rejected emit (${change::class.simpleName}); reporting drop" }
            emitEvent(MeshEvent.PacketsDropped(DroppedFlow.Nodes, count = 1))
        }
    }

    /**
     * Applies acknowledged local [writes] on the actor, in order, and returns once the session state
     * shows them. Nothing applies unless [generation] is still the current connection's.
     */
    suspend fun applyLocalWrites(writes: List<LocalWrite>, generation: Long) {
        if (writes.isEmpty()) return
        val done = CompletableDeferred<Unit>()
        if (inbox.trySend(EngineMessage.ApplyLocalWrites(writes, generation, done)).isSuccess) done.await()
    }

    // Call order, as the device applied them: a later write to the same field wins.
    private fun mergeLocalWrites(writes: List<LocalWrite>) {
        val bundleBefore = configBundleState.value
        for (write in writes) {
            when (write) {
                is LocalWrite.Config -> mergeConfigEdits(listOf(write.config), emptyList())
                is LocalWrite.ModuleConfig -> mergeConfigEdits(emptyList(), listOf(write.config))
                is LocalWrite.Channel -> updateChannelAndPersist(write.channel)
                is LocalWrite.Node -> mergeNodeWrite(write.write)
            }
        }
        val bundle = configBundleState.value
        if (bundle == null || bundle === bundleBefore) return
        configSaves?.trySend(bundle)
    }

    private suspend fun persistConfig(bundle: ConfigBundle) {
        if (storageDegraded) return
        try {
            storage?.saveConfig(bundle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, e) { "saveConfig failed (non-fatal)" }
        }
    }

    // Mirrors AdminModule: removeNodeByNum, the favorite and ignored flags, and handleSetOwner.
    private fun mergeNodeWrite(write: NodeWrite) {
        when (write) {
            is NodeWrite.Remove -> {
                val nodeId = NodeId(write.node)
                if (write.node == myNodeNum || nodeId !in meshState.nodes) return
                meshState = meshState.withNodes(meshState.nodes - nodeId)
                lastHeartbeatAt.remove(nodeId)
                offlineNodes.remove(nodeId)
                emitNodeChangeOrLog(NodeChange.Removed(nodeId))
                val s = storage ?: return
                engineScope?.launch {
                    try {
                        s.removeNode(nodeId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportStorageDegraded("removeNode failed: ${e.message ?: e::class.simpleName}", e)
                    }
                }
            }

            is NodeWrite.Flags -> {
                // set_ignored_node creates an unknown node and erases its position and telemetry.
                val nodeId = NodeId(write.node)
                if (write.ignored == true && nodeId !in meshState.nodes && write.node != myNodeNum) {
                    val created = NodeInfo.Builder().also { wb ->
                        wb.num = write.node
                        wb.is_ignored = true
                    }.build()
                    meshState = meshState.withNodes(meshState.nodes + (nodeId to created))
                    emitNodeChangeOrLog(NodeChange.Added(created))
                    return
                }
                replaceNode(write.node) { wb ->
                    write.favorite?.let { wb.is_favorite = it }
                    write.ignored?.let { wb.is_ignored = it }
                    if (write.ignored == true) {
                        wb.position = null
                        wb.device_metrics = null
                    }
                }
            }

            is NodeWrite.ToggleMuted -> replaceNode(write.node) { wb -> wb.is_muted = !wb.is_muted }

            // set_fixed_position stores the position on our node and sets fixed_position;
            // remove_fixed_position only clears the flag.
            is NodeWrite.FixedPosition -> {
                write.position?.let { position -> replaceNode(myNodeNum) { wb -> wb.position = position } }
                val bundle = configBundleState.value ?: return
                val current =
                    bundle.configs.firstNotNullOfOrNull { it.position }
                        ?: org.meshtastic.proto.Config.PositionConfig.Builder().build()
                val fixed = write.position != null
                if (current.fixed_position == fixed) return
                val positionConfig = org.meshtastic.proto.Config.Builder().also { wb ->
                    wb.position = current.newBuilder().also { it.fixed_position = fixed }.build()
                }.build()
                commitConfigBundle(bundle.copy(configs = mergeConfigs(bundle.configs, listOf(positionConfig))))
            }

            is NodeWrite.Owner -> replaceNode(myNodeNum) { wb ->
                val o = write.user
                val user = wb.user?.newBuilder() ?: org.meshtastic.proto.User.Builder()
                if (o.long_name.isNotEmpty()) user.long_name = o.long_name
                if (o.short_name.isNotEmpty()) user.short_name = o.short_name
                user.is_licensed = o.is_licensed
                o.is_unmessagable?.let { user.is_unmessagable = it }
                wb.user = user.build()
            }
        }
    }

    private fun replaceNode(nodeNum: Int, edit: (NodeInfo.Builder) -> Unit) {
        val nodeId = NodeId(nodeNum)
        val existing = meshState.nodes[nodeId] ?: return
        val updated = existing.newBuilder().also(edit).build()
        val changed = diffNodeFields(existing, updated)
        if (changed.isEmpty()) return
        meshState = meshState.withNodes(meshState.nodes + (nodeId to updated))
        if (nodeNum == myNodeNum) ownNode.value = updated
        emitNodeChangeOrLog(NodeChange.Updated(updated, changed))
    }

    // Actor only. The storage flush is best effort: the next handshake re-reads channels anyway.
    private fun updateChannelAndPersist(channel: org.meshtastic.proto.Channel) {
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
                            org.meshtastic.proto.Channel.Builder().also { wb ->
                                wb.index = list.size
                                wb.role = org.meshtastic.proto.Channel.Role.DISABLED
                            }.build(),
                        )
                    }
                    list.add(channel)
                }
            }
            list.toList()
        }
        val updated = channelsState.value
        updated?.let {
            meshState = meshState.withChannels(it)
            sessionReceive?.channelsChanged(it)
        }
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

    // Actor only. Replaces the written sections in the bundle; mergeLocalWrites persists the result.
    private fun mergeConfigEdits(
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

        commitConfigBundle(current.copy(configs = mergedConfigs, moduleConfigs = mergedModuleConfigs))
    }

    // ---- storage error wrapping ----
    // Storage writes run in async blocks; their failures reach the actor as one StorageDegraded
    // signal per session.

    private fun reportStorageDegraded(reason: String, cause: Throwable?) {
        inbox.trySend(EngineMessage.StorageDegraded(reason, cause))
    }

    private fun markStorageDegraded(reason: String, cause: Throwable?) {
        logger.warn(TAG, cause) { "Storage degraded: $reason" }
        if (storageDegraded) return
        storageDegraded = true
        emitEvent(MeshEvent.StorageDegraded(reason))
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
        val flow = pendingSends[msg.id]?.takeIf { it === msg.state } ?: return
        val paced = pacedSends[msg.id]
        if (paced != null) {
            finishPaced(paced, SendState.Failed(SendFailure.AckTimeout))
            paced.receipt.complete(QueueReceipt.Unconfirmed)
            releaseQueueWrite(paced)
            pumpPacedQueue()
            return
        }
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

    private inline fun logProtocol(message: () -> String) {
        if (protocolLogLevel != LogLevel.NONE && logger !== LogSink.Silent) {
            logger.log(protocolLogLevel, PROTOCOL_TAG, message(), null)
        }
    }

    private fun logProtocolTx(frame: Frame) = logProtocol {
        val bytes = frame.bytes.toByteArray()
        val toRadio = runCatching {
            ToRadio.ADAPTER.decode(bytes.copyOfRange(WireFraming.HEADER_SIZE, bytes.size))
        }.getOrNull()
        "tx " + (toRadio?.let(payloadRedactor::describe) ?: "undecodable frame bytes=${bytes.size}")
    }

    private companion object {
        private const val TAG = "MeshEngine"
        private const val REASON_IDENTITY_MISMATCH = "identity_mismatch"
        private const val REASON_NODE_RENUMBERED = "node_renumbered"
        private const val ADMIN_REPLY_CAP = 128
        private const val REQUEST_GENERATION_CAP = 64
        private const val MAX_PACED_SENDS = 128
        private val PACED_QUEUE_TIMEOUT = 5.seconds
        private const val PROTOCOL_TAG = "Protocol"

        // Handshake nonces (protocol.md §6).
        const val NONCE_STAGE1 = 69420
        const val NONCE_STAGE2 = 69421
        const val FIRST_HEARTBEAT_NONCE = 2

        // Timeouts (ms).
        const val STAGE1_TIMEOUT_MS = 20_000L

        // Overflow restarts in a row when maxHandshakeRecoveries is null: Meshtastic-Android's MAX_RECOVERY_RESTARTS.
        const val DEFAULT_OVERFLOW_RESTARTS = 3

        // Android stops re-arming a stage guard after 10 x its budget (STAGE_GUARD_TOTAL_CAP_FACTOR).
        const val STAGE1_HARD_CAP_MS = STAGE1_TIMEOUT_MS * 10

        // A boot-time `rebooted` ends Stage 1 unless the dump starts this soon after it.
        const val STAGE1_REBOOT_GRACE_MS = 2_000L

        // Past this a Ready session warns that it has no local passkey; it never fails the session.
        const val SEED_TIMEOUT_MS = 10_000L
        const val INTER_STAGE_SETTLE_MS = 100L
        const val HEARTBEAT_INTERVAL_MS = 30_000L

        // liveness watchdog. Android's `SharedRadioInterfaceService` uses
        // `LIVENESS_TIMEOUT_MILLIS = HEARTBEAT_INTERVAL_MS * 2` — if no decoded `FromRadio`
        // envelope has arrived for 60 s we treat the link as silently dead. The 5 s check interval
        // bounds the detection to between 60 and 65 s of silence.
        // firmware now exposes `getConfig_max_wait()` so this could be made
        // configurable (e.g. via `RadioClient.Builder.livenessTimeout`). Keeping it hardcoded
        // for now matches Android reference behaviour; revisit when the public Builder gains
        // a knob.
        const val LIVENESS_TIMEOUT_MS = HEARTBEAT_INTERVAL_MS * 2
        const val LIVENESS_CHECK_INTERVAL_MS = 5_000L

        // Polite-goodbye timeout: how long to wait for the final
        // `ToRadio { disconnect = true }` write to flush before we close the transport
        // anyway. Short by design — teardown latency must stay bounded.
        const val GOODBYE_TIMEOUT_MS = 500L

        // Stage 2 fails when a whole tick passes with no frame. The hard cap only ends a node that
        // keeps streaming without ever completing: Android's stage guard stops re-arming after
        // 10 x its 60 s Stage 2 budget (MeshConnectionManagerImpl STAGE_GUARD_TOTAL_CAP_FACTOR).
        const val STAGE2_PROGRESS_TICK_MS = 30_000L
        const val STAGE2_HARD_CAP_MS = 600_000L

        // An in-session nodes-only refresh (session receive) is bounded separately.
        const val NODE_DB_REFRESH_TIMEOUT_MS = 60_000L

        // records a slow `deviceLogs` collector can fall behind by before the oldest are dropped.
        const val DEVICE_LOG_BUFFER = 64

        // (node, offered key) pairs remembered so a repeated mismatch is reported once.
        const val MAX_REPORTED_KEY_MISMATCHES = 256

        // Frames buffered during the settle windows. Firmware flushes its whole to-phone queue once
        // Stage 1 completes: meshtasticd holds 100 by default (PortduinoGlue.h maxtophone), radios 8 to 32.
        const val SETTLE_BUFFER_CAP = 128

        // Mesh packets held until Ready, flushed in one pass; the packets flow buffers as many (128).
        const val HANDSHAKE_PACKET_BUFFER_CAP = 128

        // How long one transport write may take before the link counts as failed. Separate from
        // sendTimeout, which bounds the wait for a routing ACK.
        val TRANSPORT_WRITE_TIMEOUT = 30.seconds

        // How long the paced queue stays empty after a renumber before the handshake restarts.
        const val RENUMBER_SETTLE_MS = 1_000L

        // How long a first region set may go without an ack or my_info before the engine assumes a renumber.
        const val SILENT_RENUMBER_MS = 5_000L
        val UNSET_REGION = org.meshtastic.proto.Config.LoRaConfig.RegionCode.UNSET

        // Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY, which Router::send also returns as a QueueStatus result.
        val PKI_SEND_FAIL_PUBLIC_KEY: Int = Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY.value

        // name of the legacy secondary channel used for remote admin when PKC keys are
        // unavailable. Matched case-insensitively against ChannelSettings.name.
        const val ADMIN_CHANNEL_NAME = "admin"

        // broadcast destination is the unsigned 0xFFFFFFFF address (Int.toLong → -1
        // after wire round-trip). Used so we don't arm an ACK timer for broadcasts.
        const val BROADCAST_ADDR: Int = -1

        // QueueStatus.res namespace boundaries (firmware MeshTypes.h). Values below
        // ERRNO_BASE are Routing.Error codes; values at/above it are ERRNO_* enqueue
        // results. ERRNO_SHOULD_RELEASE explicitly means "no error".
        const val ERRNO_BASE: Int = 32
        const val ERRNO_SHOULD_RELEASE: Int = 35

        // AdminModule.cpp: checkPassKey accepts a key for 300 s from generation; setPassKey
        // generates a new one on the first response after the key is 150 s old. A key seen in a
        // response is thus expected to work for 150 s and never valid after 300 s.
        val SESSION_PASSKEY_TTL: Duration = 300.seconds
        val SESSION_PASSKEY_ACTIVE_FOR: Duration = 150.seconds

        // minimum interval between consecutive encrypted-packet skip warnings.
        const val ENCRYPTED_SKIP_WARNING_INTERVAL_MS: Long = 60_000L

        // bounded recent-history window for inbound packet deduplication.
        const val INBOUND_PACKET_DEDUP_CAP: Int = 256
    }

    private data class InboundPacketKey(val from: Int, val to: Int, val channel: Int, val id: Int)

    /** In-memory per-node session passkey and when it was last observed. */
    private class SessionPasskeyEntry(val bytes: ByteArray, val refreshedAt: Instant, val generation: Int)

    /** A mid-handshake packet with the instant it arrived. */
    // Apps forward SDK logs to crash and analytics backends: name the transport kind, never the MAC or host.
    private fun TransportIdentity.forLog(): String =
        raw.substringBefore(':') + ":#" + (raw.hashCode() and 0xFFFF).toString(16).padStart(4, '0')

    private sealed interface HeldItem {
        class Packet(val packet: MeshPacket, val receivedAt: Instant) : HeldItem

        class Event(val event: MeshEvent) : HeldItem
    }
}
