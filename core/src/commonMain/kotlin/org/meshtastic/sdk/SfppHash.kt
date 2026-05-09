/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString.Companion.toByteString

/**
 * Computes Store-Forward-Plus-Plus (SFPP) message hashes for deduplication.
 *
 * The hash is SHA-256(payload || to_LE32 || from_LE32 || id_LE32) truncated to 16 bytes.
 */
public object SfppHash {
    private const val HASH_LENGTH: Int = 16

    /**
     * Compute the SFPP deduplication hash for a message.
     *
     * @param payload the encrypted message payload
     * @param to destination node number (little-endian)
     * @param from source node number (little-endian)
     * @param id packet ID (little-endian)
     * @return 16-byte truncated SHA-256 hash
     */
    public fun compute(payload: ByteArray, to: Int, from: Int, id: Int): ByteArray {
        val input = ByteArray(payload.size + 12)
        payload.copyInto(input)
        var offset = payload.size
        for (value in intArrayOf(to, from, id)) {
            input[offset++] = value.toByte()
            input[offset++] = (value shr 8).toByte()
            input[offset++] = (value shr 16).toByte()
            input[offset++] = (value shr 24).toByte()
        }
        return input.toByteString().sha256().toByteArray().copyOf(HASH_LENGTH)
    }
}
