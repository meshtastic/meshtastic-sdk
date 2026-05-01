/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import org.meshtastic.proto.Channel
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.DeviceStorage
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.SessionPasskey
import org.meshtastic.sdk.StorageProvider
import org.meshtastic.sdk.TransportIdentity
import kotlin.random.Random

/**
 * [StorageProvider] backed by SQLDelight + SQLite, keyed by **radio NodeNum** so the same
 * physical device shares one database across transports (BLE ↔ TCP ↔ serial).
 *
 * ### File layout
 *
 * ```
 * <baseDir>/
 *   .meshtastic-index         ← identity ↔ nodeNum ↔ opaque-dbId bindings
 *   <dbId>.db                 ← one SQLite DB per radio (opaque filename)
 * ```
 *
 * ### Activation flow
 *
 * 1. `activate(identity)` reads the index.
 *    - If `identity → dbId` mapping exists, open `<dbId>.db` directly.
 *    - Otherwise return a deferred wrapper that buffers reads/writes in-memory until the
 *      radio's NodeNum is learned via [DeviceStorage.recordOwnNode].
 * 2. On first `recordOwnNode(nodeNum, …)` for a deferred wrapper:
 *    - If `nodeNum → dbId` mapping already exists (same radio already reached via another
 *      transport), open that DB; buffered scratch state is replayed into it.
 *    - Otherwise allocate a fresh opaque `dbId`, create `<dbId>.db`, replay scratch.
 *    - Persist both `identity → dbId` and `nodeNum → dbId` bindings atomically.
 * 3. If a later `recordOwnNode` reports a *different* `nodeNum` (factory reset / radio swap),
 *    the inner storage's own factory-reset logic clears the DB; the provider updates the
 *    `nodeNum → dbId` binding so the old NodeNum no longer resolves to this DB.
 *
 * Pass `baseDir = ""` for in-memory-only mode (every activation returns a fresh in-memory DB).
 *
 * @param baseDir path to the directory where database files and the index live
 */
public class SqlDelightStorageProvider(private val baseDir: String = "") : StorageProvider {

    private val indexMutex = Mutex()

    override suspend fun activate(identity: TransportIdentity): DeviceStorage {
        if (baseDir.isEmpty()) {
            // In-memory-only mode: no index, no file, fresh driver per activation.
            return SqlDelightStorage(createDriver(":memory:"))
        }

        ensureBaseDir()
        val index = indexMutex.withLock { readIndex(indexPath()) }
        val dbId = index.identityToDbId[identity.raw]
        return if (dbId != null) {
            val inner = SqlDelightStorage(createDriver(dbFilePath(dbId)))
            NodeKeyedStorage(inner, identity, dbId)
        } else {
            NodeKeyedStorage(inner = null, identity = identity, dbId = null)
        }
    }

    /** Open or create the node-keyed DB for a freshly-learned NodeNum; persist bindings. */
    private suspend fun bindToNode(identity: TransportIdentity, nodeNum: NodeId): Pair<DeviceStorage, String> =
        indexMutex.withLock {
            val index = readIndex(indexPath())
            val existingForNode = index.nodeNumToDbId[nodeNum.raw]
            val dbId = existingForNode ?: newDbId()
            val newBindings = index.copy(
                identityToDbId = index.identityToDbId + (identity.raw to dbId),
                nodeNumToDbId = index.nodeNumToDbId + (nodeNum.raw to dbId),
            )
            writeIndexAtomically(indexPath(), newBindings)
            val inner = SqlDelightStorage(createDriver(dbFilePath(dbId)))
            inner to dbId
        }

    /**
     * Remap `nodeNum → dbId` to reflect that `dbId` is now bound to `newNodeNum`. Any stale
     * NodeNum entries that still point at this `dbId` (e.g. the old NodeNum before a factory
     * reset) are pruned, so reverse-lookup remains self-consistent.
     */
    private suspend fun rebindNodeNum(newNodeNum: NodeId, dbId: String) {
        indexMutex.withLock {
            val index = readIndex(indexPath())
            val pruned = index.nodeNumToDbId.filterValues { it != dbId }
            val updated = index.copy(nodeNumToDbId = pruned + (newNodeNum.raw to dbId))
            writeIndexAtomically(indexPath(), updated)
        }
    }

    private fun ensureBaseDir() {
        val dir = baseDir.toPath()
        if (!FileSystem.SYSTEM.exists(dir)) {
            FileSystem.SYSTEM.createDirectories(dir)
        }
    }

    private fun indexPath() = "$baseDir/$INDEX_FILENAME".toPath()
    private fun dbFilePath(dbId: String) = "$baseDir/$dbId.db"

    // ── Wrapper storage ───────────────────────────────────────────────────────

