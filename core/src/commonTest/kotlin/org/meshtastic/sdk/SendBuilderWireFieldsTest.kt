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
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The send-builder fields a durable outbox needs so it never assembles a wire packet itself. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendBuilderWireFieldsTest {

    @Test
    fun `a caller-chosen id is the wire id and the handle id`() = runTest {
        withConnectedClient { client, transport ->
            val id = client.nextPacketId()
            val handle = client.sendPaced {
                text("stored first")
                to(PEER)
                id(id)
            }
            runCurrent()

            assertEquals(id, handle.message.id)
            assertEquals(id.raw, transport.outboundPackets().last().id)
        }
    }

    @Test
    fun `send of a packet with an id returns a handle under that id`() = runTest {
        withConnectedClient { client, transport ->
            val packet = MeshPacket.Builder().also { wb ->
                wb.to = PEER.raw
                wb.id = CHOSEN_ID
                wb.want_ack = true
                wb.decoded = Data.Builder().also { d ->
                    d.portnum = PortNum.TEXT_MESSAGE_APP
                    d.payload = "x".encodeToByteArray().toByteString()
                }.build()
            }.build()

            val handle = client.send(packet)
            runCurrent()

            assertEquals(CHOSEN_ID, transport.outboundPackets().last().id)
            assertEquals(CHOSEN_ID, handle.id.raw)
        }
    }

    @Test
    fun `replyTo and reaction set reply_id and the emoji flag`() = runTest {
        withConnectedClient { client, transport ->
            client.send {
                text("👍")
                to(PEER)
                replyTo(MessageId(ORIGINAL_ID))
                reaction()
            }
            runCurrent()

            val decoded = transport.outboundPackets().last().decoded!!
            // mesh.proto: Data.reply_id names the message answered; emoji = 1 marks the payload a reaction.
            assertEquals(ORIGINAL_ID, decoded.reply_id)
            assertEquals(1, decoded.emoji)
        }
    }

    @Test
    fun `pkiEncrypted sends on channel 0 with the key the node map holds`() = runTest {
        withConnectedClient { client, transport ->
            transport.injectPacket(nodeInfoFrom(PEER, PEER_KEY))
            runCurrent()

            client.send {
                text("secret")
                to(PEER)
                channel(ChannelIndex(2))
                pkiEncrypted()
            }
            runCurrent()

            val sent = transport.outboundPackets().last()
            assertTrue(sent.pki_encrypted)
            assertEquals(0, sent.channel)
            assertEquals(PEER_KEY, sent.public_key)
        }
    }

    @Test
    fun `a pinned public key wins over the stored one`() = runTest {
        withConnectedClient { client, transport ->
            transport.injectPacket(nodeInfoFrom(PEER, PEER_KEY))
            runCurrent()
            val pinned = ByteArray(KEY_SIZE) { 7 }.toByteString()

            client.send {
                text("secret")
                to(PEER)
                pkiEncrypted(pinned)
            }
            runCurrent()

            val sent = transport.outboundPackets().last()
            assertEquals(pinned, sent.public_key)
            assertNotEquals(PEER_KEY, sent.public_key)
        }
    }

    @Test
    fun `pkiEncrypted to broadcast is refused before it is sent`() = runTest {
        withConnectedClient { client, transport ->
            val before = transport.outboundPackets().size
            assertFailsWith<IllegalStateException> {
                client.send {
                    text("nobody")
                    pkiEncrypted()
                }
            }
            runCurrent()
            assertEquals(before, transport.outboundPackets().size)
        }
    }

    private fun nodeInfoFrom(node: NodeId, key: ByteString): MeshPacket = MeshPacket.Builder().also { wb ->
        wb.from = node.raw
        wb.to = NodeId.BROADCAST.raw
        wb.rx_time = 1_700_000_000
        wb.decoded = Data.Builder().also { d ->
            d.portnum = PortNum.NODEINFO_APP
            d.payload = User.ADAPTER.encode(
                User.Builder().also { u ->
                    u.long_name = "Peer"
                    u.short_name = "PR"
                    u.public_key = key
                }.build(),
            ).toByteString()
        }.build()
    }.build()

    private suspend fun TestScope.withConnectedClient(block: suspend (RadioClient, FakeRadioTransport) -> Unit) {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:send-builder"),
            autoHandshake = true,
            nodeNum = OWN_NODE,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        client.connect()
        runCurrent()
        try {
            block(client, transport)
        } finally {
            client.disconnect()
            runCurrent()
        }
    }

    private companion object {
        const val OWN_NODE = 0x11111111
        const val CHOSEN_ID = 0x0badcafe
        const val ORIGINAL_ID = 0x01020304
        const val KEY_SIZE = 32
        val PEER = NodeId(0x22222222)
        val PEER_KEY: ByteString = ByteArray(KEY_SIZE) { (it + 1).toByte() }.toByteString()
    }
}
