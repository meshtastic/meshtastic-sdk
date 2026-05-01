/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.NodeInfo

/**
 * Fields that may change in a [NodeInfo].
 *
 * Used by [NodeChange.Updated] to indicate which attributes were modified.
 *
 * @since 0.1.0
 */
public enum class NodeField {
    /** Node name or callsign changed. */
    Name,

    /** GPS position changed. */
    Position,

    /** Signal quality (SNR, RSSI) changed. */
    SignalQuality,

    /** Battery level changed. */
    Battery,

    /** Telemetry data changed. */
    Telemetry,

    /** Device info (model, firmware, etc.) changed. */
    DeviceInfo,

    /** Last seen timestamp changed. */
    LastSeen,

    /** User profile changed. */
    User,

    /** Some other field changed. */
    Other,
}

/**
 * A delta notification of node state changes.
 *
 * The SDK emits these via the [RadioClient.nodes] flow. Late subscribers first receive a
 * [Snapshot] of all known nodes, then live deltas ([Added], [Updated], [Removed]) in causal order.
 *
 * This delta-based design is efficient for large meshes: a 200-node network with frequent
 * telemetry would be wasteful to emit as a full `StateFlow<Map<NodeId, NodeInfo>>` (200 entries
 * per update). Deltas are O(1) space; subscribers can fold to a `StateFlow` if they prefer.
 *
 * @since 0.1.0
 */
public sealed interface NodeChange {
    /**
     * All known nodes at subscription time.
     *
     * Emitted exactly once to each new subscriber (never again on the same subscription), before
     * any live delta. Implemented via single-replay under the actor; subsequent [Added],
     * [Updated], and [Removed] are guaranteed to apply on top of this snapshot in causal order.
     *
     * Late subscribers see this snapshot instead of being stranded without context.
     */
    public data class Snapshot(val nodes: Map<NodeId, NodeInfo>) : NodeChange

    /**
     * A new node joined the mesh or was discovered by the local node.
     *
     * @param node the newly discovered node
     */
    public data class Added(val node: NodeInfo) : NodeChange

    /**
     * An existing node's information changed.
     *
     * @param node the updated node
     * @param changed a set of [NodeField]s that were modified (for subscribers to filter efficiently)
     */
    public data class Updated(val node: NodeInfo, val changed: Set<NodeField>) : NodeChange

    /**
     * A node was removed from the mesh (typically after a timeout or manual removal).
     *
     * @param nodeId the ID of the removed node
     */
    public data class Removed(val nodeId: NodeId) : NodeChange
}

/**
 * Side-channel event from the SDK engine.
 *
 * These are non-packet events: queue status updates, errors, key-verification prompts, etc.
 * Emitted via [RadioClient.events] alongside [RadioClient.packets] and [RadioClient.nodes].
 *
 * @since 0.1.0
 */
public sealed interface MeshEvent {
    /**
     * Device's transmit queue status changed.
     *
     * @param status the wire [QueueStatus] from the device
     */
    public data class QueueStatusChanged(val status: org.meshtastic.proto.QueueStatus) : MeshEvent

    /**
     * Device emitted a notification.
     *
     * @param notification the wire [ClientNotification] type
     */
    public data class Notification(val notification: org.meshtastic.proto.ClientNotification) : MeshEvent

    /**
     * A transport-level error occurred.
     *
     * @param error the transport exception
     */
    public data class TransportError(val error: MeshtasticException.Transport) : MeshEvent

    /**
     * Protocol-level warning (malformed data, unexpected state, etc.).
     *
     * May indicate a compatibility issue or a bug.
     *
     * @param message diagnostic text
     * @param details optional structured context for tooling/log enrichment
     */
    public data class ProtocolWarning(val message: String, val details: Map<String, Any?> = emptyMap()) : MeshEvent

    /**
     * The connected device reported a different `NodeNum` than the one previously persisted
     * for this transport identity (e.g. factory reset, radio swap, or hostname re-pointed
     * at a different physical radio).
     *
     * Emitted **before** the engine clears storage and **before** the subsequent fresh
     * [NodeChange.Snapshot] lands on [RadioClient.nodes], so subscribers can snapshot any
     * in-memory state they care about between observing the event and the next snapshot.
     *
     * @param previousNodeNum the previously persisted NodeNum
     * @param newNodeNum the NodeNum the device just reported
     * @param reason human-readable explanation (defaults to factory-reset wording)
     */
    public data class IdentityRebound(
        val previousNodeNum: NodeId,
        val newNodeNum: NodeId,
        val reason: String = "device factory reset or identity change",
    ) : MeshEvent

