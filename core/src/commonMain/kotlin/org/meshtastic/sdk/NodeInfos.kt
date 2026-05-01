/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.NodeId

/** Returns the display-friendly hex ID of this node (e.g., `"!aabbccdd"`). */
public val NodeInfo.displayId: String get() = NodeId(num).toString()

/**
 * Returns the short name of this node (e.g., `"ABCD"`).
 *
 * Falls back to the last 4 characters of the [displayId] if the short name is empty or missing.
 */
public val NodeInfo.shortName: String
    get() = user?.short_name?.takeIf { it.isNotBlank() } ?: displayId.takeLast(4)

/**
 * Returns the long name of this node (e.g., `"James Arich"`).
 *
 * Falls back to the [displayId] if the long name is empty or missing.
 */
public val NodeInfo.longName: String
    get() = user?.long_name?.takeIf { it.isNotBlank() } ?: displayId

/**
 * Returns a human-friendly display name for this [HardwareModel] (e.g., `"T-Beam"`).
 *
 * Covers 39+ known Meshtastic boards; falls back to a title-cased version of the enum name
 * if the model is unknown to this SDK version.
 */
public val HardwareModel.displayName: String
    get() = HARDWARE_MODEL_DISPLAY_NAMES[this] ?: defaultDisplayName(this.name)

private fun defaultDisplayName(enumName: String): String = enumName.split('_').joinToString(" ") { word ->
    if (word.length <= 1) {
        word.uppercase()
    } else {
        word.first().uppercase() + word.drop(1).lowercase()
    }
}

private val HARDWARE_MODEL_DISPLAY_NAMES: Map<HardwareModel, String> = mapOf(
    HardwareModel.UNSET to "Unknown",
    HardwareModel.TBEAM to "T-Beam",
    HardwareModel.T_ECHO to "T-Echo",
    HardwareModel.LORA_TYPE to "LoRa Type",
    HardwareModel.WIPHONE to "WiPhone",
    HardwareModel.HELTEC_WIRELESS_BRIDGE to "Heltec Wireless Bridge",
    HardwareModel.CANARYONE to "CanaryOne",
    HardwareModel.T_ECHO_PLUS to "T-Echo Plus",
    HardwareModel.PPR to "PPR",
    HardwareModel.GENIEBLOCKS to "GenieBlocks",
    HardwareModel.PORTDUINO to "Portduino",
    HardwareModel.ANDROID_SIM to "Android Simulator",
    HardwareModel.DR_DEV to "DR-Dev",
    HardwareModel.RPI_PICO to "Raspberry Pi Pico",
    HardwareModel.HELTEC_WIRELESS_TRACKER to "Heltec Wireless Tracker",
    HardwareModel.HELTEC_WIRELESS_PAPER to "Heltec Wireless Paper",
    HardwareModel.T_DECK to "T-Deck",
    HardwareModel.UNPHONE to "unPhone",
    HardwareModel.TD_LORAC to "TD-LoRaC",
    HardwareModel.SENSECAP_INDICATOR to "SenseCAP Indicator",
    HardwareModel.WISMESH_TAP to "WisMesh Tap",
    HardwareModel.ROUTASTIC to "Routastic",
    HardwareModel.MESH_TAB to "Mesh-Tab",
    HardwareModel.MESHLINK to "MeshLink",
    HardwareModel.T_ETH_ELITE to "T-Eth-Elite",
    HardwareModel.HELTEC_SENSOR_HUB to "Heltec Sensor Hub",
    HardwareModel.MUZI_BASE to "Muzi Base",
    HardwareModel.HELTEC_MESH_POCKET to "Heltec Mesh Pocket",
    HardwareModel.SEEED_SOLAR_NODE to "Seeed Solar Node",
    HardwareModel.NOMADSTAR_METEOR_PRO to "NomadStar Meteor Pro",
    HardwareModel.CROWPANEL to "CrowPanel",
    HardwareModel.T_DECK_PRO to "T-Deck Pro",
    HardwareModel.T_LORA_PAGER to "T-LoRa Pager",
    HardwareModel.WISMESH_TAG to "WisMesh Tag",
    HardwareModel.HELTEC_MESH_SOLAR to "Heltec Mesh Solar",
    HardwareModel.T_ECHO_LITE to "T-Echo Lite",
    HardwareModel.T_WATCH_ULTRA to "T-Watch Ultra",
    HardwareModel.TBEAM_BPF to "T-Beam (BPF)",
    HardwareModel.PRIVATE_HW to "Private Hardware",
)
