/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import org.meshtastic.proto.Config
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.TransportIdentity

/** A device that firmware will put to sleep on this link for at most [expectedWakeSeconds]. */
internal data class SleepExpectation(val expectedWakeSeconds: Int)

// Firmware's fallback when a broadcast or telemetry interval is 0, for a non-router role
// (Default.h default_broadcast_interval_secs, default_telemetry_broadcast_interval_secs).
private const val DEFAULT_INTERVAL_SECS = 60 * 60

/**
 * Whether firmware drops this BLE link by design when the device sleeps, from the last known config.
 *
 * Deep sleep (the device restarts on wake, on ESP32 and nRF52 alike): a tracker role with
 * `is_power_saving` sleeps `position_broadcast_secs` after each position (PositionModule.cpp), a
 * sensor role with `is_power_saving` and environment measurement on sleeps
 * `environment_update_interval` after each reading (EnvironmentTelemetry.cpp). Light sleep
 * (PowerFSM.cpp) runs only on ESP32 builds with WiFi support, for a router role or
 * `is_power_saving`, never for tracker or sensor roles or with a WiFi SSID or Ethernet in use, and
 * answers nothing while asleep; a router is back within `ls_secs` like a client, since
 * `setBluetoothEnable(false)` is a no-op on ESP32 (main-esp32.cpp), the only platform that light-sleeps.
 */
internal fun sleepExpectation(identity: TransportIdentity, bundle: ConfigBundle?): SleepExpectation? {
    if (bundle == null || !identity.raw.startsWith("ble:")) return null
    val power = bundle.configs.firstNotNullOfOrNull { it.power }
    val role = bundle.configs.firstNotNullOfOrNull { it.device }?.role ?: bundle.metadata.role
    val powerSaving = power?.is_power_saving == true
    return when (role) {
        Config.DeviceConfig.Role.TRACKER, Config.DeviceConfig.Role.TAK_TRACKER -> {
            val interval = bundle.configs.firstNotNullOfOrNull { it.position }?.position_broadcast_secs ?: 0
            SleepExpectation(interval.orDefaultInterval()).takeIf { powerSaving }
        }

        Config.DeviceConfig.Role.SENSOR -> {
            val telemetry = bundle.moduleConfigs.firstNotNullOfOrNull { it.telemetry }
            if (powerSaving && telemetry?.environment_measurement_enabled == true) {
                SleepExpectation(telemetry.environment_update_interval.orDefaultInterval())
            } else {
                null
            }
        }

        else -> lightSleep(bundle, role, power)
    }
}

private fun lightSleep(
    bundle: ConfigBundle,
    role: Config.DeviceConfig.Role,
    power: Config.PowerConfig?,
): SleepExpectation? {
    val router = role == Config.DeviceConfig.Role.ROUTER || role == Config.DeviceConfig.Role.ROUTER_LATE
    if (!router && power?.is_power_saving != true) return null
    if (!bundle.metadata.hasWifi) return null
    // isWifiAvailable() needs an SSID, and honors eth_enabled only on an Ethernet build (WiFiAPClient.cpp).
    val network = bundle.configs.firstNotNullOfOrNull { it.network }
    val wifi = network?.wifi_enabled == true && network.wifi_ssid.isNotEmpty()
    val ethernet = network?.eth_enabled == true && bundle.metadata.hasEthernet
    if (wifi || ethernet) return null
    val lsSecs = power?.ls_secs ?: 0
    if (lsSecs <= 0) return null
    return SleepExpectation(lsSecs)
}

private fun Int.orDefaultInterval(): Int = if (this > 0) this else DEFAULT_INTERVAL_SECS
