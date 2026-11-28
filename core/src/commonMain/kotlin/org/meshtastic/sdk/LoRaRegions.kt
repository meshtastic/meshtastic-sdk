/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.Config.LoRaConfig.RegionCode

/**
 * One row of the firmware region table (`regions[]` in `RadioInterface.cpp`), with the region profile's
 * spacing, padding and flags folded in.
 *
 * Callers may build their own instance, for example from a region list received at runtime, and pass it to
 * [LoRaFrequency].
 *
 * @property region the region this row describes.
 * @property freqStartMHz lower band edge in MHz.
 * @property freqEndMHz upper band edge in MHz.
 * @property dutyCyclePercent duty cycle limit in percent of an hour; `100` means unrestricted.
 * @property powerLimitDbm maximum transmit power in dBm.
 * @property freqSwitching whether the region requires frequency hopping.
 * @property wideLora whether the region uses the wide (2.4 GHz) bandwidth set.
 * @property spacingMHz gap between frequency slots, and at the start of the band, in MHz.
 * @property paddingMHz gap at each side of a frequency slot in MHz.
 * @property audioPermitted whether the region profile permits the audio module.
 * @property licensedOnly whether the region is limited to licensed (amateur radio) operators.
 * @property defaultPreset the modem preset firmware picks when none is set or the current one is not legal here.
 * @property overrideSlot fixed default frequency slot (1-based), or `0` to derive the slot from the primary channel
 *   name.
 * @property unitChannelMHz unit-channel raster in MHz; when positive, bonds contiguous unit channels and overrides
 *   [paddingMHz].
 * @property bandSplitMHz optional sub-band boundary in MHz that slots never straddle (`0` when unsplit).
 * @since 0.3.0
 */
public data class LoRaRegionInfo(
    public val region: RegionCode,
    public val freqStartMHz: Float,
    public val freqEndMHz: Float,
    public val dutyCyclePercent: Float,
    public val powerLimitDbm: Int,
    public val freqSwitching: Boolean,
    public val wideLora: Boolean,
    public val spacingMHz: Float,
    public val paddingMHz: Float,
    public val audioPermitted: Boolean,
    public val licensedOnly: Boolean,
    public val defaultPreset: ModemPreset,
    public val overrideSlot: Int,
    public val unitChannelMHz: Float = 0f,
    public val bandSplitMHz: Float = 0f,
) {
    public companion object {
        /**
         * Every row of the firmware table in firmware order, ending with [RegionCode.UNSET].
         * @since 0.3.0
         */
        public val all: List<LoRaRegionInfo> get() = RegionTable

        /**
         * Returns the row for [code]. A code with no row, including [RegionCode.UNSET], resolves to the
         * [RegionCode.UNSET] row, as firmware's `getRegion` does; check `forRegion(code).region` to tell the cases
         * apart.
         *
         * When [firmwareVersion] is given and parses to a release at or below the last pre-ARIB firmware
         * (`2.8.2.d5f1619` placeholder; see `LAST_LEGACY_JP_SLOT_PLAN_FIRMWARE`), [RegionCode.JP] falls back to
         * the legacy unbonded row (`unitChannelMHz = 0f`, `bandSplitMHz = 0f`).
         * @since 0.3.0
         */
        public fun forRegion(code: RegionCode, firmwareVersion: String? = null): LoRaRegionInfo {
            if (code == RegionCode.JP && usesLegacyJpSlotPlan(firmwareVersion)) return LegacyJpRow
            return RegionsByCode[code] ?: RegionsByCode.getValue(RegionCode.UNSET)
        }
    }
}

private class Profile(
    val spacing: Float,
    val padding: Float,
    val audio: Boolean,
    val licensed: Boolean,
    val unitChannel: Float = 0f,
)

private val Std = Profile(0f, 0f, audio = true, licensed = false)
private val Jp = Profile(0f, 0f, audio = true, licensed = false, unitChannel = 0.2f)
private val Eu868 = Profile(0f, 0f, audio = false, licensed = false)
private val Undef = Profile(0f, 0f, audio = true, licensed = false)
private val Lite = Profile(0.4f, 0.0375f, audio = false, licensed = false)
private val Narrow = Profile(0f, 0.0104f, audio = true, licensed = false)
private val Ham20 = Profile(0f, 0.0022f, audio = false, licensed = true)
private val Ham100 = Profile(0f, 0.01875f, audio = false, licensed = true)

@Suppress("LongParameterList")
private fun row(
    region: RegionCode,
    start: Float,
    end: Float,
    duty: Float,
    power: Int,
    switching: Boolean,
    wide: Boolean,
    profile: Profile,
    preset: ModemPreset,
    overrideSlot: Int,
    bandSplit: Float = 0f,
) = LoRaRegionInfo(
    region, start, end, duty, power, switching, wide, profile.spacing, profile.padding, profile.audio,
    profile.licensed, preset, overrideSlot, profile.unitChannel, bandSplit,
)

private val LongFast = ModemPreset.LONG_FAST

