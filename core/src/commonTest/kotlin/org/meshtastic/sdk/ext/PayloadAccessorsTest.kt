/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PayloadAccessorsTest {
    private fun pkt(port: PortNum, payload: ByteArray) =
        MeshPacket(decoded = Data(portnum = port, payload = payload.toByteString()))

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
}
