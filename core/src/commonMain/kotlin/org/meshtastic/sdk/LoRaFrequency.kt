/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Config.LoRaConfig
import org.meshtastic.proto.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.Config.LoRaConfig.RegionCode
import kotlin.math.roundToInt

/**
 * LoRa modulation parameters of a modem preset.
 *
 * @property bandwidthKHz channel bandwidth in kHz.
 * @property spreadFactor LoRa spreading factor, 5 through 12.
 * @property codingRate coding rate denominator: 5 through 8 for 4/5 through 4/8.
 * @since 0.3.0
 */
public data class ModemParams(public val bandwidthKHz: Float, public val spreadFactor: Int, public val codingRate: Int)

/**
 * Returns the bandwidth, spreading factor and coding rate firmware uses for this preset (`modemPresetToParams`).
 *
 * [wideLora] selects the wide-region bandwidths (2.4 GHz); the LITE, NARROW and TINY presets have one bandwidth
 * for both. A preset firmware has no case for, such as `VERY_LONG_SLOW`, gets the `LONG_FAST` parameters.
 * @since 0.3.0
 */
public fun ModemPreset.modemParams(wideLora: Boolean = false): ModemParams = when (this) {
    ModemPreset.SHORT_TURBO -> ModemParams(if (wideLora) 1625.0f else 500.0f, 7, 5)
    ModemPreset.SHORT_FAST -> ModemParams(if (wideLora) 812.5f else 250.0f, 7, 5)
    ModemPreset.SHORT_SLOW -> ModemParams(if (wideLora) 812.5f else 250.0f, 8, 5)
    ModemPreset.MEDIUM_FAST -> ModemParams(if (wideLora) 812.5f else 250.0f, 9, 5)
    ModemPreset.MEDIUM_SLOW -> ModemParams(if (wideLora) 812.5f else 250.0f, 10, 5)
    ModemPreset.MEDIUM_TURBO -> ModemParams(if (wideLora) 1625.0f else 500.0f, 9, 5)
    ModemPreset.LONG_TURBO -> ModemParams(if (wideLora) 1625.0f else 500.0f, 11, 8)
    ModemPreset.LONG_MODERATE -> ModemParams(if (wideLora) 406.25f else 125.0f, 11, 8)
    ModemPreset.LONG_SLOW -> ModemParams(if (wideLora) 406.25f else 125.0f, 12, 8)
    ModemPreset.LITE_FAST -> ModemParams(125.0f, 9, 5)
    ModemPreset.LITE_SLOW -> ModemParams(125.0f, 10, 5)
    ModemPreset.NARROW_FAST -> ModemParams(62.5f, 7, 6)
    ModemPreset.NARROW_SLOW -> ModemParams(62.5f, 8, 6)
    ModemPreset.TINY_FAST -> ModemParams(15.6f, 7, 5)
    ModemPreset.TINY_SLOW -> ModemParams(15.6f, 8, 6)
    else -> ModemParams(if (wideLora) 812.5f else 250.0f, 11, 5)
}

/**
 * The frequency slot count, default slot and center frequency firmware derives from a LoRa configuration.
 *
 * Mirrors `RadioInterface::frequencySlotCount`, `resolveFrequencySlot` and `applyModemConfig`. All arithmetic is
 * single-precision, as on the device.
 * @since 0.3.0
 */
public object LoRaFrequency {
    private const val DEFAULT_BANDWIDTH_KHZ = 250.0f
    private const val CUSTOM_NAME = "Custom"

    /**
     * Converts a `LoRaConfig.bandwidth` code to kHz (`bwCodeToKHz`): the small codes name fractional bandwidths
     * such as `62` for 62.5 kHz, any other code is the bandwidth in kHz.
     * @since 0.3.0
     */
    public fun bandwidthKHzForCode(code: Int): Float = when (code) {
        8 -> 7.8f
        10 -> 10.4f
        16 -> 15.6f
        21 -> 20.8f
        31 -> 31.25f
        42 -> 41.7f
        62 -> 62.5f
        200 -> 203.125f
        400 -> 406.25f
        800 -> 812.5f
        1600 -> 1625.0f
        else -> code.toFloat()
    }

