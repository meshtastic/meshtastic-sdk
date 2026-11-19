/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import com.squareup.wire.ProtoReader
import okio.Buffer
import okio.ByteString
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum

private const val MAX_BYTE: Int = 255
private const val XEDDSA_SIGNATURE_SIZE: Int = 64
private const val TRANSPORT_MECHANISM_TAG: Int = 21
private const val ACK_PROOF_STATUS_TAG: Int = 23

/**
 * How a packet reached the connected device, from `MeshPacket.transport_mechanism`.
 *
 * The field has no presence: [Internal] is the proto default, so it also reads from firmware that
 * predates the field. This is the last hop into the device only; for "ever crossed an MQTT
 * bridge" use `MeshPacket.via_mqtt`.
 *
 * @since 0.3.0
 */
public enum class PacketTransport {
    /** The device generated the packet itself, or the firmware did not report a transport. */
    Internal,

    /** The primary LoRa radio. */
    Lora,

    /** A secondary LoRa radio. */
    LoraAlt1,

    /** A tertiary LoRa radio. */
    LoraAlt2,

    /** A quaternary LoRa radio. */
    LoraAlt3,

    /** An MQTT connection. */
    Mqtt,

    /** Multicast UDP. */
    MulticastUdp,

    /** The client API connection. */
    Api,

    /** Unicast UDP. */
    UnicastUdp,

    /** A transport value this SDK's protobufs do not define. */
    Unrecognized,
}

/**
 * The transport this packet arrived over. See [PacketTransport] for what `Internal` can mean.
 *
 * @since 0.3.0
 */
public val MeshPacket.transport: PacketTransport
    get() {
        if (unknownFields.size != 0 && TRANSPORT_MECHANISM_TAG in unknownTags(unknownFields)) {
            return PacketTransport.Unrecognized
        }
        return when (transport_mechanism) {
            MeshPacket.TransportMechanism.TRANSPORT_INTERNAL -> PacketTransport.Internal
            MeshPacket.TransportMechanism.TRANSPORT_LORA -> PacketTransport.Lora
            MeshPacket.TransportMechanism.TRANSPORT_LORA_ALT1 -> PacketTransport.LoraAlt1
            MeshPacket.TransportMechanism.TRANSPORT_LORA_ALT2 -> PacketTransport.LoraAlt2
            MeshPacket.TransportMechanism.TRANSPORT_LORA_ALT3 -> PacketTransport.LoraAlt3
            MeshPacket.TransportMechanism.TRANSPORT_MQTT -> PacketTransport.Mqtt
            MeshPacket.TransportMechanism.TRANSPORT_MULTICAST_UDP -> PacketTransport.MulticastUdp
            MeshPacket.TransportMechanism.TRANSPORT_API -> PacketTransport.Api
            MeshPacket.TransportMechanism.TRANSPORT_UNICAST_UDP -> PacketTransport.UnicastUdp
        }
    }

/**
 * The last byte of a node number, as carried by `relay_node` and `next_hop`.
 *
 * It is one byte, so several nodes on a mesh can share it. It identifies no node by itself; use
 * [candidates] against the nodes you know. Firmware reports a node whose number ends in `0x00`
 * as `0xFF`, so [value] is always in `1..255`.
 *
 * @property value the byte, `1..255`.
 * @since 0.3.0
 */
public data class NodeLastByte(public val value: Int) {
    init {
        require(value in 1..MAX_BYTE) { "NodeLastByte must be in 1..$MAX_BYTE, was $value" }
    }

    /** Returns the [nodes] whose [lastByte] equals this byte, in input order. */
    public fun candidates(nodes: Collection<NodeId>): List<NodeId> = nodes.filter { it.lastByte() == this }
}

/**
 * The byte firmware uses for this node in `relay_node` and `next_hop`: the low byte of the node
 * number, with `0x00` reported as `0xFF`.
 *
 * @since 0.3.0
 */
public fun NodeId.lastByte(): NodeLastByte {
    val low = raw and MAX_BYTE
    return NodeLastByte(if (low == 0) MAX_BYTE else low)
}

/**
 * The last byte of the node that relayed this packet to the device, or `null` when firmware set
 * none (`0`) or the value is not a byte. For a packet heard directly this is the sender's own
 * byte. Never a node id: resolve it with [NodeLastByte.candidates].
 *
 * @since 0.3.0
 */
public val MeshPacket.relayNode: NodeLastByte?
    get() = relay_node.toNodeLastByteOrNull()

