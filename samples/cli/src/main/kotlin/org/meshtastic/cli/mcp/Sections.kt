/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import com.squareup.wire.Message
import org.meshtastic.proto.AdminMessage.ConfigType
import org.meshtastic.proto.AdminMessage.ModuleConfigType
import org.meshtastic.sdk.AdminApi
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import kotlin.time.Duration.Companion.milliseconds

/** One config or module-config section: the proto field name and the admin type that reads it. */
internal class Section(val name: String, val config: ConfigType? = null, val module: ModuleConfigType? = null)

internal object Sections {
    val all = listOf(
        Section("device", config = ConfigType.DEVICE_CONFIG),
        Section("position", config = ConfigType.POSITION_CONFIG),
        Section("power", config = ConfigType.POWER_CONFIG),
        Section("network", config = ConfigType.NETWORK_CONFIG),
        Section("display", config = ConfigType.DISPLAY_CONFIG),
        Section("lora", config = ConfigType.LORA_CONFIG),
        Section("bluetooth", config = ConfigType.BLUETOOTH_CONFIG),
        Section("security", config = ConfigType.SECURITY_CONFIG),
        Section("mqtt", module = ModuleConfigType.MQTT_CONFIG),
        Section("serial", module = ModuleConfigType.SERIAL_CONFIG),
        Section("external_notification", module = ModuleConfigType.EXTNOTIF_CONFIG),
        Section("store_forward", module = ModuleConfigType.STOREFORWARD_CONFIG),
        Section("range_test", module = ModuleConfigType.RANGETEST_CONFIG),
        Section("telemetry", module = ModuleConfigType.TELEMETRY_CONFIG),
        Section("canned_message", module = ModuleConfigType.CANNEDMSG_CONFIG),
        Section("audio", module = ModuleConfigType.AUDIO_CONFIG),
        Section("remote_hardware", module = ModuleConfigType.REMOTEHARDWARE_CONFIG),
        Section("neighbor_info", module = ModuleConfigType.NEIGHBORINFO_CONFIG),
        Section("ambient_lighting", module = ModuleConfigType.AMBIENTLIGHTING_CONFIG),
        Section("detection_sensor", module = ModuleConfigType.DETECTIONSENSOR_CONFIG),
        Section("paxcounter", module = ModuleConfigType.PAXCOUNTER_CONFIG),
        Section("statusmessage", module = ModuleConfigType.STATUSMESSAGE_CONFIG),
        Section("traffic_management", module = ModuleConfigType.TRAFFICMANAGEMENT_CONFIG),
        Section("tak", module = ModuleConfigType.TAK_CONFIG),
        Section("mesh_beacon", module = ModuleConfigType.MESHBEACON_CONFIG),
    )

    fun byName(raw: String): Section? {
        val name = raw.removePrefix("config.").removePrefix("module.")
        return all.firstOrNull { it.name == name }
    }
}

/** The admin surface a call targets: the local radio, or `dest` over remote admin. */
internal class Target(val api: AdminApi, val dest: NodeId?)

internal fun target(client: RadioClient, args: Args): Target {
    val dest = args.dest()
    if (dest == NodeId.BROADCAST) toolError("dest must be one node, not BROADCAST")
    val remote = dest?.takeIf { it.raw != client.ownNode.value?.num }
    var api = if (remote != null) client.admin.forNode(remote) else client.admin
    args.long("timeout_ms")?.let { api = api.withRpcTimeout(it.milliseconds) }
    return Target(api, remote)
}

internal fun sectionOf(message: Message<*, *>, name: String): Message<*, *>? =
    message.javaClass.getField(name).get(message) as? Message<*, *>

internal suspend fun rpcSection(api: AdminApi, s: Section): Message<*, *>? = when {
    s.config != null -> sectionOf(api.getConfig(s.config!!).orFail("get ${s.name}"), s.name)
    else -> sectionOf(api.getModuleConfig(s.module!!).orFail("get ${s.name}"), s.name)
}

internal fun mirrorSection(client: RadioClient, s: Section): Message<*, *>? {
    val bundle = client.configBundle.value ?: toolError("no config bundle yet; reconnect")
    return if (s.config != null) {
        bundle.configs.firstNotNullOfOrNull { sectionOf(it, s.name) }
    } else {
        bundle.moduleConfigs.firstNotNullOfOrNull { sectionOf(it, s.name) }
    }
}

internal suspend fun readSection(client: RadioClient, t: Target, s: Section, live: Boolean) =
    if (t.dest == null && !live) mirrorSection(client, s) else rpcSection(t.api, s)
