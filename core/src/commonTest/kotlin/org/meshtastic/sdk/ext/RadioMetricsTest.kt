/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.MeshPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RadioMetricsTest {
    @Test fun decodesRssiAndSnr() {
        val p = MeshPacket(rx_rssi = -85, rx_snr = 4.5f, hop_start = 3, hop_limit = 1, via_mqtt = false)
        val m = p.toRadioMetrics()!!
        assertEquals(-85, m.rssiDbm)
        assertEquals(4.5f, m.snrDb)
        assertEquals(2, m.hopsAway)
    }

    @Test fun absentRssiReturnsNull() {
        assertNull(MeshPacket().toRadioMetrics())
    }

    /**
     * 0 dBm is a reading, not an absence. An SX126x can report exactly 0 and the SX127x formula
     * can go positive, which is why protobufs 2.8.0 made rx_rssi `optional`. Before that this
     * packet was indistinguishable from one carrying no metrics and was thrown away.
     */
    @Test fun zeroDbmIsARealReading() {
        val m = MeshPacket(rx_rssi = 0, rx_snr = 0f).toRadioMetrics()
        assertNotNull(m)
        assertEquals(0, m.rssiDbm)
    }

    @Test fun signalQualityBuckets() {
        assertEquals(5, MeshPacket(rx_rssi = -50, rx_snr = 10f).signalQuality())
        assertEquals(4, MeshPacket(rx_rssi = -60, rx_snr = 1f).signalQuality())
        assertEquals(3, MeshPacket(rx_rssi = -80, rx_snr = -3f).signalQuality())
        assertEquals(2, MeshPacket(rx_rssi = -90, rx_snr = -8f).signalQuality())
        assertEquals(1, MeshPacket(rx_rssi = -110, rx_snr = -20f).signalQuality())
        assertNull(MeshPacket().signalQuality())
    }
}
