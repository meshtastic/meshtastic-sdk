/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
    return JdbcSqliteDriver(url, props)
}
