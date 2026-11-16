/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

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

internal fun decodeIndex(lines: Sequence<String>): StorageIndex {
    val identityMap = mutableMapOf<String, String>()
    val nodeMap = mutableMapOf<Int, String>()
    for (line in lines) {
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
    return StorageIndex(identityMap, nodeMap)
}

internal fun encodeIndex(index: StorageIndex): String = buildString {
    append(SqlDelightStorageProvider.INDEX_HEADER).append('\n')
    for ((id, db) in index.identityToDbId) append("$PREFIX_IDENTITY$SEP$id$SEP$db\n")
    for ((node, db) in index.nodeNumToDbId) append("$PREFIX_NODE_NUM$SEP$node$SEP$db\n")
}

/** Creates the storage root if the platform has one (a directory on jvm/android/apple). */
internal expect fun ensureStorageDir(baseDir: String)

/** Reads the identity/node index for [baseDir]; empty if none was written yet. */
internal expect fun loadIndex(baseDir: String): StorageIndex

/** Persists [index] for [baseDir], replacing the previous one atomically where possible. */
internal expect fun storeIndex(baseDir: String, index: StorageIndex)
