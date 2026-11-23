/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal.sfpp

/**
 * Channel decryption as firmware's `CryptoEngine` does it: AES-CTR for ordinary channels, AES-CCM for
 * channels with `use_aead`.
 *
 * Used only for SF++ replays, which reach the client still encrypted with the channel key. Live
 * traffic is decrypted on the device.
 */
internal object ChannelCipher {
    const val CCM_TAG_BYTES: Int = 12
    private const val CCM_NONCE_BYTES = 13
    private const val CCM_LENGTH_BYTES = 2
    private const val CCM_MAX_AAD_IN_ONE_BLOCK = Aes.BLOCK - 2

    private val DEFAULT_PSK = intArrayOf(
        0xd4, 0xf1, 0xbb, 0x3a, 0x20, 0x29, 0x07, 0x59, 0xf0, 0xbc, 0xff, 0xab, 0xcf, 0x4e, 0x69, 0x01,
    ).let { k -> ByteArray(k.size) { k[it].toByte() } }

    /**
     * The AES key a channel PSK stands for, per firmware `Channels::getKey`: empty means the channel is
     * unencrypted. Short keys are zero-padded to 16 or 32 bytes, as firmware does.
     */
    fun expandKey(psk: ByteArray): ByteArray = when {
        psk.isEmpty() -> ByteArray(0)
        psk.size == 1 && psk[0].toInt() == 0 -> ByteArray(0)
        psk.size == 1 -> DEFAULT_PSK.copyOf().also { it[15] = (it[15] + psk[0] - 1).toByte() }
        psk.size <= 16 -> psk.copyOf(16)
        else -> psk.copyOf(32)
    }

    /** AES-CTR as a channel packet uses it; encrypting and decrypting are the same. An empty [key] returns [data]. */
    fun ctr(key: ByteArray, from: Int, id: Int, data: ByteArray): ByteArray =
        if (key.isEmpty()) data.copyOf() else ctrWithCounter(key, nonce(from, id), data)

    /** AES-CTR from an explicit 16-byte initial counter block. */
    fun ctrWithCounter(key: ByteArray, initialCounter: ByteArray, data: ByteArray): ByteArray {
        val aes = Aes(key)
        val counter = initialCounter.copyOf()
        val out = ByteArray(data.size)
        var offset = 0
        while (offset < data.size) {
            val stream = aes.encryptBlock(counter)
            val n = minOf(Aes.BLOCK, data.size - offset)
            for (i in 0 until n) out[offset + i] = (data[offset + i].toInt() xor stream[i].toInt()).toByte()
            offset += n
            increment(counter)
        }
        return out
    }

    /**
     * AES-CCM decrypt of a channel packet with its 12-byte tag appended, per firmware `decryptPacketCCM`:
     * the nonce is the first 13 bytes of the CTR nonce and the AAD is `from ‖ to`, little-endian.
     * Returns null when the tag does not verify or the key is empty.
     */
    fun ccm(key: ByteArray, from: Int, to: Int, id: Int, ciphertextAndTag: ByteArray): ByteArray? {
        if (key.isEmpty()) return null
        val aad = ByteArray(8).also {
            putLe32(it, 0, from)
            putLe32(it, 4, to)
        }
        return ccmOpen(key, nonce(from, id).copyOf(CCM_NONCE_BYTES), aad, CCM_TAG_BYTES, ciphertextAndTag)
    }

    /** RFC 3610 CCM decrypt with L = 2, as firmware `aes_ccm_ad` runs it. Null when the tag does not verify. */
    fun ccmOpen(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        tagLength: Int,
        ciphertextAndTag: ByteArray,
    ): ByteArray? {
        require(nonce.size == CCM_NONCE_BYTES) { "CCM nonce must be 13 bytes" }
        require(aad.size <= CCM_MAX_AAD_IN_ONE_BLOCK) { "AAD longer than one block is not used here" }
        if (ciphertextAndTag.size <= tagLength) return null
        val aes = Aes(key)
        val length = ciphertextAndTag.size - tagLength

        val a = ByteArray(Aes.BLOCK)
        a[0] = (CCM_LENGTH_BYTES - 1).toByte()
        nonce.copyInto(a, 1)
        val plain = ByteArray(length)
        var offset = 0
        var counter = 1
        while (offset < length) {
            a[14] = (counter ushr 8).toByte()
            a[15] = counter.toByte()
            val stream = aes.encryptBlock(a)
            val chunk = minOf(Aes.BLOCK, length - offset)
            for (i in 0 until chunk) {
                plain[offset + i] = (ciphertextAndTag[offset + i].toInt() xor stream[i].toInt()).toByte()
            }
            offset += chunk
            counter++
        }

        val b0 = ByteArray(Aes.BLOCK)
        val adataFlag = if (aad.isEmpty()) 0 else 0x40
        b0[0] = (adataFlag or (((tagLength - 2) / 2) shl 3) or (CCM_LENGTH_BYTES - 1)).toByte()
        nonce.copyInto(b0, 1)
        b0[14] = (length ushr 8).toByte()
        b0[15] = length.toByte()
        var x = aes.encryptBlock(b0)
        if (aad.isNotEmpty()) {
            val aadBlock = ByteArray(Aes.BLOCK)
            aadBlock[0] = (aad.size ushr 8).toByte()
            aadBlock[1] = aad.size.toByte()
            aad.copyInto(aadBlock, 2)
            x = aes.encryptBlock(xorInto(x, aadBlock, Aes.BLOCK))
        }
        offset = 0
        while (offset < length) {
            val chunk = minOf(Aes.BLOCK, length - offset)
            x = aes.encryptBlock(xorInto(x, plain.copyOfRange(offset, offset + chunk), chunk))
            offset += chunk
        }

        a[14] = 0
        a[15] = 0
        val s0 = aes.encryptBlock(a)
        var diff = 0
        for (i in 0 until tagLength) {
            diff = diff or (x[i].toInt() xor s0[i].toInt() xor ciphertextAndTag[length + i].toInt())
        }
        return if (diff and 0xFF == 0) plain else null
    }

    // Firmware initNonce: packet id as uint64 little-endian, then from as uint32 little-endian, then zeros.
    private fun nonce(from: Int, id: Int): ByteArray = ByteArray(Aes.BLOCK).also {
        putLe32(it, 0, id)
        putLe32(it, 8, from)
    }

    // Firmware sets a 4-byte counter (CTR::setCounterSize(4)): only bytes 12..15 count, big-endian.
    private fun increment(counter: ByteArray) {
        for (i in 15 downTo 12) {
            counter[i] = (counter[i] + 1).toByte()
            if (counter[i].toInt() != 0) return
        }
    }

    private fun xorInto(x: ByteArray, block: ByteArray, len: Int): ByteArray {
        val out = x.copyOf()
        for (i in 0 until len) out[i] = (out[i].toInt() xor block[i].toInt()).toByte()
        return out
    }

    private fun putLe32(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = value.toByte()
        dst[offset + 1] = (value ushr 8).toByte()
        dst[offset + 2] = (value ushr 16).toByte()
        dst[offset + 3] = (value ushr 24).toByte()
    }
}
