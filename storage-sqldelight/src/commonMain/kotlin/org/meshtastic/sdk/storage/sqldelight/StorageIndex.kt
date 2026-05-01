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
import okio.SYSTEM

/**
 * Bindings persisted in `<baseDir>/.meshtastic-index`.
 *
 * Two independent maps are stored so the provider can resolve a DB id from either:
 *  - a transport identity (alias-fast-path on reconnect), or
 *  - a NodeNum (cross-transport sharing: same radio, different transport).
 *
 * File format — simple line-oriented text, tab-separated. One directive per line:
 *
 * ```
 * # Meshtastic storage identity/node index (v1)
 * i<TAB>ble:AA:BB:CC:DD:EE:FF<TAB>deadbeefcafe…
 * i<TAB>tcp:192.168.1.180:4403<TAB>deadbeefcafe…
 * n<TAB>305419896<TAB>deadbeefcafe…
 * ```
 *
 * Unknown-prefix lines and blank/comment lines are ignored to allow future schema additions.
 */
internal data class StorageIndex(
    val identityToDbId: Map<String, String> = emptyMap(),
    val nodeNumToDbId: Map<Int, String> = emptyMap(),
)

private const val PREFIX_IDENTITY = "i"
private const val PREFIX_NODE_NUM = "n"
private const val SEP = '\t'

internal fun readIndex(path: Path): StorageIndex {
    val fs = FileSystem.SYSTEM
    if (!fs.exists(path)) return StorageIndex()
    val identityMap = mutableMapOf<String, String>()
    val nodeMap = mutableMapOf<Int, String>()
    fs.read(path) {
        while (true) {
            val line = readUtf8Line() ?: break
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split(SEP)
            if (parts.size < 3) continue
            when (parts[0]) {
                PREFIX_IDENTITY -> identityMap[parts[1]] = parts[2]

                PREFIX_NODE_NUM -> {
                    val n = parts[1].toIntOrNull() ?: continue
                    nodeMap[n] = parts[2]
                }
            }
        }
    }
    return StorageIndex(identityMap, nodeMap)
}

internal fun writeIndexAtomically(path: Path, index: StorageIndex) {
    val fs = FileSystem.SYSTEM
    val tmp = path.parent!! / (path.name + ".tmp")
    fs.write(tmp) {
        writeUtf8(SqlDelightStorageProvider.INDEX_HEADER)
        writeUtf8("\n")
        for ((id, db) in index.identityToDbId) {
            writeUtf8("$PREFIX_IDENTITY$SEP$id$SEP$db\n")
        }
        for ((node, db) in index.nodeNumToDbId) {
            writeUtf8("$PREFIX_NODE_NUM$SEP$node$SEP$db\n")
        }
    }
    try {
        fs.atomicMove(tmp, path)
    } catch (_: UnsupportedOperationException) {
        // Platforms where atomicMove is unsupported (rare); fall back to best-effort.
        if (fs.exists(path)) fs.delete(path)
        fs.atomicMove(tmp, path)
    }
}
