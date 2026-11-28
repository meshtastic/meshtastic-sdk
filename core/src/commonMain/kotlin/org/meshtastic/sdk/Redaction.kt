/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString
import okio.ByteString.Companion.EMPTY
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ClientNotification
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceConnectionStatus
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.MeshBeacon
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.PortNum

// Credential-bearing copies of radio messages; the message itself is never modified.

/**
 * A copy of this packet that is safe to log or persist: an `ADMIN_APP` or `MESH_BEACON_APP` payload is decoded, has its
 * credentials cleared and is re-encoded; one that cannot be decoded is returned with an empty payload rather than its
 * raw bytes. Other packets are returned as they are, so text and positions are NOT removed. The credentials cleared are
 * the fields the protos mark `sensitive` (channel PSK, lockdown passphrase, device UI PIN, Wi-Fi PSK, Bluetooth PIN,
 * private key, MQTT password) plus the admin session passkey, admin public keys, the MQTT username and key-verification
 * security numbers, including the notification text that repeats them.
 *
 * @since 0.3.2
 */
public fun MeshPacket.redacted(): MeshPacket {
    val data = decoded ?: return this
    val payload = when (data.portnum) {
        PortNum.ADMIN_APP -> runCatching { AdminMessage.ADAPTER.decode(data.payload) }
            .map { AdminMessage.ADAPTER.encodeByteString(it.redacted()) }
            .getOrDefault(EMPTY)
        PortNum.MESH_BEACON_APP -> runCatching { MeshBeacon.ADAPTER.decode(data.payload) }
            .map { MeshBeacon.ADAPTER.encodeByteString(it.redacted()) }
            .getOrDefault(EMPTY)
        else -> return this
    }
    return newBuilder().also { b -> b.decoded = data.newBuilder().also { it.payload = payload }.build() }.build()
}

internal fun AdminMessage.redacted(): AdminMessage = newBuilder()
    .also { b ->
        b.session_passkey = EMPTY
        set_config?.let { b.set_config = it.redacted() }
        get_config_response?.let { b.get_config_response = it.redacted() }
        set_module_config?.let { b.set_module_config = it.redacted() }
        get_module_config_response?.let { b.get_module_config_response = it.redacted() }
        set_channel?.let { b.set_channel = it.redacted() }
        get_channel_response?.let { b.get_channel_response = it.redacted() }
        store_ui_config?.let { b.store_ui_config = it.redacted() }
        get_ui_config_response?.let { b.get_ui_config_response = it.redacted() }
        get_device_connection_status_response?.let { b.get_device_connection_status_response = it.redacted() }
        lockdown_auth?.let { b.lockdown_auth = it.newBuilder().also { l -> l.passphrase = EMPTY }.build() }
        key_verification?.let {
            b.key_verification = it.newBuilder().also { k -> k.security_number = null }.build()
        }
    }
    .build()

/**
 * As [MeshPacket.redacted] for a configuration section.
 *
 * @since 0.3.2
 */
public fun Config.redacted(): Config = newBuilder()
    .also { b ->
        device_ui?.let { b.device_ui = it.redacted() }
        network?.let { b.network = it.newBuilder().also { n -> n.wifi_psk = "" }.build() }
        bluetooth?.let { b.bluetooth = it.newBuilder().also { n -> n.fixed_pin = 0 }.build() }
        security?.let {
            b.security =
                it.newBuilder()
                    .also { s ->
                        s.private_key = EMPTY
                        s.admin_key = emptyList<ByteString>()
                    }
                    .build()
        }
    }
    .build()

internal fun DeviceConnectionStatus.redacted(): DeviceConnectionStatus =
    newBuilder().also { b -> bluetooth?.let { b.bluetooth = it.newBuilder().also { s -> s.pin = 0 }.build() } }.build()

/**
 * As [MeshPacket.redacted] for a module configuration section.
 *
 * @since 0.3.2
 */
public fun ModuleConfig.redacted(): ModuleConfig = newBuilder()
    .also { b ->
        mesh_beacon?.let { beacon ->
            beacon.broadcast_offer_channel?.let { offer ->
                b.mesh_beacon =
                    beacon
                        .newBuilder()
                        .also {
                            it.broadcast_offer_channel = offer.newBuilder().also { s -> s.psk = EMPTY }.build()
                        }
                        .build()
            }
        }
        mqtt?.let {
            b.mqtt =
                it.newBuilder()
                    .also { m ->
                        m.username = ""
                        m.password = ""
                    }
                    .build()
        }
    }
    .build()

/**
 * As [MeshPacket.redacted] for a channel.
 *
 * @since 0.3.2
 */
public fun Channel.redacted(): Channel = newBuilder()
    .also { b -> settings?.let { b.settings = it.newBuilder().also { s -> s.psk = EMPTY }.build() } }
    .build()

/**
 * As [MeshPacket.redacted] for a device UI configuration.
 *
 * @since 0.3.2
 */
public fun DeviceUIConfig.redacted(): DeviceUIConfig = newBuilder().also { it.pin_code = 0 }.build()

internal fun MeshBeacon.redacted(): MeshBeacon = offer_channel?.let { offer ->
    newBuilder().also { it.offer_channel = offer.newBuilder().also { s -> s.psk = EMPTY }.build() }.build()
} ?: this

/**
 * As [MeshPacket.redacted] for a client notification.
 *
 * @since 0.3.2
 */
public fun ClientNotification.redacted(): ClientNotification = newBuilder()
    .also { b ->
        key_verification_number_inform?.let {
            b.key_verification_number_inform = it.newBuilder().also { k -> k.security_number = 0 }.build()
        }
        // Firmware repeats the security number or verification code in the text.
        if (key_verification_number_inform != null || key_verification_final != null) b.message = ""
    }
    .build()
