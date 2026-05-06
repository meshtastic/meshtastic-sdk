/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.DeviceConnectionStatus
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NeighborInfo as ProtoNeighborInfo
import org.meshtastic.proto.NodeRemoteHardwarePinsResponse
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.Routing
import org.meshtastic.proto.StoreAndForward
import org.meshtastic.proto.Telemetry
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.LogSink
import org.meshtastic.sdk.debug
import org.meshtastic.sdk.warn

/**
 * Engine-actor-owned registry of in-flight admin/telemetry/routing RPC responses.
 *
 * **Single-writer.** All mutations happen on the engine coroutine — no atomic / mutex needed
 * (ADR-002).
 *
 * The dispatcher parks one [CompletableDeferred] per outbound `request_id` (the wire packet id of
 * the sending packet). Inbound packets carrying that id in `decoded.request_id` are routed here
 * via [tryComplete] before any other handler. The dispatcher decodes the response payload
 * according to the registered [ResponseKind] and resolves the deferred.
 *
 * Routing-level failures (Routing.Error variants on ROUTING_APP) are also routed here via
 * [tryFailFromRouting] so that getter RPCs surface Unauthorized / SessionKeyExpired / etc. without
 * the caller having to subscribe to the engine's MessageHandle path in parallel.
 */
internal class CommandDispatcher(private val logger: LogSink = LogSink.Silent) {

    private val pending = mutableMapOf<Int, Pending>()

    private data class Pending(
        val kind: ResponseKind<*>,
        val deferred: CompletableDeferred<AdminResult<Any?>>,
        var timeoutJob: Job? = null,
    )

    /** Number of in-flight pending responses. Test/diagnostic use only. */
    fun size(): Int = pending.size

    /**
     * Register an outbound request awaiting a typed response.
     *
     * The caller supplies the [deferred] (typically the one carried inside the originating
     * `EngineMessage.PostRpc`). The dispatcher resolves it when the response arrives, the
     * timer fires, or the engine winds down. Use [attachTimeoutJob] after this returns so the
     * timer can be cancelled on early completion.
     */
    fun register(requestId: Int, kind: ResponseKind<*>, deferred: CompletableDeferred<AdminResult<Any?>>) {
        // Cancel any prior pending under the same id (would be a wire-id collision; defensive).
        pending.remove(requestId)?.let {
            it.timeoutJob?.cancel()
            it.deferred.complete(AdminResult.Timeout)
            logger.warn(TAG) { "RPC id=$requestId replaced — prior entry timed out (wire-id collision)" }
        }
        pending[requestId] = Pending(kind, deferred)
        logger.debug(TAG) { "RPC registered id=$requestId kind=$kind" }
    }

    fun attachTimeoutJob(requestId: Int, job: Job) {
        pending[requestId]?.timeoutJob = job
    }

    /**
     * Try to resolve a pending response from an inbound [packet].
     *
     * Returns `true` when the packet was consumed by a pending entry (the caller should still
     * emit the packet to the public `packets` flow — this only correlates the response).
     */
    fun tryComplete(packet: MeshPacket): Boolean {
        val decoded = packet.decoded ?: return false
        val requestId = decoded.request_id
        if (requestId == 0) return false
        val entry = pending[requestId] ?: return false

        val resolved = when (entry.kind) {
            ResponseKind.AdminConfig -> decodeAdmin(decoded.payload) { it.get_config_response }
            ResponseKind.AdminModuleConfig -> decodeAdmin(decoded.payload) { it.get_module_config_response }
            ResponseKind.AdminOwner -> decodeAdmin(decoded.payload) { it.get_owner_response }
            ResponseKind.AdminChannel -> decodeAdmin(decoded.payload) { it.get_channel_response }
            ResponseKind.AdminDeviceMetadata -> decodeAdmin(decoded.payload) { it.get_device_metadata_response }
            ResponseKind.AdminCannedMessages -> decodeAdmin(decoded.payload) { it.get_canned_message_module_messages_response }
            ResponseKind.AdminRingtone -> decodeAdmin(decoded.payload) { it.get_ringtone_response }
            ResponseKind.AdminDeviceConnectionStatus -> decodeAdmin(decoded.payload) { it.get_device_connection_status_response }
            ResponseKind.AdminRemoteHardwarePins -> decodeAdmin(decoded.payload) { it.get_node_remote_hardware_pins_response }
            ResponseKind.AdminDeviceUIConfig -> decodeAdmin(decoded.payload) { it.get_ui_config_response }
            ResponseKind.Telemetry -> decodeTelemetry(decoded.payload, decoded.portnum)
            ResponseKind.RouteDiscoveryReply -> decodeRoute(decoded.payload, decoded.portnum)
            ResponseKind.NeighborInfoReply -> decodeNeighborInfo(decoded.payload, decoded.portnum)
            ResponseKind.StoreForwardReply -> decodeStoreForwardHistory(decoded.payload, decoded.portnum)
            ResponseKind.StoreForwardStatsReply -> decodeStoreForwardStats(decoded.payload, decoded.portnum)
        }

        if (resolved == null) {
            // Wrong portnum / decode failure / response oneof not set. Leave the entry alive; a
            // later packet with the same request_id may be the real answer (e.g. interleaved
            // QueueStatus) and the timeout job is already armed.
            return false
        }

        entry.timeoutJob?.cancel()
        pending.remove(requestId)
        entry.deferred.complete(resolved)
        logger.debug(TAG) { "RPC completed id=$requestId kind=${entry.kind}" }
        return true
    }

