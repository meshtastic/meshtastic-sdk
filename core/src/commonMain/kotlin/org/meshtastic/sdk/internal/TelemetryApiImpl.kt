/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AirQualityMetrics
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.LocalStats
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.PowerMetrics
import org.meshtastic.proto.Telemetry
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.TelemetryApi
import kotlin.time.Duration

/**
 * Engine-backed [TelemetryApi].
 *
 * Each `requestX` constructs an empty [Telemetry] packet on `TELEMETRY_APP` with
 * `want_response = true`, addressed to [node]. The dispatcher correlates the device's reply by
 * `request_id`. The result is then narrowed to the requested oneof arm; mismatches resolve as
 * [AdminResult.Failed] with `Routing.Error.NO_RESPONSE` (the device replied but didn't carry the
 * expected variant).
 *
 * [observe] is a thin filter over the engine's `packets` flow.
 */
internal class TelemetryApiImpl(
    private val engine: MeshEngine,
    private val packetsFlow: SharedFlow<MeshPacket>,
    private val rpcTimeout: Duration,
) : TelemetryApi {

    override suspend fun requestDevice(node: NodeId): AdminResult<DeviceMetrics> =
        requestTelemetry(node) { it.device_metrics }

    override suspend fun requestEnvironment(node: NodeId): AdminResult<EnvironmentMetrics> =
        requestTelemetry(node) { it.environment_metrics }

    override suspend fun requestPower(node: NodeId): AdminResult<PowerMetrics> =
        requestTelemetry(node) { it.power_metrics }

    override suspend fun requestAirQuality(node: NodeId): AdminResult<AirQualityMetrics> =
        requestTelemetry(node) { it.air_quality_metrics }

    override suspend fun requestLocalStats(): AdminResult<LocalStats> =
        requestTelemetry(NodeId.LOCAL) { it.local_stats }

    override fun observe(node: NodeId): Flow<Telemetry> = packetsFlow
        .filter { packet ->
            val decoded = packet.decoded ?: return@filter false
            if (decoded.portnum != PortNum.TELEMETRY_APP) return@filter false
            node == NodeId.LOCAL || packet.from == node.raw
        }
        .mapNotNull { packet ->
            val payload = packet.decoded?.payload ?: return@mapNotNull null
            try {
                Telemetry.ADAPTER.decode(payload)
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun <T : Any> requestTelemetry(node: NodeId, select: (Telemetry) -> T?): AdminResult<T> {
        val target = if (node == NodeId.LOCAL) {
            NodeId(engine.myNodeNumOrNull() ?: return AdminResult.NodeUnreachable)
        } else {
            node
        }
        val requestId = engine.nextMessageId().raw
        val payload = Telemetry.ADAPTER.encode(Telemetry()).toByteString()
        val packet = MeshPacket(
            id = requestId,
            from = engine.myNodeNumOrNull() ?: 0,
            to = target.raw,
            decoded = Data(
                portnum = PortNum.TELEMETRY_APP,
                payload = payload,
                want_response = true,
            ),
        )
        val result = engine.submitRpc(packet, requestId, ResponseKind.Telemetry, rpcTimeout)
        return when (result) {
            is AdminResult.Success -> {
                val arm = select(result.value)
                if (arm != null) {
                    AdminResult.Success(arm)
                } else {
                    AdminResult.Failed(org.meshtastic.proto.Routing.Error.NO_RESPONSE)
                }
            }

            AdminResult.Timeout -> AdminResult.Timeout

            AdminResult.NodeUnreachable -> AdminResult.NodeUnreachable

            AdminResult.SessionKeyExpired -> AdminResult.SessionKeyExpired

            AdminResult.Unauthorized -> AdminResult.Unauthorized

            is AdminResult.Failed -> result
        }
    }
}
