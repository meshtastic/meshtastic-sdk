/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.storage.sqldelight.internal.MeshDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JVM databases carry `user_version`, and databases created before that get migrated. */
class SchemaVersionTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("mesh-schema-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `fresh file database starts at the current schema version`() = runTest {
        val path = File(dir, "fresh.db").absolutePath
        val driver = createDriver(path)
        try {
            SqlDelightStorage(driver).saveNode(node(1))
            assertEquals(MeshDatabase.Schema.version, driver.userVersion())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `in-memory database starts at the current schema version`() = runTest {
        val driver = createDriver(":memory:")
        try {
            SqlDelightStorage(driver).saveHeartbeat(NodeId(1), 5L)
            assertEquals(MeshDatabase.Schema.version, driver.userVersion())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `unversioned database with the current tables opens and is stamped`() = runTest {
        val path = File(dir, "legacy-current.db").absolutePath
        // How the JVM driver used to set up a file: Schema.create, user_version left at 0.
        JdbcSqliteDriver("jdbc:sqlite:$path").useDriver { legacy ->
            MeshDatabase.Schema.create(legacy)
            assertEquals(0L, legacy.userVersion())
        }
        // A row written by the old code must survive the reopen.
        JdbcSqliteDriver("jdbc:sqlite:$path").useDriver { legacy ->
            SqlDelightStorage(legacy).saveNode(node(7))
        }

        val driver = createDriver(path)
        try {
            val storage = SqlDelightStorage(driver)
            assertEquals(MeshDatabase.Schema.version, driver.userVersion())
            assertTrue(NodeId(7) in storage.loadNodes())
            storage.saveHeartbeat(NodeId(7), 42L)
            assertEquals(42L, storage.loadHeartbeats()[NodeId(7)])
        } finally {
            driver.close()
        }
    }

    @Test
    fun `unversioned v1 database is migrated to the current schema`() = runTest {
        // The committed v1 snapshot has the `messages` table, no heartbeat column and
        // user_version 0, which is what a JVM database created by the first release looks like.
        val snapshot = File("src/commonMain/sqldelight/databases/1.db")
        val path = File(dir, "legacy-v1.db")
        snapshot.copyTo(path)
        JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}").useDriver { legacy ->
            assertEquals(0L, legacy.userVersion())
            assertEquals(1L, inferUnversionedSchemaVersion(legacy))
        }

        val driver = createDriver(path.absolutePath)
        try {
            val storage = SqlDelightStorage(driver)
            assertEquals(MeshDatabase.Schema.version, driver.userVersion())
            assertFalse(driver.hasTableNamed("messages"))
            storage.saveNode(node(3))
            storage.saveHeartbeat(NodeId(3), 9L)
            assertEquals(9L, storage.loadHeartbeats()[NodeId(3)])
        } finally {
            driver.close()
        }
    }

    @Test
    fun `reopening a versioned database keeps its data and version`() = runTest {
        val path = File(dir, "reopen.db").absolutePath
        createDriver(path).useDriver { SqlDelightStorage(it).saveNode(node(5)) }
        createDriver(path).useDriver { driver ->
            assertEquals(MeshDatabase.Schema.version, driver.userVersion())
            assertTrue(NodeId(5) in SqlDelightStorage(driver).loadNodes())
        }
    }

    private fun node(num: Int): NodeInfo = NodeInfo.Builder().also { it.num = num }.build()

    private inline fun <T> SqlDriver.useDriver(block: (SqlDriver) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    private fun SqlDriver.hasTableNamed(name: String): Boolean = executeQuery(
        identifier = null,
        sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$name'",
        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
        parameters = 0,
    ).value
}
