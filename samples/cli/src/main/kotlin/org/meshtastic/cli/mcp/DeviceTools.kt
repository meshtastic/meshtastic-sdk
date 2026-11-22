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
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import org.meshtastic.sdk.AdminApi
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.enableHamMode
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val POSITION_SCALE = 1e7
private const val READ_BACK_MS = 10_000L

internal suspend fun ownerSet(client: RadioClient, args: Args): JsonObject {
    val t = target(client, args)
    val callSign = args.string("ham_call_sign")
    if (callSign != null) {
        if (t.dest != null) toolError("ham mode is only settable on the connected radio")
        client.enableHamMode(
            callSign = callSign,
            shortName = args.string("short_name"),
            longName = args.string("long_name"),
            txPower = args.int("ham_tx_power"),
            frequency = args.double("ham_frequency")?.toFloat(),
        ).orFail("ham mode")
    } else {
        val keys = listOf("long_name", "short_name", "is_licensed", "is_unmessagable")
        if (keys.none { args.has(it) }) toolError("give at least one of ${keys.joinToString()} or ham_call_sign")
        val current = (if (t.dest == null) client.ownNode.value?.user else null)
            ?: t.api.getOwner().orFail("get owner")
        val user: User = current.newBuilder().also { u ->
            args.string("long_name")?.let { u.long_name = it }
            args.string("short_name")?.let { u.short_name = it }
            args.bool("is_licensed")?.let { u.is_licensed = it }
            args.bool("is_unmessagable")?.let { u.is_unmessagable = it }
        }.build()
        t.api.setOwner(user).orFail("owner_set")
    }
    val held = withTimeoutOrNull(READ_BACK_MS) {
        runCatching { (t.api.getOwner() as? org.meshtastic.sdk.AdminResult.Success)?.value }.getOrNull()
    }
    return buildJsonObject {
        t.dest?.let { put("dest", it.toString()) }
        put("verified", held != null)
        held?.let { put("owner", ProtoPaths.toJson(it)) }
    }
}

internal suspend fun fixedPositionSet(client: RadioClient, args: Args): JsonObject {
    val lat = args.requireDouble("latitude")
    val lon = args.requireDouble("longitude")
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) toolError("latitude/longitude out of range")
    val t = target(client, args)
    val position = Position.Builder().also { p ->
        p.latitude_i = (lat * POSITION_SCALE).toInt()
        p.longitude_i = (lon * POSITION_SCALE).toInt()
        args.int("altitude")?.let { p.altitude = it }
    }.build()
    t.api.setFixedPosition(position).orFail("fixed_position_set")
    return buildJsonObject {
        t.dest?.let { put("dest", it.toString()) }
        put("latitude", lat)
        put("longitude", lon)
        args.int("altitude")?.let { put("altitude", it) }
    }
}

internal suspend fun fixedPositionClear(client: RadioClient, args: Args): JsonObject {
    val t = target(client, args)
    t.api.removeFixedPosition().orFail("fixed_position_clear")
    return buildJsonObject {
        t.dest?.let { put("dest", it.toString()) }
        put("cleared", true)
    }
}

internal suspend fun setTime(client: RadioClient, args: Args): JsonObject {
    val t = target(client, args)
    val seconds = args.long("unix_time")
    if (seconds == null) {
        t.api.setTime().orFail("set_time")
    } else {
        t.api.setTime(Instant.fromEpochSeconds(seconds)).orFail("set_time")
    }
    return buildJsonObject {
        t.dest?.let { put("dest", it.toString()) }
        put("set", true)
    }
}

private fun nodeArg(args: Args, action: String): NodeId =
    args.string("node")?.let { parseNodeId(it) } ?: toolError("node is required for $action")

/** Each action returns true when it restarts the radio or drops the link. */
private val ACTIONS: Map<String, suspend (AdminApi, Args, String) -> Boolean> = mapOf(
    "reboot" to { api, args, name ->
        api.reboot((args.int("after_s") ?: 0).seconds).orFail(name)
        true
    },
    "shutdown" to { api, args, name ->
        api.shutdown((args.int("after_s") ?: 0).seconds).orFail(name)
        true
    },
    "factory_reset" to { api, args, name ->
        val full = when (args.requireString("scope")) {
            "config" -> false
            "full" -> true
            else -> toolError("scope must be config or full")
        }
        api.factoryReset(preserveBleBonds = !full).orFail(name)
        true
    },
    "nodedb_reset" to { api, args, name ->
        api.nodeDbReset(preserveFavorites = args.bool("preserve_favorites") ?: true).orFail(name)
        true
    },
    "favorite" to { api, args, name -> api.setFavorite(nodeArg(args, name), true).orFail(name).let { false } },
    "unfavorite" to { api, args, name -> api.setFavorite(nodeArg(args, name), false).orFail(name).let { false } },
    "ignore" to { api, args, name -> api.setIgnored(nodeArg(args, name), true).orFail(name).let { false } },
    "unignore" to { api, args, name -> api.setIgnored(nodeArg(args, name), false).orFail(name).let { false } },
    "remove_node" to { api, args, name -> api.removeNode(nodeArg(args, name)).orFail(name).let { false } },
)

internal val ADMIN_ACTIONS = ACTIONS.keys.toList()

internal suspend fun adminAction(client: RadioClient, args: Args): JsonObject {
    val action = args.requireString("action")
    val run = ACTIONS[action] ?: toolError("unknown action '$action' (${ADMIN_ACTIONS.joinToString(" | ")})")
    val t = target(client, args)
    val restarts = run(t.api, args, action)
    return buildJsonObject {
        put("action", action)
        t.dest?.let { put("dest", it.toString()) }
        if (restarts && t.dest == null) {
            put("reconnect_required", true)
            put("note", "the radio restarts or drops the link; call connect again before the next tool")
        }
    }
}
