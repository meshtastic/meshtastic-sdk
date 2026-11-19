/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketProvenanceTest {

    private fun packet(block: MeshPacket.Builder.() -> Unit = {}): MeshPacket = MeshPacket.Builder().also(block).build()

    private fun withUnknownVarint(base: MeshPacket, tag: Int, value: Int): MeshPacket {
        val extra = Buffer()
        var key = tag shl 3
        while (key >= 0x80) {
            extra.writeByte((key and 0x7F) or 0x80)
            key = key ushr 7
        }
        extra.writeByte(key).writeByte(value)
        return MeshPacket.ADAPTER.decode(
            MeshPacket.ADAPTER.encodeByteString(base).toByteArray() + extra.readByteArray(),
        )
    }

    private fun bytes(size: Int): ByteString = ByteArray(size) { (it + 1).toByte() }.toByteString()

    @Test
    fun transportDefaultReadsAsInternal() {
        assertEquals(PacketTransport.Internal, packet().transport)
    }

    @Test
    fun transportMapsEveryKnownMechanism() {
        val pairs = mapOf(
            MeshPacket.TransportMechanism.TRANSPORT_INTERNAL to PacketTransport.Internal,
            MeshPacket.TransportMechanism.TRANSPORT_LORA to PacketTransport.Lora,
            MeshPacket.TransportMechanism.TRANSPORT_LORA_ALT1 to PacketTransport.LoraAlt1,
            MeshPacket.TransportMechanism.TRANSPORT_LORA_ALT2 to PacketTransport.LoraAlt2,
            MeshPacket.TransportMechanism.TRANSPORT_LORA_ALT3 to PacketTransport.LoraAlt3,
            MeshPacket.TransportMechanism.TRANSPORT_MQTT to PacketTransport.Mqtt,
            MeshPacket.TransportMechanism.TRANSPORT_MULTICAST_UDP to PacketTransport.MulticastUdp,
            MeshPacket.TransportMechanism.TRANSPORT_API to PacketTransport.Api,
            MeshPacket.TransportMechanism.TRANSPORT_UNICAST_UDP to PacketTransport.UnicastUdp,
        )
        assertEquals(MeshPacket.TransportMechanism.entries.toSet(), pairs.keys)
        pairs.forEach { (wire, expected) ->
            assertEquals(expected, packet { transport_mechanism = wire }.transport)
        }
    }

    @Test
    fun transportUnknownWireValueIsUnrecognizedNotInternal() {
        assertEquals(PacketTransport.Unrecognized, withUnknownVarint(packet(), tag = 21, value = 99).transport)
    }

    @Test
    fun unrelatedUnknownFieldDoesNotMakeTransportUnrecognized() {
        assertEquals(PacketTransport.Internal, withUnknownVarint(packet(), tag = 900, value = 1).transport)
    }

    @Test
    fun relayByteAbsentWhenZero() {
        assertNull(packet().relayNode)
        assertNull(packet().nextHop)
    }

    @Test
    fun relayAndNextHopPresentWhenNonZero() {
        val p = packet {
            relay_node = 0xAB
            next_hop = 0x01
        }
        assertEquals(NodeLastByte(0xAB), p.relayNode)
        assertEquals(NodeLastByte(0x01), p.nextHop)
    }

    @Test
    fun relayByteOutOfRangeIsAbsent() {
        val p = packet {
            relay_node = 256
            next_hop = -1
        }
        assertNull(p.relayNode)
        assertNull(p.nextHop)
    }

    @Test
    fun nodeLastByteRejectsOutOfRangeConstruction() {
        assertFailsWith<IllegalArgumentException> { NodeLastByte(0) }
        assertFailsWith<IllegalArgumentException> { NodeLastByte(256) }
    }

    @Test
    fun nodeIdLastByteMapsZeroToFf() {
        assertEquals(NodeLastByte(0x44), NodeId(0x11223344).lastByte())
        assertEquals(NodeLastByte(0xFF), NodeId(0x11223300).lastByte())
        assertEquals(NodeLastByte(0xFF), NodeId.BROADCAST.lastByte())
    }

    @Test
    fun candidatesWithNoMatch() {
        val nodes = listOf(NodeId(0x11223344), NodeId(0x55667788))
        assertEquals(emptyList(), NodeLastByte(0x01).candidates(nodes))
    }

    @Test
    fun candidatesWithOneMatch() {
        val nodes = listOf(NodeId(0x11223344), NodeId(0x55667788))
        assertEquals(listOf(NodeId(0x55667788)), NodeLastByte(0x88).candidates(nodes))
    }

    @Test
    fun candidatesWithSeveralMatches() {
        val nodes = listOf(NodeId(0x11223344), NodeId(0x55667744), NodeId(0x19887766), NodeId(0x01020300))
        assertEquals(listOf(NodeId(0x11223344), NodeId(0x55667744)), NodeLastByte(0x44).candidates(nodes))
        assertEquals(listOf(NodeId(0x01020300)), NodeLastByte(0xFF).candidates(nodes))
    }

    @Test
    fun signatureNoneWhenNothingCarried() {
        val p = packet { decoded = Data.Builder().build() }
        assertEquals(PacketSignature.None, p.signature)
        assertNull(p.xeddsaSignature)
    }

    @Test
    fun signatureNoneWhenEncrypted() {
        assertEquals(PacketSignature.None, packet().signature)
        assertNull(packet().xeddsaSignature)
    }

    @Test
    fun signatureCarriedButUnchecked() {
        val sig = bytes(64)
        val p = packet { decoded = Data.Builder().also { wb -> wb.xeddsa_signature = sig }.build() }
        assertEquals(PacketSignature.CarriedUnchecked, p.signature)
        assertEquals(sig, p.xeddsaSignature)
    }

    @Test
    fun signatureCheckedByDevice() {
        val sig = bytes(64)
        val p = packet {
            xeddsa_signed = true
            decoded = Data.Builder().also { wb -> wb.xeddsa_signature = sig }.build()
        }
        assertEquals(PacketSignature.CheckedByDevice, p.signature)
        assertEquals(sig, p.xeddsaSignature)
    }

    @Test
    fun deviceFlagWithoutBytesIsStillCheckedByDevice() {
        val p = packet {
            xeddsa_signed = true
            decoded = Data.Builder().build()
        }
        assertEquals(PacketSignature.CheckedByDevice, p.signature)
        assertNull(p.xeddsaSignature)
    }

    @Test
    fun signatureWrongLengthIsMalformed() {
        val p = packet { decoded = Data.Builder().also { wb -> wb.xeddsa_signature = bytes(10) }.build() }
        assertEquals(PacketSignature.Malformed, p.signature)
        assertEquals(bytes(10), p.xeddsaSignature)
    }

    @Test
    fun ackProofNullOutsideRouting() {
        val p = packet {
            decoded = Data.Builder().also { wb -> wb.portnum = PortNum.TEXT_MESSAGE_APP }.build()
            ack_proof_status = MeshPacket.AckProofStatus.ACK_PROOF_VALID
        }
        assertNull(p.ackProof)
        assertNull(packet().ackProof)
    }

    @Test
    fun ackProofMapsEveryStatusOnRoutingPackets() {
        val pairs = mapOf(
            MeshPacket.AckProofStatus.ACK_PROOF_ABSENT to AckProof.Absent,
            MeshPacket.AckProofStatus.ACK_PROOF_VALID to AckProof.Valid,
            MeshPacket.AckProofStatus.ACK_PROOF_INVALID to AckProof.Invalid,
            MeshPacket.AckProofStatus.ACK_PROOF_NO_KEY to AckProof.NoKey,
        )
        assertEquals(MeshPacket.AckProofStatus.entries.toSet(), pairs.keys)
        pairs.forEach { (wire, expected) ->
            val p = packet {
                decoded = Data.Builder().also { wb -> wb.portnum = PortNum.ROUTING_APP }.build()
                ack_proof_status = wire
            }
            assertEquals(expected, p.ackProof)
        }
    }

    @Test
    fun ackProofUnknownWireValueIsUnrecognized() {
        val base = packet { decoded = Data.Builder().also { wb -> wb.portnum = PortNum.ROUTING_APP }.build() }
        assertEquals(AckProof.Unrecognized, withUnknownVarint(base, tag = 23, value = 42).ackProof)
    }

    @Test
    fun heardOnCurrentLoraReadsTheDeviceFlag() {
        assertFalse(NodeInfo.Builder().build().heardOnCurrentLora)
        assertTrue(NodeInfo.Builder().also { wb -> wb.heard_on_current_lora = true }.build().heardOnCurrentLora)
    }

    @Test
    fun signsPacketsReadsTheDeviceFlag() {
        assertFalse(NodeInfo.Builder().build().signsPackets)
        assertTrue(NodeInfo.Builder().also { wb -> wb.has_xeddsa_signed = true }.build().signsPackets)
    }
}
