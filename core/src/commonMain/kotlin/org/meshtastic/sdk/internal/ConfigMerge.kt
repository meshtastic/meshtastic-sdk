/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig

/**
 * Identifies which oneOf variant a [Config] message carries.
 *
 * Returns a string key for matching purposes, or `null` if the Config is empty/unknown.
 */
internal fun Config.sectionKey(): String? = when {
    device != null -> "device"
    position != null -> "position"
    power != null -> "power"
    network != null -> "network"
    display != null -> "display"
    lora != null -> "lora"
    bluetooth != null -> "bluetooth"
    security != null -> "security"
    sessionkey != null -> "sessionkey"
    device_ui != null -> "device_ui"
    else -> null
}

/**
 * Identifies which oneOf variant a [ModuleConfig] message carries.
 *
 * Returns a string key for matching purposes, or `null` if the ModuleConfig is empty/unknown.
 */
internal fun ModuleConfig.sectionKey(): String? = when {
    mqtt != null -> "mqtt"
    serial != null -> "serial"
    external_notification != null -> "external_notification"
    store_forward != null -> "store_forward"
    range_test != null -> "range_test"
    telemetry != null -> "telemetry"
    canned_message != null -> "canned_message"
    audio != null -> "audio"
    remote_hardware != null -> "remote_hardware"
    neighbor_info != null -> "neighbor_info"
    ambient_lighting != null -> "ambient_lighting"
    detection_sensor != null -> "detection_sensor"
    paxcounter != null -> "paxcounter"
    statusmessage != null -> "statusmessage"
    traffic_management != null -> "traffic_management"
    else -> null
}

/**
 * Merge [written] configs into [existing], replacing sections that share a [sectionKey].
 *
 * Sections in [existing] that weren't written are preserved as-is. Written sections not
 * present in [existing] are appended.
 */
internal fun mergeConfigs(existing: List<Config>, written: List<Config>): List<Config> {
    val writtenByKey = written.associateBy { it.sectionKey() }.filterKeys { it != null }
    val result = existing.map { cfg ->
        val key = cfg.sectionKey()
        if (key != null && key in writtenByKey) writtenByKey[key]!! else cfg
    }.toMutableList()
    // Append any written sections that didn't replace an existing entry.
    val existingKeys = existing.mapNotNull { it.sectionKey() }.toSet()
    for ((key, cfg) in writtenByKey) {
        if (key !in existingKeys) result.add(cfg)
    }
    return result
}

/**
 * Merge [written] module configs into [existing], replacing sections that share a [sectionKey].
 */
internal fun mergeModuleConfigs(
    existing: List<ModuleConfig>,
    written: List<ModuleConfig>,
): List<ModuleConfig> {
    val writtenByKey = written.associateBy { it.sectionKey() }.filterKeys { it != null }
    val result = existing.map { cfg ->
        val key = cfg.sectionKey()
        if (key != null && key in writtenByKey) writtenByKey[key]!! else cfg
    }.toMutableList()
    val existingKeys = existing.mapNotNull { it.sectionKey() }.toSet()
    for ((key, cfg) in writtenByKey) {
        if (key !in existingKeys) result.add(cfg)
    }
    return result
}