private val RegionTable: List<LoRaRegionInfo> = listOf(
    row(RegionCode.US, 902.0f, 928.0f, 100f, 30, false, false, Std, LongFast, 0),
    row(RegionCode.EU_433, 433.0f, 434.0f, 10f, 10, false, false, Std, LongFast, 0),
    row(RegionCode.EU_868, 869.4f, 869.65f, 10f, 27, false, false, Eu868, LongFast, 0),
    row(RegionCode.EU_866, 865.6f, 867.6f, 2.5f, 27, false, false, Lite, ModemPreset.LITE_FAST, 0),
    row(RegionCode.EU_N_868, 869.4f, 869.65f, 10f, 27, false, false, Narrow, ModemPreset.NARROW_SLOW, 1),
    row(RegionCode.CN, 470.0f, 510.0f, 100f, 19, false, false, Std, LongFast, 0),
    row(RegionCode.JP, 920.5f, 923.5f, 100f, 13, false, false, Jp, LongFast, 0, bandSplit = 922.3f),
    row(RegionCode.ANZ, 915.0f, 928.0f, 100f, 30, false, false, Std, LongFast, 0),
    row(RegionCode.ANZ_433, 433.05f, 434.79f, 100f, 14, false, false, Std, LongFast, 0),
    row(RegionCode.RU, 868.7f, 869.2f, 100f, 20, false, false, Std, LongFast, 0),
    row(RegionCode.KR, 920.0f, 923.0f, 100f, 23, false, false, Std, LongFast, 0),
    row(RegionCode.TW, 920.0f, 925.0f, 100f, 27, false, false, Std, LongFast, 0),
    row(RegionCode.IN, 865.0f, 867.0f, 100f, 30, false, false, Std, LongFast, 0),
    row(RegionCode.NZ_865, 864.0f, 868.0f, 100f, 36, false, false, Std, LongFast, 0),
    row(RegionCode.TH, 920.0f, 925.0f, 10f, 27, false, false, Std, LongFast, 0),
    row(RegionCode.UA_433, 433.0f, 434.7f, 10f, 10, false, false, Std, LongFast, 0),
    row(RegionCode.MY_433, 433.0f, 435.0f, 100f, 20, false, false, Std, LongFast, 0),
    row(RegionCode.MY_919, 919.0f, 924.0f, 100f, 27, true, false, Std, LongFast, 0),
    row(RegionCode.SG_923, 917.0f, 925.0f, 100f, 20, false, false, Std, LongFast, 0),
    row(RegionCode.PH_433, 433.0f, 434.7f, 100f, 10, false, false, Std, LongFast, 0),
    row(RegionCode.PH_868, 868.0f, 869.4f, 100f, 14, false, false, Std, LongFast, 0),
    row(RegionCode.PH_915, 915.0f, 918.0f, 100f, 24, false, false, Std, LongFast, 0),
    row(RegionCode.KZ_433, 433.075f, 434.775f, 100f, 10, false, false, Std, LongFast, 0),
    row(RegionCode.KZ_863, 863.0f, 868.0f, 100f, 30, false, false, Std, LongFast, 0),
    row(RegionCode.NP_865, 865.0f, 868.0f, 100f, 30, false, false, Std, LongFast, 0),
    row(RegionCode.BR_902, 902.0f, 907.5f, 100f, 30, false, false, Std, LongFast, 0),
    row(RegionCode.ITU1_2M, 144.0f, 146.0f, 100f, 30, false, false, Ham20, ModemPreset.TINY_FAST, 26),
    row(RegionCode.ITU2_2M, 144.0f, 148.0f, 100f, 30, false, false, Ham20, ModemPreset.TINY_FAST, 51),
    row(RegionCode.ITU3_2M, 144.0f, 148.0f, 100f, 30, false, false, Ham20, ModemPreset.TINY_FAST, 33),
    row(RegionCode.ITU2_125CM, 220.0f, 225.0f, 100f, 30, false, false, Ham100, ModemPreset.NARROW_SLOW, 37),
    row(RegionCode.ITU1_70CM, 430.0f, 440.0f, 100f, 30, false, false, Ham100, ModemPreset.NARROW_SLOW, 37),
    row(RegionCode.ITU2_70CM, 420.0f, 450.0f, 100f, 30, false, false, Ham100, ModemPreset.NARROW_SLOW, 137),
    row(RegionCode.ITU3_70CM, 430.0f, 450.0f, 100f, 30, false, false, Ham100, ModemPreset.NARROW_SLOW, 37),
    row(RegionCode.LORA_24, 2400.0f, 2483.5f, 100f, 10, false, true, Std, LongFast, 0),
    row(RegionCode.UNSET, 902.0f, 928.0f, 100f, 30, false, false, Undef, LongFast, 0),
)

private val RegionsByCode: Map<RegionCode, LoRaRegionInfo> = RegionTable.associateBy { it.region }

private val LegacyJpRow: LoRaRegionInfo =
    row(RegionCode.JP, 920.5f, 923.5f, 100f, 13, false, false, Std, LongFast, 0)

/**
 * True when [firmwareVersion] parses to a valid firmware version at or below
 * [LAST_LEGACY_JP_SLOT_PLAN_FIRMWARE] (`2.8.2.d5f1619` placeholder for the last non-ARIB-patched
 * release). A `null` or unparseable version string defaults to `false` (current ARIB grid).
 */
internal fun usesLegacyJpSlotPlan(firmwareVersion: String?): Boolean {
    if (firmwareVersion == null) return false
    val parsed = DeviceVersion(firmwareVersion)
    return parsed.asInt in 1..LAST_LEGACY_JP_SLOT_PLAN_FIRMWARE.asInt
}
