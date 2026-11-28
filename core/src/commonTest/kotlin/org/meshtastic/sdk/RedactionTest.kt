/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.BluetoothConnectionStatus
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.ClientNotification
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceConnectionStatus
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.KeyVerificationAdmin
import org.meshtastic.proto.KeyVerificationNumberInform
import org.meshtastic.proto.LockdownAuth
import org.meshtastic.proto.MeshBeacon
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.PortNum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Regression guarded by every test: a credential reaching a persisted log.
class RedactionTest {

    private val secret: ByteString = ByteArray(32) { (it + 1).toByte() }.toByteString()
    private val publicKey: ByteString = ByteArray(32) { (it + 101).toByte() }.toByteString()

    private fun adminPacket(admin: AdminMessage) = MeshPacket.Builder()
        .also { p ->
            p.id = 77
            p.from = 5
            p.decoded =
                Data.Builder()
                    .also {
                        it.portnum = PortNum.ADMIN_APP
                        it.payload = AdminMessage.ADAPTER.encodeByteString(admin)
                        it.want_response = true
                    }
                    .build()
        }
        .build()

    private fun loggedAdmin(admin: AdminMessage): AdminMessage =
        AdminMessage.ADAPTER.decode(adminPacket(admin).redacted().decoded!!.payload)

    @Test
    fun `security private key and admin keys are cleared while the public key stays`() {
        val config =
            Config.Builder()
                .also {
                    it.security =
                        Config.SecurityConfig.Builder()
                            .also { s ->
                                s.public_key = publicKey
                                s.private_key = secret
                                s.admin_key = listOf(secret, secret)
                                s.serial_enabled = true
                            }
                            .build()
                }
                .build()

        val security = config.redacted().security!!

        assertEquals(ByteString.EMPTY, security.private_key)
        assertTrue(security.admin_key.isEmpty())
        assertEquals(publicKey, security.public_key)
        assertTrue(security.serial_enabled)
    }

    @Test
    fun `wifi psk is cleared while ssid and neighbors stay`() {
        val config =
            Config.Builder()
                .also {
                    it.network =
                        Config.NetworkConfig.Builder()
                            .also { n ->
                                n.wifi_ssid = "home"
                                n.wifi_psk = "secret-psk"
                                n.ntp_server = "pool.example"
                            }
                            .build()
                }
                .build()

        val network = config.redacted().network!!

        assertEquals("", network.wifi_psk)
        assertEquals("home", network.wifi_ssid)
        assertEquals("pool.example", network.ntp_server)
    }

    @Test
    fun `bluetooth fixed pin is cleared while the pairing mode stays`() {
        val config =
            Config.Builder()
                .also {
                    it.bluetooth =
                        Config.BluetoothConfig.Builder()
                            .also { b ->
                                b.mode = Config.BluetoothConfig.PairingMode.FIXED_PIN
                                b.fixed_pin = 123456
                            }
                            .build()
                }
                .build()

        val bluetooth = config.redacted().bluetooth!!

        assertEquals(0, bluetooth.fixed_pin)
        assertEquals(Config.BluetoothConfig.PairingMode.FIXED_PIN, bluetooth.mode)
    }

    @Test
    fun `a config without secrets is returned equal`() {
        val config =
            Config.Builder()
                .also { it.lora = Config.LoRaConfig.Builder().also { l -> l.hop_limit = 5 }.build() }
                .build()

        assertEquals(config, config.redacted())
    }

    @Test
    fun `mqtt username and password are cleared while the address stays`() {
        val moduleConfig =
            ModuleConfig.Builder()
                .also { m ->
                    m.mqtt =
                        ModuleConfig.MQTTConfig.Builder()
                            .also { q ->
                                q.address = "mqtt.example"
                                q.username = "user"
                                q.password = "pass"
                            }
                            .build()
                }
                .build()

        val mqtt = moduleConfig.redacted().mqtt!!

        assertEquals("", mqtt.username)
        assertEquals("", mqtt.password)
        assertEquals("mqtt.example", mqtt.address)
    }