    /**
     * Map a Routing.Error received with [requestId] to an [AdminResult] failure and resolve
     * the matching pending entry. Called from the engine's existing routing-ACK pipeline so
     * remote-admin failures (Unauthorized, SessionKeyExpired, NoRoute) surface to getter callers.
     */
    fun tryFailFromRouting(requestId: Int, error: Routing.Error): Boolean {
        if (requestId == 0) return false
        val entry = pending[requestId] ?: return false
        // NONE on ROUTING_APP is "ack" — a setter would resolve via MessageHandle, but a pending
        // dispatcher entry expects a response payload. Treat NONE as no-op here so the dispatcher
        // keeps waiting; the actual response packet (if any) will land via tryComplete.
        if (error == Routing.Error.NONE) return false
        entry.timeoutJob?.cancel()
        pending.remove(requestId)
        entry.deferred.complete(mapRoutingError(error))
        return true
    }

    /** Time out an in-flight request from the engine's actor-scheduled timer. */
    fun timeout(requestId: Int) {
        val entry = pending.remove(requestId) ?: return
        logger.debug(TAG) { "RPC timed out id=$requestId kind=${entry.kind}" }
        entry.deferred.complete(AdminResult.Timeout)
    }

    /** Cancel every pending response (engine teardown). */
    fun cancelAll(reason: AdminResult<Nothing>) {
        if (pending.isNotEmpty()) {
            logger.debug(TAG) { "Cancelling ${pending.size} pending RPCs (reason=$reason)" }
        }
        for ((_, entry) in pending) {
            entry.timeoutJob?.cancel()
            entry.deferred.complete(reason)
        }
        pending.clear()
    }

    private fun <T : Any> decodeAdmin(payload: okio.ByteString, select: (AdminMessage) -> T?): AdminResult<T>? {
        val msg = try {
            AdminMessage.ADAPTER.decode(payload)
        } catch (_: Exception) {
            return null
        }
        val value = select(msg) ?: return null
        return AdminResult.Success(value)
    }

    private fun decodeTelemetry(payload: okio.ByteString, portnum: PortNum?): AdminResult<Telemetry>? {
        if (portnum != PortNum.TELEMETRY_APP) return null
        val telem = try {
            Telemetry.ADAPTER.decode(payload)
        } catch (_: Exception) {
            return null
        }
        return AdminResult.Success(telem)
    }

    private fun decodeRoute(payload: okio.ByteString, portnum: PortNum?): AdminResult<RouteDiscovery>? {
        if (portnum != PortNum.ROUTING_APP) return null
        val routing = try {
            Routing.ADAPTER.decode(payload)
        } catch (_: Exception) {
            return null
        }
        // RouteDiscoveryReply is only resolved by the route_reply oneof arm. route_request /
        // error_reason are handled elsewhere (the latter via tryFailFromRouting).
        val reply = routing.route_reply ?: return null
        return AdminResult.Success(reply)
    }

    private fun decodeNeighborInfo(payload: okio.ByteString, portnum: PortNum?): AdminResult<ProtoNeighborInfo>? {
        if (portnum != PortNum.NEIGHBORINFO_APP) return null
        val info = try {
            ProtoNeighborInfo.ADAPTER.decode(payload)
        } catch (_: Exception) {
            return null
        }
        return AdminResult.Success(info)
    }

