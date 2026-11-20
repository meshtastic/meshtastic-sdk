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
 * Convenience builders for config writes.
 *
 * Each builder reads the section's current value from the device this [AdminApi] targets
 * (with [AdminApi.getConfig] or [AdminApi.getModuleConfig]), passes it to the block as the
 * receiver, and writes the block's result back with [AdminApi.setConfig] or
 * [AdminApi.setModuleConfig]. The device replaces the whole section on a write, so fields the
 * block leaves alone keep their current values. Protos are Wire builders-only, so the block
 * edits through `newBuilder()`:
 *
 * ```kotlin
 * client.admin.setLoraConfig { newBuilder().hop_limit(5).build() }
 *
 * client.admin.forNode(remote).setMqttConfig { newBuilder().enabled(true).build() }
 * ```
 *
 * If the read is not an [AdminResult.Success], the builder returns that result and sends
 * nothing. If the read succeeds but does not carry the requested section, it returns
 * [AdminResult.Failed] with [Routing.Error.NO_RESPONSE] and sends nothing.
 */
private suspend fun <T : Any> AdminApi.setConfigSection(
    type: AdminMessage.ConfigType,
    section: (Config) -> T?,
    block: T.() -> T,
    wrap: (T) -> Config,
): AdminResult<Unit> {
    val current = when (val read = getConfig(type)) {
        is AdminResult.Success -> section(read.value) ?: return AdminResult.Failed(Routing.Error.NO_RESPONSE)
        else -> return read.map {}
    }
    return setConfig(wrap(current.block()))
}

private suspend fun <T : Any> AdminApi.setModuleConfigSection(
    type: AdminMessage.ModuleConfigType,
    section: (ModuleConfig) -> T?,
    block: T.() -> T,
    wrap: (T) -> ModuleConfig,
): AdminResult<Unit> {
    val current = when (val read = getModuleConfig(type)) {
        is AdminResult.Success -> section(read.value) ?: return AdminResult.Failed(Routing.Error.NO_RESPONSE)
        else -> return read.map {}
    }
    return setModuleConfig(wrap(current.block()))
}

