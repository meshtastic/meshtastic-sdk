/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.Routing

/**
 * Read-modify-write helpers for single config sections.
 *
 * Each helper reads the target node's current section (`getConfig` / `getModuleConfig`), passes
 * it to `block` as the receiver, and writes back what `block` returns. The firmware replaces a
 * whole section on `set_config`, so only the fields `block` changes are changed:
 *
 * ```kotlin
 * client.admin.setLoraConfig {
 *     newBuilder().also { wb -> wb.region = Config.LoRaConfig.RegionCode.US }.build()
 * }
 *
 * client.admin.setMqttConfig {
 *     newBuilder().also { wb -> wb.enabled = true }.build()
 * }
 * ```
 *
 * Nothing is written when the read fails; its failure is returned. A reply that doesn't carry
 * the requested section resolves as `Failed(NO_RESPONSE)`. These are two round trips each; inside
 * [AdminApi.editSettings] / [AdminApi.batch], read with `getConfig` and write with `setConfig`.
 */
private suspend fun <T : Any> AdminApi.updateConfigSection(
    type: AdminMessage.ConfigType,
    section: (Config) -> T?,
    block: T.() -> T,
    wrap: (T) -> Config,
): AdminResult<Unit> {
    val read = getConfig(type)
    if (read !is AdminResult.Success) return read.map { }
    val current = section(read.value) ?: return AdminResult.Failed(Routing.Error.NO_RESPONSE)
    return setConfig(wrap(current.block()))
}

private suspend fun <T : Any> AdminApi.updateModuleConfigSection(
    type: AdminMessage.ModuleConfigType,
    section: (ModuleConfig) -> T?,
    block: T.() -> T,
    wrap: (T) -> ModuleConfig,
): AdminResult<Unit> {
    val read = getModuleConfig(type)
    if (read !is AdminResult.Success) return read.map { }
    val current = section(read.value) ?: return AdminResult.Failed(Routing.Error.NO_RESPONSE)
    return setModuleConfig(wrap(current.block()))
}