    /**
     * Storage wrapper that may be pre-bound (inner != null) or deferred until [recordOwnNode]
     * reveals the radio's NodeNum. In deferred mode, reads return empty defaults and writes
     * are buffered into scratch state, then replayed into the real DB at bind time.
     *
     * No thread-safety primitives: per the [DeviceStorage] contract the engine serialises
     * every call on its actor, so plain field mutation is sound.
     */
    private inner class NodeKeyedStorage(
        private var inner: DeviceStorage?,
        private val identity: TransportIdentity,
        private var dbId: String?,
    ) : DeviceStorage {

        private val scratch = Scratch()

        override suspend fun loadNodes(): Map<NodeId, NodeInfo> = inner?.loadNodes() ?: scratch.nodes.toMap()

        override suspend fun saveNode(node: NodeInfo) {
            val i = inner
            if (i != null) i.saveNode(node) else scratch.nodes[NodeId(node.num)] = node
        }

        override suspend fun removeNode(nodeId: NodeId) {
            val i = inner
            if (i != null) i.removeNode(nodeId) else scratch.nodes.remove(nodeId)
        }

        override suspend fun loadConfig(): ConfigBundle? = inner?.loadConfig() ?: scratch.config

        override suspend fun saveConfig(config: ConfigBundle) {
            val i = inner
            if (i != null) i.saveConfig(config) else scratch.config = config
        }

        override suspend fun loadChannels(): List<Channel> = inner?.loadChannels() ?: scratch.channels.toList()

        override suspend fun saveChannels(channels: List<Channel>) {
            val i = inner
            if (i != null) {
                i.saveChannels(channels)
            } else {
                scratch.channels.clear()
                scratch.channels.addAll(channels)
            }
        }

        override suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String) {
            val existing = inner
            if (existing == null) {
                // First bind: open (or adopt) the node-keyed DB, replay scratch, then delegate.
                val (real, boundDbId) = bindToNode(identity, nodeNum)
                replayScratchInto(real)
                inner = real
                dbId = boundDbId
                real.recordOwnNode(nodeNum, firmwareVersion)
                return
            }
            // Already bound. Inner.recordOwnNode handles its own factory-reset detection
            // (clears the DB on NodeNum mismatch). We then unconditionally re-anchor the
            // provider-level nodeNum→dbId binding to the current NodeNum, pruning any stale
            // entries that pointed at this dbId.
            existing.recordOwnNode(nodeNum, firmwareVersion)
            dbId?.let { rebindNodeNum(nodeNum, it) }
        }

        override suspend fun clear() {
            val i = inner
            if (i != null) i.clear() else scratch.reset()
        }

        override fun close() {
            inner?.close()
        }

        override suspend fun saveSessionPasskey(passkey: SessionPasskey) {
            val i = inner
            if (i != null) i.saveSessionPasskey(passkey) else scratch.sessionPasskey = passkey
        }

        override suspend fun loadSessionPasskey(): SessionPasskey? =
            inner?.loadSessionPasskey() ?: scratch.sessionPasskey

        override suspend fun saveHeartbeat(nodeId: NodeId, epochMillis: Long) {
            val i = inner
            if (i != null) i.saveHeartbeat(nodeId, epochMillis) else scratch.heartbeats[nodeId] = epochMillis
        }

        override suspend fun loadHeartbeats(): Map<NodeId, Long> = inner?.loadHeartbeats() ?: scratch.heartbeats.toMap()

        private suspend fun replayScratchInto(target: DeviceStorage) {
            scratch.config?.let { target.saveConfig(it) }
            if (scratch.channels.isNotEmpty()) target.saveChannels(scratch.channels.toList())
            for (node in scratch.nodes.values) target.saveNode(node)
            for ((id, ts) in scratch.heartbeats) target.saveHeartbeat(id, ts)
            scratch.sessionPasskey?.let { target.saveSessionPasskey(it) }
            scratch.reset()
        }
    }

    private class Scratch {
        val nodes = mutableMapOf<NodeId, NodeInfo>()
        var config: ConfigBundle? = null
        val channels = mutableListOf<Channel>()
        var sessionPasskey: SessionPasskey? = null
        val heartbeats = mutableMapOf<NodeId, Long>()

        fun reset() {
            nodes.clear()
            config = null
            channels.clear()
            sessionPasskey = null
            heartbeats.clear()
        }
    }

    internal companion object {
        internal const val INDEX_FILENAME = ".meshtastic-index"
        internal const val INDEX_HEADER = "# Meshtastic storage identity/node index (v1)"

        internal fun newDbId(): String {
            // 128-bit random, hex-encoded (32 chars). No dashes; safe for any filesystem.
            val bytes = ByteArray(16).also { Random.nextBytes(it) }
            return buildString(32) {
                for (b in bytes) {
                    val v = b.toInt() and 0xFF
                    append(HEX_CHARS[v ushr 4])
                    append(HEX_CHARS[v and 0x0F])
                }
            }
        }

        private const val HEX_CHARS = "0123456789abcdef"
    }
}

/** Platform-specific SQLite driver factory. */
internal expect fun createDriver(dbPath: String): SqlDriver
