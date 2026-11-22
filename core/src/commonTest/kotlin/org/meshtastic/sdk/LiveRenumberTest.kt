/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
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
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * From 2.8.1 firmware's first region set moves `my_node_num` to `crc32(public_key)` and resends
 * `my_info`. The write itself gets QueueStatus 0 and no ack: `ReliableRouter::sniffReceived` acks only
 * a packet addressed to the current number. Writes addressed to the new number are acked from it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveRenumberTest {

    @Test
    fun myInfoAfterTheRegionWriteCompletesItWithoutAnAckAndRestartsTheSession() = runTest {
        val (transport, client, events) = connected()
        val save = async { client.admin.setConfig(regionUs) }
        runCurrent()

        // Bench, 2.8.3: QueueStatus 0 for the write, no ack, my_info with the new number 3.8 s later.
        advanceTimeBy(3_800)
        renumber(transport)

        assertTrue(save.isCompleted, "the write waits for an ack firmware never sends")
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
    fun aWriteAckedBeforeTheRestartDoesNotLandOnTheNewSession() = runTest {
        val (transport, client, events) = connected()
        // The caller resumes only when drained, so the session can restart between the ack and that resume.
        val caller = HeldDispatcher()
        val save = async(caller) { client.admin.setOwner(User.Builder().also { it.long_name = "Stale" }.build()) }
        caller.drain()
        runCurrent()
        transport.injectRoutingAck(lastAdminWrite(transport).id, fromNode = LOCAL)
        runCurrent()

        renumber(transport)
        assertSessionRestarted(transport, events)
        caller.drain()
        runCurrent()
        caller.drain()

        assertTrue(save.isCompleted, "the write settled")
        assertIs<AdminResult.Success<Unit>>(save.await())
        assertEquals(RENUMBERED, client.ownNode.value?.num)
        assertNotEquals("Stale", client.ownNode.value?.user?.long_name, "the old session's write reached the new one")
        client.disconnect()
    }

    @Test
    fun theRestartedSessionKeepsOneHeartbeatLoop() = runTest {
        val (transport, client, events) = connected()
        renumber(transport)
        assertSessionRestarted(transport, events)

        val before = heartbeats(transport)
        // protocol.md section 16: one keep-alive every 30 s. A frame every 20 s keeps the liveness watchdog quiet.
        repeat(15) {
            transport.injectFrame(queueStatus())
            advanceTimeBy(20_000)
            runCurrent()
        }

        assertEquals(10, heartbeats(transport) - before, "heartbeats over 300 s")
        client.disconnect()
    }

    private class HeldDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private fun heartbeats(transport: FakeRadioTransport): Int = transport.outboundFrames().count { frame ->
        val bytes = frame.bytes.toByteArray()
        runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull()?.heartbeat != null
    }

    private fun queueStatus() = FromRadio.Builder().also { wb ->
        wb.queueStatus = QueueStatus.Builder().also { it.free = 16 }.build()
    }.build().toFrame()

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
            // A freshly reset node: the region is unset until the first region set.
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
