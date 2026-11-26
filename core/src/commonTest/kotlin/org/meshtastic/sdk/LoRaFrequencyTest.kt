/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:Suppress("ktlint:standard:max-line-length")

package org.meshtastic.sdk

import org.meshtastic.proto.Config.LoRaConfig
import org.meshtastic.proto.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.Config.LoRaConfig.RegionCode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class FirmwareRow(
    val region: RegionCode,
    val start: Float,
    val end: Float,
    val duty: Float,
    val power: Int,
    val switching: Boolean,
    val wide: Boolean,
    val spacing: Float,
    val padding: Float,
    val audio: Boolean,
    val licensed: Boolean,
    val preset: ModemPreset,
    val overrideSlot: Int,
    val unitChannel: Float = 0f,
    val bandSplit: Float = 0f,
)

// Transcribed from firmware RadioInterface.cpp regions[] and its RegionProfile
// definitions at commit 68f47efd, with JP updated for ARIB STD-T108 (firmware#11559).
private val FirmwareCommit68f47efdRows = listOf(
    FirmwareRow(RegionCode.US, 902.0f, 928.0f, 100f, 30, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.EU_433, 433.0f, 434.0f, 10f, 10, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.EU_868, 869.4f, 869.65f, 10f, 27, false, false, 0f, 0f, false, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.EU_866, 865.6f, 867.6f, 2.5f, 27, false, false, 0.4f, 0.0375f, false, false, ModemPreset.LITE_FAST, 0),
    FirmwareRow(RegionCode.EU_N_868, 869.4f, 869.65f, 10f, 27, false, false, 0f, 0.0104f, true, false, ModemPreset.NARROW_SLOW, 1),
    FirmwareRow(RegionCode.CN, 470.0f, 510.0f, 100f, 19, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(
        RegionCode.JP,
        920.5f,
        923.5f,
        100f,
        13,
        false,
        false,
        0f,
        0f,
        true,
        false,
        ModemPreset.LONG_FAST,
        0,
        0.2f,
        922.3f,
    ),
    FirmwareRow(RegionCode.ANZ, 915.0f, 928.0f, 100f, 30, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.ANZ_433, 433.05f, 434.79f, 100f, 14, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.RU, 868.7f, 869.2f, 100f, 20, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.KR, 920.0f, 923.0f, 100f, 23, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.TW, 920.0f, 925.0f, 100f, 27, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.IN, 865.0f, 867.0f, 100f, 30, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.NZ_865, 864.0f, 868.0f, 100f, 36, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.TH, 920.0f, 925.0f, 10f, 27, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.UA_433, 433.0f, 434.7f, 10f, 10, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.MY_433, 433.0f, 435.0f, 100f, 20, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.MY_919, 919.0f, 924.0f, 100f, 27, true, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.SG_923, 917.0f, 925.0f, 100f, 20, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.PH_433, 433.0f, 434.7f, 100f, 10, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.PH_868, 868.0f, 869.4f, 100f, 14, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.PH_915, 915.0f, 918.0f, 100f, 24, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.KZ_433, 433.075f, 434.775f, 100f, 10, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.KZ_863, 863.0f, 868.0f, 100f, 30, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.NP_865, 865.0f, 868.0f, 100f, 30, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.BR_902, 902.0f, 907.5f, 100f, 30, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.ITU1_2M, 144.0f, 146.0f, 100f, 30, false, false, 0f, 0.0022f, false, true, ModemPreset.TINY_FAST, 26),
    FirmwareRow(RegionCode.ITU2_2M, 144.0f, 148.0f, 100f, 30, false, false, 0f, 0.0022f, false, true, ModemPreset.TINY_FAST, 51),
    FirmwareRow(RegionCode.ITU3_2M, 144.0f, 148.0f, 100f, 30, false, false, 0f, 0.0022f, false, true, ModemPreset.TINY_FAST, 33),
    FirmwareRow(RegionCode.ITU2_125CM, 220.0f, 225.0f, 100f, 30, false, false, 0f, 0.01875f, false, true, ModemPreset.NARROW_SLOW, 37),
    FirmwareRow(RegionCode.ITU1_70CM, 430.0f, 440.0f, 100f, 30, false, false, 0f, 0.01875f, false, true, ModemPreset.NARROW_SLOW, 37),
    FirmwareRow(RegionCode.ITU2_70CM, 420.0f, 450.0f, 100f, 30, false, false, 0f, 0.01875f, false, true, ModemPreset.NARROW_SLOW, 137),
    FirmwareRow(RegionCode.ITU3_70CM, 430.0f, 450.0f, 100f, 30, false, false, 0f, 0.01875f, false, true, ModemPreset.NARROW_SLOW, 37),
    FirmwareRow(RegionCode.LORA_24, 2400.0f, 2483.5f, 100f, 10, false, true, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
    FirmwareRow(RegionCode.UNSET, 902.0f, 928.0f, 100f, 30, false, false, 0f, 0f, true, false, ModemPreset.LONG_FAST, 0),
)

// Pin 2.8.2 enum values that firmware has no regions[] row for; firmware resolves them to the UNSET row.
private val CodesWithoutFirmwareRow = setOf(RegionCode.UA_868, RegionCode.EU_874, RegionCode.EU_917)

class LoRaFrequencyTest {
    private fun assertMHz(expected: Float, actual: Float?, message: String = "") {
        assertTrue(actual != null && abs(expected - actual) < 0.0005f, "$message expected $expected but was $actual")
    }

    private fun config(
        region: RegionCode = RegionCode.US,
        preset: ModemPreset = ModemPreset.LONG_FAST,
        usePreset: Boolean = true,
        bandwidth: Int = 0,
        channelNum: Int = 0,
        overrideFrequency: Float = 0f,
        offset: Float = 0f,
    ): LoRaConfig = LoRaConfig.Builder().also { wb ->
        wb.region = region
        wb.modem_preset = preset
        wb.use_preset = usePreset
        wb.bandwidth = bandwidth
        wb.channel_num = channelNum
        wb.override_frequency = overrideFrequency
        wb.frequency_offset = offset
    }.build()

    @Test
    fun tableMatchesFirmwareRowForRow() {
        assertEquals(FirmwareCommit68f47efdRows.size, LoRaRegionInfo.all.size)
        for ((expected, actual) in FirmwareCommit68f47efdRows.zip(LoRaRegionInfo.all)) {
            val m = expected.region.name
            assertEquals(expected.region, actual.region, m)
            assertEquals(expected.start, actual.freqStartMHz, m)
            assertEquals(expected.end, actual.freqEndMHz, m)
            assertEquals(expected.duty, actual.dutyCyclePercent, m)
            assertEquals(expected.power, actual.powerLimitDbm, m)
            assertEquals(expected.switching, actual.freqSwitching, m)
            assertEquals(expected.wide, actual.wideLora, m)
            assertEquals(expected.spacing, actual.spacingMHz, m)
            assertEquals(expected.padding, actual.paddingMHz, m)
            assertEquals(expected.audio, actual.audioPermitted, m)
            assertEquals(expected.licensed, actual.licensedOnly, m)
            assertEquals(expected.preset, actual.defaultPreset, m)
            assertEquals(expected.overrideSlot, actual.overrideSlot, m)
            assertEquals(expected.unitChannel, actual.unitChannelMHz, m)
            assertEquals(expected.bandSplit, actual.bandSplitMHz, m)
        }
    }

    @Test
    fun everyRegionCodeResolvesAndOnlyKnownGapsFallBack() {
        val fallbacks = RegionCode.entries.filter { LoRaRegionInfo.forRegion(it).region != it }.toSet()
        assertEquals(CodesWithoutFirmwareRow, fallbacks)
        for (code in RegionCode.entries) {
            if (code !in CodesWithoutFirmwareRow) assertEquals(code, LoRaRegionInfo.forRegion(code).region)
        }
    }

    @Test
    fun unknownRegionResolvesToUnsetRow() {
        assertEquals(RegionCode.UNSET, LoRaRegionInfo.forRegion(RegionCode.EU_874).region)
        assertEquals(0, LoRaFrequency.numChannels(LoRaRegionInfo.forRegion(RegionCode.EU_917), 250f))
    }

    @Test
    fun presetParametersMatchFirmware() {
        val expected = mapOf(
            ModemPreset.SHORT_TURBO to Triple(500f, 7, 5),
            ModemPreset.SHORT_FAST to Triple(250f, 7, 5),
            ModemPreset.SHORT_SLOW to Triple(250f, 8, 5),
            ModemPreset.MEDIUM_FAST to Triple(250f, 9, 5),
            ModemPreset.MEDIUM_SLOW to Triple(250f, 10, 5),
            ModemPreset.MEDIUM_TURBO to Triple(500f, 9, 5),
            ModemPreset.LONG_TURBO to Triple(500f, 11, 8),
            ModemPreset.LONG_FAST to Triple(250f, 11, 5),
            ModemPreset.LONG_MODERATE to Triple(125f, 11, 8),
            ModemPreset.LONG_SLOW to Triple(125f, 12, 8),
            ModemPreset.LITE_FAST to Triple(125f, 9, 5),
            ModemPreset.LITE_SLOW to Triple(125f, 10, 5),
            ModemPreset.NARROW_FAST to Triple(62.5f, 7, 6),
            ModemPreset.NARROW_SLOW to Triple(62.5f, 8, 6),
            ModemPreset.TINY_FAST to Triple(15.6f, 7, 5),
            ModemPreset.TINY_SLOW to Triple(15.6f, 8, 6),
        )
        for ((preset, t) in expected) {
            assertEquals(ModemParams(t.first, t.second, t.third), preset.modemParams(), preset.name)
        }
    }

    @Test
    fun widePresetBandwidthsScaleOnlyForWideBandsets() {
        assertEquals(812.5f, ModemPreset.LONG_FAST.modemParams(wideLora = true).bandwidthKHz)
        assertEquals(1625f, ModemPreset.SHORT_TURBO.modemParams(wideLora = true).bandwidthKHz)
        assertEquals(406.25f, ModemPreset.LONG_SLOW.modemParams(wideLora = true).bandwidthKHz)
        assertEquals(125f, ModemPreset.LITE_FAST.modemParams(wideLora = true).bandwidthKHz)
        assertEquals(15.6f, ModemPreset.TINY_SLOW.modemParams(wideLora = true).bandwidthKHz)
    }

    @Test
    fun presetWithoutFirmwareCaseUsesLongFastParameters() {
        assertEquals(ModemPreset.LONG_FAST.modemParams(), ModemPreset.VERY_LONG_SLOW.modemParams())
    }

    @Test
    fun bandwidthCodesMatchFirmware() {
        assertEquals(62.5f, LoRaFrequency.bandwidthKHzForCode(62))
        assertEquals(15.6f, LoRaFrequency.bandwidthKHzForCode(16))
        assertEquals(1625f, LoRaFrequency.bandwidthKHzForCode(1600))
        assertEquals(250f, LoRaFrequency.bandwidthKHzForCode(250))
    }

    @Test
    fun usLongFastDefaultSlotIs20At906_875() {
        val plan = LoRaFrequency.plan(config(), "LongFast")
        assertEquals(104, plan.numChannels)
        assertEquals(20, plan.slot)
        assertMHz(906.875f, plan.frequencyMHz)
        assertEquals(250f, plan.bandwidthKHz)
    }

    @Test
    fun blankNameHashesPresetDisplayName() {
        val plan = LoRaFrequency.plan(config(), "")
        assertEquals(20, plan.slot)
        assertEquals("LongFast", LoRaFrequency.hashedChannelName("", true, ModemPreset.LONG_FAST))
        assertEquals("LongMod", LoRaFrequency.hashedChannelName("", true, ModemPreset.LONG_MODERATE))
        assertEquals("Custom", LoRaFrequency.hashedChannelName("", false, ModemPreset.LONG_FAST))
        assertEquals("Invalid", LoRaFrequency.hashedChannelName("", true, ModemPreset.VERY_LONG_SLOW))
        assertEquals("mine", LoRaFrequency.hashedChannelName("mine", true, ModemPreset.LONG_FAST))
    }

    @Test
    fun customNameHashesToItsOwnSlot() {
        val plan = LoRaFrequency.plan(config(), "Test")
        assertEquals(46, plan.slot)
        assertMHz(913.375f, plan.frequencyMHz)
    }

    @Test
    fun nonAsciiNameHashesUtf8Bytes() {
        // djb2 over the bytes of "é" (0xC3 0xA9)
        val hash = ((5381u * 33u + 0xC3u) * 33u + 0xA9u)
        val region = LoRaRegionInfo.forRegion(RegionCode.US)
        assertEquals((hash % 104u).toInt() + 1, LoRaFrequency.resolveSlot(region, 104, "é"))
    }

    @Test
    fun euSingleSlotRegion() {
        val plan = LoRaFrequency.plan(config(region = RegionCode.EU_868), "LongFast")
        assertEquals(1, plan.numChannels)
        assertEquals(1, plan.slot)
        assertMHz(869.525f, plan.frequencyMHz)
    }

    @Test
    fun wideRegionUsesWideBandwidth() {
        val plan = LoRaFrequency.plan(config(region = RegionCode.LORA_24), "LongFast")
        assertEquals(812.5f, plan.bandwidthKHz)
        assertEquals(103, plan.numChannels)
        assertEquals(26, plan.slot)
        assertMHz(2420.719f, plan.frequencyMHz)
    }

    @Test
    fun narrowPresetWithRegionOverrideSlot() {
        val plan = LoRaFrequency.plan(
            config(region = RegionCode.EU_N_868, preset = ModemPreset.NARROW_SLOW),
            "NarrowSlow",
        )
        assertEquals(3, plan.numChannels)
        assertEquals(1, plan.slot)
        assertMHz(869.442f, plan.frequencyMHz)
    }

    @Test
    fun liteRegionHasFourSlots() {
        val plan = LoRaFrequency.plan(config(region = RegionCode.EU_866, preset = ModemPreset.LITE_FAST), "LiteFast")
        assertEquals(4, plan.numChannels)
        assertEquals(2, plan.slot)
        assertMHz(866.3f, plan.frequencyMHz)
    }

    @Test
    fun hamRegionUsesRegionOverrideSlotAndFifteenPointSixBandwidth() {
        val plan = LoRaFrequency.plan(config(region = RegionCode.ITU2_2M, preset = ModemPreset.TINY_FAST), "TinyFast")
        assertEquals(15.6f, plan.bandwidthKHz)
        assertEquals(200, plan.numChannels)
        assertEquals(51, plan.slot)
        assertMHz(145.010f, plan.frequencyMHz)
    }

    @Test
    fun explicitChannelNumWinsInRange() {
        assertMHz(902.125f, LoRaFrequency.plan(config(channelNum = 1), "LongFast").frequencyMHz)
        assertMHz(927.875f, LoRaFrequency.plan(config(channelNum = 104), "LongFast").frequencyMHz)
        assertEquals(7, LoRaFrequency.plan(config(channelNum = 7), "LongFast").slot)
        assertMHz(903.625f, LoRaFrequency.plan(config(channelNum = 7), "LongFast").frequencyMHz)
    }

    @Test
    fun explicitChannelNumBeatsRegionOverrideSlot() {
        val plan = LoRaFrequency.plan(
            config(region = RegionCode.ITU2_2M, preset = ModemPreset.TINY_FAST, channelNum = 10),
            "TinyFast",
        )
        assertEquals(10, plan.slot)
    }

    @Test
    fun outOfRangeChannelNumFallsBackToHashSlot() {
        val plan = LoRaFrequency.plan(config(channelNum = 105), "LongFast")
        assertEquals(20, plan.slot)
        assertMHz(906.875f, plan.frequencyMHz)
    }

    @Test
    fun overrideFrequencyReplacesSlotAndAddsOffset() {
        val plan = LoRaFrequency.plan(config(overrideFrequency = 915.5f, offset = 0.01f), "LongFast")
        assertNull(plan.slot)
        assertMHz(915.51f, plan.frequencyMHz)
        assertEquals(104, plan.numChannels)
    }

    @Test
    fun frequencyOffsetIsAddedToSlotFrequency() {
        assertMHz(907.375f, LoRaFrequency.plan(config(offset = 0.5f), "LongFast").frequencyMHz)
    }

    @Test
    fun customBandwidthUsesBandwidthCodeAndCustomName() {
        val plan = LoRaFrequency.plan(config(usePreset = false, bandwidth = 62), "")
        assertEquals(62.5f, plan.bandwidthKHz)
        assertEquals(416, plan.numChannels)
        assertEquals(225, plan.slot)
        assertMHz(916.031f, plan.frequencyMHz)
    }

    @Test
    fun zeroCustomBandwidthFallsBackTo250() {
        assertEquals(250f, LoRaFrequency.plan(config(usePreset = false, bandwidth = 0), "").bandwidthKHz)
    }

    @Test
    fun customBandwidthWiderThanBandUsesRegionDefaultPresetBandwidth() {
        val plan = LoRaFrequency.plan(config(region = RegionCode.EU_868, usePreset = false, bandwidth = 500), "x")
        assertEquals(250f, plan.bandwidthKHz)
        assertEquals(1, plan.numChannels)
        assertEquals(1, plan.slot)
    }

    @Test
    fun customBandwidthWiderThanHamBandUsesTinyBandwidth() {
        val plan = LoRaFrequency.plan(config(region = RegionCode.ITU1_2M, usePreset = false, bandwidth = 5000), "x")
        assertEquals(15.6f, plan.bandwidthKHz)
    }

    @Test
    fun numChannelsIsNotForcedToOneForVeryWideBandwidth() {
        val eu = LoRaRegionInfo.forRegion(RegionCode.EU_868)
        assertEquals(1, LoRaFrequency.numChannels(eu, 500f))
        assertEquals(0, LoRaFrequency.numChannels(eu, 1625f))
    }

    @Test
    fun numChannelsRoundsHalfUp() {
        // US span 26 MHz at 250 kHz is 104; CN 40 MHz at 250 kHz is 160
        assertEquals(160, LoRaFrequency.numChannels(LoRaRegionInfo.forRegion(RegionCode.CN), 250f))
        assertEquals(52, LoRaFrequency.numChannels(LoRaRegionInfo.forRegion(RegionCode.US), 500f))
        assertEquals(208, LoRaFrequency.numChannels(LoRaRegionInfo.forRegion(RegionCode.US), 125f))
    }

    @Test
    fun unsetRegionHasNoSlotsAndNoFrequency() {
        val plan = LoRaFrequency.plan(config(region = RegionCode.UNSET), "LongFast")
        assertEquals(0, plan.numChannels)
        assertNull(plan.slot)
        assertNull(plan.frequencyMHz)
    }

    @Test
    fun unsetRegionWithOverrideFrequencyStillHasFrequency() {
        val plan = LoRaFrequency.plan(config(region = RegionCode.UNSET, overrideFrequency = 433.5f), "")
        assertMHz(433.5f, plan.frequencyMHz)
    }

    @Test
    fun resolveSlotIgnoresRegionOverrideBeyondRange() {
        val region = LoRaRegionInfo.forRegion(RegionCode.ITU2_70CM).copy(overrideSlot = 500)
        val slot = LoRaFrequency.resolveSlot(region, 10, "name")
        assertTrue(slot != null && slot in 1..10)
        assertNull(LoRaFrequency.resolveSlot(region, 0, "name"))
    }

    @Test
    fun jpNarrowAndLitePresetsUseFifteen200kHzUnitChannels() {
        val jp = LoRaRegionInfo.forRegion(RegionCode.JP)
        for (preset in listOf(ModemPreset.NARROW_SLOW, ModemPreset.LITE_FAST)) {
            val bwKHz = preset.modemParams().bandwidthKHz
            assertMHz(0.2f, LoRaFrequency.slotWidthMHz(jp, bwKHz))
            val plan = LoRaFrequency.plan(config(region = RegionCode.JP, preset = preset, channelNum = 1), "")
            assertEquals(15, plan.numChannels)
            assertMHz(920.6f, LoRaFrequency.centerFrequencyMHz(jp, bwKHz, 1))
            assertMHz(920.8f, LoRaFrequency.centerFrequencyMHz(jp, bwKHz, 2))
            assertMHz(922.2f, LoRaFrequency.centerFrequencyMHz(jp, bwKHz, 9))
            assertMHz(922.4f, LoRaFrequency.centerFrequencyMHz(jp, bwKHz, 10))
            assertMHz(923.4f, LoRaFrequency.centerFrequencyMHz(jp, bwKHz, 15))
        }

        val narrowSlow = LoRaFrequency.plan(
            config(region = RegionCode.JP, preset = ModemPreset.NARROW_SLOW),
            "NarrowSlow",
        )
        assertEquals(2, narrowSlow.slot)
        assertMHz(920.8f, narrowSlow.frequencyMHz)

        val narrowFast = LoRaFrequency.plan(
            config(region = RegionCode.JP, preset = ModemPreset.NARROW_FAST),
            "NarrowFast",
        )
        assertEquals(11, narrowFast.slot)
        assertMHz(922.6f, narrowFast.frequencyMHz)

        val liteFast = LoRaFrequency.plan(config(region = RegionCode.JP, preset = ModemPreset.LITE_FAST), "LiteFast")
        assertEquals(5, liteFast.slot)
        assertMHz(921.4f, liteFast.frequencyMHz)
    }

    @Test
    fun jp250kHzPresetsBondTwoUnitChannelsWithoutCrossing922_3MHz() {
        val jp = LoRaRegionInfo.forRegion(RegionCode.JP)
        assertMHz(0.4f, LoRaFrequency.slotWidthMHz(jp, 250f))
        val plan = LoRaFrequency.plan(config(region = RegionCode.JP, preset = ModemPreset.LONG_FAST), "LongFast")
        assertEquals(7, plan.numChannels)
        assertEquals(6, plan.slot)
        assertMHz(922.9f, plan.frequencyMHz)
        val expected = listOf(920.7f, 921.1f, 921.5f, 921.9f, 922.5f, 922.9f, 923.3f)
        expected.forEachIndexed { index, freq ->
            assertMHz(freq, LoRaFrequency.centerFrequencyMHz(jp, 250f, index + 1), "slot ${index + 1}")
        }
    }

    @Test
    fun jp500kHzTurboPresetsBondThreeUnitChannels() {
        val jp = LoRaRegionInfo.forRegion(RegionCode.JP)
        assertMHz(0.6f, LoRaFrequency.slotWidthMHz(jp, 500f))
        val plan = LoRaFrequency.plan(config(region = RegionCode.JP, preset = ModemPreset.SHORT_TURBO), "ShortTurbo")
        assertEquals(5, plan.numChannels)
        assertEquals(1, plan.slot)
        assertMHz(920.8f, plan.frequencyMHz)
        val expected = listOf(920.8f, 921.4f, 922.0f, 922.6f, 923.2f)
        expected.forEachIndexed { index, freq ->
            assertMHz(freq, LoRaFrequency.centerFrequencyMHz(jp, 500f, index + 1), "slot ${index + 1}")
        }
    }

    @Test
    fun jpCustomBandwidthsAndSlotBoundsFollowUnitChannelGrid() {
        val jp = LoRaRegionInfo.forRegion(RegionCode.JP)

        val bw203 = LoRaFrequency.plan(config(region = RegionCode.JP, usePreset = false, bandwidth = 200), "Custom")
        assertEquals(7, bw203.numChannels)
        assertMHz(920.7f, LoRaFrequency.centerFrequencyMHz(jp, bw203.bandwidthKHz, 1))
        assertMHz(922.5f, LoRaFrequency.centerFrequencyMHz(jp, bw203.bandwidthKHz, 5))

        val bw406 = LoRaFrequency.plan(config(region = RegionCode.JP, usePreset = false, bandwidth = 400), "Custom")
        assertEquals(5, bw406.numChannels)
        assertMHz(920.8f, LoRaFrequency.centerFrequencyMHz(jp, bw406.bandwidthKHz, 1))
        assertMHz(922.6f, LoRaFrequency.centerFrequencyMHz(jp, bw406.bandwidthKHz, 4))

        val tooWide = LoRaFrequency.plan(config(region = RegionCode.JP, usePreset = false, bandwidth = 2000), "Custom")
        assertEquals(250f, tooWide.bandwidthKHz)
        assertEquals(7, tooWide.numChannels)

        assertMHz(920.7f, LoRaFrequency.centerFrequencyMHz(jp, 250f, 0))

        val jpPresets = listOf(
            ModemPreset.NARROW_SLOW,
            ModemPreset.LITE_FAST,
            ModemPreset.LONG_FAST,
            ModemPreset.SHORT_TURBO,
        )
        for (preset in jpPresets) {
            val bwKHz = preset.modemParams().bandwidthKHz
            val halfBw = bwKHz / 2000f
            val count = LoRaFrequency.numChannels(jp, bwKHz)
            var prevUpper = 0f
            for (slot in 1..count) {
                val center = LoRaFrequency.centerFrequencyMHz(jp, bwKHz, slot)
                val lo = center - halfBw
                val hi = center + halfBw
                assertTrue(lo >= jp.freqStartMHz - 1e-4f, "$preset slot $slot lower edge $lo < ${jp.freqStartMHz}")
                assertTrue(hi <= jp.freqEndMHz + 1e-4f, "$preset slot $slot upper edge $hi > ${jp.freqEndMHz}")
                if (slot > 1) {
                    assertTrue(lo >= prevUpper - 1e-4f, "$preset slot $slot overlaps previous ($lo < $prevUpper)")
                }
                assertTrue(
                    hi <= jp.bandSplitMHz + 1e-4f || lo >= jp.bandSplitMHz - 1e-4f,
                    "$preset slot $slot [$lo, $hi] straddles ${jp.bandSplitMHz}",
                )
                prevUpper = hi
            }
        }
    }

    @Test
    fun jpFallsBackToLegacyUnbondedGridOnPreAribFirmware() {
        for (ver in listOf("2.8.2.d5f1619", "2.8.2", "2.8.1.8e6a88d", "2.7.18")) {
            val region = LoRaRegionInfo.forRegion(RegionCode.JP, ver)
            assertEquals(0f, region.unitChannelMHz, ver)
            assertEquals(0f, region.bandSplitMHz, ver)

            val longFast = LoRaFrequency.plan(
                config(region = RegionCode.JP, preset = ModemPreset.LONG_FAST),
                "LongFast",
                ver,
            )
            assertEquals(12, longFast.numChannels, ver)
            assertEquals(12, longFast.slot, ver)
            assertMHz(923.375f, longFast.frequencyMHz, ver)

            val narrowSlow = LoRaFrequency.plan(
                config(region = RegionCode.JP, preset = ModemPreset.NARROW_SLOW, channelNum = 2),
                "NarrowSlow",
                ver,
            )
            assertEquals(48, narrowSlow.numChannels, ver)
            assertEquals(2, narrowSlow.slot, ver)
            assertMHz(920.59375f, narrowSlow.frequencyMHz, ver)

            val shortTurbo = LoRaFrequency.plan(
                config(region = RegionCode.JP, preset = ModemPreset.SHORT_TURBO),
                "ShortTurbo",
                ver,
            )
            assertEquals(6, shortTurbo.numChannels, ver)
            assertEquals(6, shortTurbo.slot, ver)
            assertMHz(923.25f, shortTurbo.frequencyMHz, ver)
        }

        for (ver in listOf("2.8.3.abcdef0", "2.8.3", "2.9.0", "", null)) {
            val region = LoRaRegionInfo.forRegion(RegionCode.JP, ver)
            assertEquals(0.2f, region.unitChannelMHz, "$ver")
            assertEquals(922.3f, region.bandSplitMHz, "$ver")

            val longFast = LoRaFrequency.plan(
                config(region = RegionCode.JP, preset = ModemPreset.LONG_FAST),
                "LongFast",
                ver,
            )
            assertEquals(7, longFast.numChannels, "$ver")
            assertEquals(6, longFast.slot, "$ver")
            assertMHz(922.9f, longFast.frequencyMHz, "$ver")

            val narrowSlow = LoRaFrequency.plan(
                config(region = RegionCode.JP, preset = ModemPreset.NARROW_SLOW),
                "NarrowSlow",
                ver,
            )
            assertEquals(15, narrowSlow.numChannels, "$ver")
            assertEquals(2, narrowSlow.slot, "$ver")
            assertMHz(920.8f, narrowSlow.frequencyMHz, "$ver")
        }
    }
}
