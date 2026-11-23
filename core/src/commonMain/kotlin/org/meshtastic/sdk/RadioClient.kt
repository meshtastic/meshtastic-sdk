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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.LockdownAuth
import org.meshtastic.proto.LogRecord
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.meshtastic.sdk.internal.AdminApiImpl
import org.meshtastic.sdk.internal.AdminEditLocks
import org.meshtastic.sdk.internal.FileTransferApiImpl
import org.meshtastic.sdk.internal.MeshEngine
import org.meshtastic.sdk.internal.MqttProxyApiImpl
import org.meshtastic.sdk.internal.RemoteShellApiImpl
import org.meshtastic.sdk.internal.RoutingApiImpl
import org.meshtastic.sdk.internal.StoreForwardApiImpl
import org.meshtastic.sdk.internal.TelemetryApiImpl
import org.meshtastic.sdk.internal.defaultHopLimit
import org.meshtastic.sdk.internal.nodeInfoRequestPacket
import org.meshtastic.sdk.internal.summarizeFromRadio
import org.meshtastic.sdk.internal.summarizeToRadio
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
 * **Resource management:** call the suspending [disconnect] when done — typically from the same
 * structured scope that owns the session, e.g. `try { … } finally { client.disconnect() }`.
 * There is deliberately no blocking `close()`/[AutoCloseable]: a blocking bridge invites ANR on
 * Android's main thread and deadlock on iOS main, and a radio session has no non-suspending way
 * to tear down safely.
 *
 * @since 0.1.0
 */
