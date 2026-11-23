/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.QueueStatus
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
 * `want_config_id` per session, a Stage 2 that only fails without progress, and Ready at the
 * Stage 2 completion with the passkey seed answered afterwards.
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
        assertTrue(connectJob.isCompleted, "Stage 1 must end at STAGE1_TIMEOUT_MS")
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
    fun seedTimeoutWarnsAfterReadyAndLocalWritesStillWork() = runTest {
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
        assertTrue(connectJob.isCompleted, "Ready comes at the Stage 2 completion, not at the seed reply")
        assertTrue(connectJob.await().isSuccess)

        advanceTimeBy(SEED_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(events.any { it is MeshEvent.ProtocolWarning && it.message == SEED_WARNING })

        advanceTimeBy(2)
        runCurrent()
        assertEquals(ConnectionState.Connected, client.connection.value)
        assertTrue(events.any { it is MeshEvent.ProtocolWarning && it.message == SEED_WARNING })

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

    /**
     * Firmware replays each node's stored position, telemetry, environment and status records right
     * after the Stage 2 `config_complete_id` (`PhoneAPI::sendConfigComplete`, `popReplayPacket`), up to
     * four per node for `MAX_SATELLITE_NODES` nodes, and unthrottled on a stream link. The seed reply
     * queues behind them, so none of that replay may wait in the 64-packet handshake buffer.
     */
    @Test
    fun storedReplayBeforeTheSeedReplyIsDeliveredLiveAndTheSeedStillLatches() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:large-replay"), autoQueueStatus = true)
        val client = buildClient(transport, sessionReceive = true)
        val session = mutableListOf<RadioSessionEvent>()
        val sessionJob = backgroundScope.async { runCatching { client.sessionEvents!!.collect { session += it } } }
        val received = mutableListOf<MeshPacket>()
        backgroundScope.launch { client.packets.collect { received += it } }
        val connectJob = backgroundScope.async { runCatching { client.connect() } }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage1 }
        transport.completeStage1()
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }
        transport.inject { it.node_info = NodeInfo.Builder().also { n -> n.num = NODE }.build() }
        transport.inject { it.config_complete_id = NONCE_STAGE2 }
        repeat(REPLAYED) { i -> transport.injectPacket(replayedPosition(PEER_BASE + i)) }
        runCurrent()

        assertFalse(sessionJob.isCompleted, "the session stream closed on the replay")
        assertEquals(REPLAYED, received.count { it.decoded?.portnum == PortNum.POSITION_APP })
        assertEquals(REPLAYED, session.count { it is RadioSessionEvent.Packet })
        assertIs<RadioSessionEvent.Ready>(session.first())
        assertTrue(connectJob.isCompleted && connectJob.await().isSuccess)

        transport.answerSeed()
        runCurrent()
        assertIs<AdminSessionStatus.Active>(client.adminSessions.value[NodeId(NODE)])
        assertFalse(received.any { it.decoded?.portnum == PortNum.ADMIN_APP }, "the seed reply is consumed")
        client.disconnect()
    }

    /**
     * Firmware hands out its whole to-phone queue once Stage 1 completes, and meshtasticd keeps 100
     * packets there by default (`PortduinoGlue.h` `maxtophone`), so a reconnect delivers up to 100
     * held packets inside the settle window.
     */
    @Test
    fun aFullMeshtasticdQueueFlushedAfterStage1IsHeldUntilReady() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:meshtasticd-backlog"), autoQueueStatus = true)
        val client = buildClient(transport, sessionReceive = true)
        val session = mutableListOf<RadioSessionEvent>()
        val sessionJob = backgroundScope.async { runCatching { client.sessionEvents!!.collect { session += it } } }
        val connectJob = backgroundScope.async { runCatching { client.connect() } }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage1 }
        transport.completeStage1()
        runCurrent()
        repeat(MESHTASTICD_TO_PHONE_QUEUE) { i -> transport.injectPacket(replayedPosition(PEER_BASE + i)) }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }
        transport.inject { it.node_info = NodeInfo.Builder().also { n -> n.num = NODE }.build() }
        transport.inject { it.config_complete_id = NONCE_STAGE2 }
        runCurrent()

        assertFalse(sessionJob.isCompleted, "the session stream closed on the backlog")
        assertEquals(MESHTASTICD_TO_PHONE_QUEUE, session.count { it is RadioSessionEvent.Packet })
        assertTrue(connectJob.await().isSuccess)
        client.disconnect()
    }

    /**
     * A collector busy installing the Ready snapshot reads nothing while the replay arrives: up to
     * four packets for each of `MAX_SATELLITE_NODES` (250 on large-memory radios,
     * `mesh-pb-constants.h`), and a packet from a node not yet in the map brings a `NodeChanged`.
     */
    @Test
    fun theDefaultSessionCapacityHoldsAFullReplayWhileTheCollectorIsBusy() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:busy-collector"), autoQueueStatus = true)
        val client = buildClient(transport, sessionReceive = true)
        val installed = CompletableDeferred<Unit>()
        var packets = 0
        val sessionJob = backgroundScope.async {
            runCatching {
                client.sessionEvents!!.collect { event ->
                    if (event is RadioSessionEvent.Ready) installed.await()
                    if (event is RadioSessionEvent.Packet) packets++
                }
            }
        }
        val connectJob = backgroundScope.async { runCatching { client.connect() } }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage1 }
        transport.completeStage1()
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }
        transport.inject { it.node_info = NodeInfo.Builder().also { n -> n.num = NODE }.build() }
        transport.inject { it.config_complete_id = NONCE_STAGE2 }
        repeat(FULL_REPLAY) { i -> transport.injectPacket(replayedPosition(PEER_BASE + i)) }
        runCurrent()
        assertTrue(connectJob.await().isSuccess)

        installed.complete(Unit)
        runCurrent()
        assertFalse(sessionJob.isCompleted, "the session stream overflowed")
        assertEquals(FULL_REPLAY, packets)
        client.disconnect()
    }

    /**
     * Firmware answers a heartbeat with a `QueueStatus` for packet id 0 only since 2.7.4
     * (`PhoneAPI::getFromRadio`, #7506); 2.5.14 to 2.7.3 send nothing back, so a quiet mesh leaves
     * a healthy link silent.
     */
    @Test
    fun aQuietLinkToFirmwareThatNeverAnswersHeartbeatsStaysConnected() = runTest {
        // Firmware of every supported version answers each phone packet with a QueueStatus for that packet's id.
        val transport = FakeRadioTransport(
            TransportIdentity("fake:no-heartbeat-reply"),
            autoHandshake = true,
            autoQueueStatus = true,
        )
        val client = buildClient(transport)
        client.connect()

        advanceTimeBy(5 * 60_000L)
        runCurrent()

        assertEquals(ConnectionState.Connected, client.connection.value)
        client.disconnect()
    }

    @Test
    fun aQuietLinkAfterAHeartbeatReplyFailsLiveness() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:heartbeat-reply"), autoHandshake = true)
        val client = buildClient(transport)
        client.connect()
        transport.inject {
            it.queueStatus = QueueStatus.Builder().also { q ->
                q.free = 16
                q.maxlen = 16
            }.build()
        }
        runCurrent()

        advanceTimeBy(LIVENESS_TIMEOUT_MS + LIVENESS_CHECK_MS)
        runCurrent()

        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    @Test
    fun aBleLinkWithoutPeriodicHeartbeatsHasNoLivenessWatchdog() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("ble:AA:BB:CC:DD:EE:FF"), autoHandshake = true)
        val client = buildClient(transport) { disableBleHeartbeat() }
        client.connect()
        // The reply to the inter-stage heartbeat, which is sent on every transport.
        transport.inject {
            it.queueStatus = QueueStatus.Builder().also { q ->
                q.free = 16
                q.maxlen = 16
            }.build()
        }
        runCurrent()

        advanceTimeBy(5 * 60_000L)
        runCurrent()

        assertEquals(ConnectionState.Connected, client.connection.value)
        client.disconnect()
    }

    /** Android re-arms its Stage 1 stage guard on every progress frame (MeshConnectionManagerImpl). */
    @Test
    fun stage1KeepsDrainingPastTwentySecondsWhileFramesArrive() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:stage1-slow"), autoQueueStatus = true)
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { runCatching { client.connect() } }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage1 }
        transport.nodeNum = NODE
        transport.inject { it.my_info = MyNodeInfo.Builder().also { m -> m.my_node_num = NODE }.build() }
        // A slow BLE config dump: one channel every 15 s for 90 s, past a fixed 20 s budget.
        repeat(6) { i ->
            advanceTimeBy(15_000L)
            transport.inject { it.channel = Channel.Builder().also { c -> c.index = i }.build() }
            runCurrent()
            val state = assertIs<ConnectionState.Configuring>(client.connection.value)
            assertEquals(ConfigPhase.Stage1, state.phase)
        }
        transport.inject { it.config_complete_id = NONCE_STAGE1 }
        runCurrent()
        repeat(2) {
            advanceTimeBy(INTER_STAGE_SETTLE_MS + 1)
            runCurrent()
        }
        transport.inject { it.node_info = NodeInfo.Builder().also { n -> n.num = NODE }.build() }
        transport.inject { it.config_complete_id = NONCE_STAGE2 }
        runCurrent()

        assertTrue(connectJob.await().isSuccess)
        client.disconnect()
    }

    @Test
    fun stage1EndsAtItsCapEvenWhileFramesArrive() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:stage1-cap"))
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { runCatching { client.connect() } }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage1 }
        transport.inject { it.my_info = MyNodeInfo.Builder().also { m -> m.my_node_num = NODE }.build() }
        var elapsed = 0L
        while (elapsed < STAGE1_HARD_CAP_MS - 15_000L) {
            advanceTimeBy(15_000L)
            elapsed += 15_000L
            transport.inject { it.channel = Channel.Builder().also { c -> c.index = 0 }.build() }
            runCurrent()
        }
        assertFalse(connectJob.isCompleted)

        advanceTimeBy(STAGE1_HARD_CAP_MS - elapsed + 1)
        runCurrent()
        assertTrue(connectJob.isCompleted, "Stage 1 must end at STAGE1_HARD_CAP_MS")
        val timeout = assertIs<MeshtasticException.HandshakeTimeout>(connectJob.await().exceptionOrNull())
        assertEquals("Stage 1", timeout.stage)
    }

    /**
     * Firmware before 2.8 writes `rebooted` from the `SerialConsole` constructor at boot, before the
     * PhoneAPI reads input (`SerialConsole.cpp`, `StreamAPI::emitRebooted`), so a `want_config_id`
     * answered only by it went to the previous boot and no dump follows.
     */
    @Test
    fun aRebootBeforeTheStage1DumpEndsTheAttemptWithoutWaitingOutStage1() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("fake:boot-reboot"))
        val client = buildClient(transport)
        val events = mutableListOf<MeshEvent>()
        backgroundScope.launch { client.events.collect { events += it } }
        val connectJob = backgroundScope.async { runCatching { client.connect() } }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage1 }
        transport.inject { it.rebooted = true }
        runCurrent()

        advanceTimeBy(STAGE1_REBOOT_GRACE_MS - 1)
        runCurrent()
        assertFalse(connectJob.isCompleted, "a my_info inside the grace still continues the dump")

        advanceTimeBy(2)
        runCurrent()
        assertTrue(connectJob.isCompleted, "the attempt must end at the reboot grace, not at STAGE1_TIMEOUT_MS")
        val failure = assertIs<MeshtasticException.Transport>(connectJob.await().exceptionOrNull())
        assertTrue(failure.message!!.contains("rebooted"))
        assertTrue(events.any { it is MeshEvent.DeviceRebooted })
        assertEquals(1, transport.stage1Requests(), "no in-session want_config_id resend")
    }

    private fun replayedPosition(from: Int): MeshPacket = MeshPacket.Builder().also { wb ->
        wb.from = from
        wb.to = BROADCAST
        wb.id = from
        wb.transport_mechanism = MeshPacket.TransportMechanism.TRANSPORT_LORA
        wb.decoded = Data.Builder().also { d ->
            d.portnum = PortNum.POSITION_APP
            d.payload = Position.ADAPTER.encode(
                Position.Builder().also { p ->
                    p.latitude_i = 1
                    p.longitude_i = 1
                }.build(),
            ).toByteString()
        }.build()
    }.build()

    private fun TestScope.buildClient(
        transport: FakeRadioTransport,
        sessionReceive: Boolean = false,
        configure: RadioClient.Builder.() -> Unit = {},
    ): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .autoSyncTimeOnConnect(false)
        .coroutineContext(backgroundScope.coroutineContext)
        .rpcTimeout(60.seconds)
        .sendTimeout(60.seconds)
        .apply { if (sessionReceive) sessionReceive() }
        .apply(configure)
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

        // Android's stage-guard ceiling: 10 x the budget (MeshConnectionManagerImpl STAGE_GUARD_TOTAL_CAP_FACTOR).
        const val STAGE1_HARD_CAP_MS = 200_000L
        const val INTER_STAGE_SETTLE_MS = 100L
        const val STAGE1_REBOOT_GRACE_MS = 2_000L
        const val SEED_TIMEOUT_MS = 10_000L
        const val SEED_WARNING = "session passkey seed timed out"
        const val LIVENESS_TIMEOUT_MS = 60_000L
        const val LIVENESS_CHECK_MS = 30_000L
        const val BROADCAST = -1
        const val PEER_BASE = 0x2000

        // MAX_NUM_NODES (120) peers with a stored position: more than HANDSHAKE_PACKET_BUFFER_CAP (64).
        const val REPLAYED = 120

        // PortduinoGlue.h maxtophone default.
        const val MESHTASTICD_TO_PHONE_QUEUE = 100

        // Four replay phases (PhoneAPI::popReplayPacket) x MAX_SATELLITE_NODES 250; one packet per new node here,
        // the worst case of two session events per packet.
        const val FULL_REPLAY = 1000
    }
}
