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
import org.meshtastic.sdk.storage.sqldelight.internal.MeshDatabase
import java.util.Properties

internal actual fun createDriver(dbPath: String): SqlDriver {
    val fileBacked = dbPath != ":memory:"
    val url = if (fileBacked) "jdbc:sqlite:$dbPath" else JdbcSqliteDriver.IN_MEMORY
    // xerial sqlite-jdbc applies these PRAGMAs on every new connection (ThreadLocal pool),
    // which is required because per-connection PRAGMAs issued post-ctor only affect the
    // creating thread. See ADR-014.
    val props = Properties().apply {
        setProperty("synchronous", "NORMAL")
        setProperty("foreign_keys", "true")
        if (fileBacked) setProperty("journal_mode", "WAL")
    }
    if (fileBacked) stampUnversionedDatabase(url, props)
    // The schema-aware factory creates a fresh database at the current version and runs the
    // `.sqm` migrations (tracked in `PRAGMA user_version`) on an older one.
    return JdbcSqliteDriver(url, props, MeshDatabase.Schema)
}

/**
 * Highest schema version a JVM database can have without `user_version` being set.
 *
 * Until the JVM driver was built with the schema, it created the tables with
 * `Schema.create` and left `user_version` at 0, so those files were never migrated. Every
 * database the driver creates now carries its version, so this value never changes when a new
 * migration is added.
 */
internal const val LAST_UNVERSIONED_SCHEMA_VERSION = 3L

/**
 * Gives an unversioned JVM database (tables present, `user_version` 0) the version its tables
 * actually match, so the schema-aware driver migrates it from there instead of trying to
 * create tables that already exist. A new, empty file is left alone.
 */
private fun stampUnversionedDatabase(url: String, props: Properties) {
    val driver = JdbcSqliteDriver(url, props)
    try {
        if (driver.userVersion() != 0L || !driver.hasTable("session")) return
        val version = inferUnversionedSchemaVersion(driver)
        driver.execute(null, "PRAGMA user_version = $version", 0)
    } finally {
        driver.close()
    }
}

/**
 * Infers the schema version of an unversioned database from its shape: v1 still has the
 * `messages` table (dropped by `1.sqm`), v2 lacks `nodes.last_heartbeat_at` (added by
 * `2.sqm`), anything else is [LAST_UNVERSIONED_SCHEMA_VERSION].
 */
internal fun inferUnversionedSchemaVersion(driver: SqlDriver): Long = when {
    driver.hasTable("messages") -> 1L
    "last_heartbeat_at" !in driver.columnNames("nodes") -> 2L
    else -> LAST_UNVERSIONED_SCHEMA_VERSION
}

internal fun SqlDriver.userVersion(): Long = executeQuery(
    identifier = null,
    sql = "PRAGMA user_version",
    mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
    parameters = 0,
).value

private fun SqlDriver.hasTable(name: String): Boolean = executeQuery(
    identifier = null,
    sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
    mapper = { cursor -> QueryResult.Value(cursor.next().value) },
    parameters = 1,
) { bindString(0, name) }.value

private fun SqlDriver.columnNames(table: String): Set<String> = executeQuery(
    identifier = null,
    sql = "PRAGMA table_info($table)",
    mapper = { cursor ->
        val names = mutableSetOf<String>()
        while (cursor.next().value) cursor.getString(1)?.let(names::add)
        QueryResult.Value(names)
    },
    parameters = 0,
).value
