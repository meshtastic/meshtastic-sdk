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
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.ModuleConfig.MQTTConfig
import org.meshtastic.proto.ModuleConfig.TelemetryConfig
import org.meshtastic.proto.Team
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigMergeTest {

    @Test
    fun mergeConfigs_replacesMatchingSection() {
        val existing = listOf(
            Config.Builder().also { wb ->
                wb.device = DeviceConfig.Builder().also { wb ->
                    wb.role = Config.DeviceConfig.Role.CLIENT
                }.build()
            }.build(),
            Config.Builder().also { wb ->
                wb.lora = LoRaConfig.Builder().also { wb ->
                    wb.region = Config.LoRaConfig.RegionCode.US
                }.build()
            }.build(),
            Config.Builder().also { wb ->
                wb.display = DisplayConfig.Builder().also { wb ->
                    wb.screen_on_secs = 30
                }.build()
            }.build(),
        )
        val written = listOf(
            Config.Builder().also { wb ->
                wb.lora = LoRaConfig.Builder().also { wb ->
                    wb.region = Config.LoRaConfig.RegionCode.EU_868
                }.build()
            }.build(),
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
            Config.Builder().also { wb ->
                wb.device = DeviceConfig.Builder().also { wb ->
                    wb.role = Config.DeviceConfig.Role.ROUTER
                }.build()
            }.build(),
        )
        val written = listOf(
            Config.Builder().also { wb ->
                wb.bluetooth = BluetoothConfig.Builder().also { wb ->
                    wb.enabled = true
                }.build()
            }.build(),
        )
        val merged = mergeConfigs(existing, written)

        assertEquals(2, merged.size)
        assertEquals(Config.DeviceConfig.Role.ROUTER, merged[0].device?.role)
        assertEquals(true, merged[1].bluetooth?.enabled)
    }

    @Test
    fun mergeConfigs_emptyWrittenReturnsExisting() {
        val existing = listOf(
            Config.Builder().also { wb ->
                wb.power = PowerConfig.Builder().also { wb ->
                    wb.on_battery_shutdown_after_secs = 120
                }.build()
            }.build(),
        )
        val merged = mergeConfigs(existing, emptyList())
        assertEquals(existing, merged)
    }

    @Test
    fun mergeModuleConfigs_replacesMatchingSection() {
        val existing = listOf(
            ModuleConfig.Builder().also { wb ->
                wb.mqtt = MQTTConfig.Builder().also { wb ->
                    wb.enabled = true
                }.build()
            }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.telemetry = TelemetryConfig.Builder().also { wb ->
                    wb.device_update_interval = 60
                }.build()
            }.build(),
        )
        val written = listOf(
            ModuleConfig.Builder().also { wb ->
                wb.telemetry = TelemetryConfig.Builder().also { wb ->
                    wb.device_update_interval = 30
                }.build()
            }.build(),
        )
        val merged = mergeModuleConfigs(existing, written)

        assertEquals(2, merged.size)
        assertEquals(true, merged[0].mqtt?.enabled)
        assertEquals(30, merged[1].telemetry?.device_update_interval)
    }

    @Test
    fun sectionKey_configSections() {
        assertEquals(
            "device",
            Config.Builder().also { wb ->
                wb.device = DeviceConfig.Builder().build()
            }.build().sectionKey(),
        )
        assertEquals(
            "lora",
            Config.Builder().also { wb ->
                wb.lora = LoRaConfig.Builder().build()
            }.build().sectionKey(),
        )
        assertEquals(null, Config.Builder().build().sectionKey())
    }

    @Test
    fun sectionKey_moduleConfigSections() {
        assertEquals(
            "mqtt",
            ModuleConfig.Builder().also { wb ->
                wb.mqtt = MQTTConfig.Builder().build()
            }.build().sectionKey(),
        )
        assertEquals(
            "telemetry",
            ModuleConfig.Builder().also { wb ->
                wb.telemetry = TelemetryConfig.Builder().build()
            }.build().sectionKey(),
        )
        assertEquals(null, ModuleConfig.Builder().build().sectionKey())
    }

    @Test
    fun sectionKey_everyModuleConfigArmHasADistinctKey() {
        val arms: List<ModuleConfig> = listOf(
            ModuleConfig.Builder().also { wb -> wb.mqtt = MQTTConfig.Builder().build() }.build(),
            ModuleConfig.Builder().also { wb -> wb.serial = ModuleConfig.SerialConfig.Builder().build() }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.external_notification = ModuleConfig.ExternalNotificationConfig.Builder().build()
            }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.store_forward = ModuleConfig.StoreForwardConfig.Builder().build()
            }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.range_test = ModuleConfig.RangeTestConfig.Builder().build()
            }.build(),
            ModuleConfig.Builder().also { wb -> wb.telemetry = TelemetryConfig.Builder().build() }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.canned_message = ModuleConfig.CannedMessageConfig.Builder().build()
            }.build(),
            ModuleConfig.Builder().also { wb -> wb.audio = ModuleConfig.AudioConfig.Builder().build() }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.remote_hardware = ModuleConfig.RemoteHardwareConfig.Builder().build()
            }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.neighbor_info = ModuleConfig.NeighborInfoConfig.Builder().build()
            }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.ambient_lighting = ModuleConfig.AmbientLightingConfig.Builder().build()
            }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.detection_sensor = ModuleConfig.DetectionSensorConfig.Builder().build()
            }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.paxcounter = ModuleConfig.PaxcounterConfig.Builder().build()
            }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.statusmessage = ModuleConfig.StatusMessageConfig.Builder().build()
            }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.traffic_management = ModuleConfig.TrafficManagementConfig.Builder().build()
            }.build(),
            ModuleConfig.Builder().also { wb -> wb.tak = ModuleConfig.TAKConfig.Builder().build() }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.mesh_beacon = ModuleConfig.MeshBeaconConfig.Builder().build()
            }.build(),
        )
        val keys = arms.map { it.sectionKey() }
        assertTrue(keys.all { it != null }, "arms without a key: ${arms.filterIndexed { i, _ -> keys[i] == null }}")
        assertEquals(arms.size, keys.toSet().size)
    }

    @Test
    fun sectionKey_everyConfigArmHasADistinctKey() {
        val arms: List<Config> = listOf(
            Config.Builder().also { wb -> wb.device = DeviceConfig.Builder().build() }.build(),
            Config.Builder().also { wb -> wb.position = Config.PositionConfig.Builder().build() }.build(),
            Config.Builder().also { wb -> wb.power = PowerConfig.Builder().build() }.build(),
            Config.Builder().also { wb -> wb.network = Config.NetworkConfig.Builder().build() }.build(),
            Config.Builder().also { wb -> wb.display = DisplayConfig.Builder().build() }.build(),
            Config.Builder().also { wb -> wb.lora = LoRaConfig.Builder().build() }.build(),
            Config.Builder().also { wb -> wb.bluetooth = BluetoothConfig.Builder().build() }.build(),
            Config.Builder().also { wb -> wb.security = Config.SecurityConfig.Builder().build() }.build(),
            Config.Builder().also { wb -> wb.sessionkey = Config.SessionkeyConfig.Builder().build() }.build(),
            Config.Builder().also { wb -> wb.device_ui = DeviceUIConfig.Builder().build() }.build(),
        )
        val keys = arms.map { it.sectionKey() }
        assertTrue(keys.all { it != null }, "arms without a key: ${arms.filterIndexed { i, _ -> keys[i] == null }}")
        assertEquals(arms.size, keys.toSet().size)
    }

    @Test
    fun mergeModuleConfigs_replacesTakAndMeshBeaconSections() {
        val existing = listOf(
            ModuleConfig.Builder().also { wb ->
                wb.tak = ModuleConfig.TAKConfig.Builder().also { wb -> wb.team = Team.White }.build()
            }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.mesh_beacon = ModuleConfig.MeshBeaconConfig.Builder().also { wb -> wb.flags = 1 }.build()
            }.build(),
        )
        val written = listOf(
            ModuleConfig.Builder().also { wb ->
                wb.tak = ModuleConfig.TAKConfig.Builder().also { wb -> wb.team = Team.Yellow }.build()
            }.build(),
            ModuleConfig.Builder().also { wb ->
                wb.mesh_beacon = ModuleConfig.MeshBeaconConfig.Builder().also { wb -> wb.flags = 2 }.build()
            }.build(),
        )
        val merged = mergeModuleConfigs(existing, written)

        assertEquals(2, merged.size)
        assertEquals(Team.Yellow, merged[0].tak?.team)
        assertEquals(2, merged[1].mesh_beacon?.flags)
    }
}
