/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import org.meshtastic.proto.Config
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.XModem

/**
 * Structural summaries used by `PayloadRedactor.Default`: the envelope variant, routing metadata and flow-control
 * counters only. Payload bytes, text, positions, channel PSKs, session passkeys, keys, config values and file contents
 * never appear.
 */
internal fun summarizeFromRadio(message: FromRadio): String {
    val variant = when {
        message.packet != null -> "packet"
        message.my_info != null -> "my_info"
        message.node_info != null -> "node_info"
        message.config != null -> "config=${message.config?.let(::configSection)}"
        message.log_record != null -> "log_record"
        message.config_complete_id != null -> "config_complete_id"
        message.rebooted != null -> "rebooted"
        message.moduleConfig != null -> "moduleConfig=${message.moduleConfig?.let(::moduleConfigSection)}"
        message.channel != null -> "channel"
        message.queueStatus != null -> "queueStatus"
        message.xmodemPacket != null -> "xmodemPacket"
        message.metadata != null -> "metadata"
        message.mqttClientProxyMessage != null -> "mqttClientProxyMessage"
        message.fileInfo != null -> "fileInfo"
        message.clientNotification != null -> "clientNotification"
        message.deviceuiConfig != null -> "deviceuiConfig"
        message.lockdown_status != null -> "lockdown_status"
        message.region_presets != null -> "region_presets"
        message.display_frame != null -> "display_frame"
        message.display_palette != null -> "display_palette"
        else -> "other"
    }
    val packet = message.packet?.let { " ${summarizePacket(it)}" }.orEmpty()
    val queue = message.queueStatus?.let {
        " free=${it.free} maxlen=${it.maxlen} res=${it.res} mesh_packet_id=${it.mesh_packet_id.toUInt()}"
    }.orEmpty()
    val xmodem = message.xmodemPacket?.let { " ${summarizeXmodem(it)}" }.orEmpty()
    return "FromRadio id=${message.id} variant=$variant$packet$queue$xmodem"
}

internal fun summarizeToRadio(message: ToRadio): String {
    val variant = when {
        message.packet != null -> "packet"
        message.want_config_id != null -> "want_config_id"
        message.disconnect != null -> "disconnect"
        message.xmodemPacket != null -> "xmodemPacket"
        message.mqttClientProxyMessage != null -> "mqttClientProxyMessage"
        message.heartbeat != null -> "heartbeat"
        else -> "other"
    }
    val packet = message.packet?.let { " ${summarizePacket(it)}" }.orEmpty()
    val xmodem = message.xmodemPacket?.let { " ${summarizeXmodem(it)}" }.orEmpty()
    val heartbeat = message.heartbeat?.let { " nonce=${it.nonce}" }.orEmpty()
    return "ToRadio variant=$variant$packet$xmodem$heartbeat"
}

private fun summarizeXmodem(xmodem: XModem): String =
    "control=${xmodem.control} seq=${xmodem.seq} buffer_bytes=${xmodem.buffer.size}"

private fun configSection(config: Config): String = when {
    config.device != null -> "device"
    config.position != null -> "position"
    config.power != null -> "power"
    config.network != null -> "network"
    config.display != null -> "display"
    config.lora != null -> "lora"
    config.bluetooth != null -> "bluetooth"
    config.security != null -> "security"
    config.sessionkey != null -> "sessionkey"
    config.device_ui != null -> "device_ui"
    else -> "other"
}

private fun moduleConfigSection(config: ModuleConfig): String = when {
    config.mqtt != null -> "mqtt"
    config.serial != null -> "serial"
    config.external_notification != null -> "external_notification"
    config.store_forward != null -> "store_forward"
    config.range_test != null -> "range_test"
    config.telemetry != null -> "telemetry"
    config.canned_message != null -> "canned_message"
    config.audio != null -> "audio"
    config.remote_hardware != null -> "remote_hardware"
    config.neighbor_info != null -> "neighbor_info"
    config.ambient_lighting != null -> "ambient_lighting"
    config.detection_sensor != null -> "detection_sensor"
    config.paxcounter != null -> "paxcounter"
    config.statusmessage != null -> "statusmessage"
    config.traffic_management != null -> "traffic_management"
    config.tak != null -> "tak"
    config.mesh_beacon != null -> "mesh_beacon"
    else -> "other"
}

private fun summarizePacket(packet: MeshPacket): String {
    val decoded = packet.decoded
    val body = if (decoded != null) {
        "port=${decoded.portnum} payload_bytes=${decoded.payload.size}"
    } else {
        "encrypted_bytes=${packet.encrypted?.size ?: 0}"
    }
    return "from=${packet.from.toUInt()} to=${packet.to.toUInt()} packet_id=${packet.id.toUInt()} $body"
}
