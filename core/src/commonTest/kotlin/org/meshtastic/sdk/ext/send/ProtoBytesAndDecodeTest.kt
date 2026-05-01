/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.ToRadio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProtoBytesAndDecodeTest {

    @Test
    fun meshPacket_roundTripBytes() {
        val original = MeshPacket(to = 0x42, channel = 1, want_ack = true)
        val bytes = original.toByteArray()
        val decoded = bytes.toMeshPacket()
        assertEquals(original, decoded)
    }

    @Test
    fun fromRadio_roundTripBytes() {
        val original = FromRadio(id = 7, packet = MeshPacket(to = 0x09))
        val bytes = original.toByteArray()
        assertEquals(original, bytes.toFromRadio())
    }

    @Test
    fun toRadio_roundTripBytes() {
        val original = ToRadio(packet = MeshPacket(to = 0x11))
        val bytes = original.toByteArray()
        assertEquals(original, bytes.toToRadio())
    }

    @Test
    fun corruptBytes_returnNull() {
        assertNull(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()).toMeshPacket())
    }

    @Test
    fun decodeAsText_readsTextPayload() {
        val packet = MeshPacket(
            decoded = Data(
                portnum = PortNum.TEXT_MESSAGE_APP,
                payload = ByteString.of(*"hello".encodeToByteArray()),
            ),
        )
        assertEquals("hello", packet.decodeAsText())
    }

    @Test
    fun decodeAsText_returnsNullForWrongPortnum() {
        val packet = MeshPacket(
            decoded = Data(
                portnum = PortNum.POSITION_APP,
                payload = ByteString.of(*"bytes".encodeToByteArray()),
            ),
        )
        assertNull(packet.decodeAsText())
    }

    @Test
    fun decodeAsPosition_roundTrip() {
        val pos = Position(latitude_i = 377749000, longitude_i = -1224194000, altitude = 12)
        val packet = MeshPacket(
            decoded = Data(
                portnum = PortNum.POSITION_APP,
                payload = ByteString.of(*Position.ADAPTER.encode(pos)),
            ),
        )
        val decoded = packet.decodeAsPosition()
        assertNotNull(decoded)
        assertEquals(pos, decoded)
    }

    @Test
    fun decodeAs_emptyPayloadReturnsNull() {
        assertNull(MeshPacket().decodeAsPosition())
    }
}
