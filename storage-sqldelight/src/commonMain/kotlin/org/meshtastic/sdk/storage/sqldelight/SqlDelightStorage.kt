/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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

    private val db = MeshDatabase(driver).also {
        // Create schema idempotently: the `sqlite_master` check avoids `table … already exists`
        // when reopening a previously-initialised database file (node-keyed DBs are reopened on
        // every reconnect).
        val alreadyInitialised = driver
            .executeQuery(
                identifier = null,
                sql = "SELECT name FROM sqlite_master WHERE type='table' AND name='session' LIMIT 1",
                mapper = { cursor -> app.cash.sqldelight.db.QueryResult.Value(cursor.next().value) },
                parameters = 0,
            )
            .value
        if (!alreadyInitialised) {
            MeshDatabase.Schema.create(driver)
        }
    }
    private val q get() = db.meshQueries

    // ── Nodes ─────────────────────────────────────────────────────────────────

    override suspend fun loadNodes(): Map<NodeId, NodeInfo> = withContext(dispatcher) {
        val rows = q.selectAllNodes().executeAsList()
        buildMap {
            for (row in rows) {
                val nodeId = NodeId(row.node_num.toInt())
                val nodeInfo = row.raw_node_info?.let { bytes ->
                    try {
                        NodeInfo.ADAPTER.decode(bytes)
                    } catch (_: Exception) {
                        null
                    }
                } ?: NodeInfo(num = row.node_num.toInt())
                put(nodeId, nodeInfo)
            }
        }
    }

    override suspend fun saveNode(node: NodeInfo): Unit = withContext(dispatcher) {
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

    override suspend fun removeNode(nodeId: NodeId): Unit = withContext(dispatcher) {
        q.deleteNode(nodeId.raw.toLong())
    }

    // ── Config ────────────────────────────────────────────────────────────────

    override suspend fun loadConfig(): ConfigBundle? = withContext(dispatcher) {
        val myInfoBytes = q.selectSession(SESSION_KEY_MY_INFO).executeAsOneOrNull()?.value_
            ?: return@withContext null
        val metaBytes = q.selectSession(SESSION_KEY_METADATA).executeAsOneOrNull()?.value_

        val myInfo: MyNodeInfo = try {
            MyNodeInfo.ADAPTER.decode(myInfoBytes)
        } catch (_: Exception) {
            return@withContext null
        }
        val metadata: DeviceMetadata = metaBytes?.let {
            try {
                DeviceMetadata.ADAPTER.decode(it)
            } catch (_: Exception) {
                null
            }
        } ?: DeviceMetadata()

        val allConfigs = q.selectAllConfigs().executeAsList()
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

    override suspend fun saveConfig(config: ConfigBundle) = withContext(dispatcher) {
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

    override suspend fun loadChannels(): List<Channel> = withContext(dispatcher) {
        q.selectAllChannels().executeAsList().mapNotNull { row ->
            row.settings_raw?.let { bytes ->
                try {
                    Channel.ADAPTER.decode(bytes)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    override suspend fun saveChannels(channels: List<Channel>) = withContext(dispatcher) {
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

    override suspend fun recordOwnNode(nodeNum: NodeId, firmwareVersion: String) = withContext(dispatcher) {
        db.transaction {
            val storedNumBytes = q.selectSession(SESSION_KEY_NODE_NUM).executeAsOneOrNull()?.value_
            if (storedNumBytes != null) {
                val stored = storedNumBytes.decodeToString().toIntOrNull()
                if (stored != null && stored != nodeNum.raw) {
                    q.clearAll()
                }
            }
            q.upsertSession(SESSION_KEY_NODE_NUM, nodeNum.raw.toString().encodeToByteArray())
            q.upsertSession(SESSION_KEY_FIRMWARE, firmwareVersion.encodeToByteArray())
            q.selectNode(nodeNum.raw.toLong()).executeAsOneOrNull()?.let {
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

    override suspend fun saveSessionPasskey(passkey: SessionPasskey) = withContext(dispatcher) {
        db.transaction {
            q.upsertSession(SESSION_KEY_PASSKEY, passkey.bytes.toByteArray())
            q.upsertSession(
                SESSION_KEY_PASSKEY_EXPIRES_MS,
                passkey.expiresAtEpochMs.toString().encodeToByteArray(),
            )
        }
    }

    override suspend fun loadSessionPasskey(): SessionPasskey? = withContext(dispatcher) {
        val bytes = q.selectSession(SESSION_KEY_PASSKEY).executeAsOneOrNull()?.value_
            ?: return@withContext null
        val expiresBytes = q.selectSession(SESSION_KEY_PASSKEY_EXPIRES_MS).executeAsOneOrNull()?.value_
            ?: return@withContext null
        val expiresAtMs = expiresBytes.decodeToString().toLongOrNull() ?: return@withContext null
        val now = Clock.System.now().toEpochMilliseconds()
        if (expiresAtMs <= now) {
            db.transaction {
                q.deleteSession(SESSION_KEY_PASSKEY)
                q.deleteSession(SESSION_KEY_PASSKEY_EXPIRES_MS)
            }
            return@withContext null
        }
        SessionPasskey(kotlinx.io.bytestring.ByteString(bytes), expiresAtMs)
    }

    // ── Heartbeat persistence (P1-6) ──────────────────────────────────────────

    override suspend fun saveHeartbeat(nodeId: NodeId, epochMillis: Long): Unit = withContext(dispatcher) {
        q.upsertHeartbeat(node_num = nodeId.raw.toLong(), last_heartbeat_at = epochMillis)
    }

    override suspend fun loadHeartbeats(): Map<NodeId, Long> = withContext(dispatcher) {
        q.selectAllHeartbeats().executeAsList().associate { row ->
            NodeId(row.node_num.toInt()) to row.last_heartbeat_at
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override suspend fun clear(): Unit = withContext(dispatcher) {
        q.clearAll()
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
 * Shared view of `Dispatchers.IO` (JVM/Android/native) for SQLite work. `limitedParallelism`
 * (stable since coroutines 1.9) caps concurrent blocking DB calls so storage can't starve other
 * IO consumers (transports, file writes). The value roughly matches the small per-driver
 * connection pool. Declared `expect` because `Dispatchers.IO` is not visible from commonMain.
 */
internal expect val defaultStorageDispatcher: CoroutineDispatcher
