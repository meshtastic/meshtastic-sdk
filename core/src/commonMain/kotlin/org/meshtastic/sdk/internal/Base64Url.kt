/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

private const val ALPHABET: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

internal fun base64UrlEncode(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val sb = StringBuilder((bytes.size * 4 + 2) / 3)
    var i = 0
    while (i + 2 < bytes.size) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = bytes[i + 1].toInt() and 0xff
        val b2 = bytes[i + 2].toInt() and 0xff
        sb.append(ALPHABET[b0 ushr 2])
        sb.append(ALPHABET[((b0 and 0x3) shl 4) or (b1 ushr 4)])
        sb.append(ALPHABET[((b1 and 0xf) shl 2) or (b2 ushr 6)])
        sb.append(ALPHABET[b2 and 0x3f])
        i += 3
    }
    val rem = bytes.size - i
    if (rem == 1) {
        val b0 = bytes[i].toInt() and 0xff
        sb.append(ALPHABET[b0 ushr 2])
        sb.append(ALPHABET[(b0 and 0x3) shl 4])
    } else if (rem == 2) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = bytes[i + 1].toInt() and 0xff
        sb.append(ALPHABET[b0 ushr 2])
        sb.append(ALPHABET[((b0 and 0x3) shl 4) or (b1 ushr 4)])
        sb.append(ALPHABET[(b1 and 0xf) shl 2])
    }
    return sb.toString()
}

internal fun base64UrlDecode(input: String): ByteArray? {
    val cleaned = input.trimEnd('=')
    val out = ArrayList<Byte>(cleaned.length * 3 / 4 + 2)
    var buffer = 0
    var bits = 0
    for (ch in cleaned) {
        val v = when (ch) {
            in 'A'..'Z' -> ch - 'A'
            in 'a'..'z' -> ch - 'a' + 26
            in '0'..'9' -> ch - '0' + 52
            '-' -> 62
            '_' -> 63
            else -> return null
        }
        buffer = (buffer shl 6) or v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add(((buffer ushr bits) and 0xff).toByte())
        }
    }
    return out.toByteArray()
}