/** Read the device's [Config.DeviceConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setDeviceConfig(block: Config.DeviceConfig.() -> Config.DeviceConfig): AdminResult<Unit> =
    setConfigSection(AdminMessage.ConfigType.DEVICE_CONFIG, { it.device }, block) {
        Config.Builder().also { wb ->
            wb.device = it
        }.build()
    }

/** Read the device's [Config.PositionConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setPositionConfig(
    block: Config.PositionConfig.() -> Config.PositionConfig,
): AdminResult<Unit> = setConfigSection(AdminMessage.ConfigType.POSITION_CONFIG, { it.position }, block) {
    Config.Builder().also { wb ->
        wb.position = it
    }.build()
}

/** Read the device's [Config.PowerConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setPowerConfig(block: Config.PowerConfig.() -> Config.PowerConfig): AdminResult<Unit> =
    setConfigSection(AdminMessage.ConfigType.POWER_CONFIG, { it.power }, block) {
        Config.Builder().also { wb ->
            wb.power = it
        }.build()
    }

/** Read the device's [Config.NetworkConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setNetworkConfig(
    block: Config.NetworkConfig.() -> Config.NetworkConfig,
): AdminResult<Unit> = setConfigSection(AdminMessage.ConfigType.NETWORK_CONFIG, { it.network }, block) {
    Config.Builder().also { wb ->
        wb.network = it
    }.build()
}

/** Read the device's [Config.DisplayConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setDisplayConfig(
    block: Config.DisplayConfig.() -> Config.DisplayConfig,
): AdminResult<Unit> = setConfigSection(AdminMessage.ConfigType.DISPLAY_CONFIG, { it.display }, block) {
    Config.Builder().also { wb ->
        wb.display = it
    }.build()
}

/** Read the device's [Config.LoRaConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setLoraConfig(block: Config.LoRaConfig.() -> Config.LoRaConfig): AdminResult<Unit> =
    setConfigSection(AdminMessage.ConfigType.LORA_CONFIG, { it.lora }, block) {
        Config.Builder().also { wb ->
            wb.lora = it
        }.build()
    }

/** Read the device's [Config.BluetoothConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setBluetoothConfig(
    block: Config.BluetoothConfig.() -> Config.BluetoothConfig,
): AdminResult<Unit> = setConfigSection(AdminMessage.ConfigType.BLUETOOTH_CONFIG, { it.bluetooth }, block) {
    Config.Builder().also { wb ->
        wb.bluetooth = it
    }.build()
}

/** Read the device's [Config.SecurityConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setSecurityConfig(
    block: Config.SecurityConfig.() -> Config.SecurityConfig,
): AdminResult<Unit> = setConfigSection(AdminMessage.ConfigType.SECURITY_CONFIG, { it.security }, block) {
    Config.Builder().also { wb ->
        wb.security = it
    }.build()
}

/** Read the device's [ModuleConfig.MQTTConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setMqttConfig(
    block: ModuleConfig.MQTTConfig.() -> ModuleConfig.MQTTConfig,
): AdminResult<Unit> = setModuleConfigSection(AdminMessage.ModuleConfigType.MQTT_CONFIG, { it.mqtt }, block) {
    ModuleConfig.Builder().also { wb ->
        wb.mqtt = it
    }.build()
}

/** Read the device's [ModuleConfig.SerialConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setSerialConfig(
    block: ModuleConfig.SerialConfig.() -> ModuleConfig.SerialConfig,
): AdminResult<Unit> = setModuleConfigSection(AdminMessage.ModuleConfigType.SERIAL_CONFIG, { it.serial }, block) {
    ModuleConfig.Builder().also { wb ->
        wb.serial = it
    }.build()
}

/** Read the device's [ModuleConfig.ExternalNotificationConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setExternalNotificationConfig(
    block: ModuleConfig.ExternalNotificationConfig.() -> ModuleConfig.ExternalNotificationConfig,
): AdminResult<Unit> =
    setModuleConfigSection(AdminMessage.ModuleConfigType.EXTNOTIF_CONFIG, { it.external_notification }, block) {
        ModuleConfig.Builder().also { wb -> wb.external_notification = it }.build()
    }

/** Read the device's [ModuleConfig.StoreForwardConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setStoreForwardConfig(
    block: ModuleConfig.StoreForwardConfig.() -> ModuleConfig.StoreForwardConfig,
): AdminResult<Unit> =
    setModuleConfigSection(AdminMessage.ModuleConfigType.STOREFORWARD_CONFIG, { it.store_forward }, block) {
        ModuleConfig.Builder().also { wb -> wb.store_forward = it }.build()
    }

/** Read the device's [ModuleConfig.RangeTestConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setRangeTestConfig(
    block: ModuleConfig.RangeTestConfig.() -> ModuleConfig.RangeTestConfig,
): AdminResult<Unit> =
    setModuleConfigSection(AdminMessage.ModuleConfigType.RANGETEST_CONFIG, { it.range_test }, block) {
        ModuleConfig.Builder().also { wb ->
            wb.range_test = it
        }.build()
    }

/** Read the device's [ModuleConfig.TelemetryConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setTelemetryConfig(
    block: ModuleConfig.TelemetryConfig.() -> ModuleConfig.TelemetryConfig,
): AdminResult<Unit> = setModuleConfigSection(AdminMessage.ModuleConfigType.TELEMETRY_CONFIG, { it.telemetry }, block) {
    ModuleConfig.Builder().also { wb ->
        wb.telemetry = it
    }.build()
}

/** Read the device's [ModuleConfig.CannedMessageConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setCannedMessageConfig(
    block: ModuleConfig.CannedMessageConfig.() -> ModuleConfig.CannedMessageConfig,
): AdminResult<Unit> =
    setModuleConfigSection(AdminMessage.ModuleConfigType.CANNEDMSG_CONFIG, { it.canned_message }, block) {
        ModuleConfig.Builder().also { wb -> wb.canned_message = it }.build()
    }

/** Read the device's [ModuleConfig.AudioConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setAudioConfig(
    block: ModuleConfig.AudioConfig.() -> ModuleConfig.AudioConfig,
): AdminResult<Unit> = setModuleConfigSection(AdminMessage.ModuleConfigType.AUDIO_CONFIG, { it.audio }, block) {
    ModuleConfig.Builder().also { wb ->
        wb.audio = it
    }.build()
}

/** Read the device's [ModuleConfig.RemoteHardwareConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setRemoteHardwareConfig(
    block: ModuleConfig.RemoteHardwareConfig.() -> ModuleConfig.RemoteHardwareConfig,
): AdminResult<Unit> =
    setModuleConfigSection(AdminMessage.ModuleConfigType.REMOTEHARDWARE_CONFIG, { it.remote_hardware }, block) {
        ModuleConfig.Builder().also { wb -> wb.remote_hardware = it }.build()
    }

/** Read the device's [ModuleConfig.NeighborInfoConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setNeighborInfoConfig(
    block: ModuleConfig.NeighborInfoConfig.() -> ModuleConfig.NeighborInfoConfig,
): AdminResult<Unit> =
    setModuleConfigSection(AdminMessage.ModuleConfigType.NEIGHBORINFO_CONFIG, { it.neighbor_info }, block) {
        ModuleConfig.Builder().also { wb -> wb.neighbor_info = it }.build()
    }

/** Read the device's [ModuleConfig.AmbientLightingConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setAmbientLightingConfig(
    block: ModuleConfig.AmbientLightingConfig.() -> ModuleConfig.AmbientLightingConfig,
): AdminResult<Unit> =
    setModuleConfigSection(AdminMessage.ModuleConfigType.AMBIENTLIGHTING_CONFIG, { it.ambient_lighting }, block) {
        ModuleConfig.Builder().also { wb -> wb.ambient_lighting = it }.build()
    }

/** Read the device's [ModuleConfig.DetectionSensorConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setDetectionSensorConfig(
    block: ModuleConfig.DetectionSensorConfig.() -> ModuleConfig.DetectionSensorConfig,
): AdminResult<Unit> =
    setModuleConfigSection(AdminMessage.ModuleConfigType.DETECTIONSENSOR_CONFIG, { it.detection_sensor }, block) {
        ModuleConfig.Builder().also { wb -> wb.detection_sensor = it }.build()
    }

/** Read the device's [ModuleConfig.PaxcounterConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setPaxcounterConfig(
    block: ModuleConfig.PaxcounterConfig.() -> ModuleConfig.PaxcounterConfig,
): AdminResult<Unit> =
    setModuleConfigSection(AdminMessage.ModuleConfigType.PAXCOUNTER_CONFIG, { it.paxcounter }, block) {
        ModuleConfig.Builder().also { wb -> wb.paxcounter = it }.build()
    }

/** Read the device's [ModuleConfig.StatusMessageConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setStatusMessageConfig(
    block: ModuleConfig.StatusMessageConfig.() -> ModuleConfig.StatusMessageConfig,
): AdminResult<Unit> =
    setModuleConfigSection(AdminMessage.ModuleConfigType.STATUSMESSAGE_CONFIG, { it.statusmessage }, block) {
        ModuleConfig.Builder().also { wb -> wb.statusmessage = it }.build()
    }

/** Read the device's [ModuleConfig.TrafficManagementConfig], apply [block] to it and write the result back. */
public suspend fun AdminApi.setTrafficManagementConfig(
    block: ModuleConfig.TrafficManagementConfig.() -> ModuleConfig.TrafficManagementConfig,
): AdminResult<Unit> = setModuleConfigSection(
    AdminMessage.ModuleConfigType.TRAFFICMANAGEMENT_CONFIG,
    { it.traffic_management },
    block,
) {
    ModuleConfig.Builder().also { wb -> wb.traffic_management = it }.build()
}
