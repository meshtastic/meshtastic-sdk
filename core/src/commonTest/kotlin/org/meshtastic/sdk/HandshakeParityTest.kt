/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Handshake timing that matches the Android app the SDK replaces (`protocol.md` §6): one
 * `want_config_id` per session, a Stage 2 that only fails without progress, and a passkey seed
 * that never fails the connect.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HandshakeParityTest {

    @Test
    fun silentStage1SendsWantConfigOnceThenTimesOut() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:stage1-once"))
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { runCatching { client.connect() } }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage1 }

        advanceTimeBy(STAGE1_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(connectJob.isCompleted)
        assertEquals(1, transport.stage1Requests(), "firmware has no re-entry guard for want_config_id")

        advanceTimeBy(2)
        runCurrent()
        val timeout = assertIs<MeshtasticException.HandshakeTimeout>(connectJob.await().exceptionOrNull())
        assertEquals("Stage 1", timeout.stage)
        assertEquals(1, transport.stage1Requests())
    }

    @Test
    fun stage2KeepsDrainingPastSixtySecondsWhileNodesArrive() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:stage2-slow"), autoQueueStatus = true)
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { runCatching { client.connect() } }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage1 }
        transport.completeStage1()
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }
        transport.inject { it.node_info = NodeInfo.Builder().also { n -> n.num = NODE }.build() }

        // A slow BLE drain: one node every 29 s for 290 s, well past a fixed 60 s budget.
        repeat(10) { i ->
            advanceTimeBy(29_000L)
            transport.inject { it.node_info = NodeInfo.Builder().also { n -> n.num = 0x1000 + i }.build() }
            runCurrent()
            val state = assertIs<ConnectionState.Configuring>(client.connection.value)
            assertEquals(ConfigPhase.Stage2, state.phase)
        }

        transport.inject { it.config_complete_id = NONCE_STAGE2 }
        runCurrent()
        transport.answerSeed()
        runCurrent()

        assertTrue(connectJob.await().isSuccess)
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun seedTimeoutConnectsWithoutPasskeyAndLocalWritesStillWork() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:seed-lost"), autoQueueStatus = true)
        val client = buildClient(transport)
        val events = mutableListOf<MeshEvent>()
        backgroundScope.launch { client.events.collect { events += it } }
        val connectJob = backgroundScope.async { runCatching { client.connect() } }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage1 }
        transport.completeStage1()
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }
        transport.inject { it.node_info = NodeInfo.Builder().also { n -> n.num = NODE }.build() }
        transport.inject { it.config_complete_id = NONCE_STAGE2 }
        runCurrent()
        assertTrue(transport.outboundPackets().any { adminOf(it)?.get_owner_request == true })

        advanceTimeBy(SEED_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(connectJob.isCompleted, "a seed reply inside the window still lands before Connected")

        advanceTimeBy(2)
        runCurrent()
        assertTrue(connectJob.await().isSuccess)
        assertEquals(ConnectionState.Connected, client.connection.value)
        assertTrue(events.any { it is MeshEvent.ProtocolWarning && it.message == "session passkey seed timed out" })

        // Firmware checks the passkey only when from != 0 and sets from = 0 on every client packet.
        val owner = User.Builder().also { wb ->
            wb.id = "!0000abcd"
            wb.long_name = "Owner"
            wb.short_name = "OW"
            wb.hw_model = HardwareModel.UNSET
        }.build()
        val write = async { client.admin.setOwner(owner) }
        runCurrent()
        val packet = transport.outboundPackets().last { adminOf(it)?.set_owner == owner }
        assertEquals(0, adminOf(packet)!!.session_passkey.size)
        transport.injectRoutingAck(requestId = packet.id)
        runCurrent()
        assertIs<AdminResult.Success<Unit>>(write.await())
        client.disconnect()
    }

    private fun TestScope.buildClient(transport: FakeRadioTransport): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .autoSyncTimeOnConnect(false)
        .coroutineContext(backgroundScope.coroutineContext)
        .rpcTimeout(60.seconds)
        .sendTimeout(60.seconds)
        .build()

    private fun FakeRadioTransport.inject(block: (FromRadio.Builder) -> Unit) {
        injectFrame(FromRadio.Builder().also(block).build().toFrame())
    }

    private fun FakeRadioTransport.completeStage1() {
        nodeNum = NODE
        inject { it.my_info = MyNodeInfo.Builder().also { m -> m.my_node_num = NODE }.build() }
        inject { it.config_complete_id = NONCE_STAGE1 }
    }

    private fun FakeRadioTransport.answerSeed() {
        val seed = outboundPackets().last { adminOf(it)?.get_owner_request == true }
        injectAdminResponse(
            requestId = seed.id,
            response = AdminMessage.Builder().also { wb ->
                wb.get_owner_response = User.Builder().also { u -> u.long_name = "Seed" }.build()
                wb.session_passkey = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).toByteString()
            }.build(),
        )
    }

    private fun FakeRadioTransport.stage1Requests(): Int = outboundFrames().count { frame ->
        val bytes = frame.bytes.toByteArray()
        bytes.size > 4 &&
            runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }
                .getOrNull()?.want_config_id == NONCE_STAGE1
    }

    private fun adminOf(packet: MeshPacket): AdminMessage? {
        val decoded = packet.decoded ?: return null
        if (decoded.portnum != PortNum.ADMIN_APP) return null
        return runCatching { AdminMessage.ADAPTER.decode(decoded.payload) }.getOrNull()
    }

    private companion object {
        const val NODE = 0x0000ABCD
        const val NONCE_STAGE1 = 69420
        const val NONCE_STAGE2 = 69421
        const val STAGE1_TIMEOUT_MS = 20_000L
        const val SEED_TIMEOUT_MS = 10_000L
    }
}
