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
    setConfigSection(Config.DeviceConfig(), block) { Config(device = it) }

/** Convenience: build and send a [Config.PositionConfig] in a single call. */
public suspend fun AdminApi.setPositionConfig(
    block: Config.PositionConfig.() -> Config.PositionConfig,
): AdminResult<Unit> = setConfigSection(Config.PositionConfig(), block) { Config(position = it) }

/** Convenience: build and send a [Config.PowerConfig] in a single call. */
public suspend fun AdminApi.setPowerConfig(block: Config.PowerConfig.() -> Config.PowerConfig): AdminResult<Unit> =
    setConfigSection(Config.PowerConfig(), block) { Config(power = it) }

/** Convenience: build and send a [Config.NetworkConfig] in a single call. */
public suspend fun AdminApi.setNetworkConfig(
    block: Config.NetworkConfig.() -> Config.NetworkConfig,
): AdminResult<Unit> = setConfigSection(Config.NetworkConfig(), block) { Config(network = it) }

/** Convenience: build and send a [Config.DisplayConfig] in a single call. */
public suspend fun AdminApi.setDisplayConfig(
    block: Config.DisplayConfig.() -> Config.DisplayConfig,
): AdminResult<Unit> = setConfigSection(Config.DisplayConfig(), block) { Config(display = it) }

/** Convenience: build and send a [Config.LoRaConfig] in a single call. */
public suspend fun AdminApi.setLoraConfig(block: Config.LoRaConfig.() -> Config.LoRaConfig): AdminResult<Unit> =
    setConfigSection(Config.LoRaConfig(), block) { Config(lora = it) }

/** Convenience: build and send a [Config.BluetoothConfig] in a single call. */
public suspend fun AdminApi.setBluetoothConfig(
    block: Config.BluetoothConfig.() -> Config.BluetoothConfig,
): AdminResult<Unit> = setConfigSection(Config.BluetoothConfig(), block) { Config(bluetooth = it) }

/** Convenience: build and send a [Config.SecurityConfig] in a single call. */
public suspend fun AdminApi.setSecurityConfig(
    block: Config.SecurityConfig.() -> Config.SecurityConfig,
): AdminResult<Unit> = setConfigSection(Config.SecurityConfig(), block) { Config(security = it) }

/** Convenience: build and send a [ModuleConfig.MQTTConfig] in a single call. */
public suspend fun AdminApi.setMqttConfig(
    block: ModuleConfig.MQTTConfig.() -> ModuleConfig.MQTTConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.MQTTConfig(), block) { ModuleConfig(mqtt = it) }

/** Convenience: build and send a [ModuleConfig.SerialConfig] in a single call. */
public suspend fun AdminApi.setSerialConfig(
    block: ModuleConfig.SerialConfig.() -> ModuleConfig.SerialConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.SerialConfig(), block) { ModuleConfig(serial = it) }

/** Convenience: build and send a [ModuleConfig.ExternalNotificationConfig] in a single call. */
public suspend fun AdminApi.setExternalNotificationConfig(
    block: ModuleConfig.ExternalNotificationConfig.() -> ModuleConfig.ExternalNotificationConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.ExternalNotificationConfig(), block) {
    ModuleConfig(external_notification = it)
}

/** Convenience: build and send a [ModuleConfig.StoreForwardConfig] in a single call. */
public suspend fun AdminApi.setStoreForwardConfig(
    block: ModuleConfig.StoreForwardConfig.() -> ModuleConfig.StoreForwardConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.StoreForwardConfig(), block) {
    ModuleConfig(store_forward = it)
}

/** Convenience: build and send a [ModuleConfig.RangeTestConfig] in a single call. */
public suspend fun AdminApi.setRangeTestConfig(
    block: ModuleConfig.RangeTestConfig.() -> ModuleConfig.RangeTestConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.RangeTestConfig(), block) { ModuleConfig(range_test = it) }

/** Convenience: build and send a [ModuleConfig.TelemetryConfig] in a single call. */
public suspend fun AdminApi.setTelemetryConfig(
    block: ModuleConfig.TelemetryConfig.() -> ModuleConfig.TelemetryConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.TelemetryConfig(), block) { ModuleConfig(telemetry = it) }

/** Convenience: build and send a [ModuleConfig.CannedMessageConfig] in a single call. */
public suspend fun AdminApi.setCannedMessageConfig(
    block: ModuleConfig.CannedMessageConfig.() -> ModuleConfig.CannedMessageConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.CannedMessageConfig(), block) {
    ModuleConfig(canned_message = it)
}

/** Convenience: build and send a [ModuleConfig.AudioConfig] in a single call. */
public suspend fun AdminApi.setAudioConfig(
    block: ModuleConfig.AudioConfig.() -> ModuleConfig.AudioConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.AudioConfig(), block) { ModuleConfig(audio = it) }

/** Convenience: build and send a [ModuleConfig.RemoteHardwareConfig] in a single call. */
public suspend fun AdminApi.setRemoteHardwareConfig(
    block: ModuleConfig.RemoteHardwareConfig.() -> ModuleConfig.RemoteHardwareConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.RemoteHardwareConfig(), block) {
    ModuleConfig(remote_hardware = it)
}

/** Convenience: build and send a [ModuleConfig.NeighborInfoConfig] in a single call. */
public suspend fun AdminApi.setNeighborInfoConfig(
    block: ModuleConfig.NeighborInfoConfig.() -> ModuleConfig.NeighborInfoConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.NeighborInfoConfig(), block) {
    ModuleConfig(neighbor_info = it)
}

/** Convenience: build and send a [ModuleConfig.AmbientLightingConfig] in a single call. */
public suspend fun AdminApi.setAmbientLightingConfig(
    block: ModuleConfig.AmbientLightingConfig.() -> ModuleConfig.AmbientLightingConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.AmbientLightingConfig(), block) {
    ModuleConfig(ambient_lighting = it)
}

/** Convenience: build and send a [ModuleConfig.DetectionSensorConfig] in a single call. */
public suspend fun AdminApi.setDetectionSensorConfig(
    block: ModuleConfig.DetectionSensorConfig.() -> ModuleConfig.DetectionSensorConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.DetectionSensorConfig(), block) {
    ModuleConfig(detection_sensor = it)
}

/** Convenience: build and send a [ModuleConfig.PaxcounterConfig] in a single call. */
public suspend fun AdminApi.setPaxcounterConfig(
    block: ModuleConfig.PaxcounterConfig.() -> ModuleConfig.PaxcounterConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.PaxcounterConfig(), block) {
    ModuleConfig(paxcounter = it)
}

/** Convenience: build and send a [ModuleConfig.StatusMessageConfig] in a single call. */
public suspend fun AdminApi.setStatusMessageConfig(
    block: ModuleConfig.StatusMessageConfig.() -> ModuleConfig.StatusMessageConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.StatusMessageConfig(), block) {
    ModuleConfig(statusmessage = it)
}

/** Convenience: build and send a [ModuleConfig.TrafficManagementConfig] in a single call. */
public suspend fun AdminApi.setTrafficManagementConfig(
    block: ModuleConfig.TrafficManagementConfig.() -> ModuleConfig.TrafficManagementConfig,
): AdminResult<Unit> = setModuleConfigSection(ModuleConfig.TrafficManagementConfig(), block) {
    ModuleConfig(traffic_management = it)
}