    private fun decodeStoreForwardHistory(
        payload: okio.ByteString,
        portnum: PortNum?,
    ): AdminResult<StoreAndForward.History>? {
        val message = decodeStoreForward(payload, portnum) ?: return null
        return when (message.rr) {
            StoreAndForward.RequestResponse.ROUTER_HISTORY -> {
                val history = message.history ?: return null
                AdminResult.Success(history)
            }

            StoreAndForward.RequestResponse.ROUTER_BUSY -> AdminResult.Failed(Routing.Error.NO_RESPONSE)
            StoreAndForward.RequestResponse.ROUTER_ERROR -> AdminResult.Failed(Routing.Error.GOT_NAK)
            else -> null
        }
    }

    private fun decodeStoreForwardStats(
        payload: okio.ByteString,
        portnum: PortNum?,
    ): AdminResult<StoreAndForward.Statistics>? {
        val message = decodeStoreForward(payload, portnum) ?: return null
        return when (message.rr) {
            StoreAndForward.RequestResponse.ROUTER_STATS -> {
                val stats = message.stats ?: return null
                AdminResult.Success(stats)
            }

            StoreAndForward.RequestResponse.ROUTER_BUSY -> AdminResult.Failed(Routing.Error.NO_RESPONSE)
            StoreAndForward.RequestResponse.ROUTER_ERROR -> AdminResult.Failed(Routing.Error.GOT_NAK)
            else -> null
        }
    }

    private fun decodeStoreForward(payload: okio.ByteString, portnum: PortNum?): StoreAndForward? {
        if (portnum != PortNum.STORE_FORWARD_APP) return null
        return try {
            StoreAndForward.ADAPTER.decode(payload)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "CommandDispatcher"

        /**
         * Translate a Routing.Error enum value into the appropriate AdminResult failure.
         *
         * Mirrors the error-taxonomy.md mapping for admin RPCs. Notably:
         *  - `ADMIN_BAD_SESSION_KEY` → [AdminResult.SessionKeyExpired] so the caller can trigger
         *    a single-shot retry after re-seeding via `get_owner_request`.
         *  - `NOT_AUTHORIZED` / `ADMIN_PUBLIC_KEY_UNAUTHORIZED` → [AdminResult.Unauthorized].
         *  - `NO_ROUTE` / `GOT_NAK` / `MAX_RETRANSMIT` → [AdminResult.NodeUnreachable].
         *  - `TIMEOUT` → [AdminResult.Timeout].
         *  - everything else → [AdminResult.Failed] carrying the raw enum.
         */
        fun mapRoutingError(error: Routing.Error): AdminResult<Nothing> = when (error) {
            Routing.Error.ADMIN_BAD_SESSION_KEY -> AdminResult.SessionKeyExpired

            Routing.Error.NOT_AUTHORIZED,
            Routing.Error.ADMIN_PUBLIC_KEY_UNAUTHORIZED,
            -> AdminResult.Unauthorized

            Routing.Error.RATE_LIMIT_EXCEEDED -> AdminResult.RateLimited

            Routing.Error.NO_ROUTE,
            Routing.Error.GOT_NAK,
            Routing.Error.MAX_RETRANSMIT,
            -> AdminResult.NodeUnreachable

            Routing.Error.TIMEOUT -> AdminResult.Timeout

            else -> AdminResult.Failed(error)
        }
    }
}

/**
 * The kind of response a [CommandDispatcher] entry is waiting for. The actor uses this to decode
 * the inbound payload to the right typed result.
 */
internal sealed interface ResponseKind<T> {
    data object AdminConfig : ResponseKind<org.meshtastic.proto.Config>
    data object AdminModuleConfig : ResponseKind<org.meshtastic.proto.ModuleConfig>
    data object AdminOwner : ResponseKind<org.meshtastic.proto.User>
    data object AdminChannel : ResponseKind<org.meshtastic.proto.Channel>
    data object AdminDeviceMetadata : ResponseKind<org.meshtastic.proto.DeviceMetadata>
    data object AdminCannedMessages : ResponseKind<String>
    data object AdminRingtone : ResponseKind<String>
    data object AdminDeviceConnectionStatus : ResponseKind<org.meshtastic.proto.DeviceConnectionStatus>
    data object AdminRemoteHardwarePins : ResponseKind<org.meshtastic.proto.NodeRemoteHardwarePinsResponse>
    data object AdminDeviceUIConfig : ResponseKind<org.meshtastic.proto.DeviceUIConfig>
    data object Telemetry : ResponseKind<org.meshtastic.proto.Telemetry>
    data object RouteDiscoveryReply : ResponseKind<RouteDiscovery>
    data object NeighborInfoReply : ResponseKind<ProtoNeighborInfo>
    data object StoreForwardReply : ResponseKind<StoreAndForward.History>
    data object StoreForwardStatsReply : ResponseKind<StoreAndForward.Statistics>
}
