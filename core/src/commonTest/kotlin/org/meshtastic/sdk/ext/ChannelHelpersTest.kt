/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelHelpersTest {
    @Test
    fun validChannelValidates() {
        val result = ChannelHelpers.validate(
            name = "LongFast",
            psk = ByteArray(ChannelHelpers.MIN_PSK_LENGTH) { 0x42 },
        )

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun nameTooLongFailsValidation() {
        val result = ChannelHelpers.validate(
            name = "123456789012",
            psk = byteArrayOf(0x01),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("exceeds") })
    }

    @Test
    fun invalidPskLengthsFailValidation() {
        val result = ChannelHelpers.validate(
            name = "mesh",
            psk = ByteArray(8),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("PSK") })
    }

    @Test
    fun findEmptySlotUsesFirstDisabledOrMissingSecondarySlot() {
        val channels = listOf(
            Channel.Builder().also { wb ->
                wb.index = 0
                wb.role = Channel.Role.PRIMARY
            }.build(),
            Channel.Builder().also { wb ->
                wb.index = 1
                wb.role = Channel.Role.SECONDARY
            }.build(),
            Channel.Builder().also { wb ->
                wb.index = 2
                wb.role = Channel.Role.DISABLED
            }.build(),
        )

        assertEquals(2, ChannelHelpers.findEmptySlot(channels))
        assertEquals(2, ChannelHelpers.findEmptySlot(channels.take(2)))
    }

    @Test
    fun findEmptySlotReturnsNullWhenFull() {
        val channels = listOf(
            Channel.Builder().also { wb ->
                wb.index = 0
                wb.role = Channel.Role.PRIMARY
            }.build(),
            Channel.Builder().also { wb ->
                wb.index = 1
                wb.role = Channel.Role.SECONDARY
            }.build(),
            Channel.Builder().also { wb ->
                wb.index = 2
                wb.role = Channel.Role.SECONDARY
            }.build(),
            Channel.Builder().also { wb ->
                wb.index = 3
                wb.role = Channel.Role.SECONDARY
            }.build(),
            Channel.Builder().also { wb ->
                wb.index = 4
                wb.role = Channel.Role.SECONDARY
            }.build(),
            Channel.Builder().also { wb ->
                wb.index = 5
                wb.role = Channel.Role.SECONDARY
            }.build(),
            Channel.Builder().also { wb ->
                wb.index = 6
                wb.role = Channel.Role.SECONDARY
            }.build(),
            Channel.Builder().also { wb ->
                wb.index = 7
                wb.role = Channel.Role.SECONDARY
            }.build(),
        )

        assertNull(ChannelHelpers.findEmptySlot(channels))
    }

    private fun lora(preset: Config.LoRaConfig.ModemPreset, usePreset: Boolean = true) =
        Config.LoRaConfig.Builder().also { wb ->
            wb.use_preset = usePreset
            wb.modem_preset = preset
        }.build()

    private fun channel(name: String) = Channel.Builder().also { wb ->
        wb.index = 0
        wb.role = Channel.Role.PRIMARY
        wb.settings = ChannelSettings.Builder().also { wb -> wb.name = name }.build()
    }.build()

    @Test
    fun displayNameKeepsAChannelsOwnName() {
        assertEquals("Team", ChannelHelpers.displayName(channel("Team"), lora(Config.LoRaConfig.ModemPreset.LONG_FAST)))
    }

    @Test
    fun unnamedChannelShowsTheFirmwarePresetName() {
        val unnamed = channel("")
        assertEquals("LongFast", ChannelHelpers.displayName(unnamed, lora(Config.LoRaConfig.ModemPreset.LONG_FAST)))
        assertEquals("LongMod", ChannelHelpers.displayName(unnamed, lora(Config.LoRaConfig.ModemPreset.LONG_MODERATE)))
        assertEquals("MediumFast", ChannelHelpers.displayName(unnamed, lora(Config.LoRaConfig.ModemPreset.MEDIUM_FAST)))
    }

    @Test
    fun unnamedChannelOffPresetIsCustom() {
        val name = ChannelHelpers.displayName(
            channel(""),
            lora(Config.LoRaConfig.ModemPreset.SHORT_FAST, usePreset = false),
        )
        assertEquals(ChannelHelpers.CUSTOM_PRESET_NAME, name)
    }

    @Test
    fun channelWithoutSettingsUsesThePreset() {
        val bare = Channel.Builder().also { wb -> wb.index = 0 }.build()
        assertEquals("ShortTurbo", ChannelHelpers.displayName(bare, lora(Config.LoRaConfig.ModemPreset.SHORT_TURBO)))
    }

    @Test
    fun presetNamesMatchTheFirmware() {
        // firmware src/DisplayFormatters.cpp getModemPresetDisplayName(preset, useShortName = false)
        val expected = mapOf(
            Config.LoRaConfig.ModemPreset.SHORT_TURBO to "ShortTurbo",
            Config.LoRaConfig.ModemPreset.SHORT_FAST to "ShortFast",
            Config.LoRaConfig.ModemPreset.SHORT_SLOW to "ShortSlow",
            Config.LoRaConfig.ModemPreset.MEDIUM_TURBO to "MediumTurbo",
            Config.LoRaConfig.ModemPreset.MEDIUM_FAST to "MediumFast",
            Config.LoRaConfig.ModemPreset.MEDIUM_SLOW to "MediumSlow",
            Config.LoRaConfig.ModemPreset.LONG_TURBO to "LongTurbo",
            Config.LoRaConfig.ModemPreset.LONG_FAST to "LongFast",
            Config.LoRaConfig.ModemPreset.LONG_MODERATE to "LongMod",
            Config.LoRaConfig.ModemPreset.LONG_SLOW to "LongSlow",
            Config.LoRaConfig.ModemPreset.VERY_LONG_SLOW to "VLongSlow",
            Config.LoRaConfig.ModemPreset.LITE_FAST to "LiteFast",
            Config.LoRaConfig.ModemPreset.LITE_SLOW to "LiteSlow",
            Config.LoRaConfig.ModemPreset.NARROW_FAST to "NarrowFast",
            Config.LoRaConfig.ModemPreset.NARROW_SLOW to "NarrowSlow",
            Config.LoRaConfig.ModemPreset.TINY_FAST to "TinyFast",
            Config.LoRaConfig.ModemPreset.TINY_SLOW to "TinySlow",
        )
        assertEquals(Config.LoRaConfig.ModemPreset.entries.toSet(), expected.keys)
        expected.forEach { (preset, name) -> assertEquals(name, ChannelHelpers.presetDisplayName(preset), "$preset") }
    }

    @Test
    fun defaultLongFastChannelHashesToEightWithTheDisplayName() {
        // The well-known hash of the default LongFast channel: "LongFast" xor the expanded default key.
        val defaultKey = byteArrayOf(
            0xd4.toByte(), 0xf1.toByte(), 0xbb.toByte(), 0x3a, 0x20, 0x29, 0x07, 0x59,
            0xf0.toByte(), 0xbc.toByte(), 0xff.toByte(), 0xab.toByte(), 0xcf.toByte(), 0x4e, 0x69, 0x01,
        )
        val name = ChannelHelpers.displayName(channel(""), lora(Config.LoRaConfig.ModemPreset.LONG_FAST))
        assertEquals(8, ChannelSettings.hash(name, defaultKey))
    }
}
