/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ToRadio

/**
 * Structural summaries used by `PayloadRedactor.Default`: the envelope variant and routing metadata only. Payload
 * bytes, text, positions, channel PSKs and session passkeys never appear.
 */
internal fun summarizeFromRadio(message: FromRadio): String {
    val variant = when {
        message.packet != null -> "packet"
        message.my_info != null -> "my_info"
        message.node_info != null -> "node_info"
        message.config != null -> "config"
        message.log_record != null -> "log_record"
        message.config_complete_id != null -> "config_complete_id"
        message.rebooted != null -> "rebooted"
        message.moduleConfig != null -> "moduleConfig"
        message.channel != null -> "channel"
        message.queueStatus != null -> "queueStatus"
        message.xmodemPacket != null -> "xmodemPacket"
        message.metadata != null -> "metadata"
        message.mqttClientProxyMessage != null -> "mqttClientProxyMessage"
        message.fileInfo != null -> "fileInfo"
        message.clientNotification != null -> "clientNotification"
        message.deviceuiConfig != null -> "deviceuiConfig"
        message.lockdown_status != null -> "lockdown_status"
        else -> "other"
    }
    val packet = message.packet?.let { " ${summarizePacket(it)}" }.orEmpty()
    return "FromRadio id=${message.id} variant=$variant$packet"
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
    return "ToRadio variant=$variant$packet"
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
