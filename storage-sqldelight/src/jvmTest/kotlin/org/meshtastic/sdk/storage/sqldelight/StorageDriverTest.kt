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
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun nodeInfo(id: NodeId) = org.meshtastic.proto.NodeInfo(num = id.raw)

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
}
