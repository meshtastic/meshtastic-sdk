/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import org.meshtastic.proto.Config
import org.meshtastic.proto.Config.BluetoothConfig
import org.meshtastic.proto.Config.DeviceConfig
import org.meshtastic.proto.Config.DisplayConfig
import org.meshtastic.proto.Config.LoRaConfig
import org.meshtastic.proto.Config.PowerConfig
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.ModuleConfig.MQTTConfig
import org.meshtastic.proto.ModuleConfig.TelemetryConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigMergeTest {

    @Test
    fun mergeConfigs_replacesMatchingSection() {
        val existing = listOf(
            Config.Builder().also { wb ->wb.device = DeviceConfig.Builder().also { wb -> wb.role = Config.DeviceConfig.Role.CLIENT }.build()}.build(),
            Config.Builder().also { wb ->wb.lora = LoRaConfig.Builder().also { wb -> wb.region = Config.LoRaConfig.RegionCode.US }.build()}.build(),
            Config.Builder().also { wb ->wb.display = DisplayConfig.Builder().also { wb -> wb.screen_on_secs = 30 }.build()}.build(),
        )
        val written = listOf(
            Config.Builder().also { wb ->wb.lora = LoRaConfig.Builder().also { wb -> wb.region = Config.LoRaConfig.RegionCode.EU_868 }.build()}.build(),
        )
        val merged = mergeConfigs(existing, written)

        assertEquals(3, merged.size)
        // device untouched
        assertEquals(Config.DeviceConfig.Role.CLIENT, merged[0].device?.role)
        // lora replaced
        assertEquals(Config.LoRaConfig.RegionCode.EU_868, merged[1].lora?.region)
        // display untouched
        assertEquals(30, merged[2].display?.screen_on_secs)
    }

    @Test
    fun mergeConfigs_appendsNewSection() {
        val existing = listOf(
            Config.Builder().also { wb ->wb.device = DeviceConfig.Builder().also { wb -> wb.role = Config.DeviceConfig.Role.ROUTER }.build()}.build(),
        )
        val written = listOf(
            Config.Builder().also { wb ->wb.bluetooth = BluetoothConfig.Builder().also { wb -> wb.enabled = true }.build()}.build(),
        )
        val merged = mergeConfigs(existing, written)

        assertEquals(2, merged.size)
        assertEquals(Config.DeviceConfig.Role.ROUTER, merged[0].device?.role)
        assertEquals(true, merged[1].bluetooth?.enabled)
    }

    @Test
    fun mergeConfigs_emptyWrittenReturnsExisting() {
        val existing = listOf(Config.Builder().also { wb ->wb.power = PowerConfig.Builder().also { wb -> wb.on_battery_shutdown_after_secs = 120 }.build()}.build())
        val merged = mergeConfigs(existing, emptyList())
        assertEquals(existing, merged)
    }

    @Test
    fun mergeModuleConfigs_replacesMatchingSection() {
        val existing = listOf(
            ModuleConfig.Builder().also { wb ->wb.mqtt = MQTTConfig.Builder().also { wb -> wb.enabled = true }.build()}.build(),
            ModuleConfig.Builder().also { wb ->wb.telemetry = TelemetryConfig.Builder().also { wb -> wb.device_update_interval = 60 }.build()}.build(),
        )
        val written = listOf(
            ModuleConfig.Builder().also { wb ->wb.telemetry = TelemetryConfig.Builder().also { wb -> wb.device_update_interval = 30 }.build()}.build(),
        )
        val merged = mergeModuleConfigs(existing, written)

        assertEquals(2, merged.size)
        assertEquals(true, merged[0].mqtt?.enabled)
        assertEquals(30, merged[1].telemetry?.device_update_interval)
    }

    @Test
    fun sectionKey_configSections() {
        assertEquals("device", Config.Builder().also { wb ->wb.device = DeviceConfig.Builder().build()}.build().sectionKey())
        assertEquals("lora", Config.Builder().also { wb ->wb.lora = LoRaConfig.Builder().build()}.build().sectionKey())
        assertEquals(null, Config.Builder().build().sectionKey())
    }

    @Test
    fun sectionKey_moduleConfigSections() {
        assertEquals("mqtt", ModuleConfig.Builder().also { wb ->wb.mqtt = MQTTConfig.Builder().build()}.build().sectionKey())
        assertEquals("telemetry", ModuleConfig.Builder().also { wb ->wb.telemetry = TelemetryConfig.Builder().build()}.build().sectionKey())
        assertEquals(null, ModuleConfig.Builder().build().sectionKey())
    }
}
