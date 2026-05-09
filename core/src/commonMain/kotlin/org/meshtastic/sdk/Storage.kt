/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo

/**
 * Aggregate of configuration and device metadata received during the handshake.
 *
 * Stored as an atomic unit so the SDK can reconstruct the full configuration state from
 * persistent storage after a reconnect.
 *
 * @since 0.1.0
 */
public data class ConfigBundle(
    /** The local node info (NodeNum, long name, etc.). */
    public val myInfo: MyNodeInfo,

    /** Device metadata (firmware version, role, etc.). */
    public val metadata: DeviceMetadata,

    /** All device configs (one per ConfigType). */
    public val configs: List<Config>,

    /** All module configs. */
    public val moduleConfigs: List<ModuleConfig>,

    /** Device UI configuration (display preferences, language, etc.), if provided by firmware. */
    public val deviceUIConfig: DeviceUIConfig? = null,
)

/**
 * Storage provider factory.
 *
 * Implementations are responsible for creating and activating storage backends (e.g., SQLite,
 * in-memory, or custom). The SDK calls [activate] *before* connecting to a radio, keying storage
 * by the transport identity.
 *
 * **Activation contract:** `activate()` is called exactly once per [RadioClient] lifetime, before
 * the transport connects. The returned [DeviceStorage] is then held by the engine until
 * [DeviceStorage.close].
 *
 * @since 0.1.0
 */
public interface StorageProvider {
    /**
     * Activate storage for a specific transport identity.
     *
     * Called before `transport.connect()`, allowing storage to prepare resources (open database,
     * create directories, etc.).
     *
     * **Cancellation:** if the calling coroutine is cancelled while [activate] is suspended the
     * implementation should release any partially acquired resources and rethrow
     * [kotlin.coroutines.cancellation.CancellationException]. The engine treats cancellation as
     * "storage was never activated" and will not call [DeviceStorage.close].
     *
     * @param identity the [TransportIdentity] derived from the [TransportSpec]
     * @return a [DeviceStorage] instance ready for use
     * @throws MeshtasticException.StorageUnavailable if the backend cannot be initialized
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun activate(identity: TransportIdentity): DeviceStorage
}

/**
 * Device storage backend.
 *
 * Stores node information, configuration, channels, and other persistent state keyed by
 * [TransportIdentity]. Implementations must be thread-safe from the perspective of the engine
 * actor (they may use internal locks, but the SDK makes no concurrency assumptions beyond that
 * all calls are serialized by the engine).
 *
 * **Audit trail and factory-reset detection:** The [recordOwnNode] method records the device's
 * NodeNum and firmware version. If the NodeNum changes between calls for the same identity (e.g.,
 * factory reset, radio swap, or hostname now resolves to a different physical device), the
 * implementation *must* atomically clear all state before recording the new tuple. This prevents
 * stale NodeDB rows from leaking into a new session.
 *
 * **Failure contract:** implementations may throw [MeshtasticException.StorageUnavailable]
 * (or any other `Exception`) to signal transient or persistent I/O failure. The SDK engine
 * catches these uniformly, emits [MeshEvent.StorageDegraded] **at most once per connect cycle**,
 * and continues operating entirely in-memory for the remainder of that session. The engine will
 * re-attempt storage activation on the next [RadioClient.connect]. Callers must therefore not
 * rely on writes having been durable unless they explicitly subscribe to
 * [RadioClient.events] and confirm no [MeshEvent.StorageDegraded] was emitted.
 *
 * @since 0.1.0
 */
public interface DeviceStorage : AutoCloseable {
    /**
     * Load all stored nodes.
     *
     * Suspends while the backend reads from disk; cancellable. If the caller's coroutine is
     * cancelled mid-read the implementation must rethrow
     * [kotlin.coroutines.cancellation.CancellationException].
     *
     * @return a map of NodeId → NodeInfo, or an empty map if no nodes have been stored
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun loadNodes(): Map<NodeId, NodeInfo>

    /**
     * Save or update a single node.
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun saveNode(node: NodeInfo)

    /**
     * Remove a node from storage.
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun removeNode(nodeId: NodeId)

    /**
     * Load the cached device configuration (all configs, moduleConfigs, myInfo, metadata).
     *
     * @return the [ConfigBundle] if previously stored, or `null` if this is a first connection
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun loadConfig(): ConfigBundle?

    /**
     * Save the complete device configuration.
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun saveConfig(config: ConfigBundle)

    /**
     * Load all channels.
     *
     * @return a list of channels, or an empty list if none have been stored
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun loadChannels(): List<Channel>

    /**
     * Save all channels atomically.
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun saveChannels(channels: List<Channel>)

    /**
     * Record the local node's NodeNum and firmware version.
     *
     * **Factory-reset detection:** This is called immediately after handshake with the value from
     * `MyNodeInfo.my_node_num`. The implementation must:
     *
     * 1. On *first* call for an identity: store the tuple `(nodeNum, firmwareVersion)`.
     * 2. On *subsequent* calls: if `nodeNum` differs from the stored value:
     *    - **Atomically** call [clear] and then persist the new tuple **before returning**.
     *    - This signals to the engine that storage was cleared; the engine rebuilds `MeshState`
     *      from the fresh handshake payload (no stale NodeDB rows).
     * 3. If `nodeNum` matches: no action; return normally.
     *
     * @param nodeNum the device's reported NodeNum
     * @param firmwareVersion the device's reported firmware version string
     * @throws MeshtasticException.StorageUnavailable on I/O failure
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String)

    /**
     * Clear all stored data for this storage instance.
     *
     * Invoked when:
     * - A new session starts (before handshake), or
     * - The NodeNum has changed (factory reset / radio swap / hostname resolved to a different
     *   radio).
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun clear()

    /**
     * Close and release resources.
     *
     * Called when the engine shuts down. Implementations should flush any pending writes and
     * close database connections or files.
     */
    override fun close()

