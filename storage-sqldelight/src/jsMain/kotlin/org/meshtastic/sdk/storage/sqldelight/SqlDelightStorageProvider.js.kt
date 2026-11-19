/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.w3c.dom.Worker
import org.w3c.dom.WorkerOptions
import org.w3c.dom.WorkerType

/**
 * Creates a browser provider whose databases run in workers made by [createWorker].
 *
 * [createWorker] receives the database file name (`":memory:"` for in-memory mode) and returns a
 * worker that speaks the SQLDelight web-worker protocol, such as one started from the
 * `sqlite-opfs.worker.mjs` script shipped with this module:
 *
 * ```kotlin
 * SqlDelightStorageProvider("meshtastic") { dbFile ->
 *     Worker("/sqlite-opfs.worker.mjs?db=$dbFile", WorkerOptions(type = "module".unsafeCast<WorkerType>()))
 * }
 * ```
 *
 * The index that maps transports and radios to database files is kept in `localStorage`.
 *
 * @param baseDir key prefix for the index, or `""` for in-memory mode
 * @param createWorker starts the worker for one database file
 */
public fun SqlDelightStorageProvider(
    baseDir: String,
    createWorker: (dbFile: String) -> Worker,
): SqlDelightStorageProvider = SqlDelightStorageProvider(
    baseDir = baseDir,
    openDriver = { dbPath: String -> WebWorkerDriver(createWorker(dbFileName(dbPath))) },
)

internal actual fun createDriver(dbPath: String): SqlDriver {
    val dbFile = js("encodeURIComponent")(dbFileName(dbPath))
    return WebWorkerDriver(Worker("sqlite-opfs.worker.mjs?db=$dbFile", WorkerOptions(type = MODULE_WORKER)))
}

private fun dbFileName(dbPath: String) = if (dbPath == ":memory:") dbPath else dbPath.substringAfterLast('/')

private val MODULE_WORKER: WorkerType = "module".unsafeCast<WorkerType>()

internal actual val defaultStorageDispatcher: CoroutineDispatcher = Dispatchers.Default

internal actual fun ensureStorageDir(baseDir: String) = Unit

internal actual fun loadIndex(baseDir: String): StorageIndex =
    localStorage.getItem(indexKey(baseDir))?.let { decodeIndex(it.lineSequence()) } ?: StorageIndex()

internal actual fun storeIndex(baseDir: String, index: StorageIndex) =
    localStorage.setItem(indexKey(baseDir), encodeIndex(index))

private fun indexKey(baseDir: String) = "$baseDir/${SqlDelightStorageProvider.INDEX_FILENAME}"
