/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.internal.AdminApiImpl
import org.meshtastic.sdk.internal.MeshEngine
import org.meshtastic.sdk.internal.RoutingApiImpl
import org.meshtastic.sdk.internal.StoreForwardApiImpl
import org.meshtastic.sdk.internal.TelemetryApiImpl
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The main entry point to the Meshtastic SDK.
 *
 * A `RadioClient` manages a single radio device over a chosen transport (BLE, TCP, serial, etc.)
 * and exposes a clean async/reactive API:
 *
 * - **Lifecycle:** [connect] and [disconnect] (suspend functions; may throw).
 * - **State:** [connection], [ownNode], [nodes] (reactive [StateFlow]s and [Flow]s).
 * - **Messaging:** [send] and [sendText] (enqueue immediately; return a [MessageHandle]).
 * - **Observability:** [packets], [events] (reactive flows).
 * - **Operations:** [admin], [telemetry], [routing], [storeForward] sub-APIs.
 *
 * **Drop-in philosophy:** configure once with [Builder], then observe and call:
 *
 * ```kotlin
 * val client = RadioClient.Builder()
 *     .transport(TcpTransport(host = "192.168.1.42", port = 4403))
 *     .storage(MyStorageProvider())
 *     .build()
 *
 * client.connect()                      // suspends until Connected or throws
 * client.sendText("Hello mesh!")        // non-suspending; returns a MessageHandle
 *
 * client.packets.collect { packet ->    // reactive inbound stream
 *     println(packet.decoded?.portnum)
 * }
 *
 * client.disconnect()                   // graceful shutdown
 * ```
 *
 * **Multi-radio:** instantiate one `RadioClient` per physical radio. They share nothing.
 *
 * **No host plumbing:** foreground services, permissions, notifications, and WorkManager are the
 * app's responsibility. The SDK owns only the engine.
 *
 * **Resource management:** implements [AutoCloseable], so `client.use { … }` guarantees cleanup
 * even on exception. Prefer `disconnect()` (suspend) over `close()` (blocking) when inside a
 * coroutine.
 *
 * @since 0.1.0
 */
