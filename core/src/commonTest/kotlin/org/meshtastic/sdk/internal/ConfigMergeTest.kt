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
            Config(device = DeviceConfig(role = Config.DeviceConfig.Role.CLIENT)),
            Config(lora = LoRaConfig(region = Config.LoRaConfig.RegionCode.US)),
            Config(display = DisplayConfig(screen_on_secs = 30)),
        )
        val written = listOf(
            Config(lora = LoRaConfig(region = Config.LoRaConfig.RegionCode.EU_868)),
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
            Config(device = DeviceConfig(role = Config.DeviceConfig.Role.ROUTER)),
        )
        val written = listOf(
            Config(bluetooth = BluetoothConfig(enabled = true)),
        )
        val merged = mergeConfigs(existing, written)

        assertEquals(2, merged.size)
        assertEquals(Config.DeviceConfig.Role.ROUTER, merged[0].device?.role)
        assertEquals(true, merged[1].bluetooth?.enabled)
    }

    @Test
    fun mergeConfigs_emptyWrittenReturnsExisting() {
        val existing = listOf(Config(power = PowerConfig(on_battery_shutdown_after_secs = 120)))
        val merged = mergeConfigs(existing, emptyList())
        assertEquals(existing, merged)
    }

    @Test
    fun mergeModuleConfigs_replacesMatchingSection() {
        val existing = listOf(
            ModuleConfig(mqtt = MQTTConfig(enabled = true)),
            ModuleConfig(telemetry = TelemetryConfig(device_update_interval = 60)),
        )
        val written = listOf(
            ModuleConfig(telemetry = TelemetryConfig(device_update_interval = 30)),
        )
        val merged = mergeModuleConfigs(existing, written)

        assertEquals(2, merged.size)
        assertEquals(true, merged[0].mqtt?.enabled)
        assertEquals(30, merged[1].telemetry?.device_update_interval)
    }

    @Test
    fun sectionKey_configSections() {
        assertEquals("device", Config(device = DeviceConfig()).sectionKey())
        assertEquals("lora", Config(lora = LoRaConfig()).sectionKey())
        assertEquals(null, Config().sectionKey())
    }

    @Test
    fun sectionKey_moduleConfigSections() {
        assertEquals("mqtt", ModuleConfig(mqtt = MQTTConfig()).sectionKey())
        assertEquals("telemetry", ModuleConfig(telemetry = TelemetryConfig()).sectionKey())
        assertEquals(null, ModuleConfig().sectionKey())
    }
}
