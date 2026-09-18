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
        val p = MeshPacket.Builder().also { wb ->
            wb.rx_rssi = -85
            wb.rx_snr = 4.5f
            wb.hop_start = 3
            wb.hop_limit = 1
            wb.via_mqtt = false
        }.build()
        val m = p.toRadioMetrics()!!
        assertEquals(-85, m.rssiDbm)
        assertEquals(4.5f, m.snrDb)
        assertEquals(2, m.hopsAway)
    }

    @Test fun absentRssiReturnsNull() {
        assertNull(MeshPacket.Builder().build().toRadioMetrics())
    }

    /**
     * 0 dBm is a reading, not an absence. An SX126x can report exactly 0 and the SX127x formula
     * can go positive, which is why protobufs 2.8.0 made rx_rssi `optional`. Before that this
     * packet was indistinguishable from one carrying no metrics and was thrown away.
     */
    @Test fun zeroDbmIsARealReading() {
        val m =
            MeshPacket.Builder()
                .also { wb ->
                    wb.rx_rssi = 0
                    wb.rx_snr = 0f
                }
                .build()
                .toRadioMetrics()
        assertNotNull(m)
        assertEquals(0, m.rssiDbm)
    }

    /**
     * Pre-2.8.0 firmware elides a genuine 0 dBm from the wire, so it decodes as an absent
     * `rx_rssi` next to a real snr. Those packets keep their snr rather than being discarded.
     */
    @Test fun absentRssiWithSnrStillHasMetrics() {
        val m = MeshPacket.Builder().also { wb -> wb.rx_snr = 3f }.build().toRadioMetrics()
        assertNotNull(m)
        assertEquals(0, m.rssiDbm)
        assertEquals(3f, m.snrDb)
    }

    @Test fun signalQualityBuckets() {
        assertEquals(
            5,
            MeshPacket.Builder().also { wb ->
                wb.rx_rssi = -50
                wb.rx_snr = 10f
            }.build().signalQuality(),
        )
        assertEquals(
            4,
            MeshPacket.Builder().also { wb ->
                wb.rx_rssi = -60
                wb.rx_snr = 1f
            }.build().signalQuality(),
        )
        assertEquals(
            3,
            MeshPacket.Builder().also { wb ->
                wb.rx_rssi = -80
                wb.rx_snr = -3f
            }.build().signalQuality(),
        )
        assertEquals(
            2,
            MeshPacket.Builder().also { wb ->
                wb.rx_rssi = -90
                wb.rx_snr = -8f
            }.build().signalQuality(),
        )
        assertEquals(
            1,
            MeshPacket.Builder().also { wb ->
                wb.rx_rssi = -110
                wb.rx_snr = -20f
            }.build().signalQuality(),
        )
        assertNull(MeshPacket.Builder().build().signalQuality())
    }
}