    @Test
    fun `channel psk is cleared while the channel name and index stay`() {
        val original =
            Channel.Builder()
                .also { c ->
                    c.index = 2
                    c.settings =
                        ChannelSettings.Builder()
                            .also { s ->
                                s.psk = secret
                                s.name = "Ops"
                            }
                            .build()
                }
                .build()

        val channel = original.redacted()

        assertEquals(ByteString.EMPTY, channel.settings!!.psk)
        assertEquals("Ops", channel.settings!!.name)
        assertEquals(2, channel.index)
    }

    @Test
    fun `device ui pin code is cleared`() {
        val original =
            DeviceUIConfig.Builder()
                .also { u ->
                    u.pin_code = 123456
                    u.screen_brightness = 80
                }
                .build()

        val ui = original.redacted()

        assertEquals(0, ui.pin_code)
        assertEquals(80, ui.screen_brightness)
    }

    @Test
    fun `key verification number in a client notification is cleared while the peer name stays`() {
        val notification =
            ClientNotification.Builder()
                .also { n ->
                    n.key_verification_number_inform =
                        KeyVerificationNumberInform.Builder()
                            .also { k ->
                                k.remote_longname = "Peer"
                                k.security_number = 123456
                            }
                            .build()
                }
                .build()

        val inform = notification.redacted().key_verification_number_inform!!

        assertEquals(0, inform.security_number)
        assertEquals("Peer", inform.remote_longname)
    }

    @Test
    fun `admin session passkey is cleared from a logged admin packet while the packet stays intact`() {
        val packet =
            adminPacket(
                AdminMessage.Builder()
                    .also {
                        it.session_passkey = secret
                        it.get_owner_request = true
                    }
                    .build(),
            )

        val logged = packet.redacted()
        val admin = AdminMessage.ADAPTER.decode(logged.decoded!!.payload)

        assertEquals(ByteString.EMPTY, admin.session_passkey)
        assertEquals(true, admin.get_owner_request)
        assertEquals(77, logged.id)
        assertEquals(PortNum.ADMIN_APP, logged.decoded!!.portnum)
        assertTrue(logged.decoded!!.want_response == true)
    }

    @Test
    fun `admin set config response carries cleared keys in the logged payload`() {
        val config =
            Config.Builder()
                .also {
                    it.security =
                        Config.SecurityConfig.Builder()
                            .also { s ->
                                s.private_key = secret
                                s.admin_key = listOf(secret)
                                s.public_key = publicKey
                            }
                            .build()
                }
                .build()

        val set = loggedAdmin(AdminMessage.Builder().also { it.set_config = config }.build())
        val response = loggedAdmin(AdminMessage.Builder().also { it.get_config_response = config }.build())

        listOf(set.set_config!!, response.get_config_response!!).forEach {
            assertEquals(ByteString.EMPTY, it.security!!.private_key)
            assertTrue(it.security!!.admin_key.isEmpty())
            assertEquals(publicKey, it.security!!.public_key)
        }
    }

    @Test
    fun `admin set module config and channel carry cleared credentials in the logged payload`() {
        val mqtt =
            ModuleConfig.Builder()
                .also { m ->
                    m.mqtt =
                        ModuleConfig.MQTTConfig.Builder()
                            .also { q ->
                                q.username = "user"
                                q.password = "pass"
                            }
                            .build()
                }
                .build()
        val channel =
            Channel.Builder()
                .also { c -> c.settings = ChannelSettings.Builder().also { s -> s.psk = secret }.build() }
                .build()

        val loggedModule = loggedAdmin(AdminMessage.Builder().also { it.set_module_config = mqtt }.build())
        val loggedChannel = loggedAdmin(AdminMessage.Builder().also { it.set_channel = channel }.build())
        val loggedModuleResponse =
            loggedAdmin(AdminMessage.Builder().also { it.get_module_config_response = mqtt }.build())
        val loggedChannelResponse =
            loggedAdmin(AdminMessage.Builder().also { it.get_channel_response = channel }.build())

        assertEquals("", loggedModule.set_module_config?.mqtt?.password)
        assertEquals("", loggedModule.set_module_config?.mqtt?.username)
        assertEquals(ByteString.EMPTY, loggedChannel.set_channel?.settings?.psk)
        assertEquals("", loggedModuleResponse.get_module_config_response?.mqtt?.password)
        assertEquals("", loggedModuleResponse.get_module_config_response?.mqtt?.username)
        assertEquals(ByteString.EMPTY, loggedChannelResponse.get_channel_response?.settings?.psk)
    }

