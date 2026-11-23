/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal.sfpp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class ChannelCipherTest {

    // SP 800-38A F.1 ECB examples, the vectors firmware test_crypto checks its own AES against.
    @Test
    fun aesEncryptsTheNistEcbExamples() {
        val aes128 = Aes(hex("2B7E151628AED2A6ABF7158809CF4F3C"))
        assertContentEquals(
            hex("3AD77BB40D7A3660A89ECAF32466EF97"),
            aes128.encryptBlock(hex("6BC1BEE22E409F96E93D7E117393172A")),
        )
        assertContentEquals(
            hex("F5D3D58503B9699DE785895A96FDBAAF"),
            aes128.encryptBlock(hex("AE2D8A571E03AC9C9EB76FAC45AF8E51")),
        )
        val aes256 = Aes(SfppVector.key256)
        assertContentEquals(
            hex("F3EED1BDB5D2A03C064B5A7E3DB181F8"),
            aes256.encryptBlock(hex("6BC1BEE22E409F96E93D7E117393172A")),
        )
        assertContentEquals(
            hex("B6ED21B99CA6F4F9F153E7B1BEAFED1D"),
            aes256.encryptBlock(hex("30C81C46A35CE411E5FBC1191A0A52EF")),
        )
    }

    // RFC 3686 section 6, vectors 1, 2 and 7; vector 2 spans two blocks, so it checks the counter step.
    @Test
    fun ctrMatchesRfc3686() {
        val single = "Single block msg".encodeToByteArray()
        assertContentEquals(
            hex("E4095D4FB7A7B3792D6175A3261311B8"),
            ChannelCipher.ctrWithCounter(
                hex("AE6852F8121067CC4BF7A5765577F39E"),
                hex("00000030000000000000000000000001"),
                single,
            ),
        )
        assertContentEquals(
            hex("5104A106168A72D9790D41EE8EDAD388EB2E1EFC46DA57C8FCE630DF9141BE28"),
            ChannelCipher.ctrWithCounter(
                hex("7E24067817FAE0D743D6CE1F32539163"),
                hex("006CB6DBC0543B59DA48D90B00000001"),
                ByteArray(32) { it.toByte() },
            ),
        )
        assertContentEquals(
            hex("145AD01DBF824EC7560863DC71E3E0C0"),
            ChannelCipher.ctrWithCounter(
                hex("776BEFF2851DB06F4C8A0542C8696F6C6A81AF1EEC96B4D37FC1D689E6C1C104"),
                hex("00000060DB5672C97AA8F0B200000001"),
                single,
            ),
        )
    }

    // RFC 3610 section 8, packet vectors 1, 2 and 7, as firmware test_AES_CCM_rfc3610 runs them.
    @Test
    fun ccmMatchesRfc3610AndRejectsAlteredAad() {
        val key = hex("C0C1C2C3C4C5C6C7C8C9CACBCCCDCECF")
        val aad = hex("0001020304050607")
        val cases = listOf(
            Triple(
                "00000003020100A0A1A2A3A4A5",
                "08090A0B0C0D0E0F101112131415161718191A1B1C1D1E",
                "588C979A61C663D2F066D0C2C0F989806D5F6B61DAC38417E8D12CFDF926E0",
            ),
            Triple(
                "00000004030201A0A1A2A3A4A5",
                "08090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F",
                "72C91A36E135F8CF291CA894085C87E3CC15C439C9E43A3BA091D56E10400916",
            ),
            Triple(
                "00000009080706A0A1A2A3A4A5",
                "08090A0B0C0D0E0F101112131415161718191A1B1C1D1E",
                "0135D1B2C95F41D5D1D4FEC185D166B8094E999DFED96C048C56602C97ACBB7490",
            ),
        )
        for ((nonce, plain, sealed) in cases) {
            val tagLength = hex(sealed).size - hex(plain).size
            assertContentEquals(hex(plain), ChannelCipher.ccmOpen(key, hex(nonce), aad, tagLength, hex(sealed)))
            val alteredAad = aad.copyOf().also { it[0] = 1 }
            assertNull(ChannelCipher.ccmOpen(key, hex(nonce), alteredAad, tagLength, hex(sealed)))
        }
    }

    @Test
    fun channelCtrUsesTheFirmwareNonceAndKeyExpansion() {
        val defaultKey = ChannelCipher.expandKey(byteArrayOf(1))
        assertContentEquals(
            SfppVector.data,
            ChannelCipher.ctr(defaultKey, SfppVector.FROM, SfppVector.ID, SfppVector.ctrDefault),
        )
        assertContentEquals(
            SfppVector.data,
            ChannelCipher.ctr(SfppVector.key256, SfppVector.FROM, SfppVector.ID, SfppVector.ctr256),
        )
    }

    @Test
    fun channelCcmOpensTheVectorAndRejectsTamperOrWrongDestination() {
        val key = ChannelCipher.expandKey(byteArrayOf(1))
        val broadcast = -1
        assertContentEquals(
            SfppVector.data,
            ChannelCipher.ccm(key, SfppVector.FROM, broadcast, SfppVector.ID, SfppVector.ccmDefault),
        )
        val tampered = SfppVector.ccmDefault.copyOf().also {
            it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte()
        }
        assertNull(ChannelCipher.ccm(key, SfppVector.FROM, broadcast, SfppVector.ID, tampered))
        assertNull(ChannelCipher.ccm(key, SfppVector.FROM, 0x12345678, SfppVector.ID, SfppVector.ccmDefault))
    }

    // Firmware Channels::getKey: index n sets the default key's last byte to 0x01 + n - 1; 0 disables; short keys pad.
    @Test
    fun shortPskExpandsAsFirmwareDoes() {
        val simple2 = ChannelCipher.expandKey(byteArrayOf(2))
        assertContentEquals(hex("d4f1bb3a20290759f0bcffabcf4e6902"), simple2)
        assertContentEquals(ByteArray(0), ChannelCipher.expandKey(byteArrayOf(0)))
        assertContentEquals(hex("01020300000000000000000000000000"), ChannelCipher.expandKey(hex("010203")))
        assertContentEquals(ByteArray(20) { 7 } + ByteArray(12), ChannelCipher.expandKey(ByteArray(20) { 7 }))
    }
}