public class RadioClient internal constructor(
    private val engine: MeshEngine,
    private val clock: kotlin.time.Clock,
    private val rpcTimeout: Duration,
    private val autoSyncTimeOnConnect: Boolean,
    private val parentContext: kotlin.coroutines.CoroutineContext,
) {

    // Shared by every AdminApi of this client, so edit envelopes to one node never interleave.
    private val adminEditLocks = AdminEditLocks()

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
     * Admin session state per node, keyed by the node that issued the session passkey.
     *
     * A node appears once an admin response from it carries a passkey and disappears when the
     * key expires, the node rejects it with `ADMIN_BAD_SESSION_KEY`, the connection ends or the
     * local identity changes. A node that is absent has [AdminSessionStatus.NoSession]. The local
     * node's session is seeded during the handshake when the node answers within 10 s, and
     * otherwise by its reply to the client's next admin request. Expiry is evaluated on the engine's
     * 30 s heartbeat tick against the injected [Builder.clock], so a transition can surface up to
     * 30 s late; the passkey bytes are never exposed.
     *
     * @since 0.3.0
     */
    public val adminSessions: StateFlow<Map<NodeId, AdminSessionStatus>> = engine.adminSessions.asStateFlow()

    /**
     * Admin session state of one [node]: [adminSessions] projected to a single node, with
     * [AdminSessionStatus.NoSession] standing in for an absent entry. Emits the current status
     * on collection, then only changes.
     *
     * @since 0.3.0
     */
    public fun adminSessionStatus(node: NodeId): Flow<AdminSessionStatus> =
        adminSessions.map { it[node] ?: AdminSessionStatus.NoSession }.distinctUntilChanged()

    /**
     * Node-change deltas. **Every** subscriber — early or late — first receives a
     * [NodeChange.Snapshot] of the engine's current node map (seeded per-subscription via
     * `onSubscription`), then live [NodeChange.Added] / [NodeChange.Updated] /
     * [NodeChange.Removed] / [NodeChange.WentOffline] / [NodeChange.CameOnline] in causal
     * order. A delta whose change is already reflected in the seeded snapshot re-applies
     * idempotently. The handshake additionally emits a live [NodeChange.Snapshot] at handshake
     * commit (and after each reconnect), which existing subscribers must treat as a full
     * replacement. Under [Builder.skipNodeDb] that snapshot holds only the local node — peers
     * arrive as [NodeChange.Added] once they are heard.
     *
     * **Buffering:** the underlying `MutableSharedFlow` has `extraBufferCapacity = 256` and the
     * engine emits with `tryEmit`, so it never suspends on a collector. When a slow collector
     * leaves the buffer full, the new change is dropped and [MeshEvent.PacketsDropped] with
     * `flow = DroppedFlow.Nodes` is emitted on [events]; a new subscription's
     * [NodeChange.Snapshot] restores a complete map.
     */
    public val nodes: Flow<NodeChange> = engine.nodes
        .onSubscription { emit(NodeChange.Snapshot(engine.nodeMapSnapshot())) }

    /**
     * Inbound decoded packets.
     *
     * The device decrypts mesh traffic, and the engine drops a packet that arrives without a
     * decoded payload, so `MeshPacket.decoded` is always non-null on emission. Filter by
     * `decoded.portnum` to match specific app payloads.
     *
     * **Buffering:** the underlying `MutableSharedFlow` has `extraBufferCapacity = 128` and the
     * engine emits with `tryEmit`, so it never suspends on a collector. When a slow collector
     * leaves the buffer full, the new packet is dropped and
     * [MeshEvent.PacketsDropped][org.meshtastic.sdk.MeshEvent.PacketsDropped] with
     * `flow = DroppedFlow.Packets` is emitted on [events].
     *
     * Packets that arrive during the handshake are held and emitted once the session reaches
     * [ConnectionState.Connected].
     */
    public val packets: Flow<MeshPacket> = engine.packets.hide()

    /**
     * Ordered handshake snapshots, packets and session boundaries for one consumer.
     *
     * Enable this stream with [Builder.sessionReceive] and start collecting before [connect].
     * A protected radio may report [RadioSessionEvent.Lockdown] before [RadioSessionEvent.Ready].
     * Ready precedes retained handshake packets and live packets. The stream spans automatic reconnects and completes when the client
     * shuts down. Collect it directly for ordered delivery.
     *
     * If the SDK loses receive coverage, the stream drains accepted events and then throws
     * [RadioSessionOverflowException]. The radio continues processing frames. Create a new
     * client to restart this stream after overflow or collector cancellation. Delivery
     * sequences measure SDK coverage. The radio or transport may lose packets upstream.
     *
     * `null` if session receive was left disabled.
     */
    public val sessionEvents: Flow<RadioSessionEvent>? = engine.sessionEvents

    /**
     * Side-channel advisory events: queue status, transport errors, key-verification prompts,
     * drop notifications, and identity-rebind signals
     * ([MeshEvent.IdentityRebound] — emitted before the engine clears storage when the
     * device reports a different NodeNum than the one previously persisted).
     */
    public val events: Flow<MeshEvent> = engine.events.hide()

    /**
     * Every [FromRadio] envelope the device sends, decoded and unfiltered: handshake frames,
     * config, channels, log records and packets alike. [packets] carries mesh packets only, so a
     * recorder or protocol analyzer subscribes here instead.
     *
     * Hot, no replay, and lossy: a collector that falls more than 256 envelopes behind loses the
     * oldest, so the engine is never stalled by a slow tap. Envelopes can carry channel PSKs and
     * node positions; the SDK does not redact or persist them.
     *
     * @since 0.3.0
     */
    public val fromRadio: Flow<FromRadio> = engine.rawFromRadio.hide()

    /**
     * Log lines the device sends over the phone API, in arrival order, handshake included.
     *
     * Firmware only sends them on stream transports (serial and TCP) while
     * `security.debug_log_api_enabled` is set; turn it on with [AdminApi.setConfig] and a
     * `Config.SecurityConfig`. The SDK never changes that setting. The BLE LogRadio
     * characteristic is a separate transport-level path that the SDK does not read.
     *
     * Hot, no replay. Volume is unbounded, so a collector that falls more than 64 records
     * behind loses the oldest ones; the engine and the other flows never wait for it, and no
     * [MeshEvent.PacketsDropped] is emitted for logs.
     *
     * @since 0.3.0
     */
    public val deviceLogs: Flow<LogRecord> = engine.deviceLogs.hide()

    /**
     * Complete frames of the device display, emitted while mirroring is on
     * ([AdminApi.setDisplayMirror]) or after [AdminApi.requestDisplayFrame].
     *
     * The engine reassembles the firmware's chunks; a frame with a gap, an interrupted chunk
     * sequence or an oversize `total_size` (over 1 MiB) is dropped and logged, never delivered
     * partially. **Buffering:** no replay, and a buffer of 2 that drops the **oldest** frame when a
     * collector falls behind, so a slow renderer skips to recent frames and never stalls the
     * engine. Frames emitted while nothing collects are discarded. Dropped frames are not
     * reported on [events].
     *
     * @since 0.3.0
     */
    public val displayFrames: Flow<DisplayMirrorFrame> = engine.displayFrames.hide()

    /**
     * The most recent complete display palette, or `null` until the device sends one this
     * session. Match [DisplayMirrorFrame.paletteSignature] against
     * [org.meshtastic.proto.DisplayPalette.signature]; a frame whose palette has not arrived yet
     * should be drawn monochrome. Reset to `null` on disconnect.
     *
     * @since 0.3.0
     */
    public val displayPalette: StateFlow<org.meshtastic.proto.DisplayPalette?> =
        engine.displayPalette.asStateFlow()

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
     * Queue a nodes-only download on the current connection. Requires the optional [sessionEvents] stream.
     * A completed download emits [RadioSessionEvent.NodeDbRefreshed]. Requests received during a download
     * coalesce into one follow-up. A stalled download ends the session so a late completion cannot be
     * mistaken for a subsequent download.
     *
     * Returns whether the request entered the engine queue while the client was connected.
     */
    public fun requestNodeDbRefresh(): Boolean = engine.requestNodeDbRefresh()

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
        val packet = MeshPacket.Builder().also { wb ->
            wb.to = node.raw
            wb.want_ack = true
            wb.decoded = org.meshtastic.proto.Data.Builder().also { wb ->
                wb.portnum = org.meshtastic.proto.PortNum.NODEINFO_APP
                wb.payload = okio.ByteString.EMPTY
                wb.want_response = true
            }.build()
        }.build()
        return send(packet)
    }

    /**
     * Request [node]'s [NodeInfo] on [channel], carrying [user] so the remote also learns ours.
     *
     * Sends `NODEINFO_APP` with `want_response = true` and no `want_ack`, as the Android app does.
     * [user] defaults to our own node's user; `null` sends an empty payload. The handle tracks only
     * the send; [RoutingApi.requestNodeInfo] sends the same request and returns the reply.
     *
     * @throws IllegalArgumentException if [node] is our own node or [NodeId.LOCAL]
     * @throws MeshtasticException.NotConnected if not currently connected
     * @since 0.3.0
     */
    @Throws(MeshtasticException::class, IllegalArgumentException::class)
    public fun requestNodeInfo(node: NodeId, channel: ChannelIndex, user: User? = ownNode.value?.user): MessageHandle {
        require(node != NodeId.LOCAL && node.raw != engine.myNodeNumOrNull()) {
            "node info is requested from another node, not our own"
        }
        return send(nodeInfoRequestPacket(node.raw, channel.raw, defaultHopLimit(), user))
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
    }

    /**
     * Gracefully disconnect from the device.
     *
     * Stops the engine, closes the transport, and releases storage. Never throws; idempotent.
     * In-flight [MessageHandle]s that have not reached terminal state will be failed with
     * [SendFailure.Disconnected]. The same client may [connect] again afterwards; [sessionEvents]
     * has completed and stays complete.
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

    // ── Outbound ────────────────────────────────────────────────────────────

    /**
     * Enqueue an outbound packet for transmission.
     *
     * Returns **immediately** with a [MessageHandle] tracking the packet's lifecycle. The engine
     * assigns `id` and `from` from the local node's info; callers may override these if needed.
     * A nonzero `packet.id` becomes the handle's [MessageHandle.id].
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
        checkSendable(packet)
        // A caller-chosen wire id is the handle's id, so cancel and the returned id name the packet sent.
        val id = if (packet.id != 0) MessageId(packet.id) else engine.nextMessageId()
        val stateFlow = MutableStateFlow<SendState>(SendState.Queued)
        val handle = MessageHandle(
            id = id,
            _state = stateFlow,
            cancelFn = { cancelId -> engine.tryCancelSend(cancelId, stateFlow) },
            packet = packet,
            resendFn = { pkt -> send(pkt) },
        )
        engine.trySend(packet, id, stateFlow)
        return handle
    }

    /**
     * Queues one packet behind the current radio queue write. A matching firmware receipt or
     * routing result releases the slot. If neither arrives within five seconds, the slot
     * is released and its receipt is [QueueReceipt.Unconfirmed].
     *
     * Delivery can still complete after the queue slot is released. A nonzero [MeshPacket.id]
     * is retained for durable callers and becomes the ID on the returned [MessageHandle].
     */
    @Throws(MeshtasticException::class)
    public fun sendPaced(packet: MeshPacket): PacedMessageHandle {
        checkSendable(packet)
        return engine.enqueuePaced(
            packet,
            resend = { original -> sendPaced(original.newBuilder().also { it.id = 0 }.build()).message },
        )
    }

    /**
     * The next packet id from the counter the SDK draws its own send and RPC ids from, for a host that must
     * know the id before it sends, such as a durable outbox that stores the row first. Pass it to
     * [SendBuilder.id] or as [MeshPacket.id].
     *
     * Never `0`. The counter starts at a random point for each client and steps by one, so an id does not
     * recur within a client until 2^32 ids have been drawn, and ids from an earlier process are unlikely to.
     * Works before [connect]. The id is not reserved: a send under an id that an in-flight send or RPC holds
     * fails with [SendFailure.IdCollision], and an RPC the SDK numbers itself skips ids a caller holds.
     *
     * @since 0.3.0
     */
    public fun nextPacketId(): MessageId = engine.nextMessageId()

    private fun checkSendable(packet: MeshPacket) {
        val payloadSize = packet.decoded?.payload?.size ?: 0
        if (payloadSize > DATA_PAYLOAD_LEN) {
            throw MeshtasticException.PayloadTooLarge(DATA_PAYLOAD_LEN)
        }
        if (connection.value !is ConnectionState.Connected) {
            throw MeshtasticException.NotConnected()
        }
    }

    /**
     * Convenience: send a text message.
     *
     * Wraps the text in a [MeshPacket] with `decoded.portnum = TEXT_MESSAGE_APP` and
     * `decoded.payload = text.encodeToByteArray()`, then calls [send].
     *
     * [wantAck] (default `true`) sets `want_ack` so the firmware provides delivery feedback:
     * - **Unicast**: the recipient sends a Routing ACK back to the sender.
     * - **Broadcast**: the firmware generates an implicit ACK when it overhears a neighbor
     *   relay the packet. This confirms at least one mesh node retransmitted the message.
     *
     * With it set, the returned [MessageHandle] resolves to [SendOutcome.Success] on ACK, or
     * [SendOutcome.Failure] if the firmware exhausts retransmissions without confirmation.
     * Without it the packet carries the device hop limit, since firmware fills a zero hop limit
     * only on a `want_ack` packet.
     *
     * Parameter order matches [sendReaction] (`to` before `channel`) as of 0.2.0.
     *
     * @param text the message text (UTF-8 encoded)
     * @param to the destination [NodeId] (default: [NodeId.BROADCAST])
     * @param channel the channel index (default: 0)
     * @param replyId the packet ID of the message this text replies to (threaded reply), or `0`
     *   (default) for a standalone message (parameter added in 0.2.0)
     * @param wantAck request delivery feedback (default `true`; parameter added in 0.3.0)
     * @return a handle tracking delivery state
     * @throws MeshtasticException.NotConnected if not currently connected
     * @throws MeshtasticException.PayloadTooLarge if the encoded text exceeds the device limit
     */
    @Throws(MeshtasticException::class)
    public fun sendText(
        text: String,
        to: NodeId = NodeId.BROADCAST,
        channel: ChannelIndex = ChannelIndex(0),
        replyId: Int = 0,
        wantAck: Boolean = true,
    ): MessageHandle {
        val payload = text.encodeToByteArray()
        if (payload.size > DATA_PAYLOAD_LEN) {
            throw MeshtasticException.PayloadTooLarge(DATA_PAYLOAD_LEN)
        }
        val packet = MeshPacket.Builder().also { wb ->
            wb.to = to.raw
            wb.channel = channel.raw
            wb.want_ack = wantAck
            // Firmware fills a zero hop limit only on a want_ack packet.
            if (!wantAck) wb.hop_limit = defaultHopLimit()
            wb.decoded = org.meshtastic.proto.Data.Builder().also { wb ->
                wb.portnum = org.meshtastic.proto.PortNum.TEXT_MESSAGE_APP
                wb.payload = payload.toByteString()
                wb.reply_id = replyId
            }.build()
        }.build()
        return send(packet)
    }

    /** The 0.2 signature, kept so callers compiled against it still link. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    @Throws(MeshtasticException::class)
    public fun sendText(
        text: String,
        to: NodeId = NodeId.BROADCAST,
        channel: ChannelIndex = ChannelIndex(0),
        replyId: Int = 0,
    ): MessageHandle = sendText(text, to, channel, replyId, wantAck = true)

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
        val packet = MeshPacket.Builder().also { wb ->
            wb.to = to.raw
            wb.channel = channel.raw
            wb.want_ack = true
            wb.decoded = org.meshtastic.proto.Data.Builder().also { wb ->
                wb.portnum = org.meshtastic.proto.PortNum.TEXT_MESSAGE_APP
                wb.payload = payload.toByteString()
                wb.emoji = EMOJI_INDICATOR
                wb.reply_id = replyId
            }.build()
        }.build()
        return send(packet)
    }

    /**
     *
     * Constructs a packet with `decoded = Data(portnum, payload, want_response = false)` and
     * forwards to [send]. Exists so callers do not need to import `org.meshtastic.proto.Data`
     * to send a typed application payload.
     *
     * `hopLimit = null` (the default) sends the device's configured `lora.hop_limit` from
     * [configBundle], or the firmware default of 3 when no bundle has landed yet. The SDK
     * resolves it on the host because the firmware only substitutes its default for a `0`
     * on the wire when `want_ack` is set; with `want_ack` off a `0` goes out as a packet that
     * no node relays. Set it explicitly only to override the device default for one packet.
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
        val packet = MeshPacket.Builder().also { wb ->
            wb.to = to.raw
            wb.channel = channel.raw
            wb.want_ack = wantAck
            wb.hop_limit = hopLimit ?: defaultHopLimit()
            wb.decoded = org.meshtastic.proto.Data.Builder().also { wb ->
                wb.portnum = portnum
                wb.payload = payload.toByteString()
                wb.want_response = false
            }.build()
        }.build()
        return send(packet)
    }

    /**
     * Convenience: build a [MeshPacket] for the given [portnum] from an [okio.ByteString] payload.
     *
     * Identical to the `ByteArray`-accepting [send] overload but takes the byte-string type that
     * Wire-generated proto fields already use — pass payloads (or slices of received payloads)
     * straight through without copying into a raw array first.
     *
     * @param portnum the application port number
     * @param payload the wire-encoded payload (≤ [DATA_PAYLOAD_LEN])
     * @param to destination [NodeId]; defaults to [NodeId.BROADCAST]
     * @param channel [ChannelIndex] to send on; defaults to channel 0 (primary)
     * @param wantAck request a reliable delivery ACK
     * @param hopLimit explicit hop limit override; `null` keeps the device default
     * @return a [MessageHandle] tracking delivery state
     * @throws MeshtasticException.NotConnected if not currently connected
     * @throws MeshtasticException.PayloadTooLarge if the payload exceeds the device limit
     * @since 0.2.0
     */
    @Throws(MeshtasticException::class, CancellationException::class)
    public suspend fun send(
        portnum: PortNum,
        payload: okio.ByteString,
        to: NodeId = NodeId.BROADCAST,
        channel: ChannelIndex = ChannelIndex(0),
        wantAck: Boolean = false,
        hopLimit: Int? = null,
    ): MessageHandle = send(
        portnum = portnum,
        payload = payload.toByteArray(),
        to = to,
        channel = channel,
        wantAck = wantAck,
        hopLimit = hopLimit,
    )

    // ── Sub-APIs ────────────────────────────────────────────────────────────

    /**
     * Admin API for device configuration and control. Available while connected.
     *
     * See [AdminApi] for the full method list.
     */
    public val admin: AdminApi by lazy(LazyThreadSafetyMode.PUBLICATION) {
        AdminApiImpl(
            engine = engine,
            rpcTimeout = rpcTimeout,
            nowProvider = { clock.now() },
            editLocks = adminEditLocks,
        )
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
     * Routing API for traceRoute, neighbor enumeration, and position and node-info requests that
     * wait for the reply. Available while connected.
     */
    public val routing: RoutingApi by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RoutingApiImpl(engine = engine, rpcTimeout = rpcTimeout, nowEpochSeconds = ::nowEpochSeconds)
    }

    /**
     * File transfer API for reading and writing files on the device. Available while connected.
     */
    public val files: FileTransferApi by lazy(LazyThreadSafetyMode.PUBLICATION) {
        FileTransferApiImpl(client = this)
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
            sendPacket = { send(it) },
        )
    }

    /**
     * Remote shell (`REMOTE_SHELL_APP`) frames to and from a node, PKI-encrypted. Available while connected.
     *
     * @since 0.3.0
     */
    public val remoteShell: RemoteShellApi by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RemoteShellApiImpl(client = this)
    }

    /**
     * MQTT client proxy messages between the device and a host-side broker connection. Available while connected.
     *
     * @since 0.3.0
     */
    public val mqttProxy: MqttProxyApi by lazy(LazyThreadSafetyMode.PUBLICATION) {
        MqttProxyApiImpl(client = this)
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

    /**
     * Attempts to enqueue local lockdown authentication, including before [ConnectionState.Connected].
     * The device reports the outcome through [MeshEvent.LockdownStatusChanged] and [sessionEvents].
     */
    public fun tryLockdown(auth: LockdownAuth): Boolean =
        engine.enqueueLockdown(auth, target = null) is AdminResult.Success

    /**
     * Attempts to enqueue a raw [ToRadio] frame while connected.
     *
     * @return true once the transport send queue accepts the encoded frame. Firmware acceptance and delivery
     *     must be observed separately, just as with [sendRaw].
     */
    public fun trySendRaw(frame: ToRadio): Boolean =
        connection.value is ConnectionState.Connected && engine.trySendToRadio(frame)

    /**
     * The hop limit a convenience send uses when the caller passes `null`: the device's
     * `lora.hop_limit` once the handshake has delivered it, else the firmware default.
     */
    internal fun defaultHopLimit(): Int = engine.defaultHopLimit()

    /** The injected clock's current time in epoch seconds. */
    internal fun nowEpochSeconds(): Int = clock.now().epochSeconds.toInt()

    /** The local node number, or null before the handshake has reported it. */
    internal fun nodeNum(): Int? = engine.myNodeNumOrNull()

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
        private var skipNodeDb: Boolean = false
        private var sessionReceiveCapacity: Int? = null

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
         * clock sync the handshake sends when [autoSyncTimeOnConnect] is enabled.
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
         * When enabled (default), the handshake sends one `set_time_only` from [clock] as soon as
         * the Stage 1 `my_info` arrives, so firmware can re-date packets it queued without a clock
         * while they are still queued. Best effort: a fast stream link can flush them first. Useful for
         * GPS-less radios that lose time across reboots.
         */
        public fun autoSyncTimeOnConnect(enabled: Boolean): Builder = apply { autoSyncTimeOnConnect = enabled }

        /**
         * Disable BLE heartbeats.
         *
         * By default, the engine sends a heartbeat every 30 seconds on all transports including
         * BLE. Power-sensitive BLE apps can opt out. TCP, serial and WebSocket send heartbeats
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
            recoveryStep: ReconnectRecoveryStep? = null,
        ): Builder = apply {
            autoReconnectConfig = AutoReconnectConfig(
                enabled = enabled,
                initialBackoff = initialBackoff,
                maxBackoff = maxBackoff,
                maxAttempts = maxAttempts,
                backoffMultiplier = backoffMultiplier,
                jitter = jitter,
                recoveryStep = recoveryStep,
            )
        }

        /** Configure the auto-reconnect supervisor with a pre-built [AutoReconnectConfig]. */
        public fun autoReconnect(config: AutoReconnectConfig): Builder = apply {
            autoReconnectConfig = config
        }

        /**
         * Skip the Stage-2 NodeDB download ("config-only" connect): the handshake stops after
         * Stage 1, so `want_config_id = 69421` is never sent and connect returns once config and
         * channels are in — cutting the bulk of BLE connect time on a large mesh. Config, channels,
         * own node, admin and send/receive are unaffected; the node map holds only the local node
         * until peers are heard live. For config editors, provisioning and CLI one-shots; leave off
         * for chat/map UIs. See `docs/protocol.md` §6.
         *
         * @param skip `true` to skip Stage 2; `false` restores the default two-stage handshake.
         */
        public fun skipNodeDb(skip: Boolean = true): Builder = apply { skipNodeDb = skip }

        /**
         * Enable [RadioClient.sessionEvents] with a bounded buffer for one collector.
         *
         * A received packet can bring a node change with it, so size [capacity] for about two events
         * per packet in a burst. The default holds the stored-history replay that follows `Ready`
         * (up to four packets for each of 250 nodes) while the collector installs the snapshot.
         *
         * @param capacity Maximum number of queued events, including session boundaries.
         * @throws IllegalArgumentException if capacity is nonpositive or unlimited.
         */
        public fun sessionReceive(capacity: Int = 4096): Builder = apply {
            require(capacity in 1 until Int.MAX_VALUE) { "Session receive capacity must be positive and finite" }
            sessionReceiveCapacity = capacity
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
                skipNodeDb = skipNodeDb,
                syncTimeDuringHandshake = autoSyncTimeOnConnect,
                protocolLogLevel = protocolLogLevel,
                payloadRedactor = payloadRedactor,
                sessionReceiveCapacity = sessionReceiveCapacity,
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
 * Applied when [RadioClient.Builder.protocolLogging] is active: every envelope is rendered through
 * [describe] before it reaches the [LogSink]. [Default] logs the envelope variant and routing
 * metadata only, so payload bytes, text, positions, PSKs and passkeys never appear.
 *
 * @since 0.1.0
 */
public interface PayloadRedactor {
    /** Renders an inbound [fromRadio] envelope for the protocol log. */
    public fun describe(fromRadio: FromRadio): String = summarizeFromRadio(fromRadio)

    /** Renders an outbound [toRadio] envelope for the protocol log. */
    public fun describe(toRadio: ToRadio): String = summarizeToRadio(toRadio)

    public companion object {
        /** The default redaction strategy: envelope variant and routing metadata, never payload content. */
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

/**
 * Build a [RadioClient] with Kotlin builder-lambda syntax — sugar over [RadioClient.Builder]:
 *
 * ```kotlin
 * val client = RadioClient {
 *     transport(TcpTransport(host = "meshtastic.local"))
 *     storage(SqlDelightStorageProvider(baseDir = dataDir))
 *     autoReconnect(AutoReconnectConfig(enabled = true))
 * }
 * ```
 *
 * Swift and step-wise callers should keep using [RadioClient.Builder] directly.
 *
 * @since 0.2.0
 */
public fun RadioClient(configure: RadioClient.Builder.() -> Unit): RadioClient =
    RadioClient.Builder().apply(configure).build()
