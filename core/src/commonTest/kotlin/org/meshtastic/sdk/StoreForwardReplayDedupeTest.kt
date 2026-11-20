/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.StoreAndForward
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StoreForwardReplayDedupeTest {

    private fun replay(
        packetId: Int,
        from: Int = 0x1111,
        originalId: Int,
        rr: StoreAndForward.RequestResponse = StoreAndForward.RequestResponse.ROUTER_TEXT_DIRECT,
    ): MeshPacket = MeshPacket.Builder().also { wb ->
        wb.id = packetId
        wb.from = from
        wb.decoded = Data.Builder().also { d ->
            d.portnum = PortNum.STORE_FORWARD_APP
            d.payload = StoreAndForward.ADAPTER.encode(
                StoreAndForward.Builder().also { s ->
                    s.rr = rr
                    s.text = "hi".encodeUtf8()
                    s.original_id = originalId
                }.build(),
            ).toByteString()
        }.build()
    }.build()

    private fun text(id: Int, from: Int = 0x1111): MeshPacket = MeshPacket.Builder().also { wb ->
        wb.id = id
        wb.from = from
        wb.decoded = Data.Builder().also { d ->
            d.portnum = PortNum.TEXT_MESSAGE_APP
            d.payload = "hi".encodeUtf8()
        }.build()
    }.build()

    private suspend fun List<MeshPacket>.deduped(window: Int = 256): List<MeshPacket> =
        asFlow().dropDuplicateStoreForwardReplays(window).toList()

    @Test
    fun originalIdReadFromTextReplay() {
        assertEquals(77, replay(packetId = 1, originalId = 77).storeForwardOriginalId())
        assertEquals(
            77,
            replay(
                1,
                originalId = 77,
                rr = StoreAndForward.RequestResponse.ROUTER_TEXT_BROADCAST,
            ).storeForwardOriginalId(),
        )
    }

    @Test
    fun originalIdNullWhenAbsentOrNotAReplay() {
        assertNull(replay(packetId = 1, originalId = 0).storeForwardOriginalId())
        assertNull(
            replay(1, originalId = 5, rr = StoreAndForward.RequestResponse.ROUTER_HEARTBEAT).storeForwardOriginalId(),
        )
        assertNull(text(1).storeForwardOriginalId())
        assertNull(MeshPacket.Builder().build().storeForwardOriginalId())
    }

    @Test
    fun originalIdNullWhenPayloadMalformed() {
        val bad = MeshPacket.Builder().also { wb ->
            wb.decoded = Data.Builder().also { d ->
                d.portnum = PortNum.STORE_FORWARD_APP
                d.payload = byteArrayOf(0x0A, 0x7F).toByteString()
            }.build()
        }.build()
        assertNull(bad.storeForwardOriginalId())
    }

    @Test
    fun sameOriginalIdUnderFreshPacketIdsIsDropped() = runTest {
        val out = listOf(replay(10, originalId = 77), replay(11, originalId = 77)).deduped()
        assertEquals(listOf(10), out.map { it.id })
    }

    @Test
    fun differentOriginalIdsAreKept() = runTest {
        val out = listOf(replay(10, originalId = 77), replay(11, originalId = 78)).deduped()
        assertEquals(listOf(10, 11), out.map { it.id })
    }

    @Test
    fun sameOriginalIdFromDifferentSendersIsKept() = runTest {
        val out = listOf(
            replay(10, from = 0x1111, originalId = 77),
            replay(11, from = 0x2222, originalId = 77),
        ).deduped()
        assertEquals(listOf(10, 11), out.map { it.id })
    }

    @Test
    fun replayOfAlreadyDeliveredLiveTextIsDropped() = runTest {
        val out = listOf(text(id = 77), replay(11, originalId = 77)).deduped()
        assertEquals(listOf(77), out.map { it.id })
    }

    @Test
    fun replayWithoutOriginalIdPassesThrough() = runTest {
        val out = listOf(replay(10, originalId = 0), replay(11, originalId = 0)).deduped()
        assertEquals(listOf(10, 11), out.map { it.id })
    }

    @Test
    fun liveTextAfterReplayIsNeverDropped() = runTest {
        val out = listOf(replay(10, originalId = 77), text(id = 77)).deduped()
        assertEquals(listOf(10, 77), out.map { it.id })
    }

    @Test
    fun windowForgetsTheOldestKey() = runTest {
        val out = listOf(
            replay(10, originalId = 1),
            replay(11, originalId = 2),
            replay(12, originalId = 1),
        ).deduped(window = 1)
        assertEquals(listOf(10, 11, 12), out.map { it.id })
    }

    @Test
    fun windowMustBePositive() {
        assertFailsWith<IllegalArgumentException> {
            emptyList<MeshPacket>().asFlow().dropDuplicateStoreForwardReplays(window = 0)
        }
    }
}
