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

    private val baseNode = NodeInfo.Builder().also { wb ->
    wb.num = 1
    wb.user = User.Builder().also { wb ->wb.id = "!aabbccdd"; wb.long_name = "Alpha"; wb.short_name = "AL"}.build()
    wb.position = Position.Builder().also { wb ->wb.latitude_i = 370000000; wb.longitude_i = -1220000000}.build()
    wb.snr = 10.5f
    wb.last_heard = 1000
    wb.device_metrics = DeviceMetrics.Builder().also { wb ->wb.battery_level = 80; wb.voltage = 3.9f}.build()
    wb.channel = 0
    wb.via_mqtt = false
    wb.hops_away = 0
    wb.is_favorite = false
    wb.is_ignored = false
    wb.is_muted = false
    wb.is_key_manually_verified = false
    }.build()

    @Test
    fun identicalNodes_returnsEmptySet() {
        val result = diffNodeFields(baseNode, baseNode.newBuilder().build())
        assertTrue(result.isEmpty(), "Expected empty set for identical nodes, got: $result")
    }

    @Test
    fun userNameChange_flagsNameAndUser() {
        val updated = baseNode.newBuilder().also { wb ->
            wb.user = baseNode.user!!.newBuilder().also { wb -> wb.long_name = "Beta" }.build()
        }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Name in result)
        assertTrue(NodeField.User in result)
    }

    @Test
    fun userShortNameChange_flagsNameAndUser() {
        val updated = baseNode.newBuilder().also { wb ->
            wb.user = baseNode.user!!.newBuilder().also { wb -> wb.short_name = "BT" }.build()
        }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Name in result)
        assertTrue(NodeField.User in result)
    }

    @Test
    fun userOtherFieldChange_flagsUserOnly() {
        val updated = baseNode.newBuilder().also { wb ->
            wb.user = baseNode.user!!.newBuilder().also { wb -> wb.id = "!11223344" }.build()
        }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.User in result)
        assertTrue(NodeField.Name !in result, "Name should not be flagged for non-name user changes")
    }

    @Test
    fun positionChange_flagsPosition() {
        val updated = baseNode.newBuilder().also { wb ->
            wb.position = Position.Builder().also { wb ->wb.latitude_i = 380000000; wb.longitude_i = -1220000000}.build()
        }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Position in result)
    }

    @Test
    fun snrChange_flagsSignalQuality() {
        val updated = baseNode.newBuilder().also { wb -> wb.snr = 5.0f }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.SignalQuality in result)
    }

    @Test
    fun hopsAwayChange_flagsSignalQuality() {
        val updated = baseNode.newBuilder().also { wb -> wb.hops_away = 2 }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.SignalQuality in result)
    }

    @Test
    fun viaMqttChange_flagsSignalQuality() {
        val updated = baseNode.newBuilder().also { wb -> wb.via_mqtt = true }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.SignalQuality in result)
    }

    @Test
    fun batteryChange_flagsBatteryAndTelemetry() {
        val updated = baseNode.newBuilder().also { wb ->
            wb.device_metrics = DeviceMetrics.Builder().also { wb ->wb.battery_level = 50; wb.voltage = 3.5f}.build()
        }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Battery in result)
        assertTrue(NodeField.Telemetry in result)
    }

    @Test
    fun deviceMetricsNonBatteryChange_flagsTelemetryOnly() {
        val updated = baseNode.newBuilder().also { wb ->
            wb.device_metrics = baseNode.device_metrics!!.newBuilder().also { wb -> wb.channel_utilization = 25.0f }.build()
        }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Telemetry in result)
        assertTrue(NodeField.Battery !in result)
    }

    @Test
    fun lastHeardChange_flagsLastSeen() {
        val updated = baseNode.newBuilder().also { wb -> wb.last_heard = 2000 }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.LastSeen in result)
    }

    @Test
    fun favoriteChange_flagsOther() {
        val updated = baseNode.newBuilder().also { wb -> wb.is_favorite = true }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Other in result)
    }

    @Test
    fun channelChange_flagsOther() {
        val updated = baseNode.newBuilder().also { wb -> wb.channel = 3 }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.Other in result)
    }

    @Test
    fun multipleFieldChanges_flagsAll() {
        val updated = baseNode.newBuilder().also { wb ->
            wb.snr = 2.0f
            wb.last_heard = 5000
            wb.position = Position.Builder().also { wb ->wb.latitude_i = 390000000; wb.longitude_i = -1210000000}.build()
        }.build()
        val result = diffNodeFields(baseNode, updated)
        assertTrue(NodeField.SignalQuality in result)
        assertTrue(NodeField.LastSeen in result)
        assertTrue(NodeField.Position in result)
    }
}
