/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.ExcludedModules
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.HamParameters
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceProfileTest {

    private fun allConfigs(): List<Config> = listOf(
        Config.Builder().also { wb ->
            wb.device =
                Config.DeviceConfig.Builder().also { it.role = Config.DeviceConfig.Role.ROUTER }.build()
        }.build(),
        Config.Builder().also { wb ->
            wb.position =
                Config.PositionConfig.Builder().also { it.fixed_position = true }.build()
        }.build(),
        Config.Builder().also { wb ->
            wb.power = Config.PowerConfig.Builder().also { it.is_power_saving = true }.build()
        }.build(),
        Config.Builder().also { wb ->
            wb.network = Config.NetworkConfig.Builder().also { it.wifi_ssid = "x" }.build()
        }.build(),
        Config.Builder().also { wb ->
            wb.display = Config.DisplayConfig.Builder().also { it.screen_on_secs = 9 }.build()
        }.build(),
        Config.Builder().also { wb -> wb.lora = Config.LoRaConfig.Builder().also { it.tx_power = 17 }.build() }.build(),
        Config.Builder().also { wb ->
            wb.bluetooth = Config.BluetoothConfig.Builder().also { it.enabled = true }.build()
        }.build(),
        Config.Builder().also { wb ->
            wb.security = Config.SecurityConfig.Builder().also {
                it.private_key = bytes(1, 2, 3)
                it.public_key = bytes(4, 5)
            }.build()
        }.build(),
    )

    private fun allModules(): List<ModuleConfig> = listOf(
        ModuleConfig.Builder().also { wb ->
            wb.mqtt =
                ModuleConfig.MQTTConfig.Builder().also { it.enabled = true }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.serial =
                ModuleConfig.SerialConfig.Builder().also { it.enabled = true }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.external_notification =
                ModuleConfig.ExternalNotificationConfig.Builder().also { it.enabled = true }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.store_forward =
                ModuleConfig.StoreForwardConfig.Builder().also { it.enabled = true }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.range_test =
                ModuleConfig.RangeTestConfig.Builder().also { it.enabled = true }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.telemetry =
                ModuleConfig.TelemetryConfig.Builder().also { it.device_update_interval = 5 }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.canned_message =
                ModuleConfig.CannedMessageConfig.Builder().also { it.enabled = true }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.audio =
                ModuleConfig.AudioConfig.Builder().also { it.codec2_enabled = true }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.remote_hardware =
                ModuleConfig.RemoteHardwareConfig.Builder().also { it.enabled = true }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.neighbor_info =
                ModuleConfig.NeighborInfoConfig.Builder().also { it.enabled = true }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.ambient_lighting =
                ModuleConfig.AmbientLightingConfig.Builder().also { it.led_state = true }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.detection_sensor =
                ModuleConfig.DetectionSensorConfig.Builder().also { it.enabled = true }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.paxcounter =
                ModuleConfig.PaxcounterConfig.Builder().also { it.enabled = true }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.statusmessage =
                ModuleConfig.StatusMessageConfig.Builder().also { it.node_status = "ok" }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.traffic_management =
                ModuleConfig.TrafficManagementConfig.Builder().also { it.position_min_interval_secs = 60 }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.tak =
                ModuleConfig.TAKConfig.Builder().also { it.team = org.meshtastic.proto.Team.Cyan }.build()
        }.build(),
        ModuleConfig.Builder().also { wb ->
            wb.mesh_beacon =
                ModuleConfig.MeshBeaconConfig.Builder().also { it.flags = 2 }.build()
        }.build(),
    )

    @Test
    fun configSectionsRoundTripForEveryLocalConfigArm() {
        val sections = allConfigs()
        val local = sections.toLocalConfig()
        assertEquals(8, sections.size)
        assertEquals(sections, local.toConfigSections())
    }

    @Test
    fun configArmsWithoutLocalConfigHomeAreDropped() {
        val sessionkey = Config.Builder().also { wb ->
            wb.sessionkey = Config.SessionkeyConfig.Builder().build()
        }.build()
        assertTrue(listOf(sessionkey).toLocalConfig().toConfigSections().isEmpty())
    }

    @Test
    fun moduleSectionsRoundTripForEveryLocalModuleConfigArm() {
        val sections = allModules()
        val back = sections.toLocalModuleConfig().toModuleConfigSections()
        assertEquals(17, sections.size)
        assertEquals(sections, back)
    }

    @Test
    fun exportDoesNotIncludePrivateKeyByDefault() {
        val profile = buildDeviceProfile(null, allConfigs(), allModules(), null, null, null, null, false)
        val security = assertNotNull(profile.config?.security)
        assertEquals(ByteString.EMPTY, security.private_key)
        assertEquals(bytes(4, 5), security.public_key)
    }

    @Test
    fun exportIncludesPrivateKeyOnOptIn() {
        val profile = buildDeviceProfile(null, allConfigs(), allModules(), null, null, null, null, true)
        assertEquals(bytes(1, 2, 3), profile.config?.security?.private_key)
    }

    @Test
    fun exportBuildsOwnerChannelsAndText() {
        val primary = channel(0, Channel.Role.PRIMARY, "main")
        val disabled = channel(1, Channel.Role.DISABLED, "off")
        val secondary = channel(2, Channel.Role.SECONDARY, "side")
        val owner = User.Builder().also {
            it.long_name = "Alice"
            it.short_name = "AL"
            it.is_licensed = true
        }.build()
        val profile = buildDeviceProfile(
            owner,
            allConfigs(),
            emptyList(),
            listOf(secondary, disabled, primary),
            null,
            "rt:d=4",
            "a|b",
            false,
        )
        assertEquals("Alice", profile.long_name)
        assertEquals("AL", profile.short_name)
        assertEquals(true, profile.is_licensed)
        assertEquals("rt:d=4", profile.ringtone)
        assertEquals("a|b", profile.canned_messages)
        assertNull(profile.module_config)
        val set = assertNotNull(ChannelUrl.parse(assertNotNull(profile.channel_url)))
        assertEquals(listOf("main", "side"), set.settings.map { it.name })
        assertEquals(17, set.lora_config?.tx_power)
    }

    @Test
    fun installPlanWritesOnlyPresentSectionsInOrder() {
        val profile = DeviceProfile.Builder().also { wb ->
            wb.long_name = "Bob"
            wb.config = allConfigs().toLocalConfig()
            wb.module_config = allModules().toLocalModuleConfig()
            wb.channel_url = ChannelSet.Builder().also { cs ->
                cs.settings = listOf(settings("a"), settings("b"))
                cs.lora_config = Config.LoRaConfig.Builder().also { it.tx_power = 30 }.build()
            }.build().toUrl()
        }.build()
        val current = User.Builder().also {
            it.long_name = "old"
            it.short_name = "OL"
        }.build()
        val plan = assertNotNull(planProfileInstall(profile, current, Config.LoRaConfig.Builder().build(), false))
        val edit = RecordingEdit()
        runTest { edit.applyProfilePlan(plan) }
        val kinds = edit.calls
        assertEquals("owner:Bob:OL", kinds.first())
        assertEquals(
            listOf("device", "position", "power", "network", "display", "bluetooth"),
            kinds.drop(1).take(6),
        )
        assertTrue("security" !in kinds)
        assertEquals(16, kinds.count { it.startsWith("module:") })
        assertTrue("module:traffic_management" !in kinds)
        val channelCalls = kinds.filter { it.startsWith("channel:") }
        assertEquals(8, channelCalls.size)
        assertEquals("channel:0:PRIMARY", channelCalls[0])
        assertEquals("channel:1:SECONDARY", channelCalls[1])
        assertEquals("channel:2:DISABLED", channelCalls[2])
        assertEquals("lora:30", kinds.last())
    }

    @Test
    fun installPlanSkipsAbsentSections() {
        val profile = DeviceProfile.Builder().also { wb ->
            wb.config = localConfigOf(device = Config.DeviceConfig.Builder().build())
        }.build()
        val plan = assertNotNull(planProfileInstall(profile, null, null, false))
        val edit = RecordingEdit()
        runTest { edit.applyProfilePlan(plan) }
        assertEquals(listOf("device"), edit.calls)
    }

    @Test
    fun installPlanIncludesSecurityOnlyOnOptIn() {
        val profile = DeviceProfile.Builder().also { wb -> wb.config = allConfigs().toLocalConfig() }.build()
        val off = assertNotNull(planProfileInstall(profile, null, null, false))
        val on = assertNotNull(planProfileInstall(profile, null, null, true))
        assertTrue(off.configs.none { it.security != null })
        assertTrue(on.configs.any { it.security != null })
    }

    @Test
    fun installPlanRejectsMalformedChannelUrl() {
        val profile = DeviceProfile.Builder().also { it.channel_url = "not a url" }.build()
        assertNull(planProfileInstall(profile, null, null, false))
    }

    @Test
    fun installRunsBeginWritesCommitAndFixedPosition() = runTest {
        val (transport, client) = connectedClient(listOf(loraFrame(power = 5, freq = 0f)))
        client.connect()
        runCurrent()
        val fixed = org.meshtastic.proto.Position.Builder().also {
            it.latitude_i = 100
            it.longitude_i = 200
        }.build()
        val profile = DeviceProfile.Builder().also { wb ->
            wb.config =
                localConfigOf(
                    device = Config.DeviceConfig.Builder().also {
                        it.role =
                            Config.DeviceConfig.Role.CLIENT_MUTE
                    }.build(),
                )
            wb.fixed_position = fixed
        }.build()
        val before = transport.outboundPackets().size
        val deferred = async { client.installDeviceProfile(profile) }
        runCurrent()
        transport.injectRoutingAck(admin(transport, before) { it.begin_edit_settings == true }.id)
        runCurrent()
        transport.injectRoutingAck(admin(transport, before) { it.commit_edit_settings == true }.id)
        runCurrent()
        transport.injectRoutingAck(admin(transport, before) { it.set_fixed_position != null }.id)
        runCurrent()
        assertIs<AdminResult.Success<Unit>>(deferred.await())
        val sent = transport.outboundPackets().drop(before).mapNotNull { adminOf(it) }
        val order = sent.map {
            when {
                it.begin_edit_settings == true -> "begin"
                it.set_config != null -> "set_config"
                it.commit_edit_settings == true -> "commit"
                it.set_fixed_position != null -> "fixed"
                else -> "other"
            }
        }.filter { it != "other" }
        assertEquals(listOf("begin", "set_config", "commit", "fixed"), order)
        client.disconnect()
    }

    @Test
    fun exportDoesNotAskForTextTheDeviceExcludes() = runTest {
        val excluded = ExcludedModules.CANNEDMSG_CONFIG.value or ExcludedModules.EXTNOTIF_CONFIG.value
        val (transport, client) = connectedClient(profileFrames(excluded))
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size
        val result = client.exportDeviceProfile()
        val sent = transport.outboundPackets().drop(before).mapNotNull { adminOf(it) }
        assertTrue(
            sent.none {
                it.get_ringtone_request == true || it.get_canned_message_module_messages_request == true
            },
        )
        val profile = assertIs<AdminResult.Success<DeviceProfile>>(result).value
        assertNull(profile.ringtone)
        assertNull(profile.canned_messages)
        client.disconnect()
    }

    @Test
    fun exportFromASilentDeviceWaitsOneShortTimeout() = runTest {
        val (transport, client) = connectedClient(profileFrames(0))
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size
        val start = testScheduler.currentTime
        val deferred = async { client.exportDeviceProfile() }
        runCurrent()
        val sent = transport.outboundPackets().drop(before).mapNotNull { adminOf(it) }
        assertTrue(sent.any { it.get_ringtone_request == true }, "ringtone requested")
        assertTrue(sent.any { it.get_canned_message_module_messages_request == true }, "canned requested at once")
        val profile = assertIs<AdminResult.Success<DeviceProfile>>(deferred.await()).value
        assertEquals(3_000L, testScheduler.currentTime - start)
        assertNull(profile.ringtone)
        assertNull(profile.canned_messages)
        client.disconnect()
    }

    @Test
    fun exportFromADeviceThatAnswersKeepsBothFields() = runTest {
        val (transport, client) = connectedClient(profileFrames(0))
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size
        val deferred = async { client.exportDeviceProfile() }
        runCurrent()
        val ringtone = admin(transport, before) { it.get_ringtone_request == true }
        val canned = admin(transport, before) { it.get_canned_message_module_messages_request == true }
        transport.injectAdminResponse(
            ringtone.id,
            AdminMessage.Builder().also { it.get_ringtone_response = "rt:d=4" }.build(),
        )
        transport.injectAdminResponse(
            canned.id,
            AdminMessage.Builder().also { it.get_canned_message_module_messages_response = "a|b" }.build(),
        )
        runCurrent()
        val profile = assertIs<AdminResult.Success<DeviceProfile>>(deferred.await()).value
        assertEquals("rt:d=4", profile.ringtone)
        assertEquals("a|b", profile.canned_messages)
        client.disconnect()
    }

    @Test
    fun hamParametersFillsPowerAndFrequencyFromLora() {
        val lora = Config.LoRaConfig.Builder().also {
            it.tx_power = 22
            it.override_frequency = 433.5f
        }.build()
        val p = assertNotNull(hamParameters("KD2ABC", "KD", null, null, null, lora))
        assertEquals(22, p.tx_power)
        assertEquals(433.5f, p.frequency)
        assertEquals("KD2ABC", p.call_sign)
        assertEquals("KD", p.short_name)
    }

    @Test
    fun hamParametersUsesCallerValuesWhenGiven() {
        val lora = Config.LoRaConfig.Builder().also {
            it.tx_power = 22
            it.override_frequency = 433.5f
        }.build()
        val p = assertNotNull(hamParameters("KD2ABC", null, null, 10, 144.0f, lora))
        assertEquals(10, p.tx_power)
        assertEquals(144.0f, p.frequency)
        assertNotNull(hamParameters("KD2ABC", null, null, 10, 144.0f, null))
    }

    @Test
    fun hamParametersFailsWithoutLoraWhenAValueIsMissing() {
        assertNull(hamParameters("KD2ABC", null, null, 10, null, null))
        assertNull(hamParameters("KD2ABC", null, null, null, null, null))
    }

    @Test
    fun enableHamModeSendsLoraValuesOnTheWire() = runTest {
        val (transport, client) = connectedClient(listOf(loraFrame(power = 14, freq = 144.39f)))
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size
        val deferred = async { client.enableHamMode("KD2ABC") }
        runCurrent()
        val packet = admin(transport, before) { it.set_ham_mode != null }
        val sent = assertNotNull(adminOf(packet)?.set_ham_mode)
        assertEquals(14, sent.tx_power)
        assertEquals(144.39f, sent.frequency)
        transport.injectRoutingAck(packet.id)
        runCurrent()
        assertIs<AdminResult.Success<Unit>>(deferred.await())
        client.disconnect()
    }

    @Test
    fun enableHamModeFailsCleanlyWithoutConfig() = runTest {
        val (transport, client) = connectedClient(emptyList())
        val before = transport.outboundPackets().size
        assertEquals(AdminResult.Failed(Routing.Error.BAD_REQUEST), client.enableHamMode("KD2ABC"))
        assertEquals(before, transport.outboundPackets().size)
    }

    @Test
    fun setHamModeStaysVerbatim() = runTest {
        val (transport, client) = connectedClient(listOf(loraFrame(power = 14, freq = 144.39f)))
        client.connect()
        runCurrent()
        val before = transport.outboundPackets().size
        val params = HamParameters.Builder().also { it.call_sign = "KD2ABC" }.build()
        val deferred = async { client.admin.setHamMode(params) }
        runCurrent()
        val packet = admin(transport, before) { it.set_ham_mode != null }
        assertEquals(params, adminOf(packet)?.set_ham_mode)
        transport.injectRoutingAck(packet.id)
        runCurrent()
        deferred.await()
        client.disconnect()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun localConfigOf(device: Config.DeviceConfig) =
        org.meshtastic.proto.LocalConfig.Builder().also { it.device = device }.build()

    private fun settings(name: String) = ChannelSettings.Builder().also { it.name = name }.build()

    private fun channel(index: Int, role: Channel.Role, name: String) = Channel.Builder().also {
        it.index = index
        it.role = role
        it.settings = settings(name)
    }.build()

    private fun bytes(vararg v: Int): ByteString = ByteArray(v.size) { v[it].toByte() }.toByteString()

    private fun loraFrame(power: Int, freq: Float) = FromRadio.Builder().also { fr ->
        fr.config = Config.Builder().also { c ->
            c.lora = Config.LoRaConfig.Builder().also {
                it.tx_power = power
                it.override_frequency = freq
            }.build()
        }.build()
    }.build().toFrame()

    private fun profileFrames(excludedModules: Int) = listOf(
        FromRadio.Builder().also { fr ->
            fr.metadata = DeviceMetadata.Builder().also { it.excluded_modules = excludedModules }.build()
        }.build().toFrame(),
        FromRadio.Builder().also { fr ->
            fr.node_info = NodeInfo.Builder().also { ni ->
                ni.num = 1
                ni.user = User.Builder().also { it.long_name = "Fake" }.build()
            }.build()
        }.build().toFrame(),
        loraFrame(power = 14, freq = 0f),
    )

    private fun TestScope.connectedClient(frames: List<Frame>): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:device-profile:${frames.size}"),
            frames = frames,
            autoHandshake = true,
            nodeNum = 1,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .build()
        return transport to client
    }

    private fun admin(t: FakeRadioTransport, before: Int, p: (AdminMessage) -> Boolean): MeshPacket =
        t.outboundPackets().drop(before).last { adminOf(it)?.let(p) == true }

    private fun adminOf(packet: MeshPacket): AdminMessage? {
        val decoded = packet.decoded ?: return null
        if (decoded.portnum != PortNum.ADMIN_APP) return null
        return runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull()
    }
}

private class RecordingEdit : AdminEdit {
    val calls = mutableListOf<String>()

    override suspend fun setConfig(config: Config) {
        val arm = when {
            config.device != null -> "device"
            config.position != null -> "position"
            config.power != null -> "power"
            config.network != null -> "network"
            config.display != null -> "display"
            config.lora != null -> "lora:${config.lora!!.tx_power}"
            config.bluetooth != null -> "bluetooth"
            config.security != null -> "security"
            else -> "unknown"
        }
        calls += arm
    }

    override suspend fun setModuleConfig(config: ModuleConfig) {
        val arm = when {
            config.traffic_management != null -> "traffic_management"
            else -> "x"
        }
        calls += "module:$arm"
    }

    override suspend fun setOwner(user: User) {
        calls += "owner:${user.long_name}:${user.short_name}"
    }

    override suspend fun setChannel(channel: Channel) {
        calls += "channel:${channel.index}:${channel.role}"
    }

    override suspend fun setFavorite(node: NodeId, favorite: Boolean) = Unit

    override suspend fun setIgnored(node: NodeId, ignored: Boolean) = Unit
}
