/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.DeviceStorage
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.SessionPasskey
import org.meshtastic.sdk.storage.sqldelight.internal.MeshDatabase
import kotlin.time.Clock

/**
 * [DeviceStorage] backed by a SQLDelight SQLite database.
 *
 * All proto values are stored as raw bytes (via Wire ADAPTER.encode / ADAPTER.decode).
 * Configs and ModuleConfigs are stored as individual rows keyed by section name.
 * MyNodeInfo and DeviceMetadata are stored in the `session` table.
 *
 * All suspend methods hop to [dispatcher] so blocking JDBC / Android SQLite / native sqlite3 calls
 * never run on the caller's thread (typically the engine actor on `Dispatchers.Default`). Defaults
 * to a platform-appropriate IO dispatcher view with parallelism capped to match the small driver
 * connection pool; tests can inject a direct/unconfined dispatcher.
 */
internal class SqlDelightStorage(
    private val driver: SqlDriver,
    private val dispatcher: CoroutineDispatcher = defaultStorageDispatcher,
) : DeviceStorage {

    private val db = MeshDatabase(driver)
    private val opMutex = Mutex()
    private var schemaReady = false

    private suspend fun <T> op(block: suspend () -> T): T = withContext(dispatcher) {
        opMutex.withLock {
            ensureSchema()
            block()
        }
    }

    // Schema creation is a suspending call under generateAsync (QueryResult.AsyncValue is lazy:
    // un-awaited, it never runs). Done once, on first use, instead of in the constructor.
    private suspend fun ensureSchema() {
        if (schemaReady) return
        val alreadyInitialised = driver
            .executeQuery(
                identifier = null,
                sql = "SELECT name FROM sqlite_master WHERE type='table' AND name='session' LIMIT 1",
                // Sync drivers close the cursor when executeQuery returns, so the row check must
                // run eagerly; only the async (web worker) cursor is awaited later.
                mapper = { cursor ->
                    when (val hasRow = cursor.next()) {
                        is QueryResult.Value -> hasRow
                        is QueryResult.AsyncValue -> QueryResult.AsyncValue { hasRow.await() }
                    }
                },
                parameters = 0,
            )
            .await()
        // Android/Native drivers create+migrate themselves and stamp user_version. JDBC and the
        // web worker driver take no schema, so stamp and migrate here.
        val schema = MeshDatabase.Schema
        val stamped = scalarLong("PRAGMA user_version")
        if (!alreadyInitialised) {
            schema.create(driver).await()
            driver.execute(null, "PRAGMA user_version = ${schema.version}", 0).await()
        } else {
            val version = if (stamped == 0L) unstampedVersion() else stamped
            if (version < schema.version) {
                schema.migrate(driver, version, schema.version).await()
            }
            if (version < schema.version || stamped == 0L) {
                driver.execute(null, "PRAGMA user_version = ${schema.version}", 0).await()
            }
        }
        schemaReady = true
    }

    /**
     * The schema version of a database that has tables but no `user_version`, read from its shape.
     * Earlier JDBC builds ran `Schema.create` without stamping, so their databases sit at 0 in
     * whatever shape was current then: 3.sqm's `nodes.last_heartbeat_at` marks v3, and the
     * `messages` table that 2.sqm drops marks v1.
     */
    private suspend fun unstampedVersion(): Long = when {
        scalarLong("SELECT count(*) FROM pragma_table_info('nodes') WHERE name = 'last_heartbeat_at'") > 0 -> 3L
        scalarLong("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'messages'") > 0 -> 1L
        else -> 2L
    }

    private suspend fun scalarLong(sql: String): Long = driver
        .executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                when (val hasRow = cursor.next()) {
                    is QueryResult.Value -> QueryResult.Value(if (hasRow.value) cursor.getLong(0) ?: 0L else 0L)

                    is QueryResult.AsyncValue -> QueryResult.AsyncValue {
                        if (hasRow.await()) cursor.getLong(0) ?: 0L else 0L
                    }
                }
            },
            parameters = 0,
        )
        .await()

    private val q get() = db.meshQueries

    // ── Nodes ─────────────────────────────────────────────────────────────────

    override suspend fun loadNodes(): Map<NodeId, NodeInfo> = op {
        val rows = q.selectAllNodes().awaitAsList()
        buildMap {
            for (row in rows) {
                val nodeId = NodeId(row.node_num.toInt())
                val nodeInfo = row.raw_node_info?.let { bytes ->
                    try {
                        NodeInfo.ADAPTER.decode(bytes)
                    } catch (_: Exception) {
                        null
                    }
                } ?: NodeInfo.Builder().also { wb -> wb.num = row.node_num.toInt() }.build()
                put(nodeId, nodeInfo)
            }
        }
    }

    override suspend fun saveNode(node: NodeInfo): Unit = op {
        val user = node.user
        q.upsertNode(
            node_num = node.num.toLong(),
            user_id = user?.id,
            long_name = user?.long_name,
            short_name = user?.short_name,
            hw_model = user?.hw_model?.value?.toLong(),
            is_licensed = if (user?.is_licensed == true) 1L else 0L,
            role = null, // role lives in Config.DeviceConfig, not NodeInfo
            public_key = user?.public_key?.toByteArray(),
            last_heard_epoch = node.last_heard.toLong(),
            snr = node.snr.toDouble(),
            rssi = null, // rssi not in NodeInfo proto
            hops_away = node.hops_away?.toLong(),
            via_mqtt = if (node.via_mqtt) 1L else 0L,
            is_self = 0L,
            raw_node_info = NodeInfo.ADAPTER.encode(node),
        )
    }

    override suspend fun removeNode(nodeId: NodeId): Unit = op {
        q.deleteNode(nodeId.raw.toLong())
    }

    // ── Config ────────────────────────────────────────────────────────────────

    override suspend fun loadConfig(): ConfigBundle? = op {
        val myInfoBytes = q.selectSession(SESSION_KEY_MY_INFO).awaitAsOneOrNull()?.value_
            ?: return@op null
        val metaBytes = q.selectSession(SESSION_KEY_METADATA).awaitAsOneOrNull()?.value_

        val myInfo: MyNodeInfo = try {
            MyNodeInfo.ADAPTER.decode(myInfoBytes)
        } catch (_: Exception) {
            return@op null
        }
        val metadata: DeviceMetadata = metaBytes?.let {
            try {
                DeviceMetadata.ADAPTER.decode(it)
            } catch (_: Exception) {
                null
            }
        } ?: DeviceMetadata.Builder().build()

        val allConfigs = q.selectAllConfigs().awaitAsList()
        val configs: List<Config> = allConfigs
            .filter { it.section.startsWith("config:") }
            .mapNotNull { row ->
                try {
                    Config.ADAPTER.decode(row.payload)
                } catch (_: Exception) {
                    null
                }
            }

        val moduleConfigs: List<ModuleConfig> = allConfigs
            .filter { it.section.startsWith("module:") }
            .mapNotNull { row ->
                try {
                    ModuleConfig.ADAPTER.decode(row.payload)
                } catch (_: Exception) {
                    null
                }
            }

        ConfigBundle(
            myInfo = myInfo,
            metadata = metadata,
            configs = configs,
            moduleConfigs = moduleConfigs,
        )
    }

    override suspend fun saveConfig(config: ConfigBundle) = op {
        db.transaction {
            q.upsertSession(SESSION_KEY_MY_INFO, MyNodeInfo.ADAPTER.encode(config.myInfo))
            q.upsertSession(SESSION_KEY_METADATA, DeviceMetadata.ADAPTER.encode(config.metadata))
            q.deleteAllConfigs()
            config.configs.forEachIndexed { i, c ->
                val section = "config:$i"
                q.upsertConfig(section, Config.ADAPTER.encode(c))
            }
            config.moduleConfigs.forEachIndexed { i, m ->
                val section = "module:$i"
                q.upsertConfig(section, ModuleConfig.ADAPTER.encode(m))
            }
        }
    }

    // ── Channels ──────────────────────────────────────────────────────────────

    override suspend fun loadChannels(): List<Channel> = op {
        q.selectAllChannels().awaitAsList().mapNotNull { row ->
            row.settings_raw?.let { bytes ->
                try {
                    Channel.ADAPTER.decode(bytes)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    override suspend fun saveChannels(channels: List<Channel>) = op {
        db.transaction {
            q.deleteAllChannels()
            channels.forEachIndexed { idx, channel ->
                q.upsertChannel(
                    idx = idx.toLong(),
                    role = channel.role.value.toLong(),
                    name = channel.settings?.name,
                    psk = channel.settings?.psk?.toByteArray(),
                    psk_index = null,
                    uplink = if (channel.settings?.uplink_enabled == true) 1L else 0L,
                    downlink = if (channel.settings?.downlink_enabled == true) 1L else 0L,
                    settings_raw = Channel.ADAPTER.encode(channel),
                )
            }
        }
    }

    // ── Identity / factory-reset detection ───────────────────────────────────

    override suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String) = op {
        db.transaction {
            val storedNumBytes = q.selectSession(SESSION_KEY_NODE_NUM).awaitAsOneOrNull()?.value_
            if (storedNumBytes != null) {
                val stored = storedNumBytes.decodeToString().toIntOrNull()
                if (stored != null && stored != nodeNum.raw) {
                    q.clearAll().await() // grouped statement: returns a lazy AsyncValue under generateAsync
                }
            }
            q.upsertSession(SESSION_KEY_NODE_NUM, nodeNum.raw.toString().encodeToByteArray())
            q.upsertSession(SESSION_KEY_FIRMWARE, firmwareVersion.encodeToByteArray())
            q.selectNode(nodeNum.raw.toLong()).awaitAsOneOrNull()?.let {
                q.upsertNode(
                    node_num = it.node_num,
                    user_id = it.user_id,
                    long_name = it.long_name,
                    short_name = it.short_name,
                    hw_model = it.hw_model,
                    is_licensed = it.is_licensed,
                    role = it.role,
                    public_key = it.public_key,
                    last_heard_epoch = it.last_heard_epoch,
                    snr = it.snr,
                    rssi = it.rssi,
                    hops_away = it.hops_away,
                    via_mqtt = it.via_mqtt,
                    is_self = 1L,
                    raw_node_info = it.raw_node_info,
                )
            }
        }
    }

    // ── Session passkey (R-P0-4) ──────────────────────────────────────────────

    override suspend fun saveSessionPasskey(passkey: SessionPasskey) = op {
        db.transaction {
            q.upsertSession(SESSION_KEY_PASSKEY, passkey.bytes.toByteArray())
            q.upsertSession(
                SESSION_KEY_PASSKEY_EXPIRES_MS,
                passkey.expiresAtEpochMs.toString().encodeToByteArray(),
            )
        }
    }

    override suspend fun loadSessionPasskey(): SessionPasskey? = op {
        val bytes = q.selectSession(SESSION_KEY_PASSKEY).awaitAsOneOrNull()?.value_
            ?: return@op null
        val expiresBytes = q.selectSession(SESSION_KEY_PASSKEY_EXPIRES_MS).awaitAsOneOrNull()?.value_
            ?: return@op null
        val expiresAtMs = expiresBytes.decodeToString().toLongOrNull() ?: return@op null
        val now = Clock.System.now().toEpochMilliseconds()
        if (expiresAtMs <= now) {
            db.transaction {
                q.deleteSession(SESSION_KEY_PASSKEY)
                q.deleteSession(SESSION_KEY_PASSKEY_EXPIRES_MS)
            }
            return@op null
        }
        SessionPasskey(bytes.toByteString(), expiresAtMs)
    }

    // ── Heartbeat persistence (P1-6) ──────────────────────────────────────────

    override suspend fun saveHeartbeat(nodeId: NodeId, epochMillis: Long): Unit = op {
        q.upsertHeartbeat(node_num = nodeId.raw.toLong(), last_heartbeat_at = epochMillis).await()
    }

    override suspend fun loadHeartbeats(): Map<NodeId, Long> = op {
        q.selectAllHeartbeats().awaitAsList().associate { row ->
            NodeId(row.node_num.toInt()) to row.last_heartbeat_at
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override suspend fun clear(): Unit = op {
        q.clearAll().await() // grouped statement: returns a lazy AsyncValue under generateAsync
    }

    override fun close() {
        driver.close()
    }

    private companion object {
        const val SESSION_KEY_MY_INFO = "my_info"
        const val SESSION_KEY_METADATA = "metadata"
        const val SESSION_KEY_NODE_NUM = "identity_node_num"
        const val SESSION_KEY_FIRMWARE = "firmware_version"
        const val SESSION_KEY_PASSKEY = "session_passkey"
        const val SESSION_KEY_PASSKEY_EXPIRES_MS = "session_passkey_expires_ms"
    }
}

/**
 * Dispatcher for blocking SQLite work: a fixed pool of [STORAGE_PARALLELISM] threads, sized to
 * the small per-driver connection pool, so storage cannot starve transports or file writes.
 * A pool rather than `Dispatchers.IO.limitedParallelism(n)`: the SDK compiles against
 * coroutines 1.8.1, where that overload does not exist. Declared `expect` because the pool
 * constructors are per platform.
 */
internal expect val defaultStorageDispatcher: CoroutineDispatcher

internal const val STORAGE_PARALLELISM: Int = 4
