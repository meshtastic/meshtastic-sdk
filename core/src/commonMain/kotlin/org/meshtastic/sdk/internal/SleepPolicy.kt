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

/** A device that firmware will put to sleep on this link; [expectedWakeSeconds] is `null` when unbounded. */
internal data class SleepExpectation(val expectedWakeSeconds: Int?)

/**
 * Whether firmware drops this link by design when the device sleeps, from the last known config.
 *
 * Light sleep (PowerFSM.cpp) is entered only on ESP32 builds with WiFi support, for a router role or
 * `is_power_saving`, never for tracker/sensor roles or when WiFi/Ethernet is configured, and it
 * turns Bluetooth off. Tracker/sensor roles with `is_power_saving` deep-sleep and restart on every
 * architecture. TCP and serial links are never reported: WiFi configured vetoes light sleep and an
 * attached serial host keeps the device awake.
 */
internal fun sleepExpectation(identity: TransportIdentity, bundle: ConfigBundle?): SleepExpectation? {
    if (bundle == null || !identity.raw.startsWith("ble:")) return null
    val power = bundle.configs.firstNotNullOfOrNull { it.power }
    val device = bundle.configs.firstNotNullOfOrNull { it.device }
    val network = bundle.configs.firstNotNullOfOrNull { it.network }
    val role = device?.role ?: bundle.metadata.role
    val powerSaving = power?.is_power_saving == true

    val trackerOrSensor = role == Config.DeviceConfig.Role.TRACKER ||
        role == Config.DeviceConfig.Role.TAK_TRACKER ||
        role == Config.DeviceConfig.Role.SENSOR
    if (trackerOrSensor) return if (powerSaving) SleepExpectation(null) else null

    val router = role == Config.DeviceConfig.Role.ROUTER || role == Config.DeviceConfig.Role.ROUTER_LATE
    if (!router && !powerSaving) return null
    if (!bundle.metadata.hasWifi) return null
    if (network != null && ((network.wifi_enabled && network.wifi_ssid.isNotEmpty()) || network.eth_enabled)) {
        return null
    }
    val lsSecs = power?.ls_secs ?: 0
    if (lsSecs <= 0) return null
    return SleepExpectation(if (router) null else lsSecs)
}