    /**
     * Persist the latched session passkey for this transport identity.
     *
     * The passkey is reapplied on the next connect so admin RPCs can resume without re-running
     * `get_owner_request`. Implementations must overwrite any previous passkey atomically.
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun saveSessionPasskey(passkey: SessionPasskey)

    /**
     * Load the persisted session passkey, or `null` if none is stored or the stored entry has
     * expired (per [SessionPasskey.expiresAtEpochMs]).
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun loadSessionPasskey(): SessionPasskey?

    /**
     * Persist the engine-observed last-heartbeat timestamp for [nodeId].
     *
     * The engine updates this on every frame it observes from a node (periodic heartbeats,
     * telemetry, forwarded packets, ...). Persistence is what keeps presence state alive across
     * process death: on the next [RadioClient.connect] the engine calls [loadHeartbeats] and
     * rehydrates its in-memory map so subscribers don't see every previously-known node as
     * "silent since boot".
     *
     * Implementations should upsert the row for [nodeId] without touching any other columns on
     * the node (in particular, not resetting `NodeInfo` fields).
     *
     * @param nodeId the node the timestamp is for
     * @param epochMillis wall-clock time the engine observed activity, as epoch milliseconds (UTC)
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun saveHeartbeat(nodeId: NodeId, epochMillis: Long)

    /**
     * Load all persisted last-heartbeat timestamps.
     *
     * Returns an empty map if no heartbeats have been persisted for this identity. Called once
     * per connect cycle, immediately after storage activation and before Stage 1 handshake.
     */
    @Throws(MeshtasticException.StorageUnavailable::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun loadHeartbeats(): Map<NodeId, Long>
}

/**
 * Persisted session passkey.
 *
 * Wraps the raw passkey bytes returned by `AdminMessage.session_passkey` together with an
 * absolute expiry timestamp (epoch milliseconds, UTC). Implementations of [DeviceStorage]
 * persist this so the engine can restore the passkey on the next connect, removing the need to
 * re-run `get_owner_request` for every session.
 *
 * @property bytes raw session passkey bytes
 * @property expiresAtEpochMs absolute expiry time in epoch milliseconds (UTC); a [DeviceStorage.loadSessionPasskey]
 *   implementation must return `null` once the wall-clock has advanced past this value.
 *
 * @since 0.1.0
 */
public data class SessionPasskey(public val bytes: kotlinx.io.bytestring.ByteString, public val expiresAtEpochMs: Long)
