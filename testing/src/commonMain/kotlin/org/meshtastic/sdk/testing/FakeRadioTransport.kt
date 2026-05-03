/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.testing

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.io.bytestring.ByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportState

private const val NONCE_STAGE1 = 69420
private const val NONCE_STAGE2 = 69421
private const val DEFAULT_FAKE_NODE_NUM = 1

/**
 * In-memory, script-driven [RadioTransport] for testing.
 *
 * Allows tests to replay captured frames and control connection state deterministically.
 *
 * When [autoHandshake] is `true`, the transport automatically responds to the two-stage
 * Meshtastic handshake (want_config_id 69420 / 69421) and the get_owner_request admin message,
 * enabling [RadioClient.connect] to complete without a real radio. The synthesized
 * `MyNodeInfo.my_node_num` defaults to `1`; pass [nodeNum] to simulate a different identity
 * (e.g. for identity-rebind tests).
 */
public class FakeRadioTransport(
    override val identity: TransportIdentity,
    /** Pre-loaded frames to inject immediately after [connect]. */
    private val frames: List<Frame> = emptyList(),
    /**
     * When `true`, automatically responds to handshake ToRadio messages so
     * [RadioClient.connect] can complete without a real radio.
     */
    public val autoHandshake: Boolean = false,
    /** `MyNodeInfo.my_node_num` to report during the auto-handshake. */
    public val nodeNum: Int = DEFAULT_FAKE_NODE_NUM,
) : RadioTransport {

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    private val outboundFramesList = mutableListOf<Frame>()
    private var inboundChannel = Channel<Frame>(Channel.UNLIMITED)

    override val state: StateFlow<TransportState> = _state.asStateFlow()

    /** Frames that were sent via [send]. */
    public fun outboundFrames(): List<Frame> = outboundFramesList.toList()

    /** Inject an arbitrary frame as if received from the radio. */
    public fun injectFrame(frame: Frame) {
        inboundChannel.trySend(frame)
    }

    /**
     * Decoded view of every outbound `MeshPacket` sent by the engine since [connect]. Strips the
     * 4-byte stream-framing header and re-decodes each [Frame] into a [ToRadio] envelope, then
     * surfaces packets that targeted ADMIN_APP / TELEMETRY_APP / ROUTING_APP / NEIGHBORINFO_APP.
     *
     * Used by Phase 2 RPC tests to assert that the AdminApi / TelemetryApi / RoutingApi impls
     * actually emitted the expected packets onto the wire.
     */
    public fun outboundPackets(): List<MeshPacket> = outboundFramesList.mapNotNull { frame ->
        val bytes = frame.bytes.toByteArray()
        if (bytes.size < 4) return@mapNotNull null
        val protoBytes = bytes.copyOfRange(4, bytes.size)
        runCatching { ToRadio.ADAPTER.decode(protoBytes).packet }.getOrNull()
    }

    /**
     * Inject an arbitrary [MeshPacket] as if it arrived from the radio.
     * Use this to test flows that consume [RadioClient.packets] (e.g. [RadioClient.textMessages]).
     */
    public fun injectPacket(packet: MeshPacket) {
        injectFromRadio(FromRadio(packet = packet))
    }

    /**
     * Inject an admin response packet correlated to [requestId]. The packet is constructed with
     * `decoded.request_id = requestId` so the engine's [CommandDispatcher] / `processRoutingAck`
     * can match it against an outstanding request.
     */
    public fun injectAdminResponse(requestId: Int, response: AdminMessage, fromNode: Int = nodeNum) {
        val payload = okio.ByteString.of(*AdminMessage.ADAPTER.encode(response))
        val packet = MeshPacket(
            from = fromNode,
            to = 0,
            decoded = Data(
                portnum = PortNum.ADMIN_APP,
                payload = payload,
                request_id = requestId,
            ),
        )
        injectFromRadio(FromRadio(packet = packet))
    }

    /** Inject a Telemetry response packet correlated to [requestId]. */
    public fun injectTelemetryResponse(
        requestId: Int,
        telemetry: org.meshtastic.proto.Telemetry,
        fromNode: Int = nodeNum,
    ) {
        val payload = okio.ByteString.of(*org.meshtastic.proto.Telemetry.ADAPTER.encode(telemetry))
        val packet = MeshPacket(
            from = fromNode,
            to = 0,
            decoded = Data(
                portnum = PortNum.TELEMETRY_APP,
                payload = payload,
                request_id = requestId,
            ),
        )
        injectFromRadio(FromRadio(packet = packet))
    }

    /** Inject a Routing.route_reply correlated to [requestId]. */
    public fun injectRouteReply(requestId: Int, reply: org.meshtastic.proto.RouteDiscovery, fromNode: Int = nodeNum) {
        val payload = okio.ByteString.of(*Routing.ADAPTER.encode(Routing(route_reply = reply)))
        val packet = MeshPacket(
            from = fromNode,
            to = 0,
            decoded = Data(
                portnum = PortNum.ROUTING_APP,
                payload = payload,
                request_id = requestId,
            ),
        )
        injectFromRadio(FromRadio(packet = packet))
    }

    /** Inject a Routing error correlated to [requestId] (failure-mapping tests). */
    public fun injectRoutingError(requestId: Int, error: Routing.Error, fromNode: Int = nodeNum) {
        val payload = okio.ByteString.of(*Routing.ADAPTER.encode(Routing(error_reason = error)))
        val packet = MeshPacket(
            from = fromNode,
            to = 0,
            decoded = Data(
                portnum = PortNum.ROUTING_APP,
                payload = payload,
                request_id = requestId,
            ),
        )
        injectFromRadio(FromRadio(packet = packet))
    }

    /** Inject a NeighborInfo response correlated to [requestId]. */
    public fun injectNeighborInfoResponse(
        requestId: Int,
        info: org.meshtastic.proto.NeighborInfo,
        fromNode: Int = nodeNum,
    ) {
        val payload = okio.ByteString.of(*org.meshtastic.proto.NeighborInfo.ADAPTER.encode(info))
        val packet = MeshPacket(
            from = fromNode,
            to = 0,
            decoded = Data(
                portnum = PortNum.NEIGHBORINFO_APP,
                payload = payload,
                request_id = requestId,
            ),
        )
        injectFromRadio(FromRadio(packet = packet))
    }

    /** Inject a Routing.Ack correlated to [requestId] (setter ack-style tests). */
    public fun injectRoutingAck(requestId: Int, fromNode: Int = nodeNum) {
        val payload = okio.ByteString.of(*Routing.ADAPTER.encode(Routing(error_reason = Routing.Error.NONE)))
        val packet = MeshPacket(
            from = fromNode,
            to = 0,
            decoded = Data(
                portnum = PortNum.ROUTING_APP,
                payload = payload,
                request_id = requestId,
            ),
        )
        injectFromRadio(FromRadio(packet = packet))
    }

    override suspend fun connect() {
        inboundChannel = Channel(Channel.UNLIMITED)
        _state.value = TransportState.Connecting
        for (frame in frames) inboundChannel.trySend(frame)
        _state.value = TransportState.Connected
    }

    override suspend fun disconnect() {
        inboundChannel.close()
        _state.value = TransportState.Disconnected
    }

    override suspend fun send(frame: Frame) {
        outboundFramesList.add(frame)
        if (autoHandshake) handleOutboundFrame(frame)
    }

    override fun frames(): Flow<Frame> {
        val ch = inboundChannel
        return flow {
            for (f in ch) emit(f)
        }
    }

    /** Transition to an error state. */
    public fun simulateError(cause: Throwable, recoverable: Boolean = true) {
        _state.value = TransportState.Error(cause, recoverable)
    }

    private fun handleOutboundFrame(frame: Frame) {
        val bytes = frame.bytes.toByteArray()
        if (bytes.size < 4) return
        val protoBytes = bytes.copyOfRange(4, bytes.size)
        val toRadio = try {
            ToRadio.ADAPTER.decode(protoBytes)
        } catch (_: Exception) {
            return
        }
        when (toRadio.want_config_id) {
            NONCE_STAGE1 -> injectStage1Frames()
            NONCE_STAGE2 -> injectStage2Frames()
        }
        val packet = toRadio.packet
        if (packet != null) handleAdminPacket(packet)
    }

    private fun injectStage1Frames() {
        injectFromRadio(FromRadio(my_info = MyNodeInfo(my_node_num = nodeNum)))
        injectFromRadio(FromRadio(config_complete_id = NONCE_STAGE1))
    }

    private fun injectStage2Frames() {
        injectFromRadio(FromRadio(config_complete_id = NONCE_STAGE2))
    }

    private fun handleAdminPacket(packet: MeshPacket) {
        val decoded = packet.decoded ?: return
        if (decoded.portnum != PortNum.ADMIN_APP) return
        val admin = try {
            AdminMessage.ADAPTER.decode(decoded.payload)
        } catch (_: Exception) {
            return
        }
        if (admin.get_owner_request == true) {
            val user = User(
                id = "!00000001",
                long_name = "FakeNode",
                short_name = "FN",
                hw_model = HardwareModel.UNSET,
            )
            val response = AdminMessage(
                get_owner_response = user,
                session_passkey = okio.ByteString.EMPTY,
            )
            val encoded = AdminMessage.ADAPTER.encode(response)
            val responsePayload = okio.ByteString.of(*encoded)
            val responsePacket = MeshPacket(
                from = nodeNum,
                to = packet.from,
                decoded = Data(portnum = PortNum.ADMIN_APP, payload = responsePayload),
            )
            injectFromRadio(FromRadio(packet = responsePacket))
        }
    }

    private fun injectFromRadio(fromRadio: FromRadio) {
        val proto = FromRadio.ADAPTER.encode(fromRadio)
        val frameBytes = ByteArray(4 + proto.size).apply {
            this[0] = 0x94.toByte()
            this[1] = 0xC3.toByte()
            this[2] = (proto.size shr 8).toByte()
            this[3] = (proto.size and 0xFF).toByte()
            proto.copyInto(this, destinationOffset = 4)
        }
        inboundChannel.trySend(Frame(ByteString(frameBytes)))
    }
}
