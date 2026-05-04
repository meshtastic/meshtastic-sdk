/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.Position
import org.meshtastic.proto.User

/**
 * High-level, consumer-friendly representation of a mesh node.
 *
 * Combines the raw [NodeInfo] proto with computed status helpers ([isOnline], [connectionQuality],
 * [signalQuality]) and provides convenient accessor properties that flatten the proto's nested
 * structure for direct UI binding.
 *
 * Instances are created via [NodeInfo.toMeshNode] at a specific point in time ([nowEpochSeconds])
 * so that online status is pre-computed and stable for the lifetime of the snapshot.
 *
 * @property raw the underlying [NodeInfo] proto — available for advanced consumers that need
 *   full access to all proto fields
 * @since 0.1.0
 */
public data class MeshNode(
    val raw: NodeInfo,
    val isOnline: Boolean,
    val connectionQuality: ConnectionQuality,
    val signalQuality: SignalQuality,
) {
    // ── Identity ──────────────────────────────────────────────────────────

    /** Node number (unique on the mesh). */
    val nodeNum: Int get() = raw.num

    /** Stable node ID as [NodeId]. */
    val nodeId: NodeId get() = NodeId(raw.num)

    /** User-facing long name (e.g., "Alice's T-Beam"). */
    val longName: String? get() = raw.user?.long_name

    /** User-facing short name (4 chars, e.g., "ALCE"). */
    val shortName: String? get() = raw.user?.short_name

    /** Meshtastic ID string (e.g., "!aabbccdd"). */
    val meshId: String? get() = raw.user?.id

    /** Hardware model reported by the node. */
    val hwModel: HardwareModel? get() = raw.user?.hw_model

    /** Full user proto (for advanced consumers). */
    val user: User? get() = raw.user

    // ── Connectivity ──────────────────────────────────────────────────────

    /** Number of relay hops to reach this node. 0 = direct. */
    val hopsAway: Int? get() = raw.hops_away

    /** Whether the node was reached via MQTT gateway. */
    val viaMqtt: Boolean get() = raw.via_mqtt

    /** SNR of last received packet from this node. */
    val snr: Float get() = raw.snr

    /** Last heard time (Unix epoch seconds). 0 if never heard. */
    val lastHeard: Int get() = raw.last_heard

    // ── Position ──────────────────────────────────────────────────────────

    /** Position data (latitude, longitude, altitude, etc.). Null if not reported. */
    val position: Position? get() = raw.position

    /** Latitude in degrees (null if no position or position is origin). */
    val latitude: Double?
        get() = raw.position?.let { pos ->
            val lat = pos.latitude_i ?: 0
            if (lat != 0) lat / 1e7 else null
        }

    /** Longitude in degrees (null if no position or position is origin). */
    val longitude: Double?
        get() = raw.position?.let { pos ->
            val lng = pos.longitude_i ?: 0
            if (lng != 0) lng / 1e7 else null
        }

    /** Altitude in meters (null if not reported). */
    val altitude: Int? get() = raw.position?.altitude

    // ── Device Metrics (telemetry) ────────────────────────────────────────

    /** Device metrics snapshot (battery, air utilization, etc.). */
    val deviceMetrics: DeviceMetrics? get() = raw.device_metrics

    /** Battery level percentage (0–100). Null if not reported. */
    val batteryLevel: Int? get() = raw.device_metrics?.battery_level

    /** Battery voltage. Null if not reported. */
    val voltage: Float? get() = raw.device_metrics?.voltage

    /** Channel utilization percentage. Null if not reported. */
    val channelUtilization: Float? get() = raw.device_metrics?.channel_utilization

    /** Airtime utilization (TX) percentage. Null if not reported. */
    val airUtilTx: Float? get() = raw.device_metrics?.air_util_tx

    // ── Flags ─────────────────────────────────────────────────────────────

    /** Whether this node is marked as a favorite. */
    val isFavorite: Boolean get() = raw.is_favorite

    /** Whether this node is ignored (hidden from UI). */
    val isIgnored: Boolean get() = raw.is_ignored

    /** Whether this node is muted (no notifications). */
    val isMuted: Boolean get() = raw.is_muted
}

/**
 * Creates a [MeshNode] snapshot from this [NodeInfo] at the given time.
 *
 * @param nowEpochSeconds current Unix epoch seconds — used to determine [MeshNode.isOnline]
 * @return a [MeshNode] with pre-computed status fields
 * @since 0.1.0
 */
public fun NodeInfo.toMeshNode(nowEpochSeconds: Int): MeshNode = MeshNode(
    raw = this,
    isOnline = isOnline(nowEpochSeconds),
    connectionQuality = connectionQuality,
    signalQuality = signalQuality,
)

/**
 * Converts a collection of [NodeInfo] entries to [MeshNode] snapshots.
 *
 * @param nowEpochSeconds current Unix epoch seconds
 * @return list of [MeshNode] instances
 * @since 0.1.0
 */
public fun Iterable<NodeInfo>.toMeshNodes(nowEpochSeconds: Int): List<MeshNode> =
    map { it.toMeshNode(nowEpochSeconds) }
