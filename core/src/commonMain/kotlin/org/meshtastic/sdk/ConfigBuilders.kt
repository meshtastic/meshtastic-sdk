/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig

/**
 * Convenience builders for common admin config writes.
 *
 * ```kotlin
 * client.admin.setDeviceConfig {
 *     copy(role = Config.DeviceConfig.Role.CLIENT)
 * }
 *
 * client.admin.setMqttConfig {
 *     copy(enabled = true)
 * }
 * ```
 */
private suspend fun <T> AdminApi.setConfigSection(
    initial: T,
    block: T.() -> T,
    wrap: (T) -> Config,
): AdminResult<Unit> = setConfig(wrap(initial.block()))

private suspend fun <T> AdminApi.setModuleConfigSection(
    initial: T,
    block: T.() -> T,
    wrap: (T) -> ModuleConfig,
): AdminResult<Unit> = setModuleConfig(wrap(initial.block()))

/** Convenience: build and send a [Config.DeviceConfig] in a single call. */
public suspend fun AdminApi.setDeviceConfig(block: Config.DeviceConfig.() -> Config.DeviceConfig): AdminResult<Unit> =
    setConfigSection(Config.DeviceConfig.Builder().build(), block) { Config.Builder().also { wb ->wb.device = it}.build() }

/** Convenience: build and send a [Config.PositionConfig] in a single call. */
public suspend fun AdminApi.setPositionConfig(
    block: Config.PositionConfig.() -> Config.PositionConfig,
): AdminResult<Unit> = setConfigSection(Config.PositionConfig.Builder().build(), block) { Config.Builder().also { wb ->wb.position = it}.build() }

/** Convenience: build and send a [Config.PowerConfig] in a single call. */
public suspend fun AdminApi.setPowerConfig(block: Config.PowerConfig.() -> Config.PowerConfig): AdminResult<Unit> =
    setConfigSection(Config.PowerConfig.Builder().build(), block) { Config.Builder().also { wb ->wb.power = it}.build() }

/** Convenience: build and send a [Config.NetworkConfig] in a single call. */
public suspend fun AdminApi.setNetworkConfig(
    block: Config.NetworkConfig.() -> Config.NetworkConfig,
): AdminResult<Unit> = setConfigSection(Config.NetworkConfig.Builder().build(), block) { Config.Builder().also { wb ->wb.network = it}.build() }

/** Convenience: build and send a [Config.DisplayConfig] in a single call. */
public suspend fun AdminApi.setDisplayConfig(
    block: Config.DisplayConfig.() -> Config.DisplayConfig,
): AdminResult<Unit> = setConfigSection(Config.DisplayConfig.Builder().build(), block) { Config.Builder().also { wb ->wb.display = it}.build() }

/** Convenience: build and send a [Config.LoRaConfig] in a single call. */
public suspend fun AdminApi.setLoraConfig(block: Config.LoRaConfig.() -> Config.LoRaConfig): AdminResult<Unit> =
    setConfigSection(Config.LoRaConfig.Builder().build(), block) { Config.Builder().also { wb ->wb.lora = it}.build() }

/** Convenience: build and send a [Config.BluetoothConfig] in a single call. */
public suspend fun AdminApi.setBluetoothConfig(
    block: Config.BluetoothConfig.() -> Config.BluetoothConfig,
): AdminResult<Unit> = setConfigSection(Config.BluetoothConfig.Builder().build(), block) { Config.Builder().also { wb ->wb.bluetooth = it}.build() }

/** Convenience: build and send a [Config.SecurityConfig] in a single call. */
public suspend fun AdminApi.setSecurityConfig(
    block: Config.SecurityConfig.() -> Config.SecurityConfig,
): AdminResult<Unit> = setConfigSection(Config.SecurityConfig.Builder().build(), block) { Config.Builder().also { wb ->wb.security = it}.build() }

/** Convenience: build and send a [ModuleConfig.MQTTConfig] in a single call. */
public suspend fun AdminApi.setMqttConfig(
    block: ModuleConfig.MQTTConfig.() -> ModuleConfig.MQTTConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.MQTTConfig.Builder().build(), block) { ModuleConfig.Builder().also { wb ->wb.mqtt = it}.build() }

/** Convenience: build and send a [ModuleConfig.SerialConfig] in a single call. */
public suspend fun AdminApi.setSerialConfig(
    block: ModuleConfig.SerialConfig.() -> ModuleConfig.SerialConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.SerialConfig.Builder().build(), block) { ModuleConfig.Builder().also { wb ->wb.serial = it}.build() }

/** Convenience: build and send a [ModuleConfig.ExternalNotificationConfig] in a single call. */
public suspend fun AdminApi.setExternalNotificationConfig(
    block: ModuleConfig.ExternalNotificationConfig.() -> ModuleConfig.ExternalNotificationConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.ExternalNotificationConfig.Builder().build(), block) {
    ModuleConfig.Builder().also { wb ->wb.external_notification = it}.build()
}

