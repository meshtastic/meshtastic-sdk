/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import com.squareup.wire.Message
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.meshtastic.proto.AdminMessage.ConfigType
import org.meshtastic.proto.AdminMessage.ModuleConfigType
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.sdk.AdminApi
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.exportDeviceProfile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

private fun sectionJson(s: Section, message: Message<*, *>, includeKey: Boolean): JsonObject {
    val json = ProtoPaths.toJson(message)
    if (s.name != "security" || includeKey) return json
    return JsonObject(json.filterKeys { it != "private_key" })
}

private fun requestedSections(args: Args): List<Section> {
    val wantsExtras = listOf("channels", "owner", "metadata").any { args.bool(it) == true }
    val requested = args.strings("sections")
    return when {
        requested == null -> if (wantsExtras) emptyList() else Sections.all
        "all" in requested -> Sections.all
        else -> requested.map { Sections.byName(it) ?: toolError("unknown section '$it'") }
    }
}

internal suspend fun configGet(client: RadioClient, args: Args): JsonObject {
    val t = target(client, args)
    val live = args.bool("live") ?: false
    val sections = requestedSections(args)
    val explicit = args.strings("sections")?.contains("all") == false
    val keepKey = args.bool("include_private_key") == true
    val errors = mutableMapOf<String, String>()
    val read = linkedMapOf<String, JsonObject>()
    for (s in sections) {
        try {
            val message = readSection(client, t, s, live)
            if (message == null) errors[s.name] = "absent" else read[s.name] = sectionJson(s, message, keepKey)
        } catch (e: ToolFailure) {
            if (explicit) throw e
            errors[s.name] = e.message ?: "failed"
        }
    }
    return buildJsonObject {
        t.dest?.let { put("dest", it.toString()) }
        if (sections.isNotEmpty()) putJsonObject("config") { read.forEach { (k, v) -> put(k, v) } }
        extras(client, t, args, live)
        if (errors.isNotEmpty()) putJsonObject("errors") { errors.forEach { (k, v) -> put(k, v) } }
    }
}

private suspend fun kotlinx.serialization.json.JsonObjectBuilder.extras(
    client: RadioClient,
    t: Target,
    args: Args,
    live: Boolean,
) {
    val cached = t.dest == null && !live
    if (args.bool("channels") == true) {
        val list = (if (cached) client.channels.value else null) ?: t.api.listChannels().orFail("get channels")
        put("channels", JsonArray(list.map { ProtoPaths.toJson(it) }))
    }
    if (args.bool("owner") == true) {
        val owner = (if (cached) client.ownNode.value?.user else null) ?: t.api.getOwner().orFail("get owner")
        put("owner", ProtoPaths.toJson(owner))
    }
    if (args.bool("metadata") == true) {
        val meta = (if (cached) client.configBundle.value?.metadata else null)
            ?: t.api.getDeviceMetadata().orFail("get metadata")
        put("metadata", ProtoPaths.toJson(meta))
    }
}

private fun groupBySection(flat: Map<String, JsonElement>): Map<Section, Map<String, JsonElement>> {
    val bySection = linkedMapOf<Section, MutableMap<String, JsonElement>>()
    for ((path, value) in flat) {
        val clean = path.removePrefix("config.").removePrefix("module.")
        val name = clean.substringBefore('.')
        val section = Sections.byName(name) ?: toolError("unknown section '$name' in '$path'")
        val rest = clean.substringAfter('.', "")
        if (rest.isEmpty()) toolError("'$path' names a whole section; give a field such as $name.<field>")
        bySection.getOrPut(section) { mutableMapOf() }[rest] = value
    }
    return bySection
}

internal suspend fun configSet(client: RadioClient, args: Args): JsonObject = serializedWrite {
    configSetLocked(client, args)
}

private suspend fun configSetLocked(client: RadioClient, args: Args): JsonObject {
    val values = args.obj("values") ?: toolError("values is required")
    val flat = ProtoPaths.flatten(values)
    if (flat.isEmpty()) toolError("values is empty")
    val t = target(client, args)
    val bySection = groupBySection(flat)
    val merged = linkedMapOf<Section, Message<*, *>>()
    for ((section, edits) in bySection) {
        val base = readSection(client, t, section, live = false)
            ?: toolError("device reports no '${section.name}' config")
        val next = ProtoPaths.mergeAny(base, edits)
        if (next != base) merged[section] = next
    }
    if (merged.isEmpty()) {
        return buildJsonObject {
            put("applied", JsonArray(emptyList()))
            put("unchanged", JsonArray(bySection.keys.map { JsonPrimitive(it.name) }))
        }
    }
    return commit(t, merged)
}

/** Sets [message] as the oneof member [section] on [builder] without a JSON round trip, which would drop unknown fields. */
private fun <B> wrapSection(builder: B, section: Section, message: Message<*, *>): B {
    builder!!.javaClass.getMethod(section.name, message.javaClass).invoke(builder, message)
    return builder
}

private suspend fun commit(t: Target, merged: Map<Section, Message<*, *>>): JsonObject {
    val configs = mutableListOf<Config>()
    val modules = mutableListOf<ModuleConfig>()
    for ((section, message) in merged) {
        if (section.config != null) {
            configs += wrapSection(Config.Builder(), section, message).build()
        } else {
            modules += wrapSection(ModuleConfig.Builder(), section, message).build()
        }
    }
    t.api.editSettings {
        configs.forEach { setConfig(it) }
        modules.forEach { setModuleConfig(it) }
    }.orFail("config_set")
    var verified = true
    val after = linkedMapOf<String, JsonObject>()
    for ((section, sent) in merged) {
        val held = readBack(t.api, section)
        if (held == null) verified = false
        after[section.name] = sectionJson(section, held ?: sent, includeKey = false)
    }
    return buildJsonObject {
        t.dest?.let { put("dest", it.toString()) }
        putJsonArray("applied") { merged.keys.forEach { add(it.name) } }
        put("verified", verified)
        if (!verified) {
            put("note", "read-back failed (the device may be rebooting); config shows what was sent")
        }
        putJsonObject("config") { after.forEach { (k, v) -> put(k, v) } }
    }
}

/** What the radio answers for [s] right now, or null when it does not answer (a commit can reboot it). */
private suspend fun readBack(api: AdminApi, s: Section): Message<*, *>? = try {
    withTimeoutOrNull(READ_BACK_MS) { rpcSection(api, s) }
} catch (e: CancellationException) {
    throw e
} catch (@Suppress("SwallowedException") e: ToolFailure) {
    null
} catch (@Suppress("SwallowedException") e: MeshtasticException) {
    null
}

internal suspend fun profileExport(client: RadioClient, args: Args): JsonObject {
    val profile = client.exportDeviceProfile(
        includePrivateKey = args.bool("include_private_key") ?: false,
        includeDeviceText = args.bool("include_device_text") ?: true,
    ).orFail("profile_export")
    return buildJsonObject { put("profile", ProtoPaths.toJson(profile)) }
}

private const val READ_BACK_MS = 10_000L
