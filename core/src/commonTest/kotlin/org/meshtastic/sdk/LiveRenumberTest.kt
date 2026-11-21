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
import org.meshtastic.proto.Config
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Firmware's first region set moves `my_node_num` to `crc32(public_key)`, resends `my_info`, and
 * acknowledges the write from the new number, in either order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveRenumberTest {

    @Test
    fun anAckFromTheNewNumberBeforeMyInfoSettlesTheWriteAndRestartsTheSession() = runTest {
        val (transport, client, events) = connected()
        val save = async { client.admin.setConfig(regionUs) }
        runCurrent()
        val write = lastAdminWrite(transport)

        transport.injectRoutingAck(write.id, fromNode = RENUMBERED)
        runCurrent()
        renumber(transport)

        assertIs<AdminResult.Success<Unit>>(save.await())
        assertSessionRestarted(transport, events)
        client.disconnect()
    }

    @Test
    fun myInfoBeforeTheAckWaitsForTheWriteThenRestartsTheSession() = runTest {
        val (transport, client, events) = connected()
        val save = async { client.admin.setConfig(regionUs) }
        runCurrent()
        val write = lastAdminWrite(transport)

        renumber(transport)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, stage1Requests(transport), "the restart waits for the write to settle")

        transport.injectRoutingAck(write.id, fromNode = RENUMBERED)
        runCurrent()

        assertIs<AdminResult.Success<Unit>>(save.await())
        assertSessionRestarted(transport, events)
        client.disconnect()
    }

    @Test
    fun myInfoWithTheSameNumberChangesNothing() = runTest {
        val (transport, client, events) = connected()

        transport.injectFrame(myInfo(LOCAL))
        runCurrent()
        // A wrongly armed renumber restarts only once a paced write settles, so settle one.
        val save = async { client.admin.setConfig(routerRole) }
        runCurrent()
        transport.injectRoutingAck(lastAdminWrite(transport).id, fromNode = LOCAL)
        runCurrent()
        assertIs<AdminResult.Success<Unit>>(save.await())
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, stage1Requests(transport))
        assertTrue(events.none { it is RadioSessionEvent.Ended })
        client.disconnect()
    }

    @Test
    fun writesQueuedAfterARenumberGoToTheNewNumberBeforeTheRestart() = runTest {
        val (transport, client, events) = connected()
        renumber(transport)

        val edit = async { client.admin.editSettings { setConfig(regionUs) } }
        runCurrent()
        fun admin() = transport.outboundPackets().filter { it.decoded?.portnum == PortNum.ADMIN_APP }
        fun isCommit(p: MeshPacket) =
            org.meshtastic.proto.AdminMessage.ADAPTER.decode(p.decoded!!.payload).commit_edit_settings == true
        val acked = mutableSetOf<Int>()
        while (admin().none(::isCommit)) {
            val pending = admin().filter { it.want_ack && it.id !in acked }
            check(pending.isNotEmpty()) { "batch stalled: ${admin()}" }
            for (p in pending) {
                acked += p.id
                transport.injectRoutingAck(p.id, fromNode = RENUMBERED)
            }
            runCurrent()
        }
        val commit = admin().single(::isCommit)
        val admin = admin()
        assertTrue(admin.takeLast(3).all { it.to == RENUMBERED }, "begin, write and commit all go to the new number")
        assertEquals(RENUMBERED, commit.to, "the commit is addressed to the new number")
        assertEquals(1, stage1Requests(transport), "no restart while the batch is open")
        // Retargeted writes are still local admin: no remote passkey or admin-channel routing.
        assertTrue(
            events.none {
                (it as? RadioSessionEvent.Event)?.event.let { e ->
                    e is MeshEvent.ProtocolWarning &&
                        e.message.contains("admin remote")
                }
            },
            "retargeted writes treated as remote admin: $events",
        )
        transport.injectRoutingAck(commit.id, fromNode = RENUMBERED)
        runCurrent()

        assertIs<AdminResult.Success<Unit>>(edit.await())
        assertSessionRestarted(transport, events)
        client.disconnect()
    }

    @Test
    fun aWriteStartedWhileTheRenumberingWriteAwaitsItsAckGoesToTheNewNumber() = runTest {
        val (transport, client, events) = connected()
        val first = async { client.admin.setConfig(regionUs) }
        runCurrent()
        val firstWrite = lastAdminWrite(transport)
        renumber(transport)

        val second = async { client.admin.setConfig(routerRole) }
        runCurrent()
        val secondWrite = lastAdminWrite(transport)
        assertTrue(secondWrite.id != firstWrite.id)
        assertEquals(RENUMBERED, secondWrite.to)
        transport.injectRoutingAck(secondWrite.id, fromNode = RENUMBERED)
        transport.injectRoutingAck(firstWrite.id, fromNode = RENUMBERED)
        runCurrent()

        assertIs<AdminResult.Success<Unit>>(first.await())
        assertIs<AdminResult.Success<Unit>>(second.await())
        assertSessionRestarted(transport, events)
        client.disconnect()
    }

    // Firmware reports the new number from here on, the re-handshake included.
    private fun TestScope.renumber(transport: FakeRadioTransport) {
        transport.nodeNum = RENUMBERED
        transport.injectFrame(myInfo(RENUMBERED))
        runCurrent()
    }

    private fun TestScope.assertSessionRestarted(transport: FakeRadioTransport, events: List<RadioSessionEvent>) {
        // The restart waits for the paced queue to stay empty, then the handshake settles on timers.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, stage1Requests(transport), "a fresh Stage 1 on the same link")
        val ended = events.indexOfFirst { it is RadioSessionEvent.Ended }
        val readies = events.withIndex().filter { it.value is RadioSessionEvent.Ready }
        assertTrue(ended > 0 && readies.size == 2 && readies[1].index > ended, "Ended then a new Ready: $events")
        assertTrue(readies[1].value.sessionId > readies[0].value.sessionId)
        val rebound = readies[1].value as RadioSessionEvent.Ready
        assertEquals(RENUMBERED, rebound.myInfo?.my_node_num, "the new session carries the new identity")
    }

    private suspend fun TestScope.connected(): Triple<FakeRadioTransport, RadioClient, MutableList<RadioSessionEvent>> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:renumber"),
            autoHandshake = true,
            autoQueueStatus = true,
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

    private fun myInfo(num: Int) = FromRadio.Builder().also { wb ->
        wb.my_info = MyNodeInfo.Builder().also { it.my_node_num = num }.build()
    }.build().toFrame()

    private val routerRole = Config.Builder().also { wb ->
        wb.device = Config.DeviceConfig.Builder().also { it.role = Config.DeviceConfig.Role.ROUTER }.build()
    }.build()

    private val regionUs = Config.Builder().also { wb ->
        wb.lora = Config.LoRaConfig.Builder().also { it.region = Config.LoRaConfig.RegionCode.US }.build()
    }.build()

    private companion object {
        const val LOCAL = 1
        const val RENUMBERED = 0x5A5A1234
        const val STAGE1 = 69420
    }
}
