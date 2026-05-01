/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Validates the BLE ↔ engine frame contract.
 *
 * The engine's `WireCodec` always works with stream-framed bytes
 * (`0x94 0xC3 LEN_HI LEN_LO PAYLOAD`). BLE has no stream framing, so the
 * transport synthesises the 4-byte header on receive and strips it on send.
 * Round-tripping must give back the original payload byte-for-byte.
 */
class FrameStripTest {

    @Test
    fun prependFraming_addsValidHeader() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)

        val framed = BleTransport.prependFraming(payload).toByteArray()

        assertEquals(9, framed.size)
        assertEquals(0x94.toByte(), framed[0])
        assertEquals(0xC3.toByte(), framed[1])
        assertEquals(0x00, framed[2]) // LEN_HI
        assertEquals(0x05, framed[3]) // LEN_LO
        assertContentEquals(payload, framed.copyOfRange(4, framed.size))
    }

    @Test
    fun prependFraming_handlesEmptyPayload() {
        val framed = BleTransport.prependFraming(ByteArray(0)).toByteArray()

        assertEquals(4, framed.size)
        assertEquals(0x94.toByte(), framed[0])
        assertEquals(0xC3.toByte(), framed[1])
        assertEquals(0x00, framed[2])
        assertEquals(0x00, framed[3])
    }

    @Test
    fun prependFraming_handlesLargePayload() {
        // 512 = MAX_FRAME_SIZE per protocol.md §2
        val payload = ByteArray(512) { (it and 0xFF).toByte() }

        val framed = BleTransport.prependFraming(payload).toByteArray()

        assertEquals(516, framed.size)
        assertEquals(0x02, framed[2]) // 512 >> 8
        assertEquals(0x00, framed[3]) // 512 & 0xFF
        assertContentEquals(payload, framed.copyOfRange(4, framed.size))
    }

    @Test
    fun stripAndReprepend_isIdentity() {
        // Simulate engine encoding (WireCodec produces framed bytes) then BLE
        // transport stripping the header for write, then BLE transport
        // re-prepending on read. Should round-trip.
        val original = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val framed = BleTransport.prependFraming(original).toByteArray()

        // Engine -> BLE transport strip:
        val stripped = framed.copyOfRange(4, framed.size)
        // BLE transport read -> prepend header again:
        val reframed = BleTransport.prependFraming(stripped).toByteArray()

        assertContentEquals(framed, reframed)
    }
}