    /**
     * Width of one frequency slot in MHz: the region's spacing, both paddings and the modem bandwidth
     * ([bandwidthKHz], in kHz).
     * @since 0.3.0
     */
    public fun slotWidthMHz(region: LoRaRegionInfo, bandwidthKHz: Float): Float =
        region.spacingMHz + region.paddingMHz * 2 + bandwidthKHz / 1000

    /**
     * Number of frequency slots in [region] at [bandwidthKHz], rounded half up as firmware does. Not limited to
     * at least one: a bandwidth far wider than the band rounds to 0. Returns 0 for [LoRaRegionInfo.region]
     * `UNSET`, which has no band.
     * @since 0.3.0
     */
    public fun numChannels(region: LoRaRegionInfo, bandwidthKHz: Float): Int {
        if (region.region == RegionCode.UNSET) return 0
        val span = region.freqEndMHz - region.freqStartMHz + region.spacingMHz
        return (span / slotWidthMHz(region, bandwidthKHz)).roundToInt()
    }

    /**
     * Picks the 1-based slot firmware uses: [channelNum] when it is within `1..numChannels`, else the region's
     * [LoRaRegionInfo.overrideSlot] when within range, else the djb2 hash of [channelName]'s UTF-8 bytes modulo
     * [numChannels], plus one. Returns `null` when [numChannels] is not positive.
     *
     * [channelName] is hashed as given; use [hashedChannelName] to resolve a blank name.
     * @since 0.3.0
     */
    public fun resolveSlot(region: LoRaRegionInfo, numChannels: Int, channelName: String, channelNum: Int = 0): Int? {
        if (numChannels <= 0) return null
        if (channelNum in 1..numChannels) return channelNum
        if (region.overrideSlot in 1..numChannels) return region.overrideSlot
        return (djb2(channelName) % numChannels.toUInt()).toInt() + 1
    }

    /**
     * The name firmware hashes for the primary channel (`Channels::getName`): [channelName] itself, or when blank
     * the preset's display name such as `LongFast`, or `Custom` when [usePreset] is false.
     * @since 0.3.0
     */
    public fun hashedChannelName(channelName: String, usePreset: Boolean, preset: ModemPreset): String = when {
        channelName.isNotEmpty() -> channelName
        usePreset -> presetDisplayName(preset)
        else -> CUSTOM_NAME
    }

    /**
     * Center frequency in MHz of 1-based [slot]: band start, half a bandwidth, padding, then [slot] minus one slot
     * widths, plus [frequencyOffsetMHz].
     * @since 0.3.0
     */
    public fun centerFrequencyMHz(
        region: LoRaRegionInfo,
        bandwidthKHz: Float,
        slot: Int,
        frequencyOffsetMHz: Float = 0f,
    ): Float {
        val width = slotWidthMHz(region, bandwidthKHz)
        val freq = region.freqStartMHz + bandwidthKHz / 2000 + region.paddingMHz + (slot - 1).toFloat() * width
        return freq + frequencyOffsetMHz
    }

