/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Waypoint
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WaypointsTest {

    private val own = 0x11111111
    private val now = Instant.fromEpochSeconds(1_700_000_000)

    private fun waypoint(id: Int = 7, expire: Int = 0, lockedTo: Int = 0) = Waypoint.Builder().also { wb ->
        wb.id = id
        wb.expire = expire
        wb.locked_to = lockedTo
        wb.name = "camp"
    }.build()

    @Test
    fun expireZeroNeverExpires() {
        assertFalse(waypoint(expire = 0).isExpired(now))
    }

    @Test
    fun expireOneIsDeleted() {
        assertTrue(waypoint(expire = 1).isExpired(now))
    }

    @Test
    fun expiryBoundaryIsInclusive() {
        val at = now.epochSeconds.toInt()
        assertFalse(waypoint(expire = at + 1).isExpired(now))
        assertTrue(waypoint(expire = at).isExpired(now))
        assertTrue(waypoint(expire = at - 1).isExpired(now))
    }

    @Test
    fun expireAboveIntMaxIsReadAsUnsigned() {
        assertFalse(waypoint(expire = -1).isExpired(now))
    }

    @Test
    fun unlockedWaypointIsEditableByAnyone() {
        assertTrue(waypoint(lockedTo = 0).canBeEditedBy(NodeId(5)))
    }

    @Test
    fun lockedWaypointIsEditableOnlyByItsOwner() {
        val locked = waypoint(lockedTo = 0x33333333)
        assertTrue(locked.canBeEditedBy(NodeId(0x33333333)))
        assertFalse(locked.canBeEditedBy(NodeId(own)))
    }

    @Test
    fun newWaypointIdSkipsZero() {
        val zeroThenFive = object : Random() {
            private var calls = 0
            override fun nextBits(bitCount: Int): Int = if (calls++ == 0) 0 else 5
        }
        assertEquals(5, newWaypointId(zeroThenFive))
        repeat(200) { assertNotEquals(0, newWaypointId()) }
    }

    @Test
    fun sendWaypointUsesWaypointPortAndEncodesPayload() = runTest {
        val (transport, client) = connectedClient()
        try {
            val wp = waypoint(id = 42, expire = 1_800_000_000)
            client.sendWaypoint(wp, channel = ChannelIndex(2))
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(PortNum.WAYPOINT_APP, sent.decoded?.portnum)
            assertEquals(2, sent.channel)
            assertEquals(wp, Waypoint.ADAPTER.decode(sent.decoded!!.payload))
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun deleteWaypointSendsSameWaypointWithExpireOne() = runTest {
        val (transport, client) = connectedClient()
        try {
            val wp = waypoint(id = 42, expire = 1_800_000_000)
            client.deleteWaypoint(wp)
            runCurrent()
            val sent = transport.outboundPackets().last()
            val expected = wp.newBuilder().also { wb -> wb.expire = 1 }.build()
            assertEquals(Waypoint.ADAPTER.encode(expected).toList(), sent.decoded!!.payload.toByteArray().toList())
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun sendWaypointRejectsIdZero() = runTest {
        val (_, client) = connectedClient()
        try {
            assertFailsWith<IllegalArgumentException> { client.sendWaypoint(waypoint(id = 0)) }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun sendWaypointRejectsWaypointLockedToAnotherNode() = runTest {
        val (_, client) = connectedClient()
        try {
            assertFailsWith<MeshtasticException.Protocol> {
                client.sendWaypoint(waypoint(lockedTo = 0x33333333))
            }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun sendWaypointAllowsWaypointLockedToOurNode() = runTest {
        val (transport, client) = connectedClient()
        try {
            transport.injectPacket(
                MeshPacket.Builder().also { wb ->
                    wb.from = own
                    wb.decoded = Data.Builder().also { d ->
                        d.portnum = PortNum.TEXT_MESSAGE_APP
                        d.payload = ByteString.EMPTY
                    }.build()
                }.build(),
            )
            runCurrent()
            client.sendWaypoint(waypoint(lockedTo = own))
            runCurrent()
            assertEquals(PortNum.WAYPOINT_APP, transport.outboundPackets().last().decoded?.portnum)
        } finally {
            client.disconnect()
        }
    }

    private suspend fun TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:waypoints"),
            autoHandshake = true,
            nodeNum = own,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        client.connect()
        runCurrent()
        return transport to client
    }
}