    @Test
    fun `admin lockdown passphrase ui pin and key verification number are cleared in the logged payload`() {
        val logged =
            loggedAdmin(
                AdminMessage.Builder()
                    .also {
                        it.lockdown_auth =
                            LockdownAuth.Builder()
                                .also { l ->
                                    l.passphrase = "passphrase".encodeUtf8()
                                    l.boots_remaining = 3
                                }
                                .build()
                    }
                    .build(),
            )
        val ui =
            loggedAdmin(
                AdminMessage.Builder()
                    .also { it.store_ui_config = DeviceUIConfig.Builder().also { u -> u.pin_code = 1234 }.build() }
                    .build(),
            )
        val verification =
            loggedAdmin(
                AdminMessage.Builder()
                    .also {
                        it.key_verification =
                            KeyVerificationAdmin.Builder()
                                .also { k ->
                                    k.remote_nodenum = 9
                                    k.security_number = 4321
                                }
                                .build()
                    }
                    .build(),
            )

        assertEquals(ByteString.EMPTY, logged.lockdown_auth!!.passphrase)
        assertEquals(3, logged.lockdown_auth!!.boots_remaining)
        assertEquals(0, ui.store_ui_config!!.pin_code)
        assertNull(verification.key_verification!!.security_number)
        assertEquals(9, verification.key_verification!!.remote_nodenum)
    }

    @Test
    fun `an admin payload that does not decode is logged with an empty payload`() {
        val packet =
            MeshPacket.Builder()
                .also { p ->
                    p.id = 5
                    p.decoded =
                        Data.Builder()
                            .also {
                                it.portnum = PortNum.ADMIN_APP
                                it.payload = ByteArray(8) { 0xFF.toByte() }.toByteString()
                            }
                            .build()
                }
                .build()

        val logged = packet.redacted()

        assertEquals(ByteString.EMPTY, logged.decoded!!.payload)
        assertEquals(5, logged.id)
    }

    @Test
    fun `a text message packet is stored unchanged`() {
        val packet =
            MeshPacket.Builder()
                .also { p ->
                    p.id = 9
                    p.decoded =
                        Data.Builder()
                            .also {
                                it.portnum = PortNum.TEXT_MESSAGE_APP
                                it.payload = "hello".encodeUtf8()
                            }
                            .build()
                }
                .build()

        assertEquals(packet, packet.redacted())
    }

    @Test
    fun `admin connection status response is logged without the bluetooth pin and keeps the rest`() {
        val admin =
            AdminMessage.Builder()
                .also {
                    it.get_device_connection_status_response =
                        DeviceConnectionStatus.Builder()
                            .also { c ->
                                c.bluetooth =
                                    BluetoothConnectionStatus.Builder()
                                        .also { b ->
                                            b.pin = 135791
                                            b.rssi = -60
                                        }
                                        .build()
                            }
                            .build()
                }
                .build()

        val logged = loggedAdmin(admin).get_device_connection_status_response!!.bluetooth!!

        assertEquals(0, logged.pin)
        assertEquals(-60, logged.rssi)
        val original = AdminMessage.ADAPTER.decode(adminPacket(admin).decoded!!.payload)
        assertEquals(135791, original.get_device_connection_status_response!!.bluetooth!!.pin)
    }

