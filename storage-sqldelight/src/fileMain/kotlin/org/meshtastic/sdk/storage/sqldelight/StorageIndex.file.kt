/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

internal actual fun ensureStorageDir(baseDir: String) {
    val dir = baseDir.toPath()
    if (!FileSystem.SYSTEM.exists(dir)) FileSystem.SYSTEM.createDirectories(dir)
}

internal actual fun loadIndex(baseDir: String): StorageIndex = readIndex(indexPath(baseDir))

internal actual fun storeIndex(baseDir: String, index: StorageIndex) = writeIndexAtomically(indexPath(baseDir), index)

private fun indexPath(baseDir: String) = "$baseDir/${SqlDelightStorageProvider.INDEX_FILENAME}".toPath()

internal fun readIndex(path: Path): StorageIndex {
    val fs = FileSystem.SYSTEM
    if (!fs.exists(path)) return StorageIndex()
    return fs.read(path) { decodeIndex(generateSequence { readUtf8Line() }) }
}

internal fun writeIndexAtomically(path: Path, index: StorageIndex) {
    val fs = FileSystem.SYSTEM
    val tmp = path.parent!! / (path.name + ".tmp")
    fs.write(tmp) { writeUtf8(encodeIndex(index)) }
    try {
        fs.atomicMove(tmp, path)
    } catch (_: UnsupportedOperationException) {
        // Platforms where atomicMove is unsupported (rare); fall back to best-effort.
        if (fs.exists(path)) fs.delete(path)
        fs.atomicMove(tmp, path)
    }
}