/** Convenience: build and send a [ModuleConfig.StoreForwardConfig] in a single call. */
public suspend fun AdminApi.setStoreForwardConfig(
    block: ModuleConfig.StoreForwardConfig.() -> ModuleConfig.StoreForwardConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.StoreForwardConfig.Builder().build(), block) {
    ModuleConfig.Builder().also { wb ->wb.store_forward = it}.build()
}

/** Convenience: build and send a [ModuleConfig.RangeTestConfig] in a single call. */
public suspend fun AdminApi.setRangeTestConfig(
    block: ModuleConfig.RangeTestConfig.() -> ModuleConfig.RangeTestConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.RangeTestConfig.Builder().build(), block) { ModuleConfig.Builder().also { wb ->wb.range_test = it}.build() }

/** Convenience: build and send a [ModuleConfig.TelemetryConfig] in a single call. */
public suspend fun AdminApi.setTelemetryConfig(
    block: ModuleConfig.TelemetryConfig.() -> ModuleConfig.TelemetryConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.TelemetryConfig.Builder().build(), block) { ModuleConfig.Builder().also { wb ->wb.telemetry = it}.build() }

/** Convenience: build and send a [ModuleConfig.CannedMessageConfig] in a single call. */
public suspend fun AdminApi.setCannedMessageConfig(
    block: ModuleConfig.CannedMessageConfig.() -> ModuleConfig.CannedMessageConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.CannedMessageConfig.Builder().build(), block) {
    ModuleConfig.Builder().also { wb ->wb.canned_message = it}.build()
}

/** Convenience: build and send a [ModuleConfig.AudioConfig] in a single call. */
public suspend fun AdminApi.setAudioConfig(
    block: ModuleConfig.AudioConfig.() -> ModuleConfig.AudioConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.AudioConfig.Builder().build(), block) { ModuleConfig.Builder().also { wb ->wb.audio = it}.build() }

/** Convenience: build and send a [ModuleConfig.RemoteHardwareConfig] in a single call. */
public suspend fun AdminApi.setRemoteHardwareConfig(
    block: ModuleConfig.RemoteHardwareConfig.() -> ModuleConfig.RemoteHardwareConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.RemoteHardwareConfig.Builder().build(), block) {
    ModuleConfig.Builder().also { wb ->wb.remote_hardware = it}.build()
}

/** Convenience: build and send a [ModuleConfig.NeighborInfoConfig] in a single call. */
public suspend fun AdminApi.setNeighborInfoConfig(
    block: ModuleConfig.NeighborInfoConfig.() -> ModuleConfig.NeighborInfoConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.NeighborInfoConfig.Builder().build(), block) {
    ModuleConfig.Builder().also { wb ->wb.neighbor_info = it}.build()
}

/** Convenience: build and send a [ModuleConfig.AmbientLightingConfig] in a single call. */
public suspend fun AdminApi.setAmbientLightingConfig(
    block: ModuleConfig.AmbientLightingConfig.() -> ModuleConfig.AmbientLightingConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.AmbientLightingConfig.Builder().build(), block) {
    ModuleConfig.Builder().also { wb ->wb.ambient_lighting = it}.build()
}

/** Convenience: build and send a [ModuleConfig.DetectionSensorConfig] in a single call. */
public suspend fun AdminApi.setDetectionSensorConfig(
    block: ModuleConfig.DetectionSensorConfig.() -> ModuleConfig.DetectionSensorConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.DetectionSensorConfig.Builder().build(), block) {
    ModuleConfig.Builder().also { wb ->wb.detection_sensor = it}.build()
}

/** Convenience: build and send a [ModuleConfig.PaxcounterConfig] in a single call. */
public suspend fun AdminApi.setPaxcounterConfig(
    block: ModuleConfig.PaxcounterConfig.() -> ModuleConfig.PaxcounterConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.PaxcounterConfig.Builder().build(), block) {
    ModuleConfig.Builder().also { wb ->wb.paxcounter = it}.build()
}

/** Convenience: build and send a [ModuleConfig.StatusMessageConfig] in a single call. */
public suspend fun AdminApi.setStatusMessageConfig(
    block: ModuleConfig.StatusMessageConfig.() -> ModuleConfig.StatusMessageConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.StatusMessageConfig.Builder().build(), block) {
    ModuleConfig.Builder().also { wb ->wb.statusmessage = it}.build()
}

/** Convenience: build and send a [ModuleConfig.TrafficManagementConfig] in a single call. */
public suspend fun AdminApi.setTrafficManagementConfig(
    block: ModuleConfig.TrafficManagementConfig.() -> ModuleConfig.TrafficManagementConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.TrafficManagementConfig.Builder().build(), block) {
    ModuleConfig.Builder().also { wb ->wb.traffic_management = it}.build()
}
