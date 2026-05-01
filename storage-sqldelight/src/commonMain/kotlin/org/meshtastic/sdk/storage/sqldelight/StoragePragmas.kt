/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import app.cash.sqldelight.db.SqlDriver

/**
 * Applies the ADR-014 SQLite PRAGMAs to a freshly-created driver.
 *
 * Used by the Apple/native driver, which uses a single shared connection per driver instance.
 * JVM and Android drivers use connection pools and apply PRAGMAs via JDBC URL properties and
 * `AndroidSqliteDriver.Callback.onConfigure` respectively so every pooled connection is covered.
 *
 * - `journal_mode=WAL` → concurrent readers + writer; persisted in the file header, so
 *   this only needs to be set once on file-backed databases. Skipped for `:memory:`.
 * - `synchronous=NORMAL` → fsync on commit batches instead of every commit; acceptable
 *   durability trade-off for a messaging SDK (see ADR-014).
 * - `foreign_keys=ON` → FK checks default-off in SQLite; we have no FKs today, but turning
 *   this on is cheap, protects any future references, and mirrors Room's default.
 */
internal fun applyStoragePragmas(driver: SqlDriver, fileBacked: Boolean) {
    if (fileBacked) {
        driver.execute(null, "PRAGMA journal_mode=WAL;", 0)
    }
    driver.execute(null, "PRAGMA synchronous=NORMAL;", 0)
    driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
}
