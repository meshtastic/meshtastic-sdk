/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config

/**
 * Channel validation and helper utilities.
 */
public object ChannelHelpers {
    /** Maximum allowed channel name length. */
    public const val MAX_NAME_LENGTH: Int = 11

    /** Minimum PSK length for secure channels (128-bit AES). */
    public const val MIN_PSK_LENGTH: Int = 16

    /** Maximum PSK length (256-bit AES). */
    public const val MAX_PSK_LENGTH: Int = 32

    /**
     * Validation result for channel configuration.
     */
    public data class ValidationResult(val isValid: Boolean, val errors: List<String> = emptyList())

    /**
     * Validates channel metadata before it is sent to the device.
     *
     * Checks name length, blank-only names, and supported PSK lengths.
     * @since 0.1.0
     */
    public fun validate(name: String, psk: ByteArray, role: Channel.Role = Channel.Role.SECONDARY): ValidationResult {
        val errors = mutableListOf<String>()
        if (name.length > MAX_NAME_LENGTH) {
            errors += "Channel name exceeds $MAX_NAME_LENGTH characters"
        }
        if (name.isNotEmpty() && name.isBlank()) {
            errors += "Channel name cannot be only whitespace"
        }
        val validPskLengths = setOf(0, 1, MIN_PSK_LENGTH, MAX_PSK_LENGTH)
        if (psk.size !in validPskLengths) {
            errors += "PSK must be 0 (none), 1 (default), 16 (AES-128), or 32 (AES-256) bytes"
        }
        if (role == Channel.Role.PRIMARY && name.isNotEmpty()) {
            // Primary channel typically uses empty name (firmware convention).
        }
        return ValidationResult(isValid = errors.isEmpty(), errors = errors)
    }

    /**
     * Finds the first writable channel slot after the primary channel.
     *
     * Returns `null` when every slot up to [maxChannels] is already occupied.
     * @since 0.1.0
     */
    public fun findEmptySlot(channels: List<Channel>, maxChannels: Int = 8): Int? {
        for (i in 1 until maxChannels) {
            val channel = channels.getOrNull(i)
            if (channel == null || channel.role == Channel.Role.DISABLED) return i
        }
        return null
    }

    /**
     * Creates a [ChannelSettings] only when [name] and [psk] pass [validate].
     *
     * Returns `null` instead of throwing when validation fails.
     * @since 0.1.0
     */
    public fun createSettings(name: String, psk: ByteArray = byteArrayOf(0x01)): ChannelSettings? {
        val validation = validate(name, psk)
        if (!validation.isValid) return null
        return ChannelSettings.Builder().also { wb ->
            wb.name = name
            wb.psk = psk.toByteString()
        }.build()
    }

    /**
     * The name the firmware and the official apps show for [channel]: its own name, or for an
     * unnamed channel (the default primary) the modem preset's name, e.g. `"LongFast"`, or
     * `"Custom"` when [loRa] doesn't use a preset.
     *
     * Mirrors firmware `Channels::getName`. The result is interop-critical, not cosmetic: the
     * firmware feeds this exact string into the channel hash, the default frequency slot and the
     * MQTT topic, so pass it (not the raw empty `settings.name`) to [ChannelSettings.Companion.hash].
     *
     * @param loRa the radio's LoRa config, e.g. from `configBundle.configs`
     * @since 0.3.0
     */
    public fun displayName(channel: Channel, loRa: Config.LoRaConfig): String =
        channel.settings?.name?.takeIf { it.isNotEmpty() }
            ?: if (loRa.use_preset) presetDisplayName(loRa.modem_preset) else CUSTOM_PRESET_NAME

    /**
     * The firmware's long display name for [preset] (`DisplayFormatters::getModemPresetDisplayName`),
     * e.g. `"LongFast"`. Not derived from the enum name: `LONG_MODERATE` is `"LongMod"`, and the
     * retired `VERY_LONG_SLOW` keeps the `"VLongSlow"` older firmware used.
     *
     * The `when` is exhaustive on purpose, so a protobufs bump that adds a preset fails to compile
     * until its firmware name is copied here.
     *
     * @since 0.3.0
     */
    public fun presetDisplayName(preset: Config.LoRaConfig.ModemPreset): String = when (preset) {
        Config.LoRaConfig.ModemPreset.SHORT_TURBO -> "ShortTurbo"
        Config.LoRaConfig.ModemPreset.SHORT_FAST -> "ShortFast"
        Config.LoRaConfig.ModemPreset.SHORT_SLOW -> "ShortSlow"
        Config.LoRaConfig.ModemPreset.MEDIUM_TURBO -> "MediumTurbo"
        Config.LoRaConfig.ModemPreset.MEDIUM_FAST -> "MediumFast"
        Config.LoRaConfig.ModemPreset.MEDIUM_SLOW -> "MediumSlow"
        Config.LoRaConfig.ModemPreset.LONG_TURBO -> "LongTurbo"
        Config.LoRaConfig.ModemPreset.LONG_FAST -> "LongFast"
        Config.LoRaConfig.ModemPreset.LONG_MODERATE -> "LongMod"
        Config.LoRaConfig.ModemPreset.LONG_SLOW -> "LongSlow"
        Config.LoRaConfig.ModemPreset.VERY_LONG_SLOW -> "VLongSlow"
        Config.LoRaConfig.ModemPreset.LITE_FAST -> "LiteFast"
        Config.LoRaConfig.ModemPreset.LITE_SLOW -> "LiteSlow"
        Config.LoRaConfig.ModemPreset.NARROW_FAST -> "NarrowFast"
        Config.LoRaConfig.ModemPreset.NARROW_SLOW -> "NarrowSlow"
        Config.LoRaConfig.ModemPreset.TINY_FAST -> "TinyFast"
        Config.LoRaConfig.ModemPreset.TINY_SLOW -> "TinySlow"
    }

    /** Name of an unnamed channel when the radio isn't on a modem preset (firmware `"Custom"`). */
    public const val CUSTOM_PRESET_NAME: String = "Custom"
}
