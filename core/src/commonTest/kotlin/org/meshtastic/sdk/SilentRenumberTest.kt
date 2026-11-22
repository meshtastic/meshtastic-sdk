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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.Routing
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Firmware 2.8.0 moves `my_node_num` to `crc32(public_key)` inside the first region set
 * (`AdminModule::handleSetConfig`, `NodeDB::createNewIdentity`) and resends no `my_info`; that
 * landed in 2.8.1. The write gets QueueStatus 0 and no ack, because `ReliableRouter::sniffReceived`
 * then finds the packet no longer addressed to the node. A later write to the old number fails
 * `PKI_SEND_FAIL_PUBLIC_KEY`, as QueueStatus and as a NAK from the new number to itself
 * (`Router::send`, `abortSendAndNak`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SilentRenumberTest {

    @Test
    fun aFirstRegionSetAnsweredOnlyByItsQueueStatusEndsOnTheNewNumber() = runTest {
        val (transport, client, events) = connected(autoQueueStatus = true)
        val save = async { client.admin.setConfig(regionUs) }
        runCurrent()
        transport.nodeNum = RENUMBERED

        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(save.isCompleted, "the write still waits for an ack that never comes")
        assertIs<AdminResult.Success<Unit>>(save.await())
        assertEquals(2, stage1Requests(transport), "a fresh Stage 1 on the same link")
        val ready = events.filterIsInstance<RadioSessionEvent.Ready>().last()
        assertEquals(RENUMBERED, ready.myInfo?.my_node_num)
        assertEquals(RENUMBERED, client.ownNode.value?.num)
        client.disconnect()
    }

    @Test
    fun aCommitRefusedForTheOldNumberIsResentToTheNumberItsNakNames() = runTest {
        val (transport, client, events) = connected(autoQueueStatus = false)
        val edit = async { client.admin.editSettings { setConfig(regionUs) } }
        runCurrent()
        val begin = lastAdminWrite(transport)
        transport.queueStatus(begin.id, 0)
        transport.injectRoutingAck(begin.id, fromNode = LOCAL)
        runCurrent()
        val set = lastAdminWrite(transport)
        assertTrue(admin(set).set_config != null)
        transport.queueStatus(set.id, 0)
        runCurrent()

        val commit = lastAdminWrite(transport)
        assertEquals(true, admin(commit).commit_edit_settings)
        transport.queueStatus(commit.id, PKI_SEND_FAIL_PUBLIC_KEY)
        transport.injectPacket(selfNak(commit.id))
        runCurrent()

        val resend = lastAdminWrite(transport)
        assertEquals(true, admin(resend).commit_edit_settings)
        assertNotEquals(commit.id, resend.id, "firmware drops a phone packet id it has seen")
        assertEquals(RENUMBERED, resend.to)
        transport.nodeNum = RENUMBERED
        transport.queueStatus(resend.id, 0)
        transport.injectRoutingAck(resend.id, fromNode = RENUMBERED)
        runCurrent()

        assertIs<AdminResult.Success<Unit>>(edit.await())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, stage1Requests(transport), "a fresh Stage 1 on the same link")
        assertEquals(RENUMBERED, events.filterIsInstance<RadioSessionEvent.Ready>().last().myInfo?.my_node_num)
        client.disconnect()
    }

    private fun admin(packet: MeshPacket): AdminMessage = AdminMessage.ADAPTER.decode(packet.decoded!!.payload)

    // abortSendAndNak: from and to are both the number the device now holds.
    private fun selfNak(requestId: Int): MeshPacket = MeshPacket.Builder().also { wb ->
        wb.from = RENUMBERED
        wb.to = RENUMBERED
        wb.decoded = Data.Builder().also { d ->
            d.portnum = PortNum.ROUTING_APP
            d.request_id = requestId
            d.payload = Routing.ADAPTER.encode(
                Routing.Builder().also { it.error_reason = Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY }.build(),
            ).toByteString()
        }.build()
    }.build()

    private fun FakeRadioTransport.queueStatus(id: Int, result: Int) {
        val status = QueueStatus.Builder().also {
            it.mesh_packet_id = id
            it.res = result
            it.free = 15
            it.maxlen = 16
        }.build()
        injectFrame(FromRadio.Builder().also { it.queueStatus = status }.build().toFrame())
    }

    private suspend fun TestScope.connected(
        autoQueueStatus: Boolean,
    ): Triple<FakeRadioTransport, RadioClient, MutableList<RadioSessionEvent>> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:silent-renumber"),
            // A freshly reset node reports an unset region.
            frames = listOf(
                FromRadio.Builder().also { wb ->
                    wb.config = Config.Builder().also { wb ->
                        wb.lora = Config.LoRaConfig.Builder().also {
                            it.region = Config.LoRaConfig.RegionCode.UNSET
                        }.build()
                    }.build()
                }.build().toFrame(),
            ),
            autoHandshake = true,
            autoQueueStatus = autoQueueStatus,
            nodeNum = LOCAL,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .sessionReceive()
            .build()
        val events = mutableListOf<RadioSessionEvent>()
        backgroundScope.launch { client.sessionEvents?.collect { events += it } }
        client.connect()
        runCurrent()
        return Triple(transport, client, events)
    }

    private fun lastAdminWrite(transport: FakeRadioTransport): MeshPacket =
        transport.outboundPackets().last { it.decoded?.portnum == PortNum.ADMIN_APP }

    private fun stage1Requests(transport: FakeRadioTransport): Int = transport.outboundFrames().count { frame ->
        val bytes = frame.bytes.toByteArray()
        runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull()?.want_config_id == STAGE1
    }

    private val regionUs = Config.Builder().also { wb ->
        wb.lora = Config.LoRaConfig.Builder().also { it.region = Config.LoRaConfig.RegionCode.US }.build()
    }.build()

    private companion object {
        const val LOCAL = 0x5408_8ABC
        const val RENUMBERED = -0x7B8B_46C6 // 0x8474b93a, the bench's crc32-derived number
        const val STAGE1 = 69420
        const val PKI_SEND_FAIL_PUBLIC_KEY = 39
    }
}