/**
 * The last byte of the node firmware chose as next hop for this packet, or `null` when it set
 * none (`0`, flooding) or the value is not a byte. Never a node id.
 *
 * @since 0.3.0
 */
public val MeshPacket.nextHop: NodeLastByte?
    get() = next_hop.toNodeLastByteOrNull()

private fun Int.toNodeLastByteOrNull(): NodeLastByte? = if (this in 1..MAX_BYTE) NodeLastByte(this) else null

/**
 * What a packet says about its XEdDSA signature. The SDK does not check signatures itself;
 * [CheckedByDevice] repeats the connected device's own verdict.
 *
 * @since 0.3.0
 */
public enum class PacketSignature {
    /** No signature bytes and the device did not flag the packet. */
    None,

    /** A full-length signature is present but the device did not mark it checked (for example no key for the sender). */
    CarriedUnchecked,

    /** Signature bytes of a length other than 64 are present. */
    Malformed,

    /** The device verified the signature against the sender's key and set `xeddsa_signed`. */
    CheckedByDevice,
}

/**
 * Signature state of this packet. [PacketSignature.CheckedByDevice] means firmware ran its own
 * XEdDSA verification and set `xeddsa_signed`; it is true only for a signature that verified.
 * Firmware without XEdDSA always leaves the flag false, so [PacketSignature.CarriedUnchecked] is
 * not evidence the signature is bad.
 *
 * @since 0.3.0
 */
public val MeshPacket.signature: PacketSignature
    get() {
        if (xeddsa_signed) return PacketSignature.CheckedByDevice
        val size = xeddsaSignature?.size ?: return PacketSignature.None
        return if (size == XEDDSA_SIGNATURE_SIZE) PacketSignature.CarriedUnchecked else PacketSignature.Malformed
    }

/**
 * The raw `decoded.xeddsa_signature` bytes, or `null` when the packet carries none (or is still
 * encrypted).
 *
 * @since 0.3.0
 */
public val MeshPacket.xeddsaSignature: ByteString?
    get() = decoded?.xeddsa_signature?.takeIf { it.size != 0 }

/**
 * The device's verdict on a `ROUTING_APP` ack or nak proof, from `MeshPacket.ack_proof_status`.
 *
 * @since 0.3.0
 */
public enum class AckProof {
    /** No verdict. Also reported when a proof was carried but not checked. Not evidence of forgery. */
    Absent,

    /** A proof verified against the key of the node the acknowledged packet was addressed to. */
    Valid,

    /** A proof was carried and did not verify. */
    Invalid,

    /** A proof was carried but no authoritative key was available to check it. */
    NoKey,

    /** A status value this SDK's protobufs do not define. */
    Unrecognized,
}

/**
 * The ack-proof verdict, or `null` when this is not a `ROUTING_APP` packet. The device sets it
 * and never accepts it from the radio, so it attests delivery, not authorship.
 *
 * @since 0.3.0
 */
public val MeshPacket.ackProof: AckProof?
    get() {
        if (decoded?.portnum != PortNum.ROUTING_APP) return null
        if (unknownFields.size != 0 && ACK_PROOF_STATUS_TAG in unknownTags(unknownFields)) {
            return AckProof.Unrecognized
        }
        return when (ack_proof_status) {
            MeshPacket.AckProofStatus.ACK_PROOF_ABSENT -> AckProof.Absent
            MeshPacket.AckProofStatus.ACK_PROOF_VALID -> AckProof.Valid
            MeshPacket.AckProofStatus.ACK_PROOF_INVALID -> AckProof.Invalid
            MeshPacket.AckProofStatus.ACK_PROOF_NO_KEY -> AckProof.NoKey
        }
    }

/**
 * `true` when the device has heard this node over RF on the LoRa configuration it is using now.
 * `false` also covers nodes heard only over MQTT, shared contacts, and firmware that does not
 * report the field.
 *
 * @since 0.3.0
 */
public val NodeInfo.heardOnCurrentLora: Boolean
    get() = heard_on_current_lora

/**
 * `true` when the device has seen this node sign its packets (`has_xeddsa_signed`). A flag the
 * device persists; it says nothing about any single packet.
 *
 * @since 0.3.0
 */
public val NodeInfo.signsPackets: Boolean
    get() = has_xeddsa_signed

private fun unknownTags(unknownFields: ByteString): Set<Int> {
    val tags = mutableSetOf<Int>()
    return try {
        val reader = ProtoReader(Buffer().write(unknownFields))
        reader.forEachTag { tag ->
            tags += tag
            reader.readUnknownField(tag)
        }
        tags
    } catch (_: Exception) {
        tags
    }
}