    /**
     * Storage backend reported a persistent I/O failure (disk full, permission denied, locked
     * database, etc.) and the engine has dropped into degraded mode. Subsequent storage writes
     * are skipped for the remainder of the session; in-memory state continues to flow on
     * [RadioClient.packets], [RadioClient.nodes], and [RadioClient.events] so the user-visible
     * session is preserved, but nothing is written to disk until the next [RadioClient.connect].
     *
     * Emitted **at most once per session**. Subscribers should surface a "your session is not
     * being persisted" warning to the user; the underlying cause is in [reason].
     *
     * See `docs/architecture/storage.md` for the rationale and the full list of operations that
     * the engine attempts.
     *
     * @param reason a short human-readable description of the underlying failure
     */
    public data class StorageDegraded(val reason: String) : MeshEvent

    /**
     * Key verification prompt (e.g., "confirm the radio's public key").
     *
     * Emitted when encryption setup requires user confirmation.
     *
     * @param prompt the verification details
     */
    public data class KeyVerification(val prompt: KeyVerificationPrompt) : MeshEvent

    /**
     * Security-relevant warning from the device, surfaced as typed sub-variants so that hosts
     * can react without string-parsing. These arrive from [org.meshtastic.proto.ClientNotification]
     * but the engine also still re-emits the raw [Notification] for callers that want the
     * underlying wire payload.
     *
     * @since 0.1.0
     */
    public sealed interface SecurityWarning : MeshEvent {
        /**
         * The device reports it observed another node broadcasting the *same public key* as
         * one already in its NodeDB — indicates a misconfigured / cloned device on the mesh,
         * or a nearby identity-theft attempt. Host SHOULD surface this to the user.
         */
        public data object DuplicatedPublicKey : SecurityWarning

        /**
         * The device's crypto subsystem reports the current private key was generated with
         * insufficient entropy (e.g., on a freshly-flashed board before the RNG warmed up).
         * Host SHOULD prompt the user to regenerate keys.
         */
        public data object LowEntropyKey : SecurityWarning
    }

    /**
     * A flow dropped messages due to subscriber backpressure.
     *
     * Indicates that the subscriber could not keep up with the engine's emission rate.
     * The engine applies backpressure selectively: the named [DroppedFlow] exceeded its buffer
     * and the oldest queued item was dropped.
     *
     * This event is emitted at most once per drop burst to avoid log spam.
     *
     * @param flow which flow experienced the drop
     * @param count how many items were dropped
     */
    public data class PacketsDropped(val flow: DroppedFlow, val count: Int) : MeshEvent

    /**
     * Device reported `FromRadio.rebooted = true` — the radio restarted mid-session (crash,
     * intentional reboot via admin, firmware update, or brownout). The engine treats this as
     * a forced disconnect: pending sends fail, handshake state resets, and
     * [ConnectionState] transitions to `Disconnected`. Higher-level reconnect policy (if any)
     * then restarts the session from scratch.
     *
     * Emitted **once per reboot event**, before the transition to `Disconnected`.
     *
     * @since 0.1.0
     */
    public data class DeviceRebooted(val reason: String = "device reported reboot") : MeshEvent
}

/**
 * Which flow type dropped messages.
 *
 * See [MeshEvent.PacketsDropped].
 *
 * @since 0.1.0
 */
public enum class DroppedFlow {
    /** The [RadioClient.packets] flow dropped messages. */
    Packets,

    /** The [RadioClient.events] flow dropped messages. */
    Events,
}

/**
 * Key-verification prompt details surfaced via [MeshEvent.KeyVerification].
 *
 * **Phase 1:** marker interface only — emitted as a placeholder when the engine notices that
 * encryption setup *would* prompt for confirmation, but no payload is exposed yet. Hosts
 * should treat any non-null prompt as "show a generic verification UI" until the surface is
 * filled in.
 *
 * **Phase 2+:** this interface will gain at least:
 *
 * ```kotlin
 * public interface KeyVerificationPrompt {
 *     public val remoteNodeId: NodeId
 *     public val publicKeyFingerprint: String   // hex SHA-256, abbreviated for display
 *     public suspend fun confirm()              // accept; engine continues handshake
 *     public suspend fun reject()               // refuse; engine tears down with Protocol error
 * }
 * ```
 *
 * The shape will be ratified in a follow-up ADR before 1.0; consumers wiring this today should
 * expect the interface to add abstract members (binary-incompatible for implementers, but a
 * pure marker for collectors who only `is`-check it).
 *
 * @since 0.1.0
 */
public interface KeyVerificationPrompt
