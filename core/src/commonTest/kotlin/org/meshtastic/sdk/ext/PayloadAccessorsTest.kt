/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PayloadAccessorsTest {
    private fun pkt(port: PortNum, payload: ByteArray) =
        MeshPacket(decoded = Data(portnum = port, payload = payload.toByteString()))

    private fun TestScope.buildClient(transport: FakeRadioTransport): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .coroutineContext(backgroundScope.coroutineContext)
        .build()

    private fun fakeTransport() = FakeRadioTransport(
        identity = TransportIdentity("fake:test"),
        autoHandshake = true,
    )

    @Test fun textRoundTrip() {
        assertEquals("hello mesh", pkt(PortNum.TEXT_MESSAGE_APP, "hello mesh".encodeToByteArray()).asText())
    }

    @Test fun mismatchedPortReturnsNull() {
        assertNull(pkt(PortNum.POSITION_APP, "x".encodeToByteArray()).asText())
        assertNull(MeshPacket().asText())
    }

    @Test fun positionAndUserDecode() {
        val pos = Position(latitude_i = 1, longitude_i = 2)
        assertEquals(1, pkt(PortNum.POSITION_APP, Position.ADAPTER.encode(pos)).asPosition()!!.latitude_i)
        val user = User(id = "!aabbccdd", long_name = "Alice")
        assertEquals("Alice", pkt(PortNum.NODEINFO_APP, User.ADAPTER.encode(user)).asNodeInfoUser()!!.long_name)
    }

    @Test fun telemetryAdminRouting() {
        val telPkt = pkt(PortNum.TELEMETRY_APP, Telemetry.ADAPTER.encode(Telemetry(time = 12345)))
        assertEquals(12345, telPkt.asTelemetry()!!.time)
        val adminPkt = pkt(PortNum.ADMIN_APP, AdminMessage.ADAPTER.encode(AdminMessage(get_owner_request = true)))
        assertNotNull(adminPkt.asAdminMessage())
        val routing = Routing(error_reason = Routing.Error.NO_ROUTE)
        assertEquals(
            Routing.Error.NO_ROUTE,
            pkt(PortNum.ROUTING_APP, Routing.ADAPTER.encode(routing)).asRouting()!!.error_reason,
        )
    }

    @Test fun emptyPayloadReturnsNull() {
        assertNull(MeshPacket(decoded = Data(portnum = PortNum.TEXT_MESSAGE_APP)).asText())
    }

    // ── textMessages flow ─────────────────────────────────────────────────

    @Test fun rawPacketsFlowReceivesInjected() = runTest {
        // Diagnostic: verify client.packets receives injected frames at all.
        val transport = fakeTransport()
        val client = buildClient(transport)
        client.connect()
        // NOTE: do NOT call advanceUntilIdle() here — it advances virtual time past the 60 s
        // liveness timeout, triggering handleDisconnect → handshakeStage=Idle → silent drops.

        val received = mutableListOf<MeshPacket>()
        val job = launch { client.packets.toList(received) }
        runCurrent() // start the collector coroutine (no virtual-time advance)

        transport.injectPacket(
            MeshPacket(
                from = 0xABCD,
                decoded = Data(portnum = PortNum.TEXT_MESSAGE_APP, payload = "test".encodeToByteArray().toByteString()),
            ),
        )
        runCurrent() // frame-reader → engine actor → emitPacketOrLog → collector
        runCurrent() // second pass: catch any second-hop scheduled work
        job.cancel()

        assertEquals(
            1,
            received.size,
            "rawPackets: expected 1 packet, got ${received.size} — injectPacket/engine may be broken",
        )
    }

    @Test fun textMessagesEmitsTextPackets() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport)
        client.connect()

        val received = mutableListOf<MeshPacket>()
        val job = launch { client.textMessages.toList(received) }
        runCurrent() // start the collector without advancing virtual time

        val textPkt = MeshPacket(
            from = 0x1234,
            decoded = Data(portnum = PortNum.TEXT_MESSAGE_APP, payload = "hi".encodeToByteArray().toByteString()),
        )
        transport.injectPacket(textPkt)
        runCurrent()
        runCurrent()
        job.cancel()

        assertEquals(1, received.size)
        assertEquals("hi", received[0].asText())
        assertEquals(0x1234, received[0].from)
    }

    @Test fun textMessagesExcludesNonTextPackets() = runTest {
        val transport = fakeTransport()
        val client = buildClient(transport)
        client.connect()

        val received = mutableListOf<MeshPacket>()
        val job = launch { client.textMessages.toList(received) }
        runCurrent()

        transport.injectPacket(
            MeshPacket(
                decoded = Data(portnum = PortNum.POSITION_APP, payload = "xyz".encodeToByteArray().toByteString()),
            ),
        )
        transport.injectPacket(
            MeshPacket(
                decoded = Data(portnum = PortNum.TELEMETRY_APP, payload = "xyz".encodeToByteArray().toByteString()),
            ),
        )
        runCurrent()
        runCurrent()
        job.cancel()

        assertEquals(0, received.size)
    }

    @Test fun textMessagesIncludesEmptyPayloadTextPackets() = runTest {
        // textMessages filters on portnum only — empty payload TEXT_MESSAGE_APP packets are included.
        // asText() returns null for those (empty payload), but the packet is still emitted.
        val transport = fakeTransport()
        val client = buildClient(transport)
        client.connect()

        val received = mutableListOf<MeshPacket>()
        val job = launch { client.textMessages.toList(received) }
        runCurrent()

        transport.injectPacket(
            MeshPacket(decoded = Data(portnum = PortNum.TEXT_MESSAGE_APP)), // no payload
        )
        runCurrent()
        runCurrent()
        job.cancel()

        assertEquals(1, received.size)
        assertNull(received[0].asText()) // empty payload => asText() is null, but packet was emitted
    }

    // ── Waypoint / Traceroute / NeighborInfo accessors ────────────────────

    @Test fun waypointDecodes() {
        val wp = org.meshtastic.proto.Waypoint(id = 42, name = "Base")
        val decoded = pkt(PortNum.WAYPOINT_APP, org.meshtastic.proto.Waypoint.ADAPTER.encode(wp)).asWaypoint()
        assertNotNull(decoded)
        assertEquals(42, decoded.id)
        assertEquals("Base", decoded.name)
    }

    @Test fun waypointWrongPortReturnsNull() {
        val wp = org.meshtastic.proto.Waypoint(id = 1)
        assertNull(pkt(PortNum.TEXT_MESSAGE_APP, org.meshtastic.proto.Waypoint.ADAPTER.encode(wp)).asWaypoint())
    }

    @Test fun tracerouteDecodes() {
        val route = org.meshtastic.proto.RouteDiscovery(route = listOf(100, 200, 300))
        val decoded = pkt(
            PortNum.TRACEROUTE_APP,
            org.meshtastic.proto.RouteDiscovery.ADAPTER.encode(route),
        ).asTraceroute()
        assertNotNull(decoded)
        assertEquals(listOf(100, 200, 300), decoded.route)
    }

    @Test fun tracerouteWrongPortReturnsNull() {
        val route = org.meshtastic.proto.RouteDiscovery(route = listOf(1))
        assertNull(pkt(PortNum.ROUTING_APP, org.meshtastic.proto.RouteDiscovery.ADAPTER.encode(route)).asTraceroute())
    }

    @Test fun neighborInfoDecodes() {
        val ni = org.meshtastic.proto.NeighborInfo(node_id = 0xABCD, last_sent_by_id = 0x1234)
        val decoded = pkt(
            PortNum.NEIGHBORINFO_APP,
            org.meshtastic.proto.NeighborInfo.ADAPTER.encode(ni),
        ).asNeighborInfo()
        assertNotNull(decoded)
        assertEquals(0xABCD, decoded.node_id)
        assertEquals(0x1234, decoded.last_sent_by_id)
    }

    @Test fun neighborInfoWrongPortReturnsNull() {
        val ni = org.meshtastic.proto.NeighborInfo(node_id = 1)
        assertNull(pkt(PortNum.TELEMETRY_APP, org.meshtastic.proto.NeighborInfo.ADAPTER.encode(ni)).asNeighborInfo())
    }
}
