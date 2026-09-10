/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.NodeInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class NodeStatusTest {

    private val now = 1_700_000_000 // arbitrary epoch seconds

    @Test
    fun isOnline_heardRecently_returnsTrue() {
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.last_heard = now - 60}.build() // 1 minute ago
        assertTrue(node.isOnline(now))
    }

    @Test
    fun isOnline_heardExactlyAtThreshold_returnsTrue() {
        val cutoff = now - DEFAULT_ONLINE_THRESHOLD.inWholeSeconds.toInt()
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.last_heard = cutoff}.build()
        assertTrue(node.isOnline(now))
    }

    @Test
    fun isOnline_heardBeyondThreshold_returnsFalse() {
        val cutoff = now - DEFAULT_ONLINE_THRESHOLD.inWholeSeconds.toInt() - 1
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.last_heard = cutoff}.build()
        assertFalse(node.isOnline(now))
    }

    @Test
    fun isOnline_neverHeard_returnsFalse() {
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.last_heard = 0}.build()
        assertFalse(node.isOnline(now))
    }

    @Test
    fun isOnline_customThreshold() {
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.last_heard = now - 45 * 60}.build() // 45 min ago
        assertTrue(node.isOnline(now, threshold = 1.hours))
        assertFalse(node.isOnline(now, threshold = 30.minutes))
    }

    // --- ConnectionQuality ---

    @Test
    fun connectionQuality_direct() {
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.hops_away = 0; wb.via_mqtt = false}.build()
        assertEquals(ConnectionQuality.DIRECT, node.connectionQuality)
    }

    @Test
    fun connectionQuality_relayed() {
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.hops_away = 2; wb.via_mqtt = false}.build()
        assertEquals(ConnectionQuality.RELAYED, node.connectionQuality)
    }

    @Test
    fun connectionQuality_mqtt() {
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.hops_away = 0; wb.via_mqtt = true}.build()
        assertEquals(ConnectionQuality.MQTT, node.connectionQuality)
    }

    @Test
    fun connectionQuality_mqttTakesPrecedenceOverHops() {
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.hops_away = 3; wb.via_mqtt = true}.build()
        assertEquals(ConnectionQuality.MQTT, node.connectionQuality)
    }

    @Test
    fun connectionQuality_unknown() {
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.hops_away = null; wb.via_mqtt = false}.build()
        assertEquals(ConnectionQuality.UNKNOWN, node.connectionQuality)
    }

    // --- SignalQuality ---

    @Test
    fun signalQuality_good() {
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.snr = 10.0f; wb.hops_away = 0}.build()
        assertEquals(SignalQuality.GOOD, node.signalQuality)
    }

    @Test
    fun signalQuality_fair() {
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.snr = 2.5f; wb.hops_away = 0}.build()
        assertEquals(SignalQuality.FAIR, node.signalQuality)
    }

    @Test
    fun signalQuality_poor() {
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.snr = -5.0f; wb.hops_away = 0}.build()
        assertEquals(SignalQuality.POOR, node.signalQuality)
    }

    @Test
    fun signalQuality_none_noData() {
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.snr = 0f; wb.hops_away = null}.build()
        assertEquals(SignalQuality.NONE, node.signalQuality)
    }

    @Test
    fun signalQuality_zeroSnrWithHops_isFair() {
        // If we have hops_away data, snr=0 is a valid reading (fair threshold boundary)
        val node = NodeInfo.Builder().also { wb ->wb.num = 1; wb.snr = 0f; wb.hops_away = 1}.build()
        assertEquals(SignalQuality.FAIR, node.signalQuality)
    }
}
