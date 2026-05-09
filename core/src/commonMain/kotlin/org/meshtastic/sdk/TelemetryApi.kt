/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.Flow
import org.meshtastic.proto.AirQualityMetrics
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.HealthMetrics
import org.meshtastic.proto.HostMetrics
import org.meshtastic.proto.LocalStats
import org.meshtastic.proto.PowerMetrics
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.TrafficManagementStats

/**
 * Telemetry RPCs and observation.
 *
 * Each `requestX` method sends a unicast `Telemetry` packet on `TELEMETRY_APP` with
 * `want_response = true` to [node] and waits for the device's reply. [observe] returns a cold
 * [Flow] of every [Telemetry] packet observed for a given node — including unsolicited periodic
 * broadcasts.
 *
 * Acquired via [RadioClient.telemetry]. Available only while the client is connected.
 *
 * @since 0.1.0
 */
public interface TelemetryApi {

    /** Request the latest [DeviceMetrics] from [node]. */
    public suspend fun requestDevice(node: NodeId = NodeId.LOCAL): AdminResult<DeviceMetrics>

    /** Request the latest [EnvironmentMetrics] from [node]. */
    public suspend fun requestEnvironment(node: NodeId = NodeId.LOCAL): AdminResult<EnvironmentMetrics>

    /** Request the latest [PowerMetrics] from [node]. */
    public suspend fun requestPower(node: NodeId = NodeId.LOCAL): AdminResult<PowerMetrics>

    /** Request the latest [AirQualityMetrics] from [node]. */
    public suspend fun requestAirQuality(node: NodeId = NodeId.LOCAL): AdminResult<AirQualityMetrics>

    /** Request the local node's [LocalStats] (mesh-wide stats sourced from this device). */
    public suspend fun requestLocalStats(): AdminResult<LocalStats>

    /** Request the latest [HealthMetrics] from [node] (heart rate, SpO2, temperature). */
    public suspend fun requestHealth(node: NodeId = NodeId.LOCAL): AdminResult<HealthMetrics>

    /** Request the latest [HostMetrics] from [node] (CPU, memory, disk usage on Linux hosts). */
    public suspend fun requestHost(node: NodeId = NodeId.LOCAL): AdminResult<HostMetrics>

    /** Request the latest [TrafficManagementStats] from [node] (packet counts, duty cycle). */
    public suspend fun requestTrafficManagement(node: NodeId = NodeId.LOCAL): AdminResult<TrafficManagementStats>

    /**
     * Cold flow of every [Telemetry] packet observed for [node]. The flow never completes
     * organically — collect inside a `launch { … }` and cancel when done.
     */
    public fun observe(node: NodeId): Flow<Telemetry>
}
