/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceConnectionStatus
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.HamParameters
import org.meshtastic.proto.KeyVerificationAdmin
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.NodeRemoteHardwarePinsResponse
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Routing
import org.meshtastic.proto.SensorConfig
import org.meshtastic.proto.SharedContact
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AdminApiImplComprehensiveTest {

    @Test
    fun getDeviceConfigReturnsDeviceSection() = runTest {
        val expected = Config(device = Config.DeviceConfig(role = Config.DeviceConfig.Role.CLIENT))
        assertRpcOperation(
            call = { it.getConfig(AdminMessage.ConfigType.DEVICE_CONFIG) },
            requestMatches = { it.get_config_request == AdminMessage.ConfigType.DEVICE_CONFIG },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_config_response = expected))
        }
    }

    @Test
    fun getLoraConfigReturnsLoraSection() = runTest {
        val expected = Config(lora = Config.LoRaConfig(use_preset = true))
        assertRpcOperation(
            call = { it.getConfig(AdminMessage.ConfigType.LORA_CONFIG) },
            requestMatches = { it.get_config_request == AdminMessage.ConfigType.LORA_CONFIG },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_config_response = expected))
        }
    }

    @Test
    fun getBluetoothConfigReturnsBluetoothSection() = runTest {
        val expected = Config(bluetooth = Config.BluetoothConfig(enabled = true))
        assertRpcOperation(
            call = { it.getConfig(AdminMessage.ConfigType.BLUETOOTH_CONFIG) },
            requestMatches = { it.get_config_request == AdminMessage.ConfigType.BLUETOOTH_CONFIG },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_config_response = expected))
        }
    }

    @Test
    fun getDisplayConfigReturnsDisplaySection() = runTest {
        val expected = Config(display = Config.DisplayConfig(screen_on_secs = 45))
        assertRpcOperation(
            call = { it.getConfig(AdminMessage.ConfigType.DISPLAY_CONFIG) },
            requestMatches = { it.get_config_request == AdminMessage.ConfigType.DISPLAY_CONFIG },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_config_response = expected))
        }
    }

    @Test
    fun getNetworkConfigReturnsNetworkSection() = runTest {
        val expected = Config(network = Config.NetworkConfig(wifi_enabled = true, wifi_ssid = "mesh"))
        assertRpcOperation(
            call = { it.getConfig(AdminMessage.ConfigType.NETWORK_CONFIG) },
            requestMatches = { it.get_config_request == AdminMessage.ConfigType.NETWORK_CONFIG },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_config_response = expected))
        }
    }

    @Test
    fun getPositionConfigReturnsPositionSection() = runTest {
        val expected = Config(position = Config.PositionConfig(position_broadcast_secs = 300))
        assertRpcOperation(
            call = { it.getConfig(AdminMessage.ConfigType.POSITION_CONFIG) },
            requestMatches = { it.get_config_request == AdminMessage.ConfigType.POSITION_CONFIG },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_config_response = expected))
        }
    }

    @Test
    fun getPowerConfigReturnsPowerSection() = runTest {
        val expected = Config(power = Config.PowerConfig(is_power_saving = true))
        assertRpcOperation(
            call = { it.getConfig(AdminMessage.ConfigType.POWER_CONFIG) },
            requestMatches = { it.get_config_request == AdminMessage.ConfigType.POWER_CONFIG },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_config_response = expected))
        }
    }

    @Test
    fun getSecurityConfigReturnsSecuritySection() = runTest {
        val expected = Config(security = Config.SecurityConfig(is_managed = true))
        assertRpcOperation(
            call = { it.getConfig(AdminMessage.ConfigType.SECURITY_CONFIG) },
            requestMatches = { it.get_config_request == AdminMessage.ConfigType.SECURITY_CONFIG },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_config_response = expected))
        }
    }

    @Test
    fun setDeviceConfigBuilderSendsDeviceSection() = runTest {
        val expected = Config(device = Config.DeviceConfig(role = Config.DeviceConfig.Role.CLIENT))
        assertAckedOperation(
            call = { it.setDeviceConfig { copy(role = Config.DeviceConfig.Role.CLIENT) } },
            requestMatches = { it.set_config == expected },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setLoraConfigBuilderSendsLoraSection() = runTest {
        val expected = Config(lora = Config.LoRaConfig(use_preset = true))
        assertAckedOperation(
            call = { it.setLoraConfig { copy(use_preset = true) } },
            requestMatches = { it.set_config == expected },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setBluetoothConfigBuilderSendsBluetoothSection() = runTest {
        val expected = Config(bluetooth = Config.BluetoothConfig(enabled = true))
        assertAckedOperation(
            call = { it.setBluetoothConfig { copy(enabled = true) } },
            requestMatches = { it.set_config == expected },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setDisplayConfigBuilderSendsDisplaySection() = runTest {
        val expected = Config(display = Config.DisplayConfig(screen_on_secs = 45))
        assertAckedOperation(
            call = { it.setDisplayConfig { copy(screen_on_secs = 45) } },
            requestMatches = { it.set_config == expected },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setNetworkConfigBuilderSendsNetworkSection() = runTest {
        val expected = Config(network = Config.NetworkConfig(wifi_enabled = true, wifi_ssid = "mesh"))
        assertAckedOperation(
            call = { it.setNetworkConfig { copy(wifi_enabled = true, wifi_ssid = "mesh") } },
            requestMatches = { it.set_config == expected },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setPositionConfigBuilderSendsPositionSection() = runTest {
        val expected = Config(position = Config.PositionConfig(position_broadcast_secs = 300))
        assertAckedOperation(
            call = { it.setPositionConfig { copy(position_broadcast_secs = 300) } },
            requestMatches = { it.set_config == expected },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setPowerConfigBuilderSendsPowerSection() = runTest {
        val expected = Config(power = Config.PowerConfig(is_power_saving = true))
        assertAckedOperation(
            call = { it.setPowerConfig { copy(is_power_saving = true) } },
            requestMatches = { it.set_config == expected },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setSecurityConfigBuilderSendsSecuritySection() = runTest {
        val expected = Config(security = Config.SecurityConfig(is_managed = true))
        assertAckedOperation(
            call = { it.setSecurityConfig { copy(is_managed = true) } },
            requestMatches = { it.set_config == expected },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun getDeviceMetadataReturnsResponse() = runTest {
        val expected = DeviceMetadata(firmware_version = "2.5.0")
        assertRpcOperation(
            call = { it.getDeviceMetadata() },
            requestMatches = { it.get_device_metadata_request == true },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_device_metadata_response = expected))
        }
    }

    @Test
    fun getDeviceMetadataTimeoutReturnsTimeout() = runTest {
        assertRpcOperation(
            call = { it.getDeviceMetadata() },
            requestMatches = { it.get_device_metadata_request == true },
            expected = AdminResult.Timeout,
        ) { _, _ ->
            advanceTimeBy(70.seconds)
        }
    }

    @Test
    fun getModuleConfigReturnsResponse() = runTest {
        val expected = ModuleConfig(mqtt = ModuleConfig.MQTTConfig(enabled = true))
        assertRpcOperation(
            call = { it.getModuleConfig(AdminMessage.ModuleConfigType.MQTT_CONFIG) },
            requestMatches = { it.get_module_config_request == AdminMessage.ModuleConfigType.MQTT_CONFIG },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_module_config_response = expected))
        }
    }

    @Test
    fun getModuleConfigRoutingErrorBecomesUnauthorized() = runTest {
        assertRpcOperation(
            call = { it.getModuleConfig(AdminMessage.ModuleConfigType.MQTT_CONFIG) },
            requestMatches = { it.get_module_config_request == AdminMessage.ModuleConfigType.MQTT_CONFIG },
            expected = AdminResult.Unauthorized,
        ) { transport, packet ->
            transport.injectFrame(buildRoutingErrorFrame(packet.id, Routing.Error.NOT_AUTHORIZED))
        }
    }

    @Test
    fun getUiConfigReturnsResponse() = runTest {
        val expected = DeviceUIConfig(screen_brightness = 128)
        assertRpcOperation(
            call = { it.getUIConfig() },
            requestMatches = { it.get_ui_config_request == true },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_ui_config_response = expected))
        }
    }

    @Test
    fun getCannedMessagesReturnsResponse() = runTest {
        assertRpcOperation(
            call = { it.getCannedMessages() },
            requestMatches = { it.get_canned_message_module_messages_request == true },
            expected = AdminResult.Success("alpha|bravo"),
        ) { transport, packet ->
            transport.injectAdminResponse(
                packet.id,
                AdminMessage(get_canned_message_module_messages_response = "alpha|bravo"),
            )
        }
    }

    @Test
    fun getRingtoneReturnsResponse() = runTest {
        assertRpcOperation(
            call = { it.getRingtone() },
            requestMatches = { it.get_ringtone_request == true },
            expected = AdminResult.Success("Test:d=4,o=5,b=100:c"),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_ringtone_response = "Test:d=4,o=5,b=100:c"))
        }
    }

    @Test
    fun getDeviceConnectionStatusReturnsResponse() = runTest {
        val expected = DeviceConnectionStatus()
        assertRpcOperation(
            call = { it.getDeviceConnectionStatus() },
            requestMatches = { it.get_device_connection_status_request == true },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_device_connection_status_response = expected))
        }
    }

    @Test
    fun getRemoteHardwarePinsReturnsResponse() = runTest {
        val expected = NodeRemoteHardwarePinsResponse()
        assertRpcOperation(
            call = { it.getRemoteHardwarePins() },
            requestMatches = { it.get_node_remote_hardware_pins_request == true },
            expected = AdminResult.Success(expected),
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_node_remote_hardware_pins_response = expected))
        }
    }

    @Test
    fun getChannelUsesOneBasedWireIndex() = runTest {
        val expected = Channel(index = 0, role = Channel.Role.PRIMARY)
        assertRpcOperation(
            call = { it.getChannel(ChannelIndex(0)) },
            requestMatches = { it.get_channel_request == 1 },
            expected = AdminResult.Success(expected),
            assertPacket = { _, admin -> assertEquals(1, admin.get_channel_request) },
        ) { transport, packet ->
            transport.injectAdminResponse(packet.id, AdminMessage(get_channel_response = expected))
        }
    }

    @Test
    fun listChannelsPropagatesGetterFailure() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { client.admin.listChannels() }

            runCurrent()
            val first = latestAdminPacket(transport, outboundBefore) { it.get_channel_request == 1 }
            transport.injectAdminResponse(
                first.id,
                AdminMessage(get_channel_response = Channel(index = 0, role = Channel.Role.PRIMARY)),
            )
            runCurrent()

            val second = latestAdminPacket(transport, outboundBefore) { it.get_channel_request == 2 }
            transport.injectFrame(buildRoutingErrorFrame(second.id, Routing.Error.NOT_AUTHORIZED))
            runCurrent()

            assertEquals(AdminResult.Unauthorized, deferred.await())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun setChannelSuccessUpdatesChannelsState() = runTest {
        val channel = Channel(index = 3, role = Channel.Role.SECONDARY)
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { client.admin.setChannel(channel) }
            runCurrent()

            val packet = latestAdminPacket(transport, outboundBefore) { it.set_channel == channel }
            transport.injectRoutingAck(packet.id)
            runCurrent()

            assertIs<AdminResult.Success<Unit>>(deferred.await())
            val channels = client.channels.value
            assertNotNull(channels)
            assertEquals(channel, channels[3])
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun setChannelTimeoutReturnsTimeout() = runTest {
        val channel = Channel(index = 1, role = Channel.Role.SECONDARY)
        assertAckedOperation(
            call = { it.setChannel(channel) },
            requestMatches = { it.set_channel == channel },
            expected = AdminResult.Timeout,
        ) { _, _ ->
            advanceTimeBy(70.seconds)
        }
    }

    @Test
    fun setChannelRoutingErrorMapsToNodeUnreachable() = runTest {
        val channel = Channel(index = 1, role = Channel.Role.SECONDARY)
        assertAckedOperation(
            call = { it.setChannel(channel) },
            requestMatches = { it.set_channel == channel },
            expected = AdminResult.NodeUnreachable,
        ) { transport, packet ->
            transport.injectFrame(buildRoutingErrorFrame(packet.id, Routing.Error.NO_ROUTE))
        }
    }

    @Test
    fun setFavoriteTrueSendsSetFavoriteNode() = runTest {
        val node = NodeId(0x01020304)
        assertAckedOperation(
            call = { it.setFavorite(node, favorite = true) },
            requestMatches = { it.set_favorite_node == node.raw },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setFavoriteFalseSendsRemoveFavoriteNode() = runTest {
        val node = NodeId(0x01020304)
        assertAckedOperation(
            call = { it.setFavorite(node, favorite = false) },
            requestMatches = { it.remove_favorite_node == node.raw },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setIgnoredTrueSendsSetIgnoredNode() = runTest {
        val node = NodeId(0x05060708)
        assertAckedOperation(
            call = { it.setIgnored(node, ignored = true) },
            requestMatches = { it.set_ignored_node == node.raw },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setIgnoredFalseSendsRemoveIgnoredNode() = runTest {
        val node = NodeId(0x05060708)
        assertAckedOperation(
            call = { it.setIgnored(node, ignored = false) },
            requestMatches = { it.remove_ignored_node == node.raw },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun toggleMutedSendsToggleCommand() = runTest {
        val node = NodeId(0x0a0b0c0d)
        assertAckedOperation(
            call = { it.toggleMuted(node) },
            requestMatches = { it.toggle_muted_node == node.raw },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setFixedPositionSendsPosition() = runTest {
        val position = Position(latitude_i = 377749000, longitude_i = -1224194000, altitude = 12)
        assertAckedOperation(
            call = { it.setFixedPosition(position) },
            requestMatches = { it.set_fixed_position == position },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun removeFixedPositionSendsRemovalFlag() = runTest {
        assertAckedOperation(
            call = { it.removeFixedPosition() },
            requestMatches = { it.remove_fixed_position == true },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun storeUiConfigSendsConfig() = runTest {
        val uiConfig = DeviceUIConfig(screen_brightness = 64)
        assertAckedOperation(
            call = { it.storeUIConfig(uiConfig) },
            requestMatches = { it.store_ui_config == uiConfig },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setCannedMessagesSendsString() = runTest {
        assertAckedOperation(
            call = { it.setCannedMessages("one|two") },
            requestMatches = { it.set_canned_message_module_messages == "one|two" },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setRingtoneSendsString() = runTest {
        assertAckedOperation(
            call = { it.setRingtone("Ring:d=4,o=5,b=120:c") },
            requestMatches = { it.set_ringtone_message == "Ring:d=4,o=5,b=120:c" },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun backupPreferencesSendsLocation() = runTest {
        assertAckedOperation(
            call = { it.backupPreferences(AdminMessage.BackupLocation.SD) },
            requestMatches = { it.backup_preferences == AdminMessage.BackupLocation.SD },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun restorePreferencesSendsLocation() = runTest {
        assertAckedOperation(
            call = { it.restorePreferences(AdminMessage.BackupLocation.SD) },
            requestMatches = { it.restore_preferences == AdminMessage.BackupLocation.SD },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun removeBackupPreferencesSendsLocation() = runTest {
        assertAckedOperation(
            call = { it.removeBackupPreferences(AdminMessage.BackupLocation.SD) },
            requestMatches = { it.remove_backup_preferences == AdminMessage.BackupLocation.SD },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun removeNodeSendsNodeNum() = runTest {
        val node = NodeId(0x0f0e0d0c)
        assertAckedOperation(
            call = { it.removeNode(node) },
            requestMatches = { it.remove_by_nodenum == node.raw },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setScaleSendsScale() = runTest {
        assertAckedOperation(
            call = { it.setScale(240) },
            requestMatches = { it.set_scale == 240 },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun sendInputEventSendsInputEvent() = runTest {
        val event = AdminMessage.InputEvent(event_code = 17, kb_char = 65)
        assertAckedOperation(
            call = { it.sendInputEvent(event) },
            requestMatches = { it.send_input_event == event },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun addContactSendsSharedContact() = runTest {
        val contact = SharedContact(node_num = 77, user = User(id = "!0000004d", long_name = "Contact", short_name = "CT"))
        assertAckedOperation(
            call = { it.addContact(contact) },
            requestMatches = { it.add_contact == contact },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun keyVerificationSendsVerification() = runTest {
        val verification = KeyVerificationAdmin(remote_nodenum = 99, nonce = 1234L)
        assertAckedOperation(
            call = { it.keyVerification(verification) },
            requestMatches = { it.key_verification == verification },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun rebootOtaSendsDelaySeconds() = runTest {
        assertAckedOperation(
            call = { it.rebootOta(5.seconds) },
            requestMatches = { it.reboot_ota_seconds == 5 },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun otaRequestSendsEvent() = runTest {
        val event = AdminMessage.OTAEvent()
        assertAckedOperation(
            call = { it.otaRequest(event) },
            requestMatches = { it.ota_request == event },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setSensorConfigSendsConfig() = runTest {
        val config = SensorConfig()
        assertAckedOperation(
            call = { it.setSensorConfig(config) },
            requestMatches = { it.sensor_config == config },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun exitSimulatorSendsFlag() = runTest {
        assertAckedOperation(
            call = { it.exitSimulator() },
            requestMatches = { it.exit_simulator == true },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun rebootSendsDelaySeconds() = runTest {
        assertAckedOperation(
            call = { it.reboot(3.seconds) },
            requestMatches = { it.reboot_seconds == 3 },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun shutdownSendsDelaySeconds() = runTest {
        assertAckedOperation(
            call = { it.shutdown(4.seconds) },
            requestMatches = { it.shutdown_seconds == 4 },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun enterDfuModeManagedDeviceIsUnauthorized() = runTest {
        val (transport, client) = connectedClient(
            frames = handshakeFrames(
                org.meshtastic.proto.FromRadio(metadata = DeviceMetadata(firmware_version = "managed")),
                org.meshtastic.proto.FromRadio(config = Config(security = Config.SecurityConfig(is_managed = true))),
            ),
        )
        client.connect()
        runCurrent()
        try {
            assertNotNull(client.configBundle.value)
            val outboundBefore = transport.outboundPackets().size
            assertEquals(AdminResult.Unauthorized, client.admin.enterDfuMode())
            assertEquals(outboundBefore, transport.outboundPackets().size)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun deleteFileTimeoutReturnsTimeout() = runTest {
        assertAckedOperation(
            call = { it.deleteFile("logs/app.txt") },
            requestMatches = { it.delete_file_request == "logs/app.txt" },
            expected = AdminResult.Timeout,
        ) { _, _ ->
            advanceTimeBy(70.seconds)
        }
    }

    @Test
    fun factoryResetPreserveBleBondsUsesConfigReset() = runTest {
        assertAckedOperation(
            call = { it.factoryReset(preserveBleBonds = true) },
            requestMatches = { it.factory_reset_config == 1 },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun factoryResetWithoutPreserveBleBondsUsesDeviceReset() = runTest {
        assertAckedOperation(
            call = { it.factoryReset(preserveBleBonds = false) },
            requestMatches = { it.factory_reset_device == 1 },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun nodeDbResetSendsFlag() = runTest {
        assertAckedOperation(
            call = { it.nodeDbReset() },
            requestMatches = { it.nodedb_reset == true },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setOwnerTimeoutReturnsTimeout() = runTest {
        val user = User(id = "!00000001", long_name = "Owner", short_name = "OW")
        assertAckedOperation(
            call = { it.setOwner(user) },
            requestMatches = { it.set_owner == user },
            expected = AdminResult.Timeout,
        ) { _, _ ->
            advanceTimeBy(70.seconds)
        }
    }

    @Test
    fun setOwnerRoutingErrorMapsToRateLimited() = runTest {
        val user = User(id = "!00000001", long_name = "Owner", short_name = "OW")
        assertAckedOperation(
            call = { it.setOwner(user) },
            requestMatches = { it.set_owner == user },
            expected = AdminResult.RateLimited,
        ) { transport, packet ->
            transport.injectFrame(buildRoutingErrorFrame(packet.id, Routing.Error.RATE_LIMIT_EXCEEDED))
        }
    }

    @Test
    fun setTimeOnlyManagedDeviceIsUnauthorized() = runTest {
        val (transport, client) = connectedClient(
            frames = handshakeFrames(
                org.meshtastic.proto.FromRadio(metadata = DeviceMetadata(firmware_version = "managed")),
                org.meshtastic.proto.FromRadio(config = Config(security = Config.SecurityConfig(is_managed = true))),
            ),
        )
        client.connect()
        runCurrent()
        try {
            assertNotNull(client.configBundle.value)
            val outboundBefore = transport.outboundPackets().size
            assertEquals(AdminResult.Unauthorized, client.admin.setTimeOnly(1_700_000_123))
            assertEquals(outboundBefore, transport.outboundPackets().size)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun setTimeUsesExplicitInstant() = runTest {
        val instant = Instant.fromEpochSeconds(1_700_000_456L)
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val result = client.admin.setTime(instant)
            runCurrent()

            val packet = latestAdminPacket(transport, outboundBefore) { it.set_time_only == instant.epochSeconds.toInt() }
            assertIs<AdminResult.Success<Unit>>(result)
            assertFalse(packet.want_ack)
            assertEquals(false, packet.decoded?.want_response)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun setHamModeSuccess() = runTest {
        val params = HamParameters(call_sign = "KD2ABC", tx_power = 20, frequency = 146.52f, short_name = "KD")
        assertAckedOperation(
            call = { it.setHamMode(params) },
            requestMatches = { it.set_ham_mode == params },
        ) { transport, packet ->
            transport.injectRoutingAck(packet.id)
        }
    }

    @Test
    fun setHamModeTimeoutReturnsTimeout() = runTest {
        val params = HamParameters(call_sign = "KD2ABC")
        assertAckedOperation(
            call = { it.setHamMode(params) },
            requestMatches = { it.set_ham_mode == params },
            expected = AdminResult.Timeout,
        ) { _, _ ->
            advanceTimeBy(70.seconds)
        }
    }

    @Test
    fun forNodeTargetsRemoteDestination() = runTest {
        val remote = NodeId(0x12345678)
        val expected = DeviceMetadata(firmware_version = "remote")
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { client.admin.forNode(remote).getDeviceMetadata() }
            runCurrent()

            val packet = latestAdminPacket(transport, outboundBefore) { it.get_device_metadata_request == true }
            assertEquals(remote.raw, packet.to)
            transport.injectAdminResponse(packet.id, AdminMessage(get_device_metadata_response = expected), fromNode = remote.raw)
            runCurrent()

            assertEquals(AdminResult.Success(expected), deferred.await())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun editSettingsSuccessfulWritesUpdateConfigBundle() = runTest {
        val updatedConfig = Config(device = Config.DeviceConfig(role = Config.DeviceConfig.Role.ROUTER))
        val updatedModule = ModuleConfig(mqtt = ModuleConfig.MQTTConfig(enabled = true))
        val (transport, client) = connectedClient(
            frames = handshakeFrames(
                org.meshtastic.proto.FromRadio(metadata = DeviceMetadata(firmware_version = "2.5.0")),
                org.meshtastic.proto.FromRadio(config = Config(device = Config.DeviceConfig(role = Config.DeviceConfig.Role.CLIENT))),
                org.meshtastic.proto.FromRadio(moduleConfig = ModuleConfig(mqtt = ModuleConfig.MQTTConfig(enabled = false))),
            ),
        )
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async {
                client.admin.editSettings {
                    setConfig(updatedConfig)
                    setModuleConfig(updatedModule)
                }
            }
            runCurrent()

            val begin = latestAdminPacket(transport, outboundBefore) { it.begin_edit_settings == true }
            transport.injectRoutingAck(begin.id)
            runCurrent()

            val commit = latestAdminPacket(transport, outboundBefore) { it.commit_edit_settings == true }
            transport.injectRoutingAck(commit.id)
            runCurrent()

            assertIs<AdminResult.Success<Unit>>(deferred.await())
            val bundle = client.configBundle.value
            assertNotNull(bundle)
            assertTrue(bundle.configs.any { it.device?.role == Config.DeviceConfig.Role.ROUTER })
            assertTrue(bundle.moduleConfigs.any { it.mqtt?.enabled == true })
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun batchSuccessfulWritesUpdateConfigBundle() = runTest {
        val updatedConfig = Config(device = Config.DeviceConfig(role = Config.DeviceConfig.Role.ROUTER))
        val updatedModule = ModuleConfig(mqtt = ModuleConfig.MQTTConfig(enabled = true))
        val (transport, client) = connectedClient(
            frames = handshakeFrames(
                org.meshtastic.proto.FromRadio(metadata = DeviceMetadata(firmware_version = "2.5.0")),
                org.meshtastic.proto.FromRadio(config = Config(device = Config.DeviceConfig(role = Config.DeviceConfig.Role.CLIENT))),
                org.meshtastic.proto.FromRadio(moduleConfig = ModuleConfig(mqtt = ModuleConfig.MQTTConfig(enabled = false))),
            ),
        )
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async {
                client.admin.batch {
                    setConfig(updatedConfig)
                    setModuleConfig(updatedModule)
                    "done"
                }
            }
            runCurrent()

            val begin = latestAdminPacket(transport, outboundBefore) { it.begin_edit_settings == true }
            transport.injectRoutingAck(begin.id)
            runCurrent()

            val commit = latestAdminPacket(transport, outboundBefore) { it.commit_edit_settings == true }
            transport.injectRoutingAck(commit.id)
            runCurrent()

            assertEquals("done", deferred.await())
            val bundle = client.configBundle.value
            assertNotNull(bundle)
            assertTrue(bundle.configs.any { it.device?.role == Config.DeviceConfig.Role.ROUTER })
            assertTrue(bundle.moduleConfigs.any { it.mqtt?.enabled == true })
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun batchBlockExceptionSkipsCommit() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async {
                runCatching {
                    client.admin.batch {
                        error("boom")
                    }
                }
            }
            runCurrent()

            val begin = latestAdminPacket(transport, outboundBefore) { it.begin_edit_settings == true }
            transport.injectRoutingAck(begin.id)
            runCurrent()

            val result = deferred.await()
            assertEquals("boom", result.exceptionOrNull()?.message)
            assertTrue(
                transport.outboundPackets().drop(outboundBefore)
                    .none { adminOf(it)?.commit_edit_settings == true },
            )
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun batchBeginFailureThrowsNodeUnreachable() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { runCatching { client.admin.batch { Unit } } }
            runCurrent()

            val begin = latestAdminPacket(transport, outboundBefore) { it.begin_edit_settings == true }
            transport.injectFrame(buildRoutingErrorFrame(begin.id, Routing.Error.NO_ROUTE))
            runCurrent()

            assertIs<AdminResultException.NodeUnreachable>(deferred.await().exceptionOrNull())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun batchCommitFailureThrowsUnauthorized() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { runCatching { client.admin.batch { Unit } } }
            runCurrent()

            val begin = latestAdminPacket(transport, outboundBefore) { it.begin_edit_settings == true }
            transport.injectRoutingAck(begin.id)
            runCurrent()

            val commit = latestAdminPacket(transport, outboundBefore) { it.commit_edit_settings == true }
            transport.injectFrame(buildRoutingErrorFrame(commit.id, Routing.Error.NOT_AUTHORIZED))
            runCurrent()

            assertIs<AdminResultException.Unauthorized>(deferred.await().exceptionOrNull())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun batchSetterDisconnectThrowsNodeUnreachable() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async {
                runCatching {
                    client.admin.batch {
                        gate.await()
                        setFavorite(NodeId(0x10101010), favorite = true)
                    }
                }
            }
            runCurrent()

            val begin = latestAdminPacket(transport, outboundBefore) { it.begin_edit_settings == true }
            transport.injectRoutingAck(begin.id)
            runCurrent()

            client.disconnect()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            val result = deferred.await()
            assertIs<AdminResultException.NodeUnreachable>(result.exceptionOrNull())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun editSettingsBeginFailureReturnsNodeUnreachable() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { client.admin.editSettings { Unit } }
            runCurrent()

            val begin = latestAdminPacket(transport, outboundBefore) { it.begin_edit_settings == true }
            transport.injectFrame(buildRoutingErrorFrame(begin.id, Routing.Error.NO_ROUTE))
            runCurrent()

            assertEquals(AdminResult.NodeUnreachable, deferred.await())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun editSettingsCommitFailureReturnsUnauthorized() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { client.admin.editSettings { Unit } }
            runCurrent()

            val begin = latestAdminPacket(transport, outboundBefore) { it.begin_edit_settings == true }
            transport.injectRoutingAck(begin.id)
            runCurrent()

            val commit = latestAdminPacket(transport, outboundBefore) { it.commit_edit_settings == true }
            transport.injectFrame(buildRoutingErrorFrame(commit.id, Routing.Error.NOT_AUTHORIZED))
            runCurrent()

            assertEquals(AdminResult.Unauthorized, deferred.await())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    @Test
    fun editSettingsSetterDisconnectReturnsNodeUnreachable() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async {
                client.admin.editSettings {
                    gate.await()
                    setFavorite(NodeId(0x20202020), favorite = true)
                }
            }
            runCurrent()

            val begin = latestAdminPacket(transport, outboundBefore) { it.begin_edit_settings == true }
            transport.injectRoutingAck(begin.id)
            runCurrent()

            client.disconnect()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(AdminResult.NodeUnreachable, deferred.await())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private fun TestScope.connectedClient(
        nodeNum: Int = 1,
        storageProvider: StorageProvider = InMemoryStorageProvider(),
        rpcTimeout: Duration = 60.seconds,
        sendTimeout: Duration = 60.seconds,
        frames: List<Frame> = emptyList(),
    ): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:admin-comprehensive:$nodeNum:${hashCode()}"),
            frames = frames,
            autoHandshake = true,
            nodeNum = nodeNum,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(storageProvider)
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .rpcTimeout(rpcTimeout)
            .sendTimeout(sendTimeout)
            .build()
        return transport to client
    }

    private fun handshakeFrames(vararg fromRadio: org.meshtastic.proto.FromRadio): List<Frame> =
        fromRadio.map(::fromRadioFrame)

    private fun fromRadioFrame(fromRadio: org.meshtastic.proto.FromRadio): Frame {
        val proto = org.meshtastic.proto.FromRadio.ADAPTER.encode(fromRadio)
        val bytes = ByteArray(4 + proto.size).apply {
            this[0] = 0x94.toByte()
            this[1] = 0xC3.toByte()
            this[2] = (proto.size shr 8).toByte()
            this[3] = (proto.size and 0xFF).toByte()
            proto.copyInto(this, destinationOffset = 4)
        }
        return Frame(kotlinx.io.bytestring.ByteString(bytes))
    }

    private suspend fun TestScope.assertAckedOperation(
        storageProvider: StorageProvider = InMemoryStorageProvider(),
        call: suspend (AdminApi) -> AdminResult<Unit>,
        requestMatches: (AdminMessage) -> Boolean,
        expected: AdminResult<Unit> = AdminResult.Success(Unit),
        assertPacket: (MeshPacket, AdminMessage) -> Unit = { _, _ -> },
        respond: suspend TestScope.(FakeRadioTransport, MeshPacket) -> Unit,
    ) {
        val (transport, client) = connectedClient(storageProvider = storageProvider)
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { call(client.admin) }
            runCurrent()

            val packet = latestAdminPacket(transport, outboundBefore, requestMatches)
            val admin = adminOf(packet)!!
            assertTrue(packet.want_ack)
            assertEquals(false, packet.decoded?.want_response)
            assertPacket(packet, admin)

            respond(transport, packet)
            runCurrent()

            assertEquals(expected, deferred.await())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private suspend fun <T> TestScope.assertRpcOperation(
        storageProvider: StorageProvider = InMemoryStorageProvider(),
        call: suspend (AdminApi) -> AdminResult<T>,
        requestMatches: (AdminMessage) -> Boolean,
        expected: AdminResult<T>,
        assertPacket: (MeshPacket, AdminMessage) -> Unit = { _, _ -> },
        respond: suspend TestScope.(FakeRadioTransport, MeshPacket) -> Unit,
    ) {
        val (transport, client) = connectedClient(storageProvider = storageProvider)
        client.connect()
        runCurrent()
        try {
            val outboundBefore = transport.outboundPackets().size
            val deferred = async { call(client.admin) }
            runCurrent()

            val packet = latestAdminPacket(transport, outboundBefore, requestMatches)
            val admin = adminOf(packet)!!
            assertFalse(packet.want_ack)
            assertEquals(true, packet.decoded?.want_response)
            assertPacket(packet, admin)

            respond(transport, packet)
            runCurrent()

            assertEquals(expected, deferred.await())
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private fun latestAdminPacket(
        transport: FakeRadioTransport,
        outboundBefore: Int,
        predicate: (AdminMessage) -> Boolean,
    ): MeshPacket = transport.outboundPackets().drop(outboundBefore)
        .last { packet -> adminOf(packet)?.let(predicate) == true }

    private fun adminOf(packet: MeshPacket): AdminMessage? {
        val decoded = packet.decoded ?: return null
        if (decoded.portnum != PortNum.ADMIN_APP) return null
        return runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull()
    }

    private fun buildRoutingErrorFrame(requestId: Int, error: Routing.Error): Frame {
        val payload = okio.ByteString.of(*Routing.ADAPTER.encode(Routing(error_reason = error)))
        val packet = MeshPacket(
            from = 1,
            to = 0,
            decoded = org.meshtastic.proto.Data(
                portnum = PortNum.ROUTING_APP,
                payload = payload,
                request_id = requestId,
            ),
        )
        val fromRadio = org.meshtastic.proto.FromRadio(packet = packet)
        val proto = org.meshtastic.proto.FromRadio.ADAPTER.encode(fromRadio)
        val bytes = ByteArray(4 + proto.size).apply {
            this[0] = 0x94.toByte()
            this[1] = 0xC3.toByte()
            this[2] = (proto.size shr 8).toByte()
            this[3] = (proto.size and 0xFF).toByte()
            proto.copyInto(this, destinationOffset = 4)
        }
        return Frame(kotlinx.io.bytestring.ByteString(bytes))
    }
}
