/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString
import okio.ByteString.Companion.toByteString
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
    fun asText_readsTextPayload() {
        val packet = MeshPacket(
            decoded = Data(
                portnum = PortNum.TEXT_MESSAGE_APP,
                payload = ByteString.of(*"hello".encodeToByteArray()),
            ),
        )
        assertEquals("hello", packet.asText())
    }

    @Test
    fun asText_returnsNullForWrongPortnum() {
        val packet = MeshPacket(
            decoded = Data(
                portnum = PortNum.POSITION_APP,
                payload = ByteString.of(*"bytes".encodeToByteArray()),
            ),
        )
        assertNull(packet.asText())
    }

    @Test
    fun asPosition_roundTrip() {
        val pos = Position(latitude_i = 377749000, longitude_i = -1224194000, altitude = 12)
        val packet = MeshPacket(
            decoded = Data(
                portnum = PortNum.POSITION_APP,
                payload = ByteString.of(*Position.ADAPTER.encode(pos)),
            ),
        )
        val decoded = packet.asPosition()
        assertNotNull(decoded)
        assertEquals(pos, decoded)
    }

    @Test
    fun asPosition_missingDecodedReturnsNull() {
        assertNull(MeshPacket().asPosition())
    }

    @Test
    fun decodeAs_ignoresPortnumAndSwallowsCorruptBytes() {
        val position = Position(latitude_i = 450000000, longitude_i = -930000000)
        // decodeAs is the documented escape hatch: NO portnum guard (Paxcount/StoreAndForward
        // consumers decode payloads carried under arbitrary ports).
        val mismatchedPort = MeshPacket(
            decoded = Data(
                portnum = PortNum.TEXT_MESSAGE_APP,
                payload = Position.ADAPTER.encode(position).toByteString(),
            ),
        )
        assertEquals(position, mismatchedPort.decodeAs(Position.ADAPTER))

        val corrupt = MeshPacket(
            decoded = Data(
                portnum = PortNum.POSITION_APP,
                payload = byteArrayOf(-1, -1, -1).toByteString(),
            ),
        )
        assertNull(corrupt.decodeAs(Position.ADAPTER))
        assertNull(MeshPacket().decodeAs(Position.ADAPTER))
    }
}
