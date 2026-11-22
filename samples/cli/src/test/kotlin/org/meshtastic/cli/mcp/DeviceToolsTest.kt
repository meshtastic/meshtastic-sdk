/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.LocalStats
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import org.meshtastic.proto.Waypoint
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.InMemoryStorageProvider
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val OWN = 1
private const val REMOTE = 0x33

private fun a(json: String): Args = Args(Json.parseToJsonElement(json).jsonObject)

private fun JsonObject.obj(vararg path: String): JsonObject = path.fold(this) { o, k -> o.getValue(k).jsonObject }

/** The tool layer against the SDK's fake transport: no hardware, a scripted device behind the link. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceToolsTest {

    private class Rig(val transport: FakeRadioTransport, val client: RadioClient) {
        val configs = mutableMapOf<String, Config>()
        val modules = mutableMapOf<String, ModuleConfig>()
        val channels = mutableMapOf<Int, Channel>()
        var owner: User = User.Builder().also {
            it.id = "!00000001"
            it.long_name = "Fake"
            it.short_name = "FK"
        }.build()

        fun admins(): List<Pair<MeshPacket, AdminMessage>> = transport.outboundPackets().mapNotNull { p ->
            val d = p.decoded ?: return@mapNotNull null
            if (d.portnum != PortNum.ADMIN_APP) return@mapNotNull null
            p to AdminMessage.ADAPTER.decode(d.payload)
        }

        fun packets(port: PortNum) = transport.outboundPackets().filter { it.decoded?.portnum == port }
    }

    private fun section(message: Any, name: String) = runCatching {
        message.javaClass.getField(name).get(message)
    }.getOrNull()

    private val lora = Config.LoRaConfig.Builder().also {
        it.hop_limit = 3
        it.use_preset = true
    }.build()
    private val security = Config.SecurityConfig.Builder().also {
        it.private_key = ByteArray(32) { 9 }.toByteString()
    }.build()
    private val mqtt = ModuleConfig.MQTTConfig.Builder().also { it.enabled = false }.build()

    private fun seedFrames(): List<Frame> = listOf(
        FromRadio.Builder().also {
            it.metadata =
                DeviceMetadata.Builder().also { m -> m.firmware_version = "2.7.0" }.build()
        }.build(),
        FromRadio.Builder().also { it.config = Config.Builder().also { c -> c.lora = lora }.build() }.build(),
        FromRadio.Builder().also {
            it.config = Config.Builder().also { c -> c.security = security }.build()
        }.build(),
        FromRadio.Builder().also {
            it.moduleConfig = ModuleConfig.Builder().also { c -> c.mqtt = mqtt }.build()
        }.build(),
        FromRadio.Builder().also {
            it.channel =
                Channel.Builder().also { c ->
                    c.index = 0
                    c.role = Channel.Role.PRIMARY
                    c.settings =
                        ChannelSettings.Builder().build()
                }.build()
        }.build(),
        FromRadio.Builder().also {
            it.channel = Channel.Builder().also { c ->
                c.index = 1
                c.role = Channel.Role.SECONDARY
                c.settings = ChannelSettings.Builder().also { s -> s.name = "Fun" }.build()
            }.build()
        }.build(),
    ).map { it.toFrame() }

    private fun TestScope.rig(): Rig {
        val frames = seedFrames()
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:mcp-tools"),
            frames = frames,
            autoHandshake = true,
            autoQueueStatus = true,
            nodeNum = OWN,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .rpcTimeout(30.seconds)
            .sendTimeout(30.seconds)
            .build()
        return Rig(transport, client).also {
            it.configs["lora"] = Config.Builder().also { c -> c.lora = lora }.build()
            it.modules["mqtt"] = ModuleConfig.Builder().also { c -> c.mqtt = mqtt }.build()
            it.channels[1] = Channel.Builder().also { c ->
                c.index = 1
                c.role = Channel.Role.SECONDARY
            }.build()
        }
    }

    /** Answers every admin packet the way a device would: acks writes, serves reads from [Rig] state. */
    private fun TestScope.device(rig: Rig) {
        backgroundScope.launch {
            var seen = rig.transport.outboundPackets().size
            while (true) {
                val all = rig.admins()
                val packets = rig.transport.outboundPackets()
                for ((p, m) in all.filter { (p, _) -> packets.indexOf(p) >= seen }) respond(rig, p, m)
                seen = packets.size
                delay(1)
            }
        }
    }

    private fun respond(rig: Rig, p: MeshPacket, m: AdminMessage) {
        val from = if (p.to == 0) OWN else p.to
        fun reply(wb: AdminMessage.Builder.() -> Unit) =
            rig.transport.injectAdminResponse(p.id, AdminMessage.Builder().also(wb).build(), fromNode = from)
        m.set_config?.let { c ->
            Sections.all.firstOrNull { section(c, it.name) != null }?.let {
                rig.configs[it.name] =
                    c
            }
        }
        m.set_module_config?.let { c ->
            Sections.all.firstOrNull { section(c, it.name) != null }?.let {
                rig.modules[it.name] =
                    c
            }
        }
        m.set_channel?.let { rig.channels[it.index] = it }
        m.set_owner?.let { rig.owner = it }
        when {
            m.get_config_request != null -> {
                val type = m.get_config_request!!
                val name = Sections.all.first { it.config == type }.name
                reply { get_config_response = rig.configs[name] ?: Config.Builder().build() }
            }

            m.get_module_config_request != null -> {
                val type = m.get_module_config_request!!
                val name = Sections.all.first { it.module == type }.name
                reply { get_module_config_response = rig.modules[name] ?: ModuleConfig.Builder().build() }
            }

            m.get_owner_request == true -> reply { get_owner_response = rig.owner }

            m.get_channel_request != null ->
                reply { get_channel_response = rig.channels[m.get_channel_request!! - 1] ?: Channel.Builder().build() }

            p.want_ack -> rig.transport.injectRoutingAck(p.id, fromNode = from)
        }
    }

    private suspend fun TestScope.connected(): Rig = rig().also {
        it.client.connect()
        runCurrent()
        device(it)
    }

    private fun labels(rig: Rig) = rig.admins().map { (_, m) ->
        when {
            m.begin_edit_settings == true -> "begin"
            m.commit_edit_settings == true -> "commit"
            m.set_config != null -> "set_config"
            m.set_module_config != null -> "set_module_config"
            else -> "other"
        }
    }.filter { it != "other" }

    @Test
    fun configGetReadsRequestedSectionsFromTheHandshakeCopy() = runTest {
        val rig = connected()
        val out = configGet(rig.client, a("""{"sections":["lora"]}"""))
        assertEquals(3, out.obj("config", "lora").getValue("hop_limit").jsonPrimitive.int)
        assertFalse("security" in out.obj("config"))
        assertTrue(rig.admins().none { it.second.get_config_request != null }, "no radio traffic for a local read")
    }

    @Test
    fun configGetAllRedactsThePrivateKeyAndListsAbsentSections() = runTest {
        val rig = connected()
        val out = configGet(rig.client, a("""{"sections":"all"}"""))
        assertFalse("private_key" in out.obj("config", "security"))
        assertEquals("absent", out.obj("errors").getValue("device").jsonPrimitive.content)
    }

    @Test
    fun configGetReturnsChannelsOwnerAndMetadata() = runTest {
        val rig = connected()
        val out = configGet(rig.client, a("""{"channels":true,"owner":true,"metadata":true}"""))
        assertEquals(2, out.getValue("channels").jsonArray.size)
        assertEquals("2.7.0", out.obj("metadata").getValue("firmware_version").jsonPrimitive.content)
        assertTrue("owner" in out)
        assertFalse("config" in out)
    }

    @Test
    fun configGetRemoteAsksTheRemoteNode() = runTest {
        val rig = connected()
        val call = async { configGet(rig.client, a("""{"sections":["lora"],"dest":"!00000033"}""")) }
        runCurrent()
        val request = rig.admins().last { it.second.get_config_request != null }.first
        assertEquals(REMOTE, request.to)
        val out = call.await()
        assertEquals("!00000033", out.getValue("dest").jsonPrimitive.content)
        assertEquals(3, out.obj("config", "lora").getValue("hop_limit").jsonPrimitive.int)
    }

    @Test
    fun configSetAppliesPathsAsOneTransactionAndReadsBack() = runTest {
        val rig = connected()
        val out = configSet(rig.client, a("""{"values":{"lora.hop_limit":5,"mqtt.enabled":true}}"""))
        assertEquals(listOf("begin", "set_config", "set_module_config", "commit"), labels(rig))
        assertEquals(listOf("lora", "mqtt"), out.getValue("applied").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(true, out.getValue("verified").jsonPrimitive.content.toBoolean())
        assertEquals(5, out.obj("config", "lora").getValue("hop_limit").jsonPrimitive.int)
        assertEquals(true, rig.client.configBundle.value!!.moduleConfigs.any { it.mqtt?.enabled == true })
    }

    @Test
    fun configSetAcceptsNestedObjects() = runTest {
        val rig = connected()
        val out = configSet(rig.client, a("""{"values":{"lora":{"hop_limit":6}}}"""))
        assertEquals(6, out.obj("config", "lora").getValue("hop_limit").jsonPrimitive.int)
    }

    @Test
    fun configSetRejectsAnUnknownPathWithoutTouchingTheRadio() = runTest {
        val rig = connected()
        assertFailsWith<ToolFailure> { configSet(rig.client, a("""{"values":{"lora.hop_limt":5}}""")) }
        assertFailsWith<ToolFailure> { configSet(rig.client, a("""{"values":{"nosuch.x":5}}""")) }
        assertTrue(labels(rig).isEmpty())
    }

    @Test
    fun configSetSkipsTheTransactionWhenNothingChanges() = runTest {
        val rig = connected()
        val out = configSet(rig.client, a("""{"values":{"lora.hop_limit":3}}"""))
        assertEquals(0, (out.getValue("applied") as JsonArray).size)
        assertTrue(labels(rig).isEmpty())
    }

    @Test
    fun channelSetBuildsASecondaryChannelWithARandomKey() = runTest {
        val rig = connected()
        val out = channelSet(rig.client, a("""{"index":2,"name":"Ops","psk":"random","uplink_enabled":true}"""))
        val sent = rig.admins().last { it.second.set_channel != null }.second.set_channel!!
        assertEquals(Channel.Role.SECONDARY, sent.role)
        assertEquals(32, sent.settings!!.psk.size)
        assertEquals("Ops", out.obj("channel", "settings").getValue("name").jsonPrimitive.content)
    }

    @Test
    fun channelSetKeepsExistingFieldsAndRejectsBadInput() = runTest {
        val rig = connected()
        channelSet(rig.client, a("""{"index":1,"muted":true}"""))
        val sent = rig.admins().last { it.second.set_channel != null }.second.set_channel!!
        assertEquals("Fun", sent.settings!!.name)
        assertEquals(true, sent.settings!!.module_settings?.is_muted)
        assertFailsWith<ToolFailure> { channelSet(rig.client, a("""{"index":9}""")) }
        assertFailsWith<ToolFailure> { channelSet(rig.client, a("""{"index":2,"psk":"AAAA"}""")) }
        assertFailsWith<ToolFailure> { channelSet(rig.client, a("""{"index":0,"role":"SECONDARY"}""")) }
    }

    @Test
    fun channelDeleteDisablesTheSlotAndProtectsPrimary() = runTest {
        val rig = connected()
        channelDelete(rig.client, a("""{"index":1}"""))
        assertEquals(
            Channel.Role.DISABLED,
            rig.admins().last {
                it.second.set_channel != null
            }.second.set_channel!!.role,
        )
        assertFailsWith<ToolFailure> { channelDelete(rig.client, a("""{"index":0}""")) }
    }

    @Test
    fun ownerSetEditsLicensedAndUnmessagableOnTheCurrentUser() = runTest {
        val rig = connected()
        val out = ownerSet(rig.client, a("""{"long_name":"Ham One","is_licensed":true,"is_unmessagable":true}"""))
        val sent = rig.admins().last { it.second.set_owner != null }.second.set_owner!!
        assertEquals("Ham One", sent.long_name)
        assertTrue(sent.is_licensed && sent.is_unmessagable == true)
        assertEquals("FK", sent.short_name)
        assertEquals("Ham One", out.obj("owner").getValue("long_name").jsonPrimitive.content)
        assertFailsWith<ToolFailure> { ownerSet(rig.client, a("{}")) }
    }

    @Test
    fun fixedPositionSetAndClear() = runTest {
        val rig = connected()
        fixedPositionSet(rig.client, a("""{"latitude":45.5,"longitude":-122.25,"altitude":30}"""))
        val pos = rig.admins().last { it.second.set_fixed_position != null }.second.set_fixed_position!!
        assertEquals(455_000_000, pos.latitude_i)
        assertEquals(-1_222_500_000, pos.longitude_i)
        assertEquals(30, pos.altitude)
        fixedPositionClear(rig.client, a("{}"))
        assertTrue(rig.admins().any { it.second.remove_fixed_position == true })
        assertFailsWith<ToolFailure> { fixedPositionSet(rig.client, a("""{"latitude":95,"longitude":0}""")) }
    }

    @Test
    fun adminActionsSendTheMatchingMessages() = runTest {
        val rig = connected()
        val reboot = adminAction(rig.client, a("""{"action":"reboot","after_s":5}"""))
        assertEquals(5, rig.admins().last { it.second.reboot_seconds != null }.second.reboot_seconds)
        assertEquals("true", reboot.getValue("reconnect_required").jsonPrimitive.content)
        adminAction(rig.client, a("""{"action":"factory_reset","scope":"full"}"""))
        assertNotNull(rig.admins().last { it.second.factory_reset_device != null })
        adminAction(rig.client, a("""{"action":"factory_reset","scope":"config"}"""))
        assertNotNull(rig.admins().last { it.second.factory_reset_config != null })
        adminAction(rig.client, a("""{"action":"favorite","node":"!00000044"}"""))
        assertEquals(0x44, rig.admins().last { it.second.set_favorite_node != null }.second.set_favorite_node)
        adminAction(rig.client, a("""{"action":"remove_node","node":"!00000044"}"""))
        assertEquals(0x44, rig.admins().last { it.second.remove_by_nodenum != null }.second.remove_by_nodenum)
    }

    @Test
    fun adminActionRejectsMissingArguments() = runTest {
        val rig = connected()
        assertFailsWith<ToolFailure> { adminAction(rig.client, a("""{"action":"factory_reset"}""")) }
        assertFailsWith<ToolFailure> { adminAction(rig.client, a("""{"action":"favorite"}""")) }
        assertFailsWith<ToolFailure> { adminAction(rig.client, a("""{"action":"explode"}""")) }
        assertFailsWith<ToolFailure> { adminAction(rig.client, a("""{"action":"reboot","dest":"BROADCAST"}""")) }
    }

    @Test
    fun sendTextHonorsWantAck() = runTest {
        val rig = connected()
        val call = async { sendText(rig.client, a("""{"text":"hi","dest":"!00000033","await_ms":5000}""")) }
        runCurrent()
        val sent = rig.packets(PortNum.TEXT_MESSAGE_APP).last()
        assertTrue(sent.want_ack)
        assertEquals(REMOTE, sent.to)
        rig.transport.injectRoutingAck(sent.id, fromNode = REMOTE)
        assertEquals("success", call.await().getValue("outcome").jsonPrimitive.content)
        val quiet = async { sendText(rig.client, a("""{"text":"psst","want_ack":false,"await_ms":1000}""")) }
        runCurrent()
        assertFalse(rig.packets(PortNum.TEXT_MESSAGE_APP).last().want_ack)
        quiet.await()
    }

    @Test
    fun sendPositionAndWaypointDelete() = runTest {
        val rig = connected()
        val pos = async { sendPositionTool(rig.client, a("""{"latitude":10.0,"longitude":20.0,"await_ms":1000}""")) }
        runCurrent()
        val packet = rig.packets(PortNum.POSITION_APP).last()
        assertEquals(100_000_000, Position.ADAPTER.decode(packet.decoded!!.payload).latitude_i)
        pos.await()
        val del = async { sendWaypointTool(rig.client, a("""{"id":77,"delete":true,"await_ms":1000}""")) }
        runCurrent()
        val wp = Waypoint.ADAPTER.decode(rig.packets(PortNum.WAYPOINT_APP).last().decoded!!.payload)
        assertEquals(77, wp.id)
        assertEquals(1, wp.expire)
        assertEquals("true", del.await().getValue("deleted").jsonPrimitive.content)
        assertFailsWith<ToolFailure> { sendWaypointTool(rig.client, a("""{"delete":true}""")) }
    }

    @Test
    fun tracerouteReturnsTheRouteAsNodeIds() = runTest {
        val rig = connected()
        val call = async { request(rig.client, a("""{"kind":"traceroute","dest":"!00000033","timeout_ms":20000}""")) }
        runCurrent()
        val sent = rig.packets(PortNum.TRACEROUTE_APP).last()
        assertEquals(REMOTE, sent.to)
        val reply = RouteDiscovery.Builder().also {
            it.route = listOf(0x22)
            it.snr_towards = listOf(8, 4)
        }.build()
        rig.transport.injectRouteReply(sent.id, reply, fromNode = REMOTE)
        val out = call.await()
        assertEquals(
            listOf("!00000001", "!00000022", "!00000033"),
            out.getValue("route").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun localStatsTelemetryRequest() = runTest {
        val rig = connected()
        val call = async { request(rig.client, a("""{"kind":"local_stats"}""")) }
        runCurrent()
        val sent = rig.packets(PortNum.TELEMETRY_APP).last()
        val telemetry = Telemetry.Builder().also {
            it.local_stats =
                LocalStats.Builder().also { s -> s.num_packets_tx = 12 }.build()
        }.build()
        rig.transport.injectTelemetryResponse(sent.id, telemetry)
        assertEquals(12, call.await().obj("result").getValue("num_packets_tx").jsonPrimitive.int)
    }

    @Test
    fun positionRequestReportsTheReplyOrATimeout() = runTest {
        val rig = connected()
        val call = async { request(rig.client, a("""{"kind":"position","dest":"!00000033","timeout_ms":5000}""")) }
        runCurrent()
        val sent = rig.packets(PortNum.POSITION_APP).last()
        assertTrue(sent.decoded!!.want_response)
        val payload = Position.ADAPTER.encode(
            Position.Builder().also {
                it.latitude_i = 1
                it.longitude_i = 2
            }.build(),
        )
        rig.transport.injectPacket(
            MeshPacket.Builder().also { p ->
                p.from = REMOTE
                p.to = OWN
                p.decoded = Data.Builder().also { d ->
                    d.portnum = PortNum.POSITION_APP
                    d.payload = payload.toByteString()
                    d.request_id = sent.id
                }.build()
            }.build(),
        )
        val out = call.await()
        assertEquals("reply", out.getValue("outcome").jsonPrimitive.content)
        assertEquals(1, out.obj("result").getValue("latitude_i").jsonPrimitive.int)
        val silent = request(rig.client, a("""{"kind":"node_info","dest":"!00000044","timeout_ms":1000}"""))
        assertEquals("timeout", silent.getValue("outcome").jsonPrimitive.content)
    }

    @Test
    fun requestRejectsBadKinds() = runTest {
        val rig = connected()
        assertFailsWith<ToolFailure> { request(rig.client, a("""{"kind":"nope"}""")) }
        assertFailsWith<ToolFailure> { request(rig.client, a("""{"kind":"traceroute"}""")) }
        assertFailsWith<ToolFailure> { request(rig.client, a("""{"kind":"telemetry","variant":"x"}""")) }
    }
}
