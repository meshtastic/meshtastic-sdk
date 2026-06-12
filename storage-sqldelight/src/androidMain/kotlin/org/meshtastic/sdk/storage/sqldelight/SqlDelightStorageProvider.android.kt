/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.meshtastic.sdk.storage.sqldelight.internal.MeshDatabase

internal actual fun createDriver(dbPath: String): SqlDriver {
    val ctx = AndroidContextHolder.context
        ?: error("SqlDelightStorageProvider.init(context) must be called before activate()")
    val fileBacked = dbPath != ":memory:"
    val name = if (fileBacked) dbPath.substringAfterLast('/') else null
    // AndroidSqliteDriver.Callback.onConfigure is invoked per-connection by the framework
    // connection pool. Applying PRAGMAs here ensures every connection honours ADR-014,
    // unlike a one-shot execute() which only affects the creating thread's connection.
    val callback = object : AndroidSqliteDriver.Callback(MeshDatabase.Schema) {
        override fun onConfigure(db: SupportSQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
            // WAL must go through the framework API: `PRAGMA journal_mode=WAL` returns a
            // result row, which Android's execSQL rejects ("Queries can be performed using
            // SQLiteDatabase query or rawQuery methods only") once the mode is already set.
            if (fileBacked) db.enableWriteAheadLogging()
            db.execSQL("PRAGMA synchronous=NORMAL;")
        }
    }
    return AndroidSqliteDriver(MeshDatabase.Schema, ctx, name, callback = callback)
}

/**
 * Application-level context holder for Android driver creation.
 *
 * **Lifecycle:** pass only the `Application` context (e.g. `app.applicationContext` from
 * your [android.app.Application] subclass). Passing an Activity or Service context will
 * pin it for the lifetime of the SDK and leak memory when that component is destroyed.
 * As a defence-in-depth measure, assignment coerces the provided [Context] via
 * [Context.getApplicationContext].
 */
public object AndroidContextHolder {
    @Volatile
    public var context: Context? = null
        set(value) {
            field = value?.applicationContext ?: value
        }
}
