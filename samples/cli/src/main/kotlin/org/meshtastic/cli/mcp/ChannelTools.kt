/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.ModuleSettings
import org.meshtastic.sdk.AdminApi
import org.meshtastic.sdk.ChannelHelpers
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.RadioClient
import java.security.SecureRandom

private const val PSK_RANDOM_BYTES = 32
private const val READ_BACK_MS = 10_000L

private fun channelIndex(args: Args): Int = args.requireInt("index").also {
    if (it !in 0..ChannelIndex.MAX_CHANNEL_INDEX) toolError("index must be 0..${ChannelIndex.MAX_CHANNEL_INDEX}")
}

private suspend fun currentChannel(client: RadioClient, t: Target, index: Int): Channel? = if (t.dest == null) {
    client.channels.value?.firstOrNull { it.index == index }
} else {
    t.api.getChannel(ChannelIndex(index)).orFail("get channel $index")
}

private suspend fun channelReadBack(api: AdminApi, index: Int): JsonObject? = withTimeoutOrNull(READ_BACK_MS) {
    runCatching { (api.getChannel(ChannelIndex(index)) as? org.meshtastic.sdk.AdminResult.Success)?.value }.getOrNull()
}?.let { ProtoPaths.toJson(it) }

private fun parsePsk(raw: String): ByteArray = when (raw.lowercase()) {
    "none" -> ByteArray(0)

    "default" -> byteArrayOf(1)

    "random" -> ByteArray(PSK_RANDOM_BYTES).also { SecureRandom().nextBytes(it) }

    else -> runCatching { java.util.Base64.getDecoder().decode(raw) }
        .getOrElse { toolError("psk must be base64, none, default or random") }
}

private fun roleOf(args: Args, existing: Channel?, index: Int): Channel.Role {
    val role = args.string("role")?.let { raw ->
        Channel.Role.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: toolError("role must be one of ${Channel.Role.entries.joinToString { it.name }}")
    } ?: when {
        existing != null && existing.role != Channel.Role.DISABLED -> existing.role
        index == 0 -> Channel.Role.PRIMARY
        else -> Channel.Role.SECONDARY
    }
    if (index == 0 && role != Channel.Role.PRIMARY) toolError("channel 0 is the primary channel")
    if (index != 0 && role == Channel.Role.PRIMARY) toolError("only channel 0 can be PRIMARY")
    return role
}

private fun settingsOf(args: Args, old: ChannelSettings, role: Channel.Role): ChannelSettings {
    val psk = args.string("psk")?.let { parsePsk(it) }
    val name = args.string("name") ?: old.name
    ChannelHelpers.validate(name, psk ?: old.psk.toByteArray(), role).let {
        if (!it.isValid) toolError(it.errors.joinToString("; "))
    }
    val module = (old.module_settings ?: ModuleSettings.Builder().build()).newBuilder().also { m ->
        args.int("position_precision")?.let { m.position_precision = it }
        args.bool("muted")?.let { m.is_muted = it }
    }.build()
    return old.newBuilder().also { s ->
        s.name = name
        psk?.let { s.psk = it.toByteString() }
        args.bool("uplink_enabled")?.let { s.uplink_enabled = it }
        args.bool("downlink_enabled")?.let { s.downlink_enabled = it }
        s.module_settings = module
    }.build()
}

internal suspend fun channelSet(client: RadioClient, args: Args): JsonObject {
    val index = channelIndex(args)
    val t = target(client, args)
    val existing = currentChannel(client, t, index)
    val base = existing ?: Channel.Builder().also { it.index = index }.build()
    val role = roleOf(args, existing, index)
    val settings = settingsOf(args, base.settings ?: ChannelSettings.Builder().build(), role)
    val channel = base.newBuilder().also { c ->
        c.index = index
        c.role = role
        c.settings = settings
    }.build()
    t.api.setChannel(channel).orFail("channel_set")
    return channelResult(t, channelReadBack(t.api, index), channel)
}

internal suspend fun channelDelete(client: RadioClient, args: Args): JsonObject {
    val index = channelIndex(args)
    if (index == 0) toolError("channel 0 is the primary channel and cannot be deleted")
    val t = target(client, args)
    val channel = Channel.Builder().also { c ->
        c.index = index
        c.role = Channel.Role.DISABLED
        c.settings = ChannelSettings.Builder().build()
    }.build()
    t.api.setChannel(channel).orFail("channel_delete")
    return channelResult(t, channelReadBack(t.api, index), channel)
}

private fun channelResult(t: Target, held: JsonObject?, sent: Channel): JsonObject = buildJsonObject {
    t.dest?.let { put("dest", it.toString()) }
    put("verified", held != null)
    put("channel", held ?: ProtoPaths.toJson(sent))
}