public class RadioClient internal constructor(
    private val engine: MeshEngine,
    private val clock: kotlin.time.Clock,
    private val rpcTimeout: Duration,
    private val autoSyncTimeOnConnect: Boolean,
    private val parentContext: kotlin.coroutines.CoroutineContext,
) : AutoCloseable {

    // ── Observable state ────────────────────────────────────────────────────

    /**
     * The current connection state.
     *
     * Transitions: [ConnectionState.Disconnected] → [ConnectionState.Connecting] →
     * [ConnectionState.Configuring] → [ConnectionState.Connected]. May cycle through
     * [ConnectionState.Reconnecting] on transport drops.
     */
    public val connection: StateFlow<ConnectionState> = engine.connectionState.asStateFlow()

    /**
     * The local node's [NodeInfo], populated after handshake completes.
     *
     * `null` until [ConnectionState.Connected] is reached; remains non-null while connected.
     */
    public val ownNode: StateFlow<NodeInfo?> = engine.ownNode.asStateFlow()

    /**
     * Most recently committed [ConfigBundle] for the active session.
     *
     * `null` until [ConnectionState.Connected] is reached. Cleared on disconnect / cleanup
     * so a stale bundle from a prior session can never leak into a new one.
     */
    public val configBundle: StateFlow<ConfigBundle?> = engine.configBundleState.asStateFlow()

    /**
     * Channel list for the active session.
     *
     * Seeded during the handshake from the device's channel payload; falls back to the
     * persisted storage snapshot on reconnect if the device does not re-send channels.
     * `null` until [ConnectionState.Connected] is reached.
     *
     * Updated in-memory (and persisted) when [AdminApi.setChannel] succeeds. Call
     * [AdminApi.listChannels] to force a full device re-read (8 RPCs).
     */
    public val channels: StateFlow<List<org.meshtastic.proto.Channel>?> = engine.channelsState.asStateFlow()

    /**
     * Node-change deltas. Late subscribers receive a [NodeChange.Snapshot] immediately
     * (single-replay), then live [NodeChange.Added] / [NodeChange.Updated] /
     * [NodeChange.Removed] / [NodeChange.WentOffline] / [NodeChange.CameOnline] in causal order.
     *
     * **Buffering and backpressure:** the underlying `MutableSharedFlow` uses
     * `extraBufferCapacity = 256` with `BufferOverflow.SUSPEND` (per ADR-005). Slow collectors
     * apply backpressure to the engine actor; deltas are **never silently dropped** on this
     * flow. If the engine inbox itself fills as a result, drops surface as
     * [MeshEvent.PacketsDropped] on [events] — see ADR-005 §"Backpressure policy".
     */
    public val nodes: Flow<NodeChange> = engine.nodes.hide()

    /**
     * Inbound decoded packets.
     *
     * The engine decrypts channel payloads transparently; `MeshPacket.decoded` is always
     * non-null on emission. Filter by `decoded.portnum` to match specific app payloads.
     *
     * **Buffering and backpressure:** the underlying `MutableSharedFlow` uses
     * `extraBufferCapacity = 128` with `BufferOverflow.SUSPEND` (per ADR-005 / SPEC §4.4).
     * Slow collectors apply backpressure to the engine. If the engine inbox itself fills, the
     * engine drops the **oldest queued frame** and emits
     * [MeshEvent.PacketsDropped][org.meshtastic.sdk.MeshEvent.PacketsDropped] with
     * `flow = DroppedFlow.Packets` on [events]. Silent loss is forbidden in the public surface
     * — every drop is observable.
     *
     * Populated by the HandshakeMachine after entering [ConnectionState.Connected].
     */
    public val packets: Flow<MeshPacket> = engine.packets.hide()

    /**
     * Side-channel advisory events: queue status, transport errors, key-verification prompts,
     * drop notifications, and identity-rebind signals
     * ([MeshEvent.IdentityRebound] — emitted before the engine clears storage when the
     * device reports a different NodeNum than the one previously persisted).
     */
    public val events: Flow<MeshEvent> = engine.events.hide()

    // ── On-demand query ─────────────────────────────────────────────────────

    /**
     * Pull a snapshot of all known nodes on demand.
     *
     * Returns a copy of the engine's current node map. Cheaper than folding [nodes] yourself
     * when you only need a one-shot view.
     *
     * **Suspend semantics:** suspends only long enough to round-trip a request through the
     * engine actor (no network or storage I/O). Cooperatively cancellable — if the caller's
     * coroutine is cancelled before the actor responds, [kotlin.coroutines.cancellation.CancellationException]
     * is rethrown and no observable state changes.
     *
     * @return a map of [NodeId] → [NodeInfo]
     * @throws MeshtasticException.NotConnected if not currently connected
     */
    @Throws(MeshtasticException::class, CancellationException::class)
    public suspend fun nodeSnapshot(): Map<NodeId, NodeInfo> {
        if (connection.value !is ConnectionState.Connected) throw MeshtasticException.NotConnected()
        return engine.nodeSnapshot()
    }

    /**
     * Request a remote node to send its [NodeInfo] (user identity + position).
     *
     * Sends an empty `NODEINFO_APP` packet with `want_response = true`. The remote node will
     * reply with its [User] information, which the engine processes and emits via [nodes].
     *
     * @param node the target node to request info from
     * @return a [MessageHandle] tracking delivery state
     * @throws MeshtasticException.NotConnected if not currently connected
     * @since 0.2.0
     */
    @Throws(MeshtasticException::class)
    public fun requestNodeInfo(node: NodeId): MessageHandle {
        val packet = MeshPacket(
            to = node.raw,
            want_ack = true,
            decoded = org.meshtastic.proto.Data(
                portnum = org.meshtastic.proto.PortNum.NODEINFO_APP,
                payload = okio.ByteString.EMPTY,
                want_response = true,
            ),
        )
        return send(packet)
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Initiate connection to the device.
     *
     * Activates storage, starts the engine coroutines, connects the transport, and performs the
     * handshake. [connection] is updated throughout; subscribers can observe
     * [ConnectionState.Configuring] progress.
     *
     * **Suspend semantics:** suspends until [ConnectionState.Connected] is reached (i.e. the
     * Stage 2 `config_complete_id` matches the Stage 2 nonce — see `protocol.md` §6). The
     * function does **not** wait for any subsequent NodeDB churn.
     *
     * **Cancellation:** cooperatively cancellable at every suspension point. If the caller's
     * coroutine is cancelled mid-connect the engine tears down (transport disconnect, storage
     * close, in-flight handles failed with `Disconnected`) before
     * [kotlin.coroutines.cancellation.CancellationException] is rethrown. After cancellation
     * the client is observably [ConnectionState.Disconnected] and may be re-`connect()`-ed.
     *
     * **Idempotency:** throws [MeshtasticException.AlreadyConnected] if already connected.
     *
     * @throws MeshtasticException.AlreadyConnected if already connected
     * @throws MeshtasticException.Transport if the transport cannot establish a connection
     * @throws MeshtasticException.Protocol if the handshake fails or data is malformed
     * @throws MeshtasticException.StorageUnavailable if storage activation fails
     * @throws CancellationException if the caller's coroutine is cancelled
     */
    @Throws(MeshtasticException::class, CancellationException::class)
    public suspend fun connect() {
        engine.connect()
        if (autoSyncTimeOnConnect) {
            // Best-effort and fire-and-forget: a slow / silent device must not delay connect()'s
            // return. Launches under the same parent context as the engine so cancellation /
            // shutdown propagates cleanly.
            kotlinx.coroutines.CoroutineScope(parentContext).launch(
                kotlinx.coroutines.CoroutineName("meshtastic-auto-time-sync"),
            ) {
                try {
                    admin.setTime()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Swallow — clock sync is auxiliary, the session is otherwise live.
                }
            }
        }
    }

    /**
     * Gracefully disconnect from the device.
     *
     * Stops the engine, closes the transport, and releases storage. Never throws; idempotent.
     * In-flight [MessageHandle]s that have not reached terminal state will be failed with
     * [SendFailure.Disconnected].
     *
     * **Suspend semantics:** suspends until the engine has drained pending failures, the
     * transport is fully disconnected, and storage is closed. Returns when [connection]
     * settles on [ConnectionState.Disconnected].
     *
     * **Cancellation:** the disconnect path runs under
     * [kotlinx.coroutines.NonCancellable] internally for resource release — cancelling the
     * caller's coroutine will not leave the transport or storage half-open. Cancellation may
     * still be observed before any teardown work begins.
     */
    public suspend fun disconnect() {
        engine.disconnect()
    }

    /**
     * Blocking close for [AutoCloseable] conformance.
     *
     * Delegates to [disconnect] via `runBlocking`. Prefer the suspending [disconnect] when
     * already inside a coroutine — this overload exists so `RadioClient` works with Kotlin's
     * `use { }` idiom and Java's try-with-resources.
     *
     * **Warning:** Do not call from the main/UI thread — `runBlocking` will block the caller
     * until disconnection completes, which can trigger ANR on Android or deadlock on iOS main.
     * Use [disconnect] directly from a coroutine scope instead.
     */
    override fun close() {
        runBlocking { disconnect() }
    }

    // ── Outbound ────────────────────────────────────────────────────────────

    /**
     * Enqueue an outbound packet for transmission.
     *
     * Returns **immediately** with a [MessageHandle] tracking the packet's lifecycle. The engine
     * assigns `id` and `from` from the local node's info; callers may override these if needed.
     *
     * **Payload size:** enforced client-side against [DATA_PAYLOAD_LEN] (233 bytes).
     * Oversized payloads throw [MeshtasticException.PayloadTooLarge] before enqueue — no handle
     * is returned.
     *
     * @param packet the packet to send
     * @return a handle tracking delivery state
     * @throws MeshtasticException.NotConnected if not currently connected
     * @throws MeshtasticException.PayloadTooLarge if the payload exceeds the device limit
     * @throws MeshtasticException.Protocol if the packet is malformed
     */
    @Throws(MeshtasticException::class)
    public fun send(packet: MeshPacket): MessageHandle {
        val payloadSize = packet.decoded?.payload?.size ?: 0
        if (payloadSize > DATA_PAYLOAD_LEN) {
            throw MeshtasticException.PayloadTooLarge(DATA_PAYLOAD_LEN)
        }
        if (connection.value !is ConnectionState.Connected) {
            throw MeshtasticException.NotConnected()
        }

        val id = engine.nextMessageId()
        val stateFlow = MutableStateFlow<SendState>(SendState.Queued)
        val handle = MessageHandle(
            id = id,
            _state = stateFlow,
            cancelFn = { cancelId -> engine.tryCancelSend(cancelId) },
            packet = packet,
            resendFn = { pkt -> send(pkt) },
        )
        engine.trySend(packet, id, stateFlow)
        return handle
    }

    /**
     * Convenience: send a text message.
     *
     * Wraps the text in a [MeshPacket] with `decoded.portnum = TEXT_MESSAGE_APP` and
     * `decoded.payload = text.encodeToByteArray()`, then calls [send].
     *
     * Sets `want_ack = true` so the firmware provides delivery feedback:
     * - **Unicast**: the recipient sends a Routing ACK back to the sender.
     * - **Broadcast**: the firmware generates an implicit ACK when it overhears a neighbor
     *   relay the packet. This confirms at least one mesh node retransmitted the message.
     *
     * The returned [MessageHandle] will resolve to [SendOutcome.Success] on ACK, or
     * [SendOutcome.Failure] if the firmware exhausts retransmissions without confirmation.
     *
     * @param text the message text (UTF-8 encoded)
     * @param channel the channel index (default: 0)
     * @param to the destination [NodeId] (default: [NodeId.BROADCAST])
     * @return a handle tracking delivery state
     * @throws MeshtasticException.NotConnected if not currently connected
     * @throws MeshtasticException.PayloadTooLarge if the encoded text exceeds the device limit
     */
    @Throws(MeshtasticException::class)
    public fun sendText(
        text: String,
        channel: ChannelIndex = ChannelIndex(0),
        to: NodeId = NodeId.BROADCAST,
    ): MessageHandle {
        val payload = text.encodeToByteArray()
        if (payload.size > DATA_PAYLOAD_LEN) {
            throw MeshtasticException.PayloadTooLarge(DATA_PAYLOAD_LEN)
        }
        val packet = MeshPacket(
            to = to.raw,
            channel = channel.raw,
            want_ack = true,
            decoded = org.meshtastic.proto.Data(
                portnum = org.meshtastic.proto.PortNum.TEXT_MESSAGE_APP,
                payload = payload.toByteString(),
            ),
        )
        return send(packet)
    }

    /**
     * Convenience: send an emoji reaction to an existing message.
     *
     * Wraps the [emoji] in a [MeshPacket] with `decoded.portnum = TEXT_MESSAGE_APP`,
     * `decoded.emoji = 1` (indicating this is a reaction, not a standalone message), and
     * `decoded.reply_id` set to [replyId] (the packet ID of the message being reacted to).
     *
     * @param emoji the emoji string (single emoji character or sequence)
     * @param to the destination [NodeId] (the sender of the original message, or broadcast)
     * @param channel the channel index the reaction should be sent on
     * @param replyId the packet ID of the original message being reacted to
     * @return a handle tracking delivery state
     * @throws MeshtasticException.NotConnected if not currently connected
     * @throws MeshtasticException.PayloadTooLarge if the encoded emoji exceeds the device limit
     * @since 0.2.0
     */
    @Throws(MeshtasticException::class)
    public fun sendReaction(
        emoji: String,
        to: NodeId = NodeId.BROADCAST,
        channel: ChannelIndex = ChannelIndex(0),
        replyId: Int,
    ): MessageHandle {
        val payload = emoji.encodeToByteArray()
        if (payload.size > DATA_PAYLOAD_LEN) {
            throw MeshtasticException.PayloadTooLarge(DATA_PAYLOAD_LEN)
        }
        val packet = MeshPacket(
            to = to.raw,
            channel = channel.raw,
            want_ack = true,
            decoded = org.meshtastic.proto.Data(
                portnum = org.meshtastic.proto.PortNum.TEXT_MESSAGE_APP,
                payload = payload.toByteString(),
                emoji = EMOJI_INDICATOR,
                reply_id = replyId,
            ),
        )
        return send(packet)
    }

    /**
     *
     * Constructs a packet with `decoded = Data(portnum, payload, want_response = false)` and
     * forwards to [send]. Exists so callers do not need to import `org.meshtastic.proto.Data`
     * to send a typed application payload.
     *
     * `hopLimit = null` (the default) leaves the field at the proto-default `0`, which the
     * firmware interprets as "use the device's configured default hop limit". Set it explicitly
     * only when you need to override that default for a single packet.
     *
     * Example:
     * ```kotlin
     * client.send(
     *     portnum = PortNum.TEXT_MESSAGE_APP,
     *     payload = "ack".encodeToByteArray(),
     *     to = NodeId(0xa1b2c3d4.toInt()),
     *     wantAck = true,
     * )
     * ```
     *
     * **Suspend-safe** but does not actually suspend — it delegates to the non-suspending
     * [send] entry point. The `suspend` modifier is reserved so future versions can apply
     * back-pressure on the engine inbox without a source-incompatible signature change.
     *
     * @param portnum the application port number ([PortNum.TEXT_MESSAGE_APP], [PortNum.POSITION_APP], …)
     * @param payload the wire-encoded payload bytes (must be ≤ [DATA_PAYLOAD_LEN])
     * @param to destination [NodeId]; defaults to [NodeId.BROADCAST]
     * @param channel [ChannelIndex] to send on; defaults to channel 0 (primary)
     * @param wantAck request a reliable delivery ACK (firmware-side ACK, not end-to-end)
     * @param hopLimit explicit hop limit override; `null` keeps the device default
     * @return a [MessageHandle] tracking delivery state
     * @throws MeshtasticException.NotConnected if not currently connected
     * @throws MeshtasticException.PayloadTooLarge if [payload] exceeds the device limit
     * @since 0.1.0
     */
    @Throws(MeshtasticException::class, CancellationException::class)
    public suspend fun send(
        portnum: PortNum,
        payload: ByteArray,
        to: NodeId = NodeId.BROADCAST,
        channel: ChannelIndex = ChannelIndex(0),
        wantAck: Boolean = false,
        hopLimit: Int? = null,
    ): MessageHandle {
        if (payload.size > DATA_PAYLOAD_LEN) {
            throw MeshtasticException.PayloadTooLarge(DATA_PAYLOAD_LEN)
        }
        val packet = MeshPacket(
            to = to.raw,
            channel = channel.raw,
            want_ack = wantAck,
            hop_limit = hopLimit ?: 0,
            decoded = org.meshtastic.proto.Data(
                portnum = portnum,
                payload = payload.toByteString(),
                want_response = false,
            ),
        )
        return send(packet)
    }

    /**
     * Convenience: build a [MeshPacket] for the given [portnum] from a [kotlinx.io.Buffer] payload.
     *
     * Identical to the `ByteArray`-accepting [send] overload but reads from a [kotlinx.io.Buffer],
     * allowing callers to compose payloads with the `kotlinx-io` sink/source API instead of raw
     * byte arrays. The buffer is consumed (read fully) on invocation.
     *
     * @param portnum the application port number
     * @param payload a [kotlinx.io.Buffer] containing the wire-encoded payload (≤ [DATA_PAYLOAD_LEN])
     * @param to destination [NodeId]; defaults to [NodeId.BROADCAST]
     * @param channel [ChannelIndex] to send on; defaults to channel 0 (primary)
     * @param wantAck request a reliable delivery ACK
     * @param hopLimit explicit hop limit override; `null` keeps the device default
     * @return a [MessageHandle] tracking delivery state
     * @throws MeshtasticException.NotConnected if not currently connected
     * @throws MeshtasticException.PayloadTooLarge if the buffer content exceeds the device limit
     * @since 0.1.0
     */
    @Throws(MeshtasticException::class, CancellationException::class)
    public suspend fun send(
        portnum: PortNum,
        payload: kotlinx.io.Buffer,
        to: NodeId = NodeId.BROADCAST,
        channel: ChannelIndex = ChannelIndex(0),
        wantAck: Boolean = false,
        hopLimit: Int? = null,
    ): MessageHandle {
        val bytes = payload.readByteArray()
        return send(
            portnum = portnum,
            payload = bytes,
            to = to,
            channel = channel,
            wantAck = wantAck,
            hopLimit = hopLimit,
        )
    }

    // ── Sub-APIs ────────────────────────────────────────────────────────────

    /**
     * Admin API for device configuration and control. Available while connected.
     *
     * See [AdminApi] for the full method list.
     */
    public val admin: AdminApi by lazy(LazyThreadSafetyMode.PUBLICATION) {
        AdminApiImpl(engine = engine, rpcTimeout = rpcTimeout, nowProvider = { clock.now() })
    }

    /**
     * Telemetry API for device and environment metrics. Available while connected.
     *
     * See [TelemetryApi] for the full method list. [TelemetryApi.observe] is a cold flow over
     * the engine's inbound packets — collect inside a `launch { … }`.
     */
    public val telemetry: TelemetryApi by lazy(LazyThreadSafetyMode.PUBLICATION) {
        TelemetryApiImpl(engine = engine, packetsFlow = engine.packets, rpcTimeout = rpcTimeout)
    }

    /**
     * Routing API for traceRoute and neighbor enumeration. Available while connected.
     */
    public val routing: RoutingApi by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RoutingApiImpl(engine = engine, rpcTimeout = rpcTimeout)
    }

    /**
     * Store-and-Forward API for requesting stored messages. Available while connected.
     */
    public val storeForward: StoreForwardApi by lazy(LazyThreadSafetyMode.PUBLICATION) {
        StoreForwardApiImpl(
            engine = engine,
            packetsFlow = engine.packets,
            rpcTimeout = rpcTimeout,
            coroutineContext = parentContext,
            nowProvider = { clock.now() },
        )
    }

    /**
     * Sends a raw [ToRadio] frame to the device.
     *
     * This is a low-level escape hatch for device features not yet covered by higher-level APIs
     * (e.g., MQTT client proxy messages, XModem file transfers). The SDK engine does **not** track
     * or acknowledge these frames — delivery is best-effort at the transport level.
     *
     * Prefer [send], [sendText], or the typed sub-APIs ([admin], [telemetry], [routing],
     * [storeForward], etc.) whenever
     * possible.
     *
     * @param frame the fully constructed [ToRadio] message to send
     * @throws MeshtasticException.NotConnected if not currently connected
     * @since 0.2.0
     */
    @Throws(MeshtasticException::class)
    public fun sendRaw(frame: ToRadio) {
        if (connection.value !is ConnectionState.Connected) {
            throw MeshtasticException.NotConnected()
        }
        engine.sendToRadio(frame)
    }

    // ── Builder ─────────────────────────────────────────────────────────────

    public companion object {
        /** Indicates a reaction (emoji) rather than a standalone text message. */
        private const val EMOJI_INDICATOR: Int = 1

        /** Create a new [Builder]. */
        public fun Builder(): Builder = Builder()
    }

    /**
     * DSL builder for [RadioClient].
     *
     * [transport] and [storage] are required; all other settings have sensible defaults.
     *
     * ```kotlin
     * val client = RadioClient.Builder()
     *     .transport(TcpTransport(host = "192.168.1.42", port = 4403))
     *     .storage(InMemoryStorageProvider())
     *     .build()
     * ```
     *
     * @since 0.1.0
     */
    public class Builder {

        private var radioTransport: RadioTransport? = null
        private var storageProvider: StorageProvider? = null
        private var logSink: LogSink = LogSink.Silent
        private var clock: kotlin.time.Clock = kotlin.time.Clock.System
        private var coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default
        private var autoSyncTimeOnConnect: Boolean = true
        private var bleHeartbeatEnabled: Boolean = true
        private var protocolLogLevel: LogLevel = LogLevel.NONE
        private var payloadRedactor: PayloadRedactor = PayloadRedactor.Default
        private var sendTimeout: Duration = 30.seconds
        private var rpcTimeout: Duration = 30.seconds
        private var presenceTimeout: Duration = 2.hours
        private var autoReconnectConfig: AutoReconnectConfig = AutoReconnectConfig.Disabled

        /**
         * Set a pre-built [RadioTransport] instance directly.
         *
         * Intended for testing (e.g., `FakeRadioTransport`) and for transport modules that
         * construct their own transport before passing to the builder.
         *
         * ```kotlin
         * val fake = FakeRadioTransport(identity = TransportIdentity("fake:test"))
         * RadioClient.Builder()
         *     .transport(fake)
         *     .storage(InMemoryStorageProvider())
         *     .build()
         * ```
         *
         * Future: factory helpers for common transport configurations may allow
         * [TransportSpec] to be used without manually constructing a transport implementation.
         */
        public fun transport(transport: RadioTransport): Builder = apply { radioTransport = transport }

        /**
         * Set the storage provider (required).
         *
         * No default — apps must choose a backend (SQLDelight for production, in-memory for tests).
         *
         * ```kotlin
         * // Production:
         * .storage(SqlDelightStorageProvider(baseDir = "/data/meshtastic"))
         *
         * // Tests:
         * .storage(InMemoryStorageProvider())
         * ```
         */
        public fun storage(provider: StorageProvider): Builder = apply { storageProvider = provider }

        /**
         * Set a custom logger.
         *
         * Default: [LogSink.Silent] (all messages discarded).
         *
         * ```kotlin
         * .logger(LogSink { level, tag, msg, cause ->
         *     println("[$level] $tag: $msg")
         *     cause?.printStackTrace()
         * })
         * ```
         */
        public fun logger(sink: LogSink): Builder = apply { logSink = sink }

        /**
         * Set a custom clock (useful for testing deterministic time-based behaviour).
         *
         * Default: [kotlin.time.Clock.System].
         *
         * Wired through to [AdminApi.setTime] (when called with `at = null`) and to the
         * post-handshake auto-sync trigger when [autoSyncTimeOnConnect] is enabled.
         */
        public fun clock(clock: kotlin.time.Clock): Builder = apply { this.clock = clock }

        /**
         * Override the coroutine context for the engine actor and helper coroutines.
         *
         * Default: `SupervisorJob() + Dispatchers.Default`.
         *
         * **Platform note on `Dispatchers.Default`:**
         * - **JVM / Android:** backed by a shared work-stealing pool sized to
         *   `max(2, Runtime.availableProcessors() - 1)`. Suitable for CPU-bound coding/decoding.
         * - **iOS / native:** backed by a fixed multi-threaded worker pool sized to
         *   `Runtime.availableProcessors()` (kotlinx.coroutines ≥ 1.7's "new memory model"
         *   default). The engine's actor invariant (single-writer, see ADR-002) is preserved
         *   regardless of pool size — the actor channel serialises writes itself.
         *
         * Pass a [SupervisorJob] in your context if you need cancellation control independent
         * of the SDK; the SDK does not install one for you when you override this.
         *
         * ```kotlin
         * // Hilt / lifecycle-scoped:
         * .coroutineContext(viewModelScope.coroutineContext + SupervisorJob())
         *
         * // Tests:
         * .coroutineContext(testScope.backgroundScope.coroutineContext)
         * ```
         */
        public fun coroutineContext(ctx: CoroutineContext): Builder = apply { coroutineContext = ctx }

        /**
         * Enable or disable automatic time sync on connect.
         *
         * When enabled (default), [RadioClient.connect] fires a single [AdminApi.setTime] call
         * after the handshake reaches [ConnectionState.Connected]. The supplied [clock] sources
         * the value. Useful for GPS-less radios that lose wall-clock time across reboots.
         *
         * The current implementation syncs unconditionally on each Connected transition; a future
         * iteration will gate on observed device skew (> 60 s drift).
         */
        public fun autoSyncTimeOnConnect(enabled: Boolean): Builder = apply { autoSyncTimeOnConnect = enabled }

        /**
         * Disable BLE heartbeats.
         *
         * By default, the engine sends a heartbeat every 30 seconds on all transports including
         * BLE. Power-sensitive BLE apps can opt out. TCP and serial send heartbeats
         * unconditionally (see `protocol.md` §16).
         */
        public fun disableBleHeartbeat(): Builder = apply { bleHeartbeatEnabled = false }

        /**
         * Enable protocol-level logging.
         *
         * Routes framed-packet diagnostics through the configured [LogSink]. The [redactor]
         * masks positions, text payloads, and PSKs. Default: [LogLevel.NONE] (off).
         * See `docs/security.md`.
         */
        public fun protocolLogging(level: LogLevel, redactor: PayloadRedactor = PayloadRedactor.Default): Builder =
            apply {
                protocolLogLevel = level
                payloadRedactor = redactor
            }

        /**
         * Configure the per-send ACK timeout.
         *
         * Unicast packets sent with `wantAck = true` are placed in `SendState.Sent` after the
         * device acknowledges queuing, then the engine waits up to [duration] for a routing ACK
         * back from the destination. If none arrives the handle transitions to
         * `SendState.Failed(SendFailure.AckTimeout)`. Broadcasts never receive a routing ACK and
         * are not affected by this timeout.
         *
         * Default: 30 seconds.
         *
         * ```kotlin
         * // Aggressive: fail fast in low-latency LANs.
         * .sendTimeout(5.seconds)
         *
         * // Lenient: long mesh paths, sleepy nodes.
         * .sendTimeout(2.minutes)
         * ```
         */
        public fun sendTimeout(duration: Duration): Builder = apply { sendTimeout = duration }

        /**
         * Configure the per-RPC timeout for [AdminApi] / [TelemetryApi] / [RoutingApi] calls.
         *
         * Each call posts a request and parks until the device replies, the engine winds down,
         * or this duration elapses (whichever comes first). On expiry the call resolves as
         * [AdminResult.Timeout].
         *
         * Default: 30 seconds. Use a longer value when the request crosses many mesh hops
         * (`traceRoute` to a far node may legitimately take 60+ seconds), shorter when the
         * device is local and you want fast failover.
         */
        public fun rpcTimeout(duration: Duration): Builder = apply { rpcTimeout = duration }

        /** Configure the online/offline presence timeout for node presence events. */
        public fun presenceTimeout(timeout: Duration): Builder = apply { presenceTimeout = timeout }

        /**
         * Configure the engine's built-in auto-reconnect supervisor.
         *
         * Convenience overload that constructs an [AutoReconnectConfig] from the supplied
         * tunables. **Default: disabled.** The 1.0 release will flip the default to
         * `enabled = true`; pin a value here if you want today's "host owns reconnect"
         * behaviour to survive that bump.
         */
        public fun autoReconnect(
            enabled: Boolean = true,
            initialBackoff: Duration = 1.seconds,
            maxBackoff: Duration = 60.seconds,
            maxAttempts: Int? = null,
            backoffMultiplier: Double = 2.0,
            jitter: Double = 0.2,
        ): Builder = apply {
            autoReconnectConfig = AutoReconnectConfig(
                enabled = enabled,
                initialBackoff = initialBackoff,
                maxBackoff = maxBackoff,
                maxAttempts = maxAttempts,
                backoffMultiplier = backoffMultiplier,
                jitter = jitter,
            )
        }

        /** Configure the auto-reconnect supervisor with a pre-built [AutoReconnectConfig]. */
        public fun autoReconnect(config: AutoReconnectConfig): Builder = apply {
            autoReconnectConfig = config
        }

        /**
         * Build and return a [RadioClient].
         *
         * @throws IllegalArgumentException if [transport] or [storage] was not set
         */
        public fun build(): RadioClient {
            val storage = requireNotNull(storageProvider) { "storage() is required" }

            val transport: RadioTransport = requireNotNull(radioTransport) { "transport() is required" }

            val engine = MeshEngine(
                transport = transport,
                storageProvider = storage,
                logger = logSink,
                bleHeartbeatEnabled = bleHeartbeatEnabled,
                parentContext = coroutineContext,
                clock = clock,
                sendTimeout = sendTimeout,
                presenceTimeout = presenceTimeout,
                autoReconnectConfig = autoReconnectConfig,
            )

            return RadioClient(
                engine = engine,
                clock = clock,
                rpcTimeout = rpcTimeout,
                autoSyncTimeOnConnect = autoSyncTimeOnConnect,
                parentContext = coroutineContext,
            )
        }
    }
}

