/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import org.meshtastic.sdk.NodeField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeDiffTest {

    private val baseNode = NodeInfo(
        num = 1,
        user = User(id = "!aabbccdd", long_name = "Alpha", short_name = "AL"),
        position = Position(latitude_i = 370000000, longitude_i = -1220000000),
        snr = 10.5f,
        last_heard = 1000,
        device_metrics = DeviceMetrics(battery_level = 80, voltage = 3.9f),
        channel = 0,
        via_mqtt = false,
        hops_away = 0,
        is_favorite = false,
        is_ignored = false,
        is_muted = false,
        is_key_manually_verified = false,
    )

    @Test
    fun identicalNodes_returnsEmptySet() {
        val result = diffNodeFields(baseNode, baseNode.copy())
        assertTrue(result.isEmpty(), "Expected empty set for identical nodes, got: $result")
    }

    @Test
    fun userNameChange_flagsNameAndUser() {
        val updated = baseNode.copy(user = baseNode.user!!.copy(long_name = "Beta"))
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Name in result)
        assertTrue(NodeField.User in result)
    }

    @Test
    fun userShortNameChange_flagsNameAndUser() {
        val updated = baseNode.copy(user = baseNode.user!!.copy(short_name = "BT"))
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Name in result)
        assertTrue(NodeField.User in result)
    }

    @Test
    fun userOtherFieldChange_flagsUserOnly() {
        val updated = baseNode.copy(user = baseNode.user!!.copy(id = "!11223344"))
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.User in result)
        assertTrue(NodeField.Name !in result, "Name should not be flagged for non-name user changes")
    }

    @Test
    fun positionChange_flagsPosition() {
        val updated = baseNode.copy(position = Position(latitude_i = 380000000, longitude_i = -1220000000))
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Position in result)
    }

    @Test
    fun snrChange_flagsSignalQuality() {
        val updated = baseNode.copy(snr = 5.0f)
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.SignalQuality in result)
    }

    @Test
    fun hopsAwayChange_flagsSignalQuality() {
        val updated = baseNode.copy(hops_away = 2)
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.SignalQuality in result)
    }

    @Test
    fun viaMqttChange_flagsSignalQuality() {
        val updated = baseNode.copy(via_mqtt = true)
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.SignalQuality in result)
    }

    @Test
    fun batteryChange_flagsBatteryAndTelemetry() {
        val updated = baseNode.copy(
            device_metrics = DeviceMetrics(battery_level = 50, voltage = 3.5f),
        )
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Battery in result)
        assertTrue(NodeField.Telemetry in result)
    }

    @Test
    fun deviceMetricsNonBatteryChange_flagsTelemetryOnly() {
        val updated = baseNode.copy(
            device_metrics = baseNode.device_metrics!!.copy(channel_utilization = 25.0f),
        )
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Telemetry in result)
        assertTrue(NodeField.Battery !in result)
    }

    @Test
    fun lastHeardChange_flagsLastSeen() {
        val updated = baseNode.copy(last_heard = 2000)
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.LastSeen in result)
    }

    @Test
    fun favoriteChange_flagsOther() {
        val updated = baseNode.copy(is_favorite = true)
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Other in result)
    }

    @Test
    fun channelChange_flagsOther() {
        val updated = baseNode.copy(channel = 3)
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Other in result)
    }

    @Test
    fun multipleFieldChanges_flagsAll() {
        val updated = baseNode.copy(
            snr = 2.0f,
            last_heard = 5000,
            position = Position(latitude_i = 390000000, longitude_i = -1210000000),
        )
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.SignalQuality in result)
        assertTrue(NodeField.LastSeen in result)
        assertTrue(NodeField.Position in result)
    }
}
