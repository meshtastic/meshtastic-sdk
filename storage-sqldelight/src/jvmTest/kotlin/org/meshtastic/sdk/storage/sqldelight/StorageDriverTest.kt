/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.test.runTest
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.storage.sqldelight.internal.MeshDatabase
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StorageDriverTest {

    @Test
    fun `file-backed driver applies WAL + NORMAL synchronous + foreign_keys`() = runTest {
        val dir = Files.createTempDirectory("mesh-pragma-test").absolutePathString()
        val path = "$dir/pragma.db"
        val driver = createDriver(path)
        try {
            // Allocate the schema so the DB file is written and WAL header flushed.
            SqlDelightStorage(driver)
            assertEquals("wal", scalarString(driver, "PRAGMA journal_mode"))
            // synchronous=NORMAL → 1
            assertEquals("1", scalarString(driver, "PRAGMA synchronous"))
            assertEquals("1", scalarString(driver, "PRAGMA foreign_keys"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun `removeNode is O(1) and only affects target row`() = runTest {
        val driver = createDriver(":memory:")
        val storage = SqlDelightStorage(driver)

        val a = NodeId(0x1001)
        val b = NodeId(0x1002)
        storage.saveNode(nodeInfo(a))
        storage.saveNode(nodeInfo(b))

        storage.removeNode(a)

        val loaded = storage.loadNodes()
        assertNull(loaded[a])
        assertEquals(b, loaded[b]?.let { NodeId(it.num) })
        driver.close()
    }

    @Test
    fun `heartbeat and clear round-trip through grouped statements`() = runTest {
        val driver = createDriver(":memory:")
        val storage = SqlDelightStorage(driver)
        storage.saveHeartbeat(NodeId(7), 1234L)
        assertEquals(mapOf(NodeId(7) to 1234L), storage.loadHeartbeats())
        storage.clear()
        assertEquals(emptyMap(), storage.loadHeartbeats())
        driver.close()
    }

    @Test
    fun `v1 database is migrated to the current schema on first use`() = runTest {
        val driver = createDriver(":memory:")
        V1_DDL.forEach { driver.execute(null, it, 0).value }
        driver.execute(null, "PRAGMA user_version = 1", 0).value
        driver.execute(null, "INSERT INTO nodes(node_num) VALUES (77)", 0).value
        val storage = SqlDelightStorage(driver)
        assertEquals(setOf(NodeId(77)), storage.loadNodes().keys)
        storage.saveHeartbeat(NodeId(77), 5L) // needs 3.sqm's last_heartbeat_at column
        assertEquals(mapOf(NodeId(77) to 5L), storage.loadHeartbeats())
        assertEquals("3", scalarString(driver, "PRAGMA user_version"))
        assertEquals("0", scalarString(driver, "SELECT count(*) FROM sqlite_master WHERE name='messages'"))
        driver.close()
    }

    @Test
    fun `a migration cut off before its version stamp runs again on the next open`() = runTest {
        val path = Files.createTempDirectory("interrupted-migration").resolve("mesh.db").absolutePathString()
        val setup = createDriver(path)
        V1_DDL.forEach { setup.execute(null, it, 0).value }
        setup.execute(null, "PRAGMA user_version = 1", 0).value
        setup.execute(null, "INSERT INTO nodes(node_num) VALUES (77)", 0).value
        setup.close()

        val real = createDriver(path)
        val cutOff = object : SqlDriver by real {
            override fun execute(
                identifier: Int?,
                sql: String,
                parameters: Int,
                binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
            ): QueryResult<Long> = if (sql.startsWith(
                    "PRAGMA user_version =",
                )
            ) {
                error("reload")
            } else {
                real.execute(identifier, sql, parameters, binders)
            }
        }
        assertFailsWith<IllegalStateException> { SqlDelightStorage(cutOff).loadNodes() }
        real.close()

        val reopened = createDriver(path)
        val storage = SqlDelightStorage(reopened)
        storage.saveHeartbeat(NodeId(77), 5L) // 2.sqm's column, added once
        assertEquals(mapOf(NodeId(77) to 5L), storage.loadHeartbeats())
        assertEquals("3", scalarString(reopened, "PRAGMA user_version"))
        reopened.close()
    }

    @Test
    fun `unstamped database created by main is stamped with the current version`() = runTest {
        // main's JVM path ran Schema.create without ever setting user_version.
        val driver = createDriver(":memory:")
        MeshDatabase.Schema.create(driver).await()
        driver.execute(null, "INSERT INTO nodes(node_num) VALUES (77)", 0).value
        assertEquals("0", scalarString(driver, "PRAGMA user_version"))
        val storage = SqlDelightStorage(driver)
        assertEquals(setOf(NodeId(77)), storage.loadNodes().keys)
        assertEquals("3", scalarString(driver, "PRAGMA user_version"))
        driver.close()
    }

    @Test
    fun `unstamped v1 database is detected by its schema and migrated`() = runTest {
        val driver = createDriver(":memory:")
        V1_DDL.forEach { driver.execute(null, it, 0).value }
        driver.execute(null, "INSERT INTO nodes(node_num) VALUES (77)", 0).value
        val storage = SqlDelightStorage(driver)
        storage.saveHeartbeat(NodeId(77), 5L) // needs 3.sqm's last_heartbeat_at column
        assertEquals(mapOf(NodeId(77) to 5L), storage.loadHeartbeats())
        assertEquals("3", scalarString(driver, "PRAGMA user_version"))
        assertEquals("0", scalarString(driver, "SELECT count(*) FROM sqlite_master WHERE name='messages'"))
        driver.close()
    }

    private fun nodeInfo(id: NodeId) = org.meshtastic.proto.NodeInfo.Builder().also { wb ->
        wb.num = id.raw
    }.build()

    private fun scalarString(driver: SqlDriver, sql: String): String {
        var result: String? = null
        driver.executeQuery<Unit>(
            identifier = null,
            sql = sql,
            mapper = { cursor: SqlCursor ->
                cursor.next()
                result = cursor.getString(0) ?: cursor.getLong(0)?.toString()
                QueryResult.Unit
            },
            parameters = 0,
        )
        return result ?: error("no value for $sql")
    }

    private companion object {
        // Schema v1 as dumped from src/commonMain/sqldelight/databases/1.db (`sqlite3 1.db .schema`).
        val V1_DDL = listOf(
            "CREATE TABLE nodes (node_num INTEGER PRIMARY KEY NOT NULL, user_id TEXT, long_name TEXT, " +
                "short_name TEXT, hw_model INTEGER, is_licensed INTEGER NOT NULL DEFAULT 0, role INTEGER, " +
                "public_key BLOB, last_heard_epoch INTEGER, snr REAL, rssi INTEGER, hops_away INTEGER, " +
                "via_mqtt INTEGER NOT NULL DEFAULT 0, is_self INTEGER NOT NULL DEFAULT 0, raw_node_info BLOB)",
            "CREATE TABLE channels (idx INTEGER PRIMARY KEY NOT NULL, role INTEGER NOT NULL, name TEXT, psk BLOB, " +
                "psk_index INTEGER, uplink INTEGER NOT NULL DEFAULT 0, downlink INTEGER NOT NULL DEFAULT 0, settings_raw BLOB)",
            "CREATE TABLE configs (section TEXT PRIMARY KEY NOT NULL, payload BLOB NOT NULL)",
            "CREATE TABLE messages (packet_id INTEGER PRIMARY KEY NOT NULL, from_num INTEGER NOT NULL, " +
                "to_num INTEGER NOT NULL, channel_idx INTEGER NOT NULL, text TEXT NOT NULL, " +
                "sent_epoch INTEGER NOT NULL, " +
                "rx_epoch INTEGER, send_state TEXT NOT NULL, failure_reason TEXT)",
            "CREATE TABLE session (key TEXT PRIMARY KEY NOT NULL, value BLOB)",
            "CREATE INDEX nodes_last_heard_idx ON nodes(last_heard_epoch)",
        )
    }
}
