/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.MessageHandle
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.RouteDiscoveryResult
import org.meshtastic.sdk.channelFor
import org.meshtastic.sdk.requestPosition
import kotlin.time.Duration.Companion.milliseconds

private const val NANOS_PER_MS = 1_000_000L
private const val SNR_SCALE = 4f

private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / NANOS_PER_MS

/** Sends a request via [send] and waits for the reply packet that answers it on [port]. */
private suspend fun requestWithReply(
    client: RadioClient,
    port: PortNum,
    timeoutMs: Long,
    send: suspend () -> MessageHandle,
): Pair<MessageHandle, MeshPacket?> = coroutineScope {
    // Subscribe before sending so a fast reply cannot slip past.
    val inbox = Channel<MeshPacket>(Channel.UNLIMITED)
    val collector = launch(start = CoroutineStart.UNDISPATCHED) { client.packets.collect { inbox.trySend(it) } }
    try {
        val handle = try {
            send()
        } catch (e: MeshtasticException) {
            toolError("request: ${e::class.simpleName}: ${e.message}", e)
        }
        val reply = withTimeoutOrNull(timeoutMs) {
            var found: MeshPacket? = null
            while (found == null) {
                val packet = inbox.receive()
                val data = packet.decoded
                if (data != null && data.portnum == port && data.request_id == handle.id.raw) found = packet
            }
            found
        }
        handle to reply
    } finally {
        collector.cancel()
    }
}

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

        "telemetry" -> telemetryRequest(client, args.requireString("variant"), dest, timeoutMs)

        "local_stats" -> telemetryRequest(client, "local_stats", dest, timeoutMs)

        "neighbor_info" -> {
            val info = client.routing.withRpcTimeout(timeoutMs.milliseconds)
                .requestNeighborInfo(dest ?: NodeId.LOCAL, channel).orFail("neighbor_info")
            buildJsonObject { put("result", ProtoPaths.toJson(info)) }
        }

        "position" -> replyRequest(client, PortNum.POSITION_APP, timeoutMs, dest, channel) { node, ch ->
            client.requestPosition(node, ch)
        }

        "node_info" -> replyRequest(client, PortNum.NODEINFO_APP, timeoutMs, dest, channel) { node, ch ->
            client.requestNodeInfo(node, ch)
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

private suspend fun telemetryRequest(client: RadioClient, variant: String, dest: NodeId?, timeoutMs: Long): JsonObject {
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

private suspend fun replyRequest(
    client: RadioClient,
    port: PortNum,
    timeoutMs: Long,
    dest: NodeId?,
    channel: ChannelIndex?,
    send: (NodeId, ChannelIndex) -> MessageHandle,
): JsonObject {
    val node = dest ?: toolError("dest is required")
    val (handle, reply) = requestWithReply(client, port, timeoutMs) {
        send(node, channel ?: client.channelFor(node))
    }
    return buildJsonObject {
        put("message_id", handle.id.toString())
        if (reply == null) {
            put("outcome", "timeout")
        } else {
            put("outcome", "reply")
            put("from", NodeId(reply.from).toString())
            val payload = reply.decoded?.payload ?: okio.ByteString.EMPTY
            when (port) {
                PortNum.POSITION_APP -> put("result", ProtoPaths.toJson(Position.ADAPTER.decode(payload)))
                else -> put("result", ProtoPaths.toJson(User.ADAPTER.decode(payload)))
            }
        }
    }
}

private const val DEFAULT_REQUEST_TIMEOUT_MS = 60_000L
