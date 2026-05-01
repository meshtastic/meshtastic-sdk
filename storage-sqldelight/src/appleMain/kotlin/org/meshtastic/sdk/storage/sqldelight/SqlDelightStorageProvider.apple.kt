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
import org.meshtastic.sdk.storage.sqldelight.internal.MeshDatabase

internal actual fun createDriver(dbPath: String): SqlDriver {
    // NativeSqliteDriver on iOS/macOS requires the name parameter to be non-null
    // even for in-memory databases (use any non-empty string for in-memory)
    val fileBacked = dbPath != ":memory:"
    val name = if (fileBacked) dbPath.substringAfterLast('/') else ":memory:"
    return NativeSqliteDriver(MeshDatabase.Schema, name).also { applyStoragePragmas(it, fileBacked) }
}