// ── Internal helpers ────────────────────────────────────────────────────────

/**
 * Wraps a [SharedFlow] as a plain [Flow], hiding hot-flow semantics (`replayCache`,
 * `subscriptionCount`) from public consumers. Prevents down-casting.
 *
 * Equivalent to the experimental `SharedFlow.asFlow()` (coroutines 1.11+) but uses only
 * stable APIs so the public class doesn't carry an `@OptIn` annotation.
 */
private fun <T> SharedFlow<T>.hide(): Flow<T> = flow { emitAll(this@hide) }

// ── Auxiliary types ─────────────────────────────────────────────────────────

/**
 * Strategy for redacting sensitive payload data in protocol logs.
 *
 * Applied when [RadioClient.Builder.protocolLogging] is active. The default implementation masks
 * positions, text payloads, and PSKs.
 *
 * @since 0.1.0
 */
public interface PayloadRedactor {
    public companion object {
        /** The default redaction strategy — masks position, text, and key fields. */
        public val Default: PayloadRedactor = object : PayloadRedactor {}
    }
}

/**
 * Maximum application-layer payload length (bytes).
 *
 * `MeshPacket.decoded.payload` is capped at this before framing. Any [RadioClient.send]
 * call with a larger payload is rejected with [MeshtasticException.PayloadTooLarge].
 *
 * Source: `firmware:src/mesh/MeshTypes.h` `DATA_PAYLOAD_LEN`.
 *
 * @since 0.1.0
 */
public const val DATA_PAYLOAD_LEN: Int = 233
