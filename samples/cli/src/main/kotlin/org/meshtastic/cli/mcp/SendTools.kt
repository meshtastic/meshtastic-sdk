/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.meshtastic.proto.Waypoint
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.LatLng
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.MessageHandle
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SendOutcome
import org.meshtastic.sdk.deleteWaypoint
import org.meshtastic.sdk.newWaypointId
import org.meshtastic.sdk.sendPosition
import org.meshtastic.sdk.sendWaypoint

private const val DEFAULT_AWAIT_MS = 30_000L
private const val NANOS_PER_MS = 1_000_000L
private const val POSITION_SCALE = 1e7
private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / NANOS_PER_MS

internal suspend fun nodesList(client: RadioClient, args: Args): JsonObject {
    val all = client.nodeSnapshot().values.sortedByDescending { it.last_heard.toUInt() }
    val rows = args.int("limit")?.let { all.take(it) } ?: all
    return buildJsonObject {
        put("count", all.size)
        put("nodes", buildJsonArray { rows.forEach { add(buildJsonObject { nodeFields(it) }) } })
    }
}

/**
 * Runs [send], then waits up to `await_ms` for the handle's terminal outcome. Without [wantAck] the
 * SDK completes the handle on dispatch, so a success is reported as `dispatched`, not delivery.
 */
private suspend fun sendAndAwait(
    args: Args,
    dest: NodeId,
    channel: Int,
    wantAck: Boolean,
    send: suspend () -> MessageHandle,
): JsonObject {
    val awaitMs = args.long("await_ms") ?: DEFAULT_AWAIT_MS
    val started = System.nanoTime()
    val handle = try {
        send()
    } catch (e: MeshtasticException) {
        toolError("send: ${e::class.simpleName}: ${e.message}", e)
    }
    val states = mutableListOf<String>()
    val outcome = coroutineScope {
        val watcher = launch {
            handle.state.collect { s -> states += "${s::class.simpleName}@${elapsedMs(started)}ms" }
        }
        val result = withTimeoutOrNull(awaitMs) { handle.await() }
        watcher.cancel()
        result
    }
    return buildJsonObject {
        put("message_id", handle.id.toString())
        put("to", if (dest == NodeId.BROADCAST) "BROADCAST" else dest.toString())
        put("channel", channel)
        when (outcome) {
            null -> put("outcome", "timeout")

            is SendOutcome.Success -> put("outcome", if (wantAck) "success" else "dispatched")

            is SendOutcome.Failure -> {
                put("outcome", "failure")
                put("reason", outcome.reason::class.simpleName)
            }
        }
        put("states", buildJsonArray { states.forEach { add(it) } })
        put("elapsed_ms", elapsedMs(started))
    }
}

internal suspend fun sendText(client: RadioClient, args: Args): JsonObject {
    val text = args.requireString("text")
    val dest = args.dest() ?: NodeId.BROADCAST
    val channel = args.int("channel") ?: 0
    val wantAck = args.bool("want_ack") ?: true
    return sendAndAwait(args, dest, channel, wantAck) {
        client.sendText(text = text, to = dest, channel = ChannelIndex(channel), wantAck = wantAck)
    }
}

internal suspend fun sendPositionTool(client: RadioClient, args: Args): JsonObject {
    val latitude = args.requireDouble("latitude").also { checkLatitude(it) }
    val longitude = args.requireDouble("longitude").also { checkLongitude(it) }
    val latLng = LatLng(latitude, longitude, args.int("altitude"))
    val dest = args.dest() ?: NodeId.BROADCAST
    val channel = args.int("channel") ?: 0
    val wantAck = args.bool("want_ack") ?: false
    return sendAndAwait(args, dest, channel, wantAck) {
        client.sendPosition(latLng, dest, ChannelIndex(channel), wantAck = wantAck)
    }
}

private fun checkLatitude(v: Double) {
    if (!v.isFinite() || v !in -MAX_LATITUDE..MAX_LATITUDE) toolError("latitude must be within -90..90")
}

private fun checkLongitude(v: Double) {
    if (!v.isFinite() || v !in -MAX_LONGITUDE..MAX_LONGITUDE) toolError("longitude must be within -180..180")
}

private fun iconOf(raw: String?): Int? = raw?.let { it.toIntOrNull() ?: it.codePointAt(0) }

private fun waypointOf(args: Args, id: Int): Waypoint = Waypoint.Builder().also { w ->
    w.id = id
    args.double("latitude")?.let {
        checkLatitude(it)
        w.latitude_i = (it * POSITION_SCALE).toInt()
    }
    args.double("longitude")?.let {
        checkLongitude(it)
        w.longitude_i = (it * POSITION_SCALE).toInt()
    }
    args.string("name")?.let { w.name = it }
    args.string("description")?.let { w.description = it }
    iconOf(args.string("icon"))?.let { w.icon = it }
    args.int("expire")?.let { w.expire = it }
    args.int("locked_to")?.let { w.locked_to = it }
}.build()

internal suspend fun sendWaypointTool(client: RadioClient, args: Args): JsonObject {
    val delete = args.bool("delete") ?: false
    val dest = args.dest() ?: NodeId.BROADCAST
    val channel = ChannelIndex(args.int("channel") ?: 0)
    val wantAck = args.bool("want_ack") ?: false
    val id = args.int("id") ?: if (delete) toolError("id is required to delete a waypoint") else newWaypointId()
    val waypoint = waypointOf(args, id)
    if (!delete && (waypoint.latitude_i == null || waypoint.longitude_i == null)) {
        toolError("latitude and longitude are required")
    }
    val result = sendAndAwait(args, dest, channel.raw, wantAck) {
        try {
            if (delete) {
                client.deleteWaypoint(waypoint, dest, channel, wantAck)
            } else {
                client.sendWaypoint(waypoint, dest, channel, wantAck)
            }
        } catch (e: IllegalArgumentException) {
            toolError("waypoint: ${e.message}", e)
        }
    }
    return buildJsonObject {
        result.forEach { (k, v) -> put(k, v) }
        put("waypoint_id", id)
        put("deleted", delete)
    }
}
