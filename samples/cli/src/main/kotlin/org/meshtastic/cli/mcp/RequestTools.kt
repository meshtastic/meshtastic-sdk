/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import com.squareup.wire.Message
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.RouteDiscoveryResult
import kotlin.time.Duration.Companion.milliseconds

private const val NANOS_PER_MS = 1_000_000L
private const val SNR_SCALE = 4f

private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / NANOS_PER_MS

internal val TELEMETRY_VARIANTS = listOf(
    "device",
    "environment",
    "power",
    "air_quality",
    "local_stats",
    "health",
    "host",
    "traffic_management",
)
internal val REQUEST_KINDS = listOf("traceroute", "telemetry", "position", "neighbor_info", "node_info", "local_stats")

internal suspend fun request(client: RadioClient, args: Args): JsonObject {
    val kind = args.requireString("kind")
    val timeoutMs = args.long("timeout_ms") ?: DEFAULT_REQUEST_TIMEOUT_MS
    val dest = args.dest()
    if (dest == NodeId.BROADCAST) toolError("dest must be one node, not BROADCAST")
    val channel = args.int("channel")?.let { ChannelIndex(it) }
    val started = System.nanoTime()
    val body = when (kind) {
        "traceroute" -> traceroute(
            client,
            args,
            dest ?: toolError("dest is required for traceroute"),
            channel,
            timeoutMs,
        )

        "telemetry" -> telemetryRequest(client, args.requireString("variant"), dest, timeoutMs, channel)

        "local_stats" -> telemetryRequest(client, "local_stats", dest, timeoutMs, channel)

        "neighbor_info" -> {
            val info = client.routing.withRpcTimeout(timeoutMs.milliseconds)
                .requestNeighborInfo(dest ?: NodeId.LOCAL, channel).orFail("neighbor_info")
            buildJsonObject { put("result", ProtoPaths.toJson(info)) }
        }

        "position" -> replyRequest("position", dest) { node ->
            client.routing.withRpcTimeout(timeoutMs.milliseconds).requestPosition(node, channel)
        }

        "node_info" -> replyRequest("node_info", dest) { node ->
            client.routing.withRpcTimeout(timeoutMs.milliseconds).requestNodeInfo(node, channel)
        }

        else -> toolError("unknown kind '$kind' (${REQUEST_KINDS.joinToString(" | ")})")
    }
    return buildJsonObject {
        put("kind", kind)
        dest?.let { put("dest", it.toString()) }
        body.forEach { (k, v) -> put(k, v) }
        put("elapsed_ms", elapsedMs(started))
    }
}

private suspend fun traceroute(
    client: RadioClient,
    args: Args,
    dest: NodeId,
    channel: ChannelIndex?,
    timeoutMs: Long,
): JsonObject {
    val discovery = client.routing.withRpcTimeout(timeoutMs.milliseconds)
        .traceRoute(dest, args.int("hop_limit"), channel).orFail("traceroute")
    val source = NodeId(client.ownNode.value?.num ?: 0)
    val route = RouteDiscoveryResult.fromProto(
        source = source,
        destination = dest,
        intermediateRoute = discovery.route,
        intermediateRouteBack = discovery.route_back,
        snrTowards = discovery.snr_towards.map { it / SNR_SCALE },
        snrBack = discovery.snr_back.map { it / SNR_SCALE },
    )
    return buildJsonObject {
        putJsonArray("route") { route.route.forEach { add(it.toString()) } }
        putJsonArray("route_back") { route.routeBack.forEach { add(it.toString()) } }
        put("snr_towards", JsonArray(route.snrTowards.map { JsonPrimitive(it) }))
        put("snr_back", JsonArray(route.snrBack.map { JsonPrimitive(it) }))
        put("hops_away", route.hopsAway)
        put("result", ProtoPaths.toJson(discovery))
    }
}

private suspend fun telemetryRequest(
    client: RadioClient,
    variant: String,
    dest: NodeId?,
    timeoutMs: Long,
    channel: ChannelIndex?,
): JsonObject {
    if (channel != null) toolError("channel is not supported for telemetry requests")
    val api = client.telemetry.withRpcTimeout(timeoutMs.milliseconds)
    val node = dest ?: NodeId.LOCAL
    val message = when (variant) {
        "device" -> api.requestDevice(node).orFail("telemetry")
        "environment" -> api.requestEnvironment(node).orFail("telemetry")
        "power" -> api.requestPower(node).orFail("telemetry")
        "air_quality" -> api.requestAirQuality(node).orFail("telemetry")
        "local_stats" -> api.requestLocalStats(node).orFail("telemetry")
        "health" -> api.requestHealth(node).orFail("telemetry")
        "host" -> api.requestHost(node).orFail("telemetry")
        "traffic_management" -> api.requestTrafficManagement(node).orFail("telemetry")
        else -> toolError("unknown variant '$variant' (${TELEMETRY_VARIANTS.joinToString(" | ")})")
    }
    return buildJsonObject {
        put("variant", variant)
        put("result", ProtoPaths.toJson(message))
    }
}

/** Runs a position or node-info [call]; a silent node is `outcome: timeout`, any other failure a tool error. */
private suspend fun <T : Message<*, *>> replyRequest(
    what: String,
    dest: NodeId?,
    call: suspend (NodeId) -> AdminResult<T>,
): JsonObject {
    val node = dest ?: toolError("dest is required")
    val result = try {
        call(node)
    } catch (e: IllegalArgumentException) {
        toolError("$what: ${e.message}", e)
    }
    return buildJsonObject {
        if (result == AdminResult.Timeout) {
            put("outcome", "timeout")
        } else {
            put("outcome", "reply")
            put("from", node.toString())
            put("result", ProtoPaths.toJson(result.orFail(what)))
        }
    }
}

private const val DEFAULT_REQUEST_TIMEOUT_MS = 60_000L
