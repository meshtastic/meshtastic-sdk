/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal.sfpp

/**
 * AES block encryption (FIPS-197) in common code, so every target, web included, decrypts the same way.
 *
 * Only the forward cipher exists: CTR and CCM never run the inverse. Not constant-time; it decrypts
 * channel traffic whose key every channel member already holds.
 */
internal class Aes(key: ByteArray) {
    private val rounds: Int
    private val roundKeys: IntArray

    init {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES key must be 16, 24 or 32 bytes" }
        val nk = key.size / 4
        rounds = nk + 6
        val words = IntArray(4 * (rounds + 1))
        for (i in 0 until nk) {
            words[i] = (key[4 * i].u() shl 24) or (key[4 * i + 1].u() shl 16) or
                (key[4 * i + 2].u() shl 8) or key[4 * i + 3].u()
        }
        var rcon = 1
        for (i in nk until words.size) {
            var t = words[i - 1]
            if (i % nk == 0) {
                t = subWord((t shl 8) or (t ushr 24)) xor (rcon shl 24)
                rcon = xtime(rcon)
            } else if (nk > 6 && i % nk == 4) {
                t = subWord(t)
            }
            words[i] = words[i - nk] xor t
        }
        roundKeys = words
    }

    /** Encrypts one 16-byte block of [input] at [offset] into a new array. */
    fun encryptBlock(input: ByteArray, offset: Int = 0): ByteArray {
        val s = IntArray(BLOCK) { input[offset + it].u() }
        addRoundKey(s, 0)
        for (round in 1 until rounds) {
            subBytes(s)
            shiftRows(s)
            mixColumns(s)
            addRoundKey(s, round)
        }
        subBytes(s)
        shiftRows(s)
        addRoundKey(s, rounds)
        return ByteArray(BLOCK) { s[it].toByte() }
    }

    private fun addRoundKey(s: IntArray, round: Int) {
        for (c in 0 until 4) {
            val w = roundKeys[round * 4 + c]
            s[4 * c] = s[4 * c] xor (w ushr 24)
            s[4 * c + 1] = s[4 * c + 1] xor ((w ushr 16) and 0xFF)
            s[4 * c + 2] = s[4 * c + 2] xor ((w ushr 8) and 0xFF)
            s[4 * c + 3] = s[4 * c + 3] xor (w and 0xFF)
        }
    }

    private fun subBytes(s: IntArray) {
        for (i in s.indices) s[i] = SBOX[s[i]].u()
    }

    // State is column-major: s[4 * column + row]. Row r rotates left by r columns.
    private fun shiftRows(s: IntArray) {
        val t = s.copyOf()
        for (c in 0 until 4) {
            for (r in 1 until 4) s[4 * c + r] = t[4 * ((c + r) % 4) + r]
        }
    }

    private fun mixColumns(s: IntArray) {
        for (c in 0 until 4) {
            val a0 = s[4 * c]
            val a1 = s[4 * c + 1]
            val a2 = s[4 * c + 2]
            val a3 = s[4 * c + 3]
            s[4 * c] = xtime(a0) xor (xtime(a1) xor a1) xor a2 xor a3
            s[4 * c + 1] = a0 xor xtime(a1) xor (xtime(a2) xor a2) xor a3
            s[4 * c + 2] = a0 xor a1 xor xtime(a2) xor (xtime(a3) xor a3)
            s[4 * c + 3] = (xtime(a0) xor a0) xor a1 xor a2 xor xtime(a3)
        }
    }

    internal companion object {
        const val BLOCK: Int = 16

        private fun Byte.u(): Int = toInt() and 0xFF

        private fun xtime(b: Int): Int = ((b shl 1) xor (if (b and 0x80 != 0) 0x1B else 0)) and 0xFF

        private fun subWord(w: Int): Int = (SBOX[w ushr 24].u() shl 24) or
            (SBOX[(w ushr 16) and 0xFF].u() shl 16) or
            (SBOX[(w ushr 8) and 0xFF].u() shl 8) or
            SBOX[w and 0xFF].u()

        private val SBOX: ByteArray = intArrayOf(
            0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
            0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
            0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
            0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
            0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
            0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
            0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
            0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
            0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
            0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
            0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
            0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
            0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
            0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
            0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
            0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
        ).let { table -> ByteArray(table.size) { table[it].toByte() } }
    }
}
