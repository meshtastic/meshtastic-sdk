/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.StoreForwardPlusPlus
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SfppHash
import org.meshtastic.sdk.StoreForwardEvent
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class StoreForwardApiImplSfppTest {

    private fun kotlinx.coroutines.test.TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:p2-store-forward-sfpp"),
            autoHandshake = true,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .build()
        return transport to client
    }

    @Test
    fun linkProvidePacketEmitsSfppLinkProvidedEvent() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val expectedHash = byteArrayOf(1, 2, 3, 4)
        val eventDeferred = async {
            client.storeForward.events.first { it is StoreForwardEvent.SfppLinkProvided }
        }
        runCurrent()

        transport.injectSfpp(
            StoreForwardPlusPlus(
                sfpp_message_type = StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE,
                message_hash = expectedHash.toByteString(),
                commit_hash = byteArrayOf(9, 8, 7).toByteString(),
                encapsulated_id = 0x1234,
                encapsulated_to = 0x01020304,
                encapsulated_from = 0x55667788,
            ),
        )
        runCurrent()

        val event = assertIs<StoreForwardEvent.SfppLinkProvided>(eventDeferred.await())
        assertEquals(0x1234, event.packetId)
        assertEquals(0x55667788, event.from)
        assertEquals(0x01020304, event.to)
        assertEquals(true, event.confirmed)
        assertContentEquals(expectedHash, assertNotNull(event.messageHash))

        client.disconnect()
    }

    @Test
    fun canonAnnouncePacketEmitsSfppCanonAnnouncedEvent() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val expectedHash = byteArrayOf(7, 6, 5, 4)
        val eventDeferred = async {
            client.storeForward.events.first { it is StoreForwardEvent.SfppCanonAnnounced }
        }
        runCurrent()

        transport.injectSfpp(
            StoreForwardPlusPlus(
                sfpp_message_type = StoreForwardPlusPlus.SFPP_message_type.CANON_ANNOUNCE,
                message_hash = expectedHash.toByteString(),
                encapsulated_rxtime = 0xFEDCBA98.toInt(),
            ),
        )
        runCurrent()

        val event = assertIs<StoreForwardEvent.SfppCanonAnnounced>(eventDeferred.await())
        assertContentEquals(expectedHash, event.messageHash)
        assertEquals(0xFEDCBA98L, event.rxTime)

        client.disconnect()
    }

    @Test
    fun fragmentPacketsStillEmitSfppLinkProvidedEvents() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val expectedHash = byteArrayOf(0xA, 0xB, 0xC)
        val eventDeferred = async {
            client.storeForward.events.first { it is StoreForwardEvent.SfppLinkProvided }
        }
        runCurrent()

        transport.injectSfpp(
            StoreForwardPlusPlus(
                sfpp_message_type = StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE_FIRSTHALF,
                message_hash = expectedHash.toByteString(),
                encapsulated_id = 77,
                encapsulated_to = 88,
                encapsulated_from = 99,
            ),
        )
        runCurrent()

        val event = assertIs<StoreForwardEvent.SfppLinkProvided>(eventDeferred.await())
        assertEquals(77, event.packetId)
        assertEquals(99, event.from)
        assertEquals(88, event.to)
        assertEquals(false, event.confirmed)
        assertContentEquals(expectedHash, assertNotNull(event.messageHash))

        client.disconnect()
    }

    @Test
    fun linkProvideComputesHashWhenMessageHashMissing() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        val message = "payload".encodeToByteArray()
        val eventDeferred = async {
            client.storeForward.events.first { it is StoreForwardEvent.SfppLinkProvided }
        }
        runCurrent()

        transport.injectSfpp(
            StoreForwardPlusPlus(
                sfpp_message_type = StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE,
                message = message.toByteString(),
                encapsulated_id = 42,
                encapsulated_to = 0,
                encapsulated_from = 0x0BADF00D,
            ),
        )
        runCurrent()

        val event = assertIs<StoreForwardEvent.SfppLinkProvided>(eventDeferred.await())
        val expectedHash = SfppHash.compute(
            payload = message,
            to = NodeId.BROADCAST.raw,
            from = 0x0BADF00D,
            id = 42,
        )
        assertEquals(42, event.packetId)
        assertEquals(0x0BADF00D, event.from)
        assertEquals(0, event.to)
        assertContentEquals(expectedHash, assertNotNull(event.messageHash))

        client.disconnect()
    }

    private fun FakeRadioTransport.injectSfpp(
        message: StoreForwardPlusPlus,
        fromNode: Int = 0x10203040,
    ) {
        injectPacket(
            MeshPacket(
                id = 1,
                from = fromNode,
                to = 0,
                decoded = Data(
                    portnum = PortNum.STORE_FORWARD_APP,
                    payload = StoreForwardPlusPlus.ADAPTER.encode(message).toByteString(),
                ),
            ),
        )
    }
}