    @Test
    fun `device ui inside a config has its pin cleared and the original keeps it`() {
        val config =
            Config.Builder()
                .also {
                    it.device_ui =
                        DeviceUIConfig.Builder()
                            .also { u ->
                                u.pin_code = 246801
                                u.screen_brightness = 70
                            }
                            .build()
                }
                .build()

        val logged = config.redacted().device_ui!!

        assertEquals(0, logged.pin_code)
        assertEquals(70, logged.screen_brightness)
        assertEquals(246801, config.device_ui!!.pin_code)
    }

    @Test
    fun `device ui inside admin set and get config payloads has its pin cleared`() {
        val config =
            Config.Builder()
                .also { it.device_ui = DeviceUIConfig.Builder().also { u -> u.pin_code = 246801 }.build() }
                .build()

        val set = loggedAdmin(AdminMessage.Builder().also { it.set_config = config }.build())
        val response = loggedAdmin(AdminMessage.Builder().also { it.get_config_response = config }.build())

        assertEquals(0, set.set_config!!.device_ui!!.pin_code)
        assertEquals(0, response.get_config_response!!.device_ui!!.pin_code)
    }

    @Test
    fun `mesh beacon offer channel psk is cleared while its name stays`() {
        val moduleConfig =
            ModuleConfig.Builder()
                .also { m ->
                    m.mesh_beacon =
                        ModuleConfig.MeshBeaconConfig.Builder()
                            .also { b ->
                                b.broadcast_message = "hello"
                                b.broadcast_offer_channel =
                                    ChannelSettings.Builder()
                                        .also { s ->
                                            s.psk = secret
                                            s.name = "Offer"
                                        }
                                        .build()
                            }
                            .build()
                }
                .build()

        val beacon = moduleConfig.redacted().mesh_beacon!!

        assertEquals(ByteString.EMPTY, beacon.broadcast_offer_channel!!.psk)
        assertEquals("Offer", beacon.broadcast_offer_channel!!.name)
        assertEquals("hello", beacon.broadcast_message)
    }

    // Catches a received beacon packet reaching a persisted log with its offered channel's PSK.
    @Test
    fun aMeshBeaconPacketHasItsOfferedChannelPskClearedWhileItsMessageStays() {
        val beaconBytes =
            MeshBeacon.ADAPTER.encodeByteString(
                MeshBeacon.Builder()
                    .also { b ->
                        b.message = "join"
                        b.offer_channel =
                            ChannelSettings.Builder()
                                .also { s ->
                                    s.name = "Ops"
                                    s.psk = ByteArray(16) { 1 }.toByteString()
                                }
                                .build()
                    }
                    .build(),
            )
        val packet =
            MeshPacket.Builder()
                .also { p ->
                    p.decoded =
                        Data.Builder()
                            .also { d ->
                                d.portnum = PortNum.MESH_BEACON_APP
                                d.payload = beaconBytes
                            }
                            .build()
                }
                .build()

        val beacon = MeshBeacon.ADAPTER.decode(packet.redacted().decoded!!.payload)

        assertEquals(ByteString.EMPTY, beacon.offer_channel!!.psk)
        assertEquals("Ops", beacon.offer_channel!!.name)
        assertEquals("join", beacon.message)
    }

    // Catches the key-verification security number surviving in the logged notification text.
    @Test
    fun keyVerificationNotificationTextIsClearedWithItsNumber() {
        val notification =
            ClientNotification.Builder()
                .also { n ->
                    n.message = "Incoming Key Verification.\nSecurity Number\n123 456"
                    n.key_verification_number_inform =
                        KeyVerificationNumberInform.Builder()
                            .also { k ->
                                k.remote_longname = "Peer"
                                k.security_number = 123456
                            }
                            .build()
                }
                .build()

        val redacted = notification.redacted()

        assertEquals("", redacted.message)
        assertEquals(0, redacted.key_verification_number_inform!!.security_number)
        assertEquals("Peer", redacted.key_verification_number_inform!!.remote_longname)
    }
}