/** Convenience: read the current [Config.DeviceConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setDeviceConfig(block: Config.DeviceConfig.() -> Config.DeviceConfig): AdminResult<Unit> =
    updateConfigSection(AdminMessage.ConfigType.DEVICE_CONFIG, { it.device }, block) {
        Config.Builder().also { wb ->
            wb.device = it
        }.build()
    }

/** Convenience: read the current [Config.PositionConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setPositionConfig(
    block: Config.PositionConfig.() -> Config.PositionConfig,
): AdminResult<Unit> = updateConfigSection(AdminMessage.ConfigType.POSITION_CONFIG, { it.position }, block) {
    Config.Builder().also { wb ->
        wb.position = it
    }.build()
}

/** Convenience: read the current [Config.PowerConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setPowerConfig(block: Config.PowerConfig.() -> Config.PowerConfig): AdminResult<Unit> =
    updateConfigSection(AdminMessage.ConfigType.POWER_CONFIG, { it.power }, block) {
        Config.Builder().also { wb ->
            wb.power = it
        }.build()
    }

/** Convenience: read the current [Config.NetworkConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setNetworkConfig(
    block: Config.NetworkConfig.() -> Config.NetworkConfig,
): AdminResult<Unit> = updateConfigSection(AdminMessage.ConfigType.NETWORK_CONFIG, { it.network }, block) {
    Config.Builder().also { wb ->
        wb.network = it
    }.build()
}

/** Convenience: read the current [Config.DisplayConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setDisplayConfig(
    block: Config.DisplayConfig.() -> Config.DisplayConfig,
): AdminResult<Unit> = updateConfigSection(AdminMessage.ConfigType.DISPLAY_CONFIG, { it.display }, block) {
    Config.Builder().also { wb ->
        wb.display = it
    }.build()
}

/** Convenience: read the current [Config.LoRaConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setLoraConfig(block: Config.LoRaConfig.() -> Config.LoRaConfig): AdminResult<Unit> =
    updateConfigSection(AdminMessage.ConfigType.LORA_CONFIG, { it.lora }, block) {
        Config.Builder().also { wb ->
            wb.lora = it
        }.build()
    }

/** Convenience: read the current [Config.BluetoothConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setBluetoothConfig(
    block: Config.BluetoothConfig.() -> Config.BluetoothConfig,
): AdminResult<Unit> = updateConfigSection(AdminMessage.ConfigType.BLUETOOTH_CONFIG, { it.bluetooth }, block) {
    Config.Builder().also { wb ->
        wb.bluetooth = it
    }.build()
}

/** Convenience: read the current [Config.SecurityConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setSecurityConfig(
    block: Config.SecurityConfig.() -> Config.SecurityConfig,
): AdminResult<Unit> = updateConfigSection(AdminMessage.ConfigType.SECURITY_CONFIG, { it.security }, block) {
    Config.Builder().also { wb ->
        wb.security = it
    }.build()
}

/** Convenience: read the current [ModuleConfig.MQTTConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setMqttConfig(
    block: ModuleConfig.MQTTConfig.() -> ModuleConfig.MQTTConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.MQTT_CONFIG, { it.mqtt }, block) {
    ModuleConfig.Builder().also { wb ->
        wb.mqtt = it
    }.build()
}

/** Convenience: read the current [ModuleConfig.SerialConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setSerialConfig(
    block: ModuleConfig.SerialConfig.() -> ModuleConfig.SerialConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.SERIAL_CONFIG, { it.serial }, block) {
    ModuleConfig.Builder().also { wb ->
        wb.serial = it
    }.build()
}

/** Convenience: read the current [ModuleConfig.ExternalNotificationConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setExternalNotificationConfig(
    block: ModuleConfig.ExternalNotificationConfig.() -> ModuleConfig.ExternalNotificationConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.EXTNOTIF_CONFIG, {
    it.external_notification
}, block) {
    ModuleConfig.Builder().also { wb -> wb.external_notification = it }.build()
}

/** Convenience: read the current [ModuleConfig.StoreForwardConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setStoreForwardConfig(
    block: ModuleConfig.StoreForwardConfig.() -> ModuleConfig.StoreForwardConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.STOREFORWARD_CONFIG, {
    it.store_forward
}, block) {
    ModuleConfig.Builder().also { wb -> wb.store_forward = it }.build()
}

/** Convenience: read the current [ModuleConfig.RangeTestConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setRangeTestConfig(
    block: ModuleConfig.RangeTestConfig.() -> ModuleConfig.RangeTestConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.RANGETEST_CONFIG, {
    it.range_test
}, block) {
    ModuleConfig.Builder().also { wb ->
        wb.range_test = it
    }.build()
}

/** Convenience: read the current [ModuleConfig.TelemetryConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setTelemetryConfig(
    block: ModuleConfig.TelemetryConfig.() -> ModuleConfig.TelemetryConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.TELEMETRY_CONFIG, {
    it.telemetry
}, block) {
    ModuleConfig.Builder().also { wb ->
        wb.telemetry = it
    }.build()
}

/** Convenience: read the current [ModuleConfig.CannedMessageConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setCannedMessageConfig(
    block: ModuleConfig.CannedMessageConfig.() -> ModuleConfig.CannedMessageConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.CANNEDMSG_CONFIG, {
    it.canned_message
}, block) {
    ModuleConfig.Builder().also { wb -> wb.canned_message = it }.build()
}

/** Convenience: read the current [ModuleConfig.AudioConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setAudioConfig(
    block: ModuleConfig.AudioConfig.() -> ModuleConfig.AudioConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.AUDIO_CONFIG, { it.audio }, block) {
    ModuleConfig.Builder().also { wb ->
        wb.audio = it
    }.build()
}

/** Convenience: read the current [ModuleConfig.RemoteHardwareConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setRemoteHardwareConfig(
    block: ModuleConfig.RemoteHardwareConfig.() -> ModuleConfig.RemoteHardwareConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.REMOTEHARDWARE_CONFIG, {
    it.remote_hardware
}, block) {
    ModuleConfig.Builder().also { wb -> wb.remote_hardware = it }.build()
}

/** Convenience: read the current [ModuleConfig.NeighborInfoConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setNeighborInfoConfig(
    block: ModuleConfig.NeighborInfoConfig.() -> ModuleConfig.NeighborInfoConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.NEIGHBORINFO_CONFIG, {
    it.neighbor_info
}, block) {
    ModuleConfig.Builder().also { wb -> wb.neighbor_info = it }.build()
}

/** Convenience: read the current [ModuleConfig.AmbientLightingConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setAmbientLightingConfig(
    block: ModuleConfig.AmbientLightingConfig.() -> ModuleConfig.AmbientLightingConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.AMBIENTLIGHTING_CONFIG, {
    it.ambient_lighting
}, block) {
    ModuleConfig.Builder().also { wb -> wb.ambient_lighting = it }.build()
}

/** Convenience: read the current [ModuleConfig.DetectionSensorConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setDetectionSensorConfig(
    block: ModuleConfig.DetectionSensorConfig.() -> ModuleConfig.DetectionSensorConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.DETECTIONSENSOR_CONFIG, {
    it.detection_sensor
}, block) {
    ModuleConfig.Builder().also { wb -> wb.detection_sensor = it }.build()
}

/** Convenience: read the current [ModuleConfig.PaxcounterConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setPaxcounterConfig(
    block: ModuleConfig.PaxcounterConfig.() -> ModuleConfig.PaxcounterConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.PAXCOUNTER_CONFIG, {
    it.paxcounter
}, block) {
    ModuleConfig.Builder().also { wb -> wb.paxcounter = it }.build()
}

/** Convenience: read the current [ModuleConfig.StatusMessageConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setStatusMessageConfig(
    block: ModuleConfig.StatusMessageConfig.() -> ModuleConfig.StatusMessageConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.STATUSMESSAGE_CONFIG, {
    it.statusmessage
}, block) {
    ModuleConfig.Builder().also { wb -> wb.statusmessage = it }.build()
}

/** Convenience: read the current [ModuleConfig.TrafficManagementConfig], change it with `block`, and write it back. */
public suspend fun AdminApi.setTrafficManagementConfig(
    block: ModuleConfig.TrafficManagementConfig.() -> ModuleConfig.TrafficManagementConfig,
): AdminResult<Unit> = updateModuleConfigSection(AdminMessage.ModuleConfigType.TRAFFICMANAGEMENT_CONFIG, {
    it.traffic_management
}, block) {
    ModuleConfig.Builder().also { wb -> wb.traffic_management = it }.build()
}
