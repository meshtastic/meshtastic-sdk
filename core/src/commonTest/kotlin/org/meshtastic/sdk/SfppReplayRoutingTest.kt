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
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.StoreForwardPlusPlus
import org.meshtastic.sdk.internal.sfpp.ChannelCipher
import org.meshtastic.sdk.internal.sfpp.SfppVector
import org.meshtastic.sdk.internal.sfpp.SfppWire
import org.meshtastic.sdk.internal.sfpp.encodeSfpp
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.TestClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SfppReplayRoutingTest {
    private val archive = 0x0A0A0A0A
    private var nextPacketId = 100
    private val clock = TestClock(Instant.fromEpochSeconds(1_800_000_000))

    private suspend fun TestScope.connected(): Pair<FakeRadioTransport, RadioClient> {
        val storage = InMemoryStorage()
        storage.recordOwnNode(NodeId(LOCAL), "2.8.0", null)
        storage.saveChannels(
            listOf(
                Channel.Builder().also { wb ->
                    wb.role = Channel.Role.PRIMARY
                    wb.settings = ChannelSettings.Builder().also { it.psk = byteArrayOf(1).toByteString() }.build()
                }.build(),
            ),
        )
        val transport = FakeRadioTransport(TransportIdentity("fake:sfpp-replay"), autoHandshake = true, nodeNum = LOCAL)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(object : StorageProvider {
                override suspend fun activate(identity: TransportIdentity): DeviceStorage = storage
            })
            .clock(clock)
            .autoSyncTimeOnConnect(false)
            .coroutineContext(backgroundScope.coroutineContext)
            .build()
        return transport to client
    }

    private fun FakeRadioTransport.injectSfpp(
        payload: ByteArray,
        port: PortNum = PortNum.STORE_FORWARD_PLUSPLUS_APP,
        signed: Boolean = false,
        pki: Boolean = false,
    ) {
        injectPacket(
            MeshPacket.Builder().also { wb ->
                // A fresh id each time, or the engine drops the packet as a duplicate.
                wb.id = nextPacketId++
                wb.from = archive
                wb.to = -1
                wb.xeddsa_signed = signed
                wb.pki_encrypted = pki
                wb.decoded = Data.Builder().also {
                    it.portnum = port
                    it.payload = payload.toByteString()
                }.build()
            }.build(),
        )
    }

    @Test
    fun itemFrameOnPortnum35IsRecoveredWithoutTouchingTheNodeDb() = runTest {
        val (transport, client) = connected()
        client.connect()
        runCurrent()
        val events = mutableListOf<StoreForwardEvent>()
        backgroundScope.launch { client.storeForward.events.collect { events += it } }
        runCurrent()

        transport.injectSfpp(
            encodeSfpp(
                type = SfppWire.ITEM_FRAME,
                hash = SfppVector.ctrDefaultHash,
                message = SfppVector.ctrDefault,
                id = SfppVector.ID,
                from = SfppVector.FROM,
                heardAgoS = 3600,
                originVerified = true,
            ),
            signed = false,
        )
        runCurrent()

        val event = assertIs<StoreForwardEvent.SfppMessageRecovered>(events.single())
        assertEquals(SfppVector.TEXT, event.text)
        assertEquals(NodeId(SfppVector.FROM), event.from)
        assertEquals(NodeId(archive), event.archive)
        assertEquals(clock.now() - 3600.seconds, event.heardAt)
        // origin_verified counts only in a frame the archive signed.
        assertFalse(event.senderVerified)
        assertFalse(event.timeAttested)
        assertTrue(NodeId(SfppVector.FROM) !in client.nodeSnapshot())

        // Signed without origin_verified: the time is attested, the sender is not.
        val copy = replayOf(SfppVector.FROM, SfppVector.ID, SfppVector.ctrDefault, heardAgoS = 3600)
        transport.injectSfpp(
            replayOf(SfppVector.FROM, SfppVector.ID, SfppVector.ctrDefault, heardAgoS = 3600, originVerified = false),
            signed = true,
        )
        runCurrent()
        val signed = assertIs<StoreForwardEvent.SfppMessageRecovered>(events.last())
        assertTrue(signed.timeAttested && !signed.senderVerified)
        // PKI-encrypted to us with origin_verified: the sender is verified too.
        transport.injectSfpp(copy, pki = true)
        runCurrent()
        val pki = assertIs<StoreForwardEvent.SfppMessageRecovered>(events.last())
        assertEquals(3, events.size)
        assertTrue(pki.senderVerified && pki.timeAttested)
        client.disconnect()
    }

    @Test
    fun messagesHeldLiveOrSentAreNotRecovered() = runTest {
        val (transport, client) = connected()
        client.connect()
        runCurrent()
        val events = mutableListOf<StoreForwardEvent>()
        backgroundScope.launch { client.storeForward.events.collect { events += it } }
        runCurrent()

        transport.injectPacket(
            MeshPacket.Builder().also { wb ->
                wb.id = SfppVector.ID
                wb.from = SfppVector.FROM
                wb.to = -1
                wb.decoded = Data.ADAPTER.decode(SfppVector.data)
            }.build(),
        )
        val sent = client.sendText("mine", wantAck = false).id.raw
        runCurrent()
        val mine = Data.ADAPTER.encode(
            Data.Builder().also {
                it.portnum = PortNum.TEXT_MESSAGE_APP
                it.payload = "mine".encodeToByteArray().toByteString()
            }.build(),
        )
        val mineCt = ChannelCipher.ctr(ChannelCipher.expandKey(byteArrayOf(1)), LOCAL, sent, mine)
        transport.injectSfpp(replayOf(SfppVector.FROM, SfppVector.ID, SfppVector.ctrDefault))
        transport.injectSfpp(replayOf(LOCAL, sent, mineCt))
        runCurrent()

        assertEquals(emptyList(), events)
        client.disconnect()
    }

    private fun replayOf(from: Int, id: Int, ct: ByteArray, heardAgoS: Int = 0, originVerified: Boolean = true) =
        encodeSfpp(
            type = SfppWire.ITEM_FRAME,
            hash = SfppHash.compute(ct, -1, from, id),
            message = ct,
            id = id,
            from = from,
            heardAgoS = heardAgoS,
            originVerified = originVerified,
        )

    // The pinned Wire enum has no ITEM_FRAME; decoded through it, a replay frame reads as CANON_ANNOUNCE.
    @Test
    fun replayTypesNeverReachTheChainHandlers() = runTest {
        val (transport, client) = connected()
        client.connect()
        runCurrent()
        val events = mutableListOf<StoreForwardEvent>()
        backgroundScope.launch { client.storeForward.events.collect { events += it } }
        runCurrent()

        for (port in listOf(PortNum.STORE_FORWARD_PLUSPLUS_APP, PortNum.STORE_FORWARD_APP)) {
            transport.injectSfpp(
                encodeSfpp(
                    type = SfppWire.ITEM_FRAME,
                    hash = ByteArray(16) {
                        1
                    },
                    message = byteArrayOf(9),
                ),
                port,
            )
            transport.injectSfpp(encodeSfpp(type = SfppWire.REQUEST_DONE, hash = ByteArray(16) { 2 }), port)
        }
        runCurrent()

        assertEquals(emptyList(), events)
        client.disconnect()
    }

    @Test
    fun chainLinkOnPortnum35StillReportsDeliveryStatus() = runTest {
        val (transport, client) = connected()
        client.connect()
        runCurrent()
        val events = mutableListOf<StoreForwardEvent>()
        backgroundScope.launch { client.storeForward.events.collect { events += it } }
        runCurrent()

        val link = StoreForwardPlusPlus.Builder().also { wb ->
            wb.sfpp_message_type = StoreForwardPlusPlus.SFPP_message_type.LINK_PROVIDE
            wb.encapsulated_id = 5
            wb.encapsulated_from = LOCAL
            wb.encapsulated_to = 0
            wb.message = byteArrayOf(1, 2, 3).toByteString()
            wb.commit_hash = ByteArray(16) { 3 }.toByteString()
        }.build()
        transport.injectSfpp(StoreForwardPlusPlus.ADAPTER.encode(link))
        runCurrent()

        assertEquals(5, assertIs<StoreForwardEvent.SfppLinkProvided>(events.single()).packetId)
        client.disconnect()
    }

    private companion object {
        const val LOCAL = 0x01020304
    }
}
