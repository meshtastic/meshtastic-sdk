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
        transport.injectFrame(myInfo(RENUMBERED))
        runCurrent()

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

        transport.injectFrame(myInfo(RENUMBERED))
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

        assertEquals(1, stage1Requests(transport))
        assertTrue(events.none { it is RadioSessionEvent.Ended })
        client.disconnect()
    }

    private fun TestScope.assertSessionRestarted(transport: FakeRadioTransport, events: List<RadioSessionEvent>) {
        // The handshake settles on timers.
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, stage1Requests(transport), "a fresh Stage 1 on the same link")
        val ended = events.indexOfFirst { it is RadioSessionEvent.Ended }
        val readies = events.withIndex().filter { it.value is RadioSessionEvent.Ready }
        assertTrue(ended > 0 && readies.size == 2 && readies[1].index > ended, "Ended then a new Ready: $events")
        assertTrue(readies[1].value.sessionId > readies[0].value.sessionId)
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

    private val regionUs = Config.Builder().also { wb ->
        wb.lora = Config.LoRaConfig.Builder().also { it.region = Config.LoRaConfig.RegionCode.US }.build()
    }.build()

    private companion object {
        const val LOCAL = 1
        const val RENUMBERED = 0x5A5A1234
        const val STAGE1 = 69420
    }
}
