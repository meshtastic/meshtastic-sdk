/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Routing results for any packet id, including ones sent before this session, so a host settles its stored
 * messages without decoding ROUTING_APP. Packet shapes follow firmware: `MeshModule::allocAckNak` builds every
 * ACK/NAK, an implicit ACK copies the overheard rebroadcast's `relay_node`, and a first transmission carries the
 * sender's own byte (`FloodingRouter::send`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingReportTest {

    @Test
    fun `a recipient ACK relayed once reports the relay and the proof`() = runTest {
        withClient { transport, reports, _ ->
            transport.injectPacket(
                routing(from = PEER, requestId = EARLIER_SESSION_ID, error = Routing.Error.NONE) {
                    hop_start = 3
                    hop_limit = 2
                    relay_node = RELAY_BYTE
                    ack_proof_status = MeshPacket.AckProofStatus.ACK_PROOF_VALID
                },
            )
            runCurrent()

            val report = reports.single()
            assertEquals(MessageId(EARLIER_SESSION_ID), report.requestId)
            assertEquals(NodeId(PEER), report.from)
            assertTrue(report.isAck)
            assertFalse(report.fromLocalRadio)
            assertEquals(RELAY_BYTE, report.relayNode)
            assertEquals(MeshPacket.AckProofStatus.ACK_PROOF_VALID, report.ackProof)
            assertFalse(report.isImplicitAckFor(NodeId(PEER)))
        }
    }

    // A gateway rebroadcast keeps via_mqtt and stamps its own byte; our radio marks it TRANSPORT_LORA.
    @Test
    fun `an ACK that crossed MQTT then arrived over LoRa names its relay`() = runTest {
        withClient { transport, reports, _ ->
            transport.injectPacket(
                routing(from = PEER, requestId = EARLIER_SESSION_ID, error = Routing.Error.NONE) {
                    hop_start = 3
                    hop_limit = 1
                    relay_node = RELAY_BYTE
                    via_mqtt = true
                    transport_mechanism = MeshPacket.TransportMechanism.TRANSPORT_LORA
                },
            )
            runCurrent()

            assertEquals(RELAY_BYTE, reports.single().relayNode)
        }
    }

    // Firmware before 2.7.5 sends no transport, so via_mqtt alone means the byte is not an RF hop.
    @Test
    fun `an MQTT ACK without a transport names no relay`() = runTest {
        withClient { transport, reports, _ ->
            transport.injectPacket(
                routing(from = PEER, requestId = EARLIER_SESSION_ID, error = Routing.Error.NONE) {
                    hop_start = 3
                    hop_limit = 1
                    relay_node = RELAY_BYTE
                    via_mqtt = true
                },
            )
            runCurrent()

            assertNull(reports.single().relayNode)
        }
    }

    @Test
    fun `an ACK heard with no hops used names no relay`() = runTest {
        withClient { transport, reports, _ ->
            transport.injectPacket(
                routing(from = PEER, requestId = EARLIER_SESSION_ID, error = Routing.Error.NONE) {
                    hop_start = 3
                    hop_limit = 3
                    relay_node = PEER and LAST_BYTE
                },
            )
            runCurrent()

            assertNull(reports.single().relayNode)
        }
    }

    @Test
    fun `an implicit ACK comes from our radio and keeps the relayer byte`() = runTest {
        withClient { transport, reports, _ ->
            transport.injectPacket(
                routing(from = OWN_NODE, requestId = EARLIER_SESSION_ID, error = Routing.Error.NONE) {
                    relay_node = RELAY_BYTE
                },
            )
            runCurrent()

            val report = reports.single()
            assertTrue(report.fromLocalRadio)
            assertEquals(RELAY_BYTE, report.relayNode)
            assertTrue(report.isImplicitAckFor(NodeId(PEER)))
            assertTrue(report.isImplicitAckFor(NodeId.BROADCAST))
            assertFalse(report.isImplicitAckFor(NodeId(OWN_NODE)))
        }
    }

    @Test
    fun `a NAK carries its error reason`() = runTest {
        withClient { transport, reports, _ ->
            transport.injectPacket(
                routing(from = OWN_NODE, requestId = EARLIER_SESSION_ID, Routing.Error.MAX_RETRANSMIT),
            )
            runCurrent()

            val report = reports.single()
            assertEquals(Routing.Error.MAX_RETRANSMIT, report.error)
            assertFalse(report.isAck)
            assertNull(report.relayNode)
        }
    }

    @Test
    fun `an ACK addressed to another node is not ours to report`() = runTest {
        withClient { transport, reports, _ ->
            transport.injectPacket(
                routing(from = PEER, requestId = EARLIER_SESSION_ID, error = Routing.Error.NONE) { to = OTHER },
            )
            runCurrent()

            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun `a routing packet without a request id is not reported`() = runTest {
        withClient { transport, reports, _ ->
            transport.injectPacket(routing(from = PEER, requestId = 0, error = Routing.Error.NONE))
            runCurrent()

            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun `the session stream carries the report after its routing packet`() = runTest {
        withClient { transport, _, session ->
            transport.injectPacket(routing(from = PEER, requestId = EARLIER_SESSION_ID, error = Routing.Error.NONE))
            runCurrent()

            val packetAt = session.indexOfFirst {
                it is RadioSessionEvent.Packet && it.packet.decoded?.portnum == PortNum.ROUTING_APP
            }
            val reportAt = session.indexOfFirst { it is RadioSessionEvent.Event && it.event is MeshEvent.RoutingReport }
            assertTrue(packetAt >= 0 && reportAt > packetAt, "report follows its packet: $session")
            val event = assertIs<RadioSessionEvent.Event>(session[reportAt]).event
            assertEquals(MessageId(EARLIER_SESSION_ID), assertIs<MeshEvent.RoutingReport>(event).requestId)
        }
    }

    private fun routing(
        from: Int,
        requestId: Int,
        error: Routing.Error,
        extra: MeshPacket.Builder.() -> Unit = {},
    ): MeshPacket = MeshPacket.Builder().also { wb ->
        wb.from = from
        wb.to = OWN_NODE
        wb.id = requestId + 1
        wb.priority = MeshPacket.Priority.ACK
        wb.decoded = Data.Builder().also { d ->
            d.portnum = PortNum.ROUTING_APP
            d.request_id = requestId
            d.payload = Routing.ADAPTER.encode(Routing.Builder().also { r -> r.error_reason = error }.build())
                .toByteString()
        }.build()
        wb.extra()
    }.build()

    private suspend fun TestScope.withClient(
        block: suspend (FakeRadioTransport, List<MeshEvent.RoutingReport>, List<RadioSessionEvent>) -> Unit,
    ) {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:routing-report"),
            autoHandshake = true,
            nodeNum = OWN_NODE,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .sessionReceive()
            .build()
        val session = mutableListOf<RadioSessionEvent>()
        backgroundScope.launch { client.sessionEvents.collect { session += it } }
        val reports = mutableListOf<MeshEvent.RoutingReport>()
        backgroundScope.launch { client.events.collect { if (it is MeshEvent.RoutingReport) reports += it } }
        client.connect()
        runCurrent()
        try {
            block(transport, reports, session)
        } finally {
            client.disconnect()
            runCurrent()
        }
    }

    private companion object {
        const val OWN_NODE = 0x11111111
        const val PEER = 0x22222222
        const val OTHER = 0x33333333
        const val EARLIER_SESSION_ID = 0x5eed0001
        const val RELAY_BYTE = 0x44
        const val LAST_BYTE = 0xff
    }
}
