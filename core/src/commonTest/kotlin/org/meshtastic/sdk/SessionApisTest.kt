/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MqttClientProxyMessage
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.Paxcount
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.RemoteShell
import org.meshtastic.proto.StoreAndForward
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SessionApisTest {

    private val own = 0x11111111
    private val peer = 0x22222222
    private val now = 1_700_000_500L
    private val peerKey = ByteString.of(*ByteArray(32) { it.toByte() })

    /** Differs from the firmware fallback of 3, so a hardcoded default fails. */
    private val deviceHopLimit = 5

    @Test
    fun requestNodeInfoOnHeardChannelCarriesOurUser() = runTest {
        val (transport, client) = connectedClient()
        try {
            learnOwnUser(transport, client)
            hearPeer(transport, channel = 2)
            client.requestNodeInfoOnHeardChannel(NodeId(peer))
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(peer, sent.to)
            assertEquals(2, sent.channel)
            assertFalse(sent.want_ack)
            assertEquals(deviceHopLimit, sent.hop_limit)
            assertEquals(PortNum.NODEINFO_APP, sent.decoded?.portnum)
            assertEquals(true, sent.decoded?.want_response)
            assertEquals("FakeNode", User.ADAPTER.decode(sent.decoded!!.payload).long_name)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun requestNodeInfoWithNullUserSendsEmptyPayloadEvenWhenOurUserIsKnown() = runTest {
        val (transport, client) = connectedClient()
        try {
            learnOwnUser(transport, client)
            client.requestNodeInfo(NodeId(peer), ChannelIndex(1), user = null)
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(1, sent.channel)
            assertEquals(0, sent.decoded?.payload?.size)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun requestNodeInfoRejectsOurOwnNode() = runTest {
        val (_, client) = connectedClient()
        try {
            assertFailsWith<IllegalArgumentException> { client.requestNodeInfo(NodeId(own), ChannelIndex(0)) }
            assertFailsWith<IllegalArgumentException> { client.requestNodeInfo(NodeId.LOCAL, ChannelIndex(0)) }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun requestPositionCarryingPositionStampsTimeAndCoordinates() = runTest {
        val (transport, client) = connectedClient()
        try {
            hearPeer(transport, channel = 3)
            val mine = Position.Builder().also { wb ->
                wb.latitude_i = 375_000_000
                wb.longitude_i = -1_220_000_000
                wb.altitude = 12
            }.build()
            client.requestPositionOnHeardChannel(NodeId(peer), mine)
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(3, sent.channel)
            assertEquals(MeshPacket.Priority.BACKGROUND, sent.priority)
            assertFalse(sent.want_ack)
            assertEquals(deviceHopLimit, sent.hop_limit)
            assertEquals(true, sent.decoded?.want_response)
            val payload = Position.ADAPTER.decode(sent.decoded!!.payload)
            assertEquals(375_000_000, payload.latitude_i)
            assertEquals(-1_220_000_000, payload.longitude_i)
            assertEquals(12, payload.altitude)
            assertEquals(now.toInt(), payload.time)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun requestPositionWithNullPositionKeepsTheEmptyPayload() = runTest {
        val (transport, client) = connectedClient()
        try {
            client.requestPosition(NodeId(peer), ChannelIndex(2), null)
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(2, sent.channel)
            assertEquals(0, sent.decoded?.payload?.size)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun aPlainPositionRequestCarriesTheDeviceHopLimit() = runTest {
        val (transport, client) = connectedClient()
        try {
            client.requestPosition(NodeId(peer))
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(PortNum.POSITION_APP, sent.decoded?.portnum)
            assertEquals(false, sent.want_ack)
            assertEquals(deviceHopLimit, sent.hop_limit)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun requestPaxcountSendsEmptyPaxcountWithDest() = runTest {
        val (transport, client) = connectedClient()
        try {
            hearPeer(transport, channel = 1)
            client.requestPaxcount(NodeId(peer))
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(peer, sent.to)
            assertEquals(1, sent.channel)
            assertFalse(sent.want_ack)
            assertEquals(PortNum.PAXCOUNTER_APP, sent.decoded?.portnum)
            assertEquals(true, sent.decoded?.want_response)
            assertEquals(deviceHopLimit, sent.hop_limit)
            assertEquals(peer, sent.decoded?.dest)
            assertEquals(Paxcount.Builder().build(), Paxcount.ADAPTER.decode(sent.decoded!!.payload))
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun telemetryRequestsNameTheirVariantAndSetDestAndHopLimit() = runTest {
        val (transport, client) = connectedClient()
        try {
            hearPeer(transport, channel = 2)
            assertTelemetryRequest(transport, { client.telemetry.requestDevice(NodeId(peer)) }) {
                it.device_metrics != null
            }
            assertTelemetryRequest(transport, { client.telemetry.requestEnvironment(NodeId(peer)) }) {
                it.environment_metrics != null
            }
            assertTelemetryRequest(transport, { client.telemetry.requestAirQuality(NodeId(peer)) }) {
                it.air_quality_metrics != null
            }
            assertTelemetryRequest(transport, { client.telemetry.requestPower(NodeId(peer)) }) {
                it.power_metrics != null
            }
            assertTelemetryRequest(transport, { client.telemetry.requestHost(NodeId(peer)) }) {
                it.host_metrics != null
            }
            assertTelemetryRequest(transport, { client.telemetry.requestHealth(NodeId(peer)) }) {
                it.health_metrics != null
            }
            assertTelemetryRequest(transport, { client.telemetry.requestTrafficManagement(NodeId(peer)) }) {
                it.traffic_management_stats != null
            }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun localStatsRequestGoesToOurNodeWithLocalStatsVariant() = runTest {
        val (transport, client) = connectedClient()
        try {
            val pending = async { client.telemetry.requestLocalStats() }
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(own, sent.to)
            assertEquals(own, sent.decoded?.dest)
            assertNotNull(Telemetry.ADAPTER.decode(sent.decoded!!.payload).local_stats)
            pending.cancel()
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun requestLocalHistorySendsClientHistoryToOurOwnNode() = runTest {
        val (transport, client) = connectedClient()
        try {
            client.storeForward.requestLocalHistory(lastRequest = 42, windowMinutes = 1440, maxMessages = -5)
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(own, sent.to)
            assertEquals(own, sent.from)
            assertEquals(MeshPacket.Priority.BACKGROUND, sent.priority)
            assertFalse(sent.want_ack)
            assertEquals(0, sent.hop_limit)
            assertEquals(PortNum.STORE_FORWARD_APP, sent.decoded?.portnum)
            assertEquals(false, sent.decoded?.want_response)
            val request = StoreAndForward.ADAPTER.decode(sent.decoded!!.payload)
            assertEquals(StoreAndForward.RequestResponse.CLIENT_HISTORY, request.rr)
            assertEquals(42, request.history?.last_request)
            assertEquals(1440, request.history?.window)
            assertEquals(0, request.history?.history_messages)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun remoteShellSendIsPkiEncryptedWithTheStoredKey() = runTest {
        val (transport, client) = connectedClient()
        try {
            hearPeer(transport, channel = 2, publicKey = peerKey)
            val frame = RemoteShell.Builder().also { wb ->
                wb.op = RemoteShell.OpCode.OPEN
                wb.session_id = 7
            }.build()
            client.remoteShell.send(NodeId(peer), frame)
            runCurrent()
            val sent = transport.outboundPackets().last()
            assertEquals(peer, sent.to)
            assertEquals(0, sent.channel)
            assertTrue(sent.pki_encrypted)
            assertEquals(peerKey, sent.public_key)
            assertFalse(sent.want_ack)
            assertEquals(PortNum.REMOTE_SHELL_APP, sent.decoded?.portnum)
            assertEquals(frame, RemoteShell.ADAPTER.decode(sent.decoded!!.payload))
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun remoteShellSendWithoutAStoredKeyThrows() = runTest {
        val (transport, client) = connectedClient()
        try {
            hearPeer(transport, channel = 0)
            assertFailsWith<MeshtasticException.Protocol> {
                client.remoteShell.send(NodeId(peer), RemoteShell.Builder().build())
            }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun remoteShellFramesDecodeOnlyThatNodesPackets() = runTest {
        val (transport, client) = connectedClient()
        try {
            val received = async { client.remoteShell.frames(NodeId(peer)).first() }
            runCurrent()
            injectShell(transport, from = 0x33333333, sessionId = 1)
            injectShell(transport, from = peer, sessionId = 2)
            runCurrent()
            assertEquals(2, received.await().session_id)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun mqttProxyEmitsDeviceMessagesAndDeliversBrokerMessages() = runTest {
        val (transport, client) = connectedClient()
        try {
            val message = MqttClientProxyMessage.Builder().also { wb ->
                wb.topic = "msh/US/2/e/LongFast/!11111111"
                wb.text = "hello broker"
            }.build()
            val received = async { client.mqttProxy.messages.first() }
            runCurrent()
            transport.injectFrame(
                FromRadio.Builder().also { wb ->
                    wb.mqttClientProxyMessage = message
                }.build().toFrame(),
            )
            runCurrent()
            assertEquals(message, received.await())

            assertTrue(client.mqttProxy.deliver(message))
            runCurrent()
            val written = transport.outboundFrames().mapNotNull { frame ->
                val bytes = frame.bytes.toByteArray()
                runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull()
            }.last()
            assertEquals(message, written.mqttClientProxyMessage)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun sendPacedDslBuildsTheSamePacketAsSend() = runTest {
        val (transport, client) = connectedClient(autoQueueStatus = true)
        try {
            val paced = client.sendPaced {
                text("hello")
                to(NodeId(peer))
                channel(ChannelIndex(2))
                wantAck()
            }
            runCurrent()
            assertTrue(paced.awaitDispatch())
            val sent = transport.outboundPackets().last()
            assertEquals(paced.message.id.raw, sent.id)
            assertEquals(peer, sent.to)
            assertEquals(2, sent.channel)
            assertTrue(sent.want_ack)
            assertEquals(deviceHopLimit, sent.hop_limit)
            assertEquals(PortNum.TEXT_MESSAGE_APP, sent.decoded?.portnum)
            assertEquals("hello", sent.decoded?.payload?.utf8())
        } finally {
            client.disconnect()
        }
    }

    private fun TestScope.assertTelemetryRequest(
        transport: FakeRadioTransport,
        call: suspend () -> Any,
        hasVariant: (Telemetry) -> Boolean,
    ) {
        val pending = async { call() }
        runCurrent()
        val sent = transport.outboundPackets().last()
        assertEquals(peer, sent.to)
        assertEquals(2, sent.channel)
        assertEquals(deviceHopLimit, sent.hop_limit)
        assertEquals(PortNum.TELEMETRY_APP, sent.decoded?.portnum)
        assertEquals(true, sent.decoded?.want_response)
        assertEquals(peer, sent.decoded?.dest)
        assertTrue(hasVariant(Telemetry.ADAPTER.decode(sent.decoded!!.payload)))
        pending.cancel()
    }

    private fun TestScope.learnOwnUser(transport: FakeRadioTransport, client: RadioClient) {
        val ownUser = User.Builder().also { wb ->
            wb.long_name = "FakeNode"
            wb.short_name = "FN"
        }.build()
        transport.injectFrame(
            FromRadio.Builder().also { wb ->
                wb.node_info = NodeInfo.Builder().also { n ->
                    n.num = own
                    n.user = ownUser
                }.build()
            }.build().toFrame(),
        )
        runCurrent()
        assertEquals(ownUser, client.ownNode.value?.user)
    }

    private fun TestScope.hearPeer(transport: FakeRadioTransport, channel: Int, publicKey: ByteString? = null) {
        val user = User.Builder().also { wb ->
            wb.long_name = "Peer"
            wb.short_name = "PR"
            if (publicKey != null) wb.public_key = publicKey
        }.build()
        transport.injectPacket(
            MeshPacket.Builder().also { wb ->
                wb.from = peer
                wb.to = 0
                wb.channel = channel
                wb.rx_time = 1_700_000_100
                wb.decoded = Data.Builder().also { d ->
                    d.portnum = PortNum.NODEINFO_APP
                    d.payload = User.ADAPTER.encode(user).toByteString()
                }.build()
            }.build(),
        )
        runCurrent()
    }

    private fun TestScope.injectShell(transport: FakeRadioTransport, from: Int, sessionId: Int) {
        val frame = RemoteShell.Builder().also { wb -> wb.session_id = sessionId }.build()
        transport.injectPacket(
            MeshPacket.Builder().also { wb ->
                wb.from = from
                wb.to = own
                wb.decoded = Data.Builder().also { d ->
                    d.portnum = PortNum.REMOTE_SHELL_APP
                    d.payload = RemoteShell.ADAPTER.encode(frame).toByteString()
                }.build()
            }.build(),
        )
    }

    private suspend fun TestScope.connectedClient(
        autoQueueStatus: Boolean = false,
    ): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:session-apis"),
            frames = listOf(
                FromRadio.Builder().also { wb ->
                    wb.metadata = DeviceMetadata.Builder().also { wb -> wb.firmware_version = "2.7.0" }.build()
                }.build().toFrame(),
                FromRadio.Builder().also { wb ->
                    wb.config = Config.Builder().also { wb ->
                        wb.lora = Config.LoRaConfig.Builder().also { wb -> wb.hop_limit = deviceHopLimit }.build()
                    }.build()
                }.build().toFrame(),
            ),
            autoHandshake = true,
            nodeNum = own,
            autoQueueStatus = autoQueueStatus,
        )
        val fixed = object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(now)
        }
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .clock(fixed)
            .autoSyncTimeOnConnect(false)
            .build()
        client.connect()
        runCurrent()
        return transport to client
    }
}