    /**
     * Resolves the band, bandwidth, slot count, slot and frequency of [config] for a primary channel named
     * [primaryChannelName] (blank for the default channel).
     *
     * With `use_preset` false the bandwidth comes from `bandwidth`, falling back to 250 kHz for 0; one wider than
     * the region's band is replaced by the region default preset's bandwidth. A preset the region does not offer
     * is not substituted. See [LoRaFrequencyPlan] for when slot and frequency are `null`.
     * @since 0.3.0
     */
    public fun plan(config: LoRaConfig, primaryChannelName: String): LoRaFrequencyPlan {
        val region = LoRaRegionInfo.forRegion(config.region)
        var bandwidth = if (config.use_preset) {
            config.modem_preset.modemParams(region.wideLora).bandwidthKHz
        } else {
            bandwidthKHzForCode(config.bandwidth).let { if (it <= 0f) DEFAULT_BANDWIDTH_KHZ else it }
        }
        if (!config.use_preset) {
            val edges = region.spacingMHz + region.paddingMHz * 2
            if (region.freqEndMHz - region.freqStartMHz < edges + bandwidth / 1000) {
                bandwidth = bandwidthKHzForCode(
                    bandwidthCodeForKHz(region.defaultPreset.modemParams(region.wideLora).bandwidthKHz),
                )
            }
        }
        val count = numChannels(region, bandwidth)
        if (config.override_frequency != 0f) {
            return LoRaFrequencyPlan(
                region,
                bandwidth,
                count,
                null,
                config.override_frequency + config.frequency_offset,
            )
        }
        val name = hashedChannelName(primaryChannelName, config.use_preset, config.modem_preset)
        val slot = resolveSlot(region, count, name, config.channel_num)
        val frequency = slot?.let { centerFrequencyMHz(region, bandwidth, it, config.frequency_offset) }
        return LoRaFrequencyPlan(region, bandwidth, count, slot, frequency)
    }

    private fun bandwidthCodeForKHz(khz: Float): Int = when {
        khz > 7.7f && khz < 7.9f -> 8
        khz > 10.3f && khz < 10.5f -> 10
        khz > 15.5f && khz < 15.7f -> 16
        khz > 20.7f && khz < 20.9f -> 21
        khz > 31.24f && khz < 31.26f -> 31
        khz > 41.6f && khz < 41.8f -> 42
        khz > 62.49f && khz < 62.51f -> 62
        khz > 203.12f && khz < 203.13f -> 200
        khz > 406.24f && khz < 406.26f -> 400
        khz > 812.49f && khz < 812.51f -> 800
        khz > 1624.99f && khz < 1625.01f -> 1600
        else -> (khz + 0.5f).toInt()
    }

    private fun presetDisplayName(preset: ModemPreset): String = when (preset) {
        ModemPreset.SHORT_TURBO -> "ShortTurbo"
        ModemPreset.SHORT_SLOW -> "ShortSlow"
        ModemPreset.SHORT_FAST -> "ShortFast"
        ModemPreset.MEDIUM_SLOW -> "MediumSlow"
        ModemPreset.MEDIUM_FAST -> "MediumFast"
        ModemPreset.MEDIUM_TURBO -> "MediumTurbo"
        ModemPreset.LONG_SLOW -> "LongSlow"
        ModemPreset.LONG_FAST -> "LongFast"
        ModemPreset.LONG_TURBO -> "LongTurbo"
        ModemPreset.LONG_MODERATE -> "LongMod"
        ModemPreset.LITE_FAST -> "LiteFast"
        ModemPreset.LITE_SLOW -> "LiteSlow"
        ModemPreset.NARROW_FAST -> "NarrowFast"
        ModemPreset.NARROW_SLOW -> "NarrowSlow"
        ModemPreset.TINY_FAST -> "TinyFast"
        ModemPreset.TINY_SLOW -> "TinySlow"
        else -> "Invalid"
    }

    private fun djb2(name: String): UInt {
        var hash = 5381u
        for (b in name.encodeToByteArray()) {
            if (b.toInt() == 0) break
            hash = hash * 33u + b.toUByte().toUInt()
        }
        return hash
    }
}

/**
 * The frequency a [LoRaConfig] resolves to, from [LoRaFrequency.plan].
 *
 * @property region the region row in use; the `UNSET` row for an unset or unknown region.
 * @property bandwidthKHz modem bandwidth in kHz after firmware's preset and band-width rules.
 * @property numChannels number of frequency slots in the band; 0 for `UNSET`.
 * @property slot the 1-based slot in use, or `null` when `override_frequency` is set or there are no slots.
 * @property frequencyMHz center frequency in MHz including `frequency_offset`: `override_frequency` plus the offset
 *   when set, `null` when there is no slot.
 * @since 0.3.0
 */
public data class LoRaFrequencyPlan(
    public val region: LoRaRegionInfo,
    public val bandwidthKHz: Float,
    public val numChannels: Int,
    public val slot: Int?,
    public val frequencyMHz: Float?,
)
