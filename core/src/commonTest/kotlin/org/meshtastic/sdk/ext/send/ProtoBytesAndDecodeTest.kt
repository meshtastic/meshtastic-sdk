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
        val original = MeshPacket.Builder().also { wb ->wb.to = 0x42; wb.channel = 1; wb.want_ack = true}.build()
        val bytes = original.toByteArray()
        val decoded = bytes.toMeshPacket()
        assertEquals(original, decoded)
    }

    @Test
    fun fromRadio_roundTripBytes() {
        val original = FromRadio.Builder().also { wb ->wb.id = 7; wb.packet = MeshPacket.Builder().also { wb ->wb.to = 0x09}.build()}.build()
        val bytes = original.toByteArray()
        assertEquals(original, bytes.toFromRadio())
    }

    @Test
    fun toRadio_roundTripBytes() {
        val original = ToRadio.Builder().also { wb ->wb.packet = MeshPacket.Builder().also { wb ->wb.to = 0x11}.build()}.build()
        val bytes = original.toByteArray()
        assertEquals(original, bytes.toToRadio())
    }

    @Test
    fun corruptBytes_returnNull() {
        assertNull(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()).toMeshPacket())
    }

    @Test
    fun asText_readsTextPayload() {
        val packet = MeshPacket.Builder().also { wb ->
        wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.TEXT_MESSAGE_APP
                    wb.payload = ByteString.of(*"hello".encodeToByteArray())
                    }.build()
        }.build()
        assertEquals("hello", packet.asText())
    }

    @Test
    fun asText_returnsNullForWrongPortnum() {
        val packet = MeshPacket.Builder().also { wb ->
        wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.POSITION_APP
                    wb.payload = ByteString.of(*"bytes".encodeToByteArray())
                    }.build()
        }.build()
        assertNull(packet.asText())
    }

    @Test
    fun asPosition_roundTrip() {
        val pos = Position.Builder().also { wb ->wb.latitude_i = 377749000; wb.longitude_i = -1224194000; wb.altitude = 12}.build()
        val packet = MeshPacket.Builder().also { wb ->
        wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.POSITION_APP
                    wb.payload = ByteString.of(*Position.ADAPTER.encode(pos))
                    }.build()
        }.build()
        val decoded = packet.asPosition()
        assertNotNull(decoded)
        assertEquals(pos, decoded)
    }

    @Test
    fun asPosition_missingDecodedReturnsNull() {
        assertNull(MeshPacket.Builder().build().asPosition())
    }

    @Test
    fun decodeAs_ignoresPortnumAndSwallowsCorruptBytes() {
        val position = Position.Builder().also { wb ->wb.latitude_i = 450000000; wb.longitude_i = -930000000}.build()
        // decodeAs is the documented escape hatch: NO portnum guard (Paxcount/StoreAndForward
        // consumers decode payloads carried under arbitrary ports).
        val mismatchedPort = MeshPacket.Builder().also { wb ->
        wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.TEXT_MESSAGE_APP
                    wb.payload = Position.ADAPTER.encode(position).toByteString()
                    }.build()
        }.build()
        assertEquals(position, mismatchedPort.decodeAs(Position.ADAPTER))

        val corrupt = MeshPacket.Builder().also { wb ->
        wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.POSITION_APP
                    wb.payload = byteArrayOf(-1, -1, -1).toByteString()
                    }.build()
        }.build()
        assertNull(corrupt.decodeAs(Position.ADAPTER))
        assertNull(MeshPacket.Builder().build().decodeAs(Position.ADAPTER))
    }
}
