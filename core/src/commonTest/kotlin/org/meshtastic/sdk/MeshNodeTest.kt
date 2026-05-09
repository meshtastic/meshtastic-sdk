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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshNodeTest {

    private val now = 1700000000 // arbitrary epoch seconds

    private fun nodeInfo(
        num: Int = 1,
        lastHeard: Int = now - 60, // 1 minute ago
        snr: Float = 7f,
        hopsAway: Int? = 0,
        viaMqtt: Boolean = false,
        user: User? = User(
            id = "!00000001",
            long_name = "TestNode",
            short_name = "TN",
            hw_model = HardwareModel.TBEAM,
        ),
        position: Position? = null,
        deviceMetrics: DeviceMetrics? = null,
    ) = NodeInfo(
        num = num,
        last_heard = lastHeard,
        snr = snr,
        hops_away = hopsAway,
        via_mqtt = viaMqtt,
        user = user,
        position = position,
        device_metrics = deviceMetrics,
    )

    @Test
    fun toMeshNodePreservesIdentity() {
        val node = nodeInfo().toMeshNode(now)
        assertEquals(1, node.nodeNum)
        assertEquals(NodeId(1), node.nodeId)
        assertEquals("TestNode", node.longName)
        assertEquals("TN", node.shortName)
        assertEquals("!00000001", node.meshId)
        assertEquals(HardwareModel.TBEAM, node.hwModel)
    }

    @Test
    fun onlineWhenRecentlyHeard() {
        val node = nodeInfo(lastHeard = now - 60).toMeshNode(now)
        assertTrue(node.isOnline)
    }

    @Test
    fun offlineWhenNeverHeard() {
        val node = nodeInfo(lastHeard = 0).toMeshNode(now)
        assertFalse(node.isOnline)
    }

    @Test
    fun offlineWhenStale() {
        val node = nodeInfo(lastHeard = now - 8000).toMeshNode(now) // > 2 hours
        assertFalse(node.isOnline)
    }

    @Test
    fun connectionQualityDirect() {
        val node = nodeInfo(hopsAway = 0, viaMqtt = false).toMeshNode(now)
        assertEquals(ConnectionQuality.DIRECT, node.connectionQuality)
    }

    @Test
    fun connectionQualityRelayed() {
        val node = nodeInfo(hopsAway = 2).toMeshNode(now)
        assertEquals(ConnectionQuality.RELAYED, node.connectionQuality)
    }

    @Test
    fun connectionQualityMqtt() {
        val node = nodeInfo(viaMqtt = true).toMeshNode(now)
        assertEquals(ConnectionQuality.MQTT, node.connectionQuality)
    }

    @Test
    fun signalQualityGood() {
        val node = nodeInfo(snr = 10f).toMeshNode(now)
        assertEquals(SignalQuality.GOOD, node.signalQuality)
    }

    @Test
    fun signalQualityPoor() {
        val node = nodeInfo(snr = -3f).toMeshNode(now)
        assertEquals(SignalQuality.POOR, node.signalQuality)
    }

    @Test
    fun positionAccessors() {
        val pos = Position(latitude_i = 371234567, longitude_i = -1221234567, altitude = 100)
        val node = nodeInfo(position = pos).toMeshNode(now)
        assertNotNull(node.latitude)
        assertNotNull(node.longitude)
        assertEquals(37.1234567, node.latitude!!, 0.0000001)
        assertEquals(-122.1234567, node.longitude!!, 0.0000001)
        assertEquals(100, node.altitude)
    }

    @Test
    fun nullPositionWhenZero() {
        val pos = Position(latitude_i = 0, longitude_i = 0)
        val node = nodeInfo(position = pos).toMeshNode(now)
        assertNull(node.latitude)
        assertNull(node.longitude)
    }

    @Test
    fun deviceMetricsAccessors() {
        val metrics = DeviceMetrics(battery_level = 85, voltage = 4.1f, channel_utilization = 12.5f)
        val node = nodeInfo(deviceMetrics = metrics).toMeshNode(now)
        assertEquals(85, node.batteryLevel)
        assertEquals(4.1f, node.voltage)
        assertEquals(12.5f, node.channelUtilization)
    }

    @Test
    fun nullUser() {
        val node = nodeInfo(user = null).toMeshNode(now)
        assertNull(node.longName)
        assertNull(node.shortName)
        assertNull(node.hwModel)
    }

    @Test
    fun toMeshNodesCollectionHelper() {
        val nodes = listOf(
            nodeInfo(num = 1, lastHeard = now - 60),
            nodeInfo(num = 2, lastHeard = now - 9000),
        ).toMeshNodes(now)
        assertEquals(2, nodes.size)
        assertTrue(nodes[0].isOnline)
        assertFalse(nodes[1].isOnline)
    }
}
