/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MqttClientProxyMessage
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.User
import org.meshtastic.proto.XModem
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behaviour added for the Meshtastic-Android hard cutover (0.2.0): typed auxiliary-frame
 * events, QueueStatus fast-fail, threaded-reply sendText, and PKC / admin-channel routing
 * for remote admin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCutoverPrereqsTest {

    @Test
    fun sendTextCarriesReplyIdForThreadedReplies() = runTest {
        withConnectedClient { client, transport ->
            val before = transport.outboundPackets().size

            client.sendText("on my way", to = REMOTE_NODE, replyId = 42)
            runCurrent()

            val outbound = transport.outboundPackets().drop(before).last()
            val decoded = assertNotNull(outbound.decoded)
            assertEquals(42, decoded.reply_id)
            assertEquals(0, decoded.emoji, "A threaded reply is not an emoji reaction")
        }
    }

    @Test
    fun queueStatusRejectionFastFailsTheHandle() = runTest {
        withConnectedClient { client, transport ->
            val handle = client.sendText("burst")
            runCurrent()
            assertEquals(SendState.Sent, handle.state.value)

            transport.injectFrame(
                frameOf(FromRadio(queueStatus = QueueStatus(res = 9, mesh_packet_id = handle.id.raw))),
            )
            runCurrent()

            val state = assertIs<SendState.Failed>(handle.state.value)
            assertEquals(SendFailure.QueueRejected(res = 9), state.reason)
        }
    }

    @Test
    fun auxiliaryFramesSurfaceAsTypedEvents() = runTest {
        withConnectedClient { client, transport ->
            client.events.test {
                val proxy = MqttClientProxyMessage(topic = "msh/2/e/test", retained = true)
                transport.injectFrame(frameOf(FromRadio(mqttClientProxyMessage = proxy)))
                runCurrent()
                assertEquals(MeshEvent.MqttProxyMessage(proxy), awaitItem())

                val xmodem = XModem(seq = 7)
                transport.injectFrame(frameOf(FromRadio(xmodemPacket = xmodem)))
                runCurrent()
                assertEquals(MeshEvent.XmodemPacket(xmodem), awaitItem())

                val fileInfo = org.meshtastic.proto.FileInfo(file_name = "firmware.uf2", size_bytes = 1024)
                transport.injectFrame(frameOf(FromRadio(fileInfo = fileInfo)))
                runCurrent()
                assertEquals(MeshEvent.FileInfo(fileInfo), awaitItem())

                // lockdown_status (protobufs 2.7.25+) is not yet typed — but it must not be
                // silently dropped either.
                transport.injectFrame(frameOf(FromRadio(lockdown_status = org.meshtastic.proto.LockdownStatus())))
                runCurrent()
                val warning = assertIs<MeshEvent.ProtocolWarning>(awaitItem())
                assertEquals("lockdown_status", warning.details["variant"])

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun remoteAdminUsesPkcWhenBothNodesHavePublishedKeys() = runTest {
        withConnectedClient { client, transport ->
            transport.injectFrame(
                frameOf(
                    FromRadio(node_info = NodeInfo(num = TEST_NODE_NUM, user = User(id = "!1", public_key = MY_KEY))),
                ),
            )
            transport.injectFrame(
                frameOf(
                    FromRadio(
                        node_info = NodeInfo(num = REMOTE_NODE.raw, user = User(id = "!2", public_key = REMOTE_KEY)),
                    ),
                ),
            )
            runCurrent()

            val before = transport.outboundPackets().size
            val deferred = backgroundScope.async { client.admin.forNode(REMOTE_NODE).getDeviceMetadata() }
            runCurrent()

            val outbound = transport.outboundPackets().drop(before).last { it.to == REMOTE_NODE.raw }
            assertTrue(outbound.pki_encrypted, "Remote admin must be PKI-encrypted when both nodes have keys")
            assertContentEquals(REMOTE_KEY.toByteArray(), outbound.public_key.toByteArray())
            assertEquals(0, outbound.channel, "PKC admin is sent on channel 0")
            assertEquals(MeshPacket.Priority.RELIABLE, outbound.priority)

            transport.injectAdminResponse(
                requestId = outbound.id,
                response = AdminMessage(get_device_metadata_response = DeviceMetadata(firmware_version = "2.7.0")),
                fromNode = REMOTE_NODE.raw,
            )
            runCurrent()
            assertIs<AdminResult.Success<DeviceMetadata>>(deferred.await())
        }
    }

    @Test
    fun remoteAdminFallsBackToNamedAdminChannelWithoutKeys() = runTest {
        withConnectedClient { client, transport ->
            // Establish a secondary channel named "admin" via the normal setChannel path so
            // channelsState carries it at index 1.
            val adminChannel = Channel(
                index = 1,
                role = Channel.Role.SECONDARY,
                settings = ChannelSettings(name = "Admin"),
            )
            val setDeferred = backgroundScope.async { client.admin.setChannel(adminChannel) }
            runCurrent()
            val setPacket = transport.outboundPackets().last {
                runCatching { AdminMessage.ADAPTER.decode(it.decoded!!.payload) }.getOrNull()?.set_channel != null
            }
            transport.injectRoutingAck(requestId = setPacket.id)
            runCurrent()
            assertIs<AdminResult.Success<Unit>>(setDeferred.await())

            val before = transport.outboundPackets().size
            val deferred = backgroundScope.async { client.admin.forNode(REMOTE_NODE).getDeviceMetadata() }
            runCurrent()

            val outbound = transport.outboundPackets().drop(before).last { it.to == REMOTE_NODE.raw }
            assertFalse(outbound.pki_encrypted, "No keys published — PKC must not be used")
            assertEquals(1, outbound.channel, "Remote admin without keys uses the channel named 'admin'")

            deferred.cancel()
        }
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private fun TestScope.buildClient(transport: FakeRadioTransport): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .coroutineContext(backgroundScope.coroutineContext)
        .autoSyncTimeOnConnect(false)
        .build()

    private suspend fun TestScope.withConnectedClient(block: suspend (RadioClient, FakeRadioTransport) -> Unit) {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:cutover"),
            autoHandshake = true,
            nodeNum = TEST_NODE_NUM,
        )
        val client = buildClient(transport)
        client.connect()
        runCurrent()
        assertEquals(ConnectionState.Connected, client.connection.value)
        try {
            block(client, transport)
        } finally {
            client.disconnect()
            runCurrent()
        }
    }

    private fun frameOf(fromRadio: FromRadio): Frame {
        val proto = FromRadio.ADAPTER.encode(fromRadio)
        val bytes = ByteArray(4 + proto.size).apply {
            this[0] = 0x94.toByte()
            this[1] = 0xC3.toByte()
            this[2] = (proto.size shr 8).toByte()
            this[3] = (proto.size and 0xFF).toByte()
            proto.copyInto(this, destinationOffset = 4)
        }
        return Frame(bytes.toByteString())
    }

    private companion object {
        const val TEST_NODE_NUM: Int = 0x11111111
        val REMOTE_NODE: NodeId = NodeId(0x22222222)
        val MY_KEY = byteArrayOf(1, 1, 2, 2).toByteString()
        val REMOTE_KEY = byteArrayOf(3, 3, 4, 4).toByteString()
    }
}
