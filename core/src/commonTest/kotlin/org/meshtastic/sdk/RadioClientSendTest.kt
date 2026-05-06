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
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class RadioClientSendTest {

    @Test
    fun sendMeshPacket_connectedClientReturnsHandleAndWritesPacket() = runTest {
        withConnectedClient { client, transport ->
            val before = transport.outboundPackets().size
            val packet = MeshPacket(
                to = TARGET_NODE.raw,
                channel = SECONDARY_CHANNEL.raw,
                decoded = Data(
                    portnum = PortNum.TEXT_MESSAGE_APP,
                    payload = "mesh-packet".encodeToByteArray().toByteString(),
                ),
            )

            val handle = client.send(packet)

            assertCondition(handle.id.raw != 0, "Expected non-zero message id")
            runCurrent()

            val outbound = transport.lastNewOutboundPacket(before)
            val decoded = outbound.requireDecoded()
            assertValue(handle.id.raw, outbound.id, "outbound.id")
            assertValue(TEST_NODE_NUM, outbound.from, "outbound.from")
            assertValue(TARGET_NODE.raw, outbound.to, "outbound.to")
            assertValue(SECONDARY_CHANNEL.raw, outbound.channel, "outbound.channel")
            assertValue(PortNum.TEXT_MESSAGE_APP, decoded.portnum, "decoded.portnum")
            assertContentEquals("mesh-packet".encodeToByteArray(), decoded.payload.toByteArray())
        }
    }

    @Test
    fun sendMeshPacket_notConnectedThrows() = runTest {
        val client = buildClient()

        assertFailsWith<MeshtasticException.NotConnected> {
            client.send(
                MeshPacket(
                    decoded = Data(
                        portnum = PortNum.TEXT_MESSAGE_APP,
                        payload = "offline".encodeToByteArray().toByteString(),
                    ),
                ),
            )
        }
    }

    @Test
    fun sendMeshPacket_payloadTooLargeThrows() = runTest {
        withConnectedClient { client, _ ->
            val oversized = MeshPacket(
                decoded = Data(
                    portnum = PortNum.TEXT_MESSAGE_APP,
                    payload = ByteArray(DATA_PAYLOAD_LEN + 1).toByteString(),
                ),
            )

            assertFailsWith<MeshtasticException.PayloadTooLarge> {
                client.send(oversized)
            }
        }
    }

    @Test
    fun sendText_connectedClientEncodesUtf8AndTargetsChannel() = runTest {
        withConnectedClient { client, transport ->
            val before = transport.outboundPackets().size
            val text = "héllo 🚀"

            val handle = client.sendText(text, channel = SECONDARY_CHANNEL, to = TARGET_NODE)

            assertCondition(handle.id.raw != 0, "Expected non-zero message id")
            runCurrent()

            val outbound = transport.lastNewOutboundPacket(before)
            val decoded = outbound.requireDecoded()
            assertValue(handle.id.raw, outbound.id, "outbound.id")
            assertValue(TARGET_NODE.raw, outbound.to, "outbound.to")
            assertValue(SECONDARY_CHANNEL.raw, outbound.channel, "outbound.channel")
            assertValue(PortNum.TEXT_MESSAGE_APP, decoded.portnum, "decoded.portnum")
            assertContentEquals(text.encodeToByteArray(), decoded.payload.toByteArray())
        }
    }

    @Test
    fun sendText_notConnectedThrows() = runTest {
        val client = buildClient()

        assertFailsWith<MeshtasticException.NotConnected> {
            client.sendText("hello")
        }
    }

    @Test
    fun sendText_payloadTooLargeThrows() = runTest {
        withConnectedClient { client, _ ->
            val oversized = "a".repeat(DATA_PAYLOAD_LEN + 1)

            assertFailsWith<MeshtasticException.PayloadTooLarge> {
                client.sendText(oversized)
            }
        }
    }

    @Test
    fun sendReaction_connectedClientMarksEmojiReplyAndAck() = runTest {
        withConnectedClient { client, transport ->
            val before = transport.outboundPackets().size
            val replyId = 0x01020304
            val emoji = "🔥"

            val handle = client.sendReaction(
                emoji = emoji,
                to = TARGET_NODE,
                channel = SECONDARY_CHANNEL,
                replyId = replyId,
            )

            assertCondition(handle.id.raw != 0, "Expected non-zero message id")
            runCurrent()

            val outbound = transport.lastNewOutboundPacket(before)
            val decoded = outbound.requireDecoded()
            assertValue(handle.id.raw, outbound.id, "outbound.id")
            assertValue(TARGET_NODE.raw, outbound.to, "outbound.to")
            assertValue(SECONDARY_CHANNEL.raw, outbound.channel, "outbound.channel")
            assertCondition(outbound.want_ack, "Expected reaction packet to request ACK")
            assertValue(PortNum.TEXT_MESSAGE_APP, decoded.portnum, "decoded.portnum")
            assertContentEquals(emoji.encodeToByteArray(), decoded.payload.toByteArray())
            assertValue(1, decoded.emoji, "decoded.emoji")
            assertValue(replyId, decoded.reply_id, "decoded.reply_id")
        }
    }

    @Test
    fun sendReaction_notConnectedThrows() = runTest {
        val client = buildClient()

        assertFailsWith<MeshtasticException.NotConnected> {
            client.sendReaction(emoji = "👍", replyId = 42)
        }
    }

    @Test
    fun sendReaction_payloadTooLargeThrows() = runTest {
        withConnectedClient { client, _ ->
            val oversized = "a".repeat(DATA_PAYLOAD_LEN + 1)

            assertFailsWith<MeshtasticException.PayloadTooLarge> {
                client.sendReaction(emoji = oversized, replyId = 7)
            }
        }
    }

    @Test
    fun sendByteArray_connectedClientBuildsTypedPacket() = runTest {
        withConnectedClient { client, transport ->
            val before = transport.outboundPackets().size
            val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)

            val handle = client.send(
                portnum = PortNum.NODEINFO_APP,
                payload = payload,
                to = TARGET_NODE,
                channel = SECONDARY_CHANNEL,
                wantAck = true,
                hopLimit = 4,
            )

            assertCondition(handle.id.raw != 0, "Expected non-zero message id")
            runCurrent()

            val outbound = transport.lastNewOutboundPacket(before)
            val decoded = outbound.requireDecoded()
            assertValue(handle.id.raw, outbound.id, "outbound.id")
            assertValue(TARGET_NODE.raw, outbound.to, "outbound.to")
            assertValue(SECONDARY_CHANNEL.raw, outbound.channel, "outbound.channel")
            assertCondition(outbound.want_ack, "Expected typed packet to request ACK")
            assertValue(4, outbound.hop_limit, "outbound.hop_limit")
            assertValue(PortNum.NODEINFO_APP, decoded.portnum, "decoded.portnum")
            assertContentEquals(payload, decoded.payload.toByteArray())
            assertCondition(!decoded.want_response, "Expected typed send want_response=false")
        }
    }

    @Test
    fun sendByteArray_notConnectedThrows() = runTest {
        val client = buildClient()

        assertFailsWith<MeshtasticException.NotConnected> {
            client.send(PortNum.TEXT_MESSAGE_APP, "hello".encodeToByteArray())
        }
    }

    @Test
    fun sendByteArray_payloadTooLargeThrows() = runTest {
        withConnectedClient { client, _ ->
            assertFailsWith<MeshtasticException.PayloadTooLarge> {
                client.send(PortNum.TEXT_MESSAGE_APP, ByteArray(DATA_PAYLOAD_LEN + 1))
            }
        }
    }

    @Test
    fun sendBuffer_connectedClientConsumesBufferAndWritesBytes() = runTest {
        withConnectedClient { client, transport ->
            val before = transport.outboundPackets().size
            val payload = byteArrayOf(0x0A, 0x0B, 0x0C)
            val buffer = Buffer().apply { write(payload) }

            val handle = client.send(
                portnum = PortNum.NODEINFO_APP,
                payload = buffer,
                to = TARGET_NODE,
                channel = SECONDARY_CHANNEL,
                wantAck = true,
                hopLimit = 5,
            )

            assertCondition(handle.id.raw != 0, "Expected non-zero message id")
            assertContentEquals(byteArrayOf(), buffer.readByteArray())
            runCurrent()

            val outbound = transport.lastNewOutboundPacket(before)
            val decoded = outbound.requireDecoded()
            assertValue(handle.id.raw, outbound.id, "outbound.id")
            assertValue(TARGET_NODE.raw, outbound.to, "outbound.to")
            assertValue(SECONDARY_CHANNEL.raw, outbound.channel, "outbound.channel")
            assertCondition(outbound.want_ack, "Expected typed packet to request ACK")
            assertValue(5, outbound.hop_limit, "outbound.hop_limit")
            assertValue(PortNum.NODEINFO_APP, decoded.portnum, "decoded.portnum")
            assertContentEquals(payload, decoded.payload.toByteArray())
        }
    }

    @Test
    fun sendBuffer_notConnectedThrows() = runTest {
        val client = buildClient()
        val buffer = Buffer().apply { write("hello".encodeToByteArray()) }

        assertFailsWith<MeshtasticException.NotConnected> {
            client.send(PortNum.TEXT_MESSAGE_APP, buffer)
        }
    }

    @Test
    fun sendBuffer_payloadTooLargeThrows() = runTest {
        withConnectedClient { client, _ ->
            val buffer = Buffer().apply { write(ByteArray(DATA_PAYLOAD_LEN + 1)) }

            assertFailsWith<MeshtasticException.PayloadTooLarge> {
                client.send(PortNum.TEXT_MESSAGE_APP, buffer)
            }
        }
    }

    @Test
    fun sendRaw_connectedClientWritesFrameDirectlyToTransport() = runTest {
        withConnectedClient { client, transport ->
            val before = transport.outboundFrames().size
            val frame = ToRadio(disconnect = true)

            client.sendRaw(frame)
            runCurrent()

            val outbound = transport.lastNewOutboundFrame(before).decodeToRadio()
            assertValue(frame, outbound, "outbound ToRadio frame")
        }
    }

    @Test
    fun sendRaw_notConnectedThrows() = runTest {
        val client = buildClient()

        assertFailsWith<MeshtasticException.NotConnected> {
            client.sendRaw(ToRadio(disconnect = true))
        }
    }

    @Test
    fun requestNodeInfo_connectedClientReturnsHandleWithOutboundId() = runTest {
        withConnectedClient { client, transport ->
            val before = transport.outboundPackets().size

            val handle = client.requestNodeInfo(TARGET_NODE)

            assertCondition(handle.id.raw != 0, "Expected non-zero message id")
            runCurrent()

            val outbound = transport.lastNewOutboundPacket(before)
            assertValue(handle.id.raw, outbound.id, "outbound.id")
            assertValue(TEST_NODE_NUM, outbound.from, "outbound.from")
        }
    }

    @Test
    fun requestNodeInfo_connectedClientSendsNodeInfoRequestPacket() = runTest {
        withConnectedClient { client, transport ->
            val before = transport.outboundPackets().size

            client.requestNodeInfo(TARGET_NODE)
            runCurrent()

            val outbound = transport.lastNewOutboundPacket(before)
            val decoded = outbound.requireDecoded()
            assertValue(TARGET_NODE.raw, outbound.to, "outbound.to")
            assertValue(ChannelIndex(0).raw, outbound.channel, "outbound.channel")
            assertCondition(outbound.want_ack, "Expected node info request to request ACK")
            assertValue(PortNum.NODEINFO_APP, decoded.portnum, "decoded.portnum")
            assertCondition(decoded.want_response, "Expected node info request want_response=true")
            assertContentEquals(byteArrayOf(), decoded.payload.toByteArray())
        }
    }

    @Test
    fun requestNodeInfo_notConnectedThrows() = runTest {
        val client = buildClient()

        assertFailsWith<MeshtasticException.NotConnected> {
            client.requestNodeInfo(TARGET_NODE)
        }
    }

    private fun TestScope.buildClient(transport: FakeRadioTransport = buildTransport()): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .coroutineContext(backgroundScope.coroutineContext)
        .autoSyncTimeOnConnect(false)
        .build()

    private suspend fun TestScope.withConnectedClient(block: suspend (RadioClient, FakeRadioTransport) -> Unit) {
        val transport = buildTransport()
        val client = buildClient(transport)
        client.connect()
        runCurrent()
        assertValue(ConnectionState.Connected, client.connection.value, "client.connection")
        try {
            block(client, transport)
        } finally {
            client.disconnect()
            runCurrent()
        }
    }

    private fun buildTransport(): FakeRadioTransport = FakeRadioTransport(
        identity = TransportIdentity("fake:test"),
        autoHandshake = true,
        nodeNum = TEST_NODE_NUM,
    )

    private fun FakeRadioTransport.lastNewOutboundPacket(before: Int): MeshPacket {
        val outbound = outboundPackets()
        assertValue(before + 1, outbound.size, "outboundPackets().size")
        return outbound.last()
    }

    private fun FakeRadioTransport.lastNewOutboundFrame(before: Int): Frame {
        val outbound = outboundFrames()
        assertValue(before + 1, outbound.size, "outboundFrames().size")
        return outbound.last()
    }

    private fun Frame.decodeToRadio(): ToRadio {
        val bytes = bytes.toByteArray()
        if (bytes.size < 4) fail("Expected framed ToRadio bytes")
        return runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }
            .getOrElse { throw AssertionError("Failed to decode ToRadio frame", it) }
    }

    private fun MeshPacket.requireDecoded(): Data = decoded ?: fail("Expected decoded payload")

    private fun assertCondition(condition: Boolean, message: String) {
        if (!condition) fail(message)
    }

    private fun <T> assertValue(expected: T, actual: T, label: String) {
        if (expected != actual) {
            fail("Expected $label=$expected, actual=$actual")
        }
    }

    private companion object {
        const val TEST_NODE_NUM: Int = 0x11111111
        val TARGET_NODE: NodeId = NodeId(0x22222222)
        val SECONDARY_CHANNEL: ChannelIndex = ChannelIndex(2)
    }
}
