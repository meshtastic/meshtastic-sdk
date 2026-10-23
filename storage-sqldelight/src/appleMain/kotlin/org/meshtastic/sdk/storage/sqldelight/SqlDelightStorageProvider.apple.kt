/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import org.meshtastic.sdk.storage.sqldelight.internal.MeshDatabase

internal actual fun createDriver(dbPath: String): SqlDriver {
    val fileBacked = dbPath != ":memory:"
    val driver = if (fileBacked) {
        // SQLiter takes a bare file name and opens it in its default databases directory
        // (under Application Support), not in `baseDir`; see docs/architecture/storage.md.
        NativeSqliteDriver(MeshDatabase.Schema, dbPath.substringAfterLast('/'))
    } else {
        // SQLiter only opens an in-memory database when its configuration has
        // `inMemory = true`. Passing ":memory:" as the name instead opens a file called
        // ":memory:" in the databases directory, shared by every activation and kept across
        // launches. `inMemoryDriver` sets the flag and uses a single connection.
        inMemoryDriver(MeshDatabase.Schema)
    }
    return driver.also { applyStoragePragmas(it, fileBacked) }
}
