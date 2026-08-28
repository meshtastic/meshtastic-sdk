/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import app.cash.turbine.test
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage for the config-only handshake opt-in
 * ([RadioClient.Builder.skipNodeDb] / [RadioClient.Builder.configOnly]).
 *
 * The device streams configuration under `want_config_id = 69420` and the node database under
 * `69421` (see `docs/protocol.md` §6). A config-only session stops after the first nonce, which
 * on BLE removes the bulk of connect latency on a large mesh. These tests pin:
 *
 * - `69421` never reaches the wire, and `ConfigPhase.Stage2` is never projected.
 * - Everything Stage 1 carries (config bundle, channels, own node) still commits, the admin
 *   session passkey is still seeded, and the session still reaches [ConnectionState.Connected].
 * - Peer nodes are absent from the session's node map — that's the trade the flag buys.
 * - The default builder, and an explicit `skipNodeDb(false)`, keep the two-stage handshake.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfigOnlyHandshakeTest {

    @Test
    fun configOnlyConnectSkipsTheStage2Request() = runTest {
        val transport = ScriptedDevice(TransportIdentity("fake:config-only"))
        val client = buildClient(transport, skipNodeDb = true)

        client.connect()

        assertEquals(ConnectionState.Connected, client.connection.value)
        val nonces = transport.outboundToRadio().mapNotNull { it.want_config_id }
        assertEquals(listOf(NONCE_STAGE1), nonces, "config-only must request Stage 1 only")
    }

    @Test
    fun configOnlyStillSendsTheInterStageHeartbeatBeforeCommitting() = runTest {
        val transport = ScriptedDevice(TransportIdentity("fake:config-only-heartbeat"))
        val client = buildClient(transport, skipNodeDb = true)

        client.connect()

        val outbound = transport.outboundToRadio()
        val stage1Index = outbound.indexOfFirst { it.want_config_id == NONCE_STAGE1 }
        val heartbeatIndex = outbound.indexOfFirst { it.heartbeat?.nonce == 0 }
        assertTrue(stage1Index >= 0, "Stage 1 nonce must be sent")
        assertTrue(heartbeatIndex > stage1Index, "settle heartbeat must still follow Stage 1")
    }

    @Test
    fun configOnlyCommitsStage1StateAndSeedsTheSessionPasskey() = runTest {
        val transport = ScriptedDevice(TransportIdentity("fake:config-only-state"))
        val client = buildClient(transport, skipNodeDb = true)

        client.connect()

        val bundle = assertNotNull(client.configBundle.value, "config bundle must commit")
        assertEquals("2.7.0", bundle.metadata.firmware_version)
        assertEquals(1, bundle.configs.size)
        assertEquals(1, bundle.moduleConfigs.size)
        assertEquals(listOf(Channel(index = 0, role = Channel.Role.PRIMARY)), client.channels.value)
        assertEquals(DEVICE_NODE_NUM, assertNotNull(client.ownNode.value).num)

        // The seed RPC is what makes AdminApi writes usable; it must still go out.
        val seeded = transport.outboundToRadio()
            .mapNotNull { it.packet }
            .any { adminOf(it)?.get_owner_request == true }
        assertTrue(seeded, "get_owner_request must still seed the admin session passkey")
    }

    @Test
    fun configOnlyLeavesPeerNodesOutOfTheSessionNodeMap() = runTest {
        val transport = ScriptedDevice(TransportIdentity("fake:config-only-nodes"))
        val client = buildClient(transport, skipNodeDb = true)

        client.connect()

        // Only the device's own NodeInfo — streamed in Stage 1 — is known.
        assertEquals(setOf(NodeId(DEVICE_NODE_NUM)), client.nodeSnapshot().keys)
    }

    @Test
    fun configOnlyNeverProjectsStage2Phase() = runTest {
        val transport = ScriptedDevice(TransportIdentity("fake:config-only-phases"))
        val client = buildClient(transport, skipNodeDb = true)

        client.connection.test {
            assertEquals(ConnectionState.Disconnected, awaitItem())

            val connectJob = backgroundScope.async { client.connect() }

            assertIs<ConnectionState.Connecting>(awaitItem())
            assertEquals(ConfigPhase.Stage1, assertIs<ConnectionState.Configuring>(awaitItem()).phase)
            assertEquals(ConfigPhase.Settling, assertIs<ConnectionState.Configuring>(awaitItem()).phase)
            assertEquals(ConnectionState.Connected, awaitItem())

            connectJob.await()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun configOnlyAliasMatchesSkipNodeDb() = runTest {
        val transport = ScriptedDevice(TransportIdentity("fake:config-only-alias"))
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .clock(SchedulerClock { currentTime })
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .rpcTimeout(60.seconds)
            .configOnly()
            .build()

        client.connect()

        assertEquals(ConnectionState.Connected, client.connection.value)
        assertEquals(
            listOf(NONCE_STAGE1),
            transport.outboundToRadio().mapNotNull { it.want_config_id },
        )
    }

    @Test
    fun defaultBuilderStillDownloadsTheNodeDb() = runTest {
        val transport = ScriptedDevice(TransportIdentity("fake:two-stage-default"))
        val client = buildClient(transport, skipNodeDb = false)

        client.connect()

        assertEquals(
            listOf(NONCE_STAGE1, NONCE_STAGE2),
            transport.outboundToRadio().mapNotNull { it.want_config_id },
        )
        assertEquals(
            setOf(NodeId(DEVICE_NODE_NUM)) + PEER_NODE_NUMS.map { NodeId(it) },
            client.nodeSnapshot().keys,
        )
    }

    @Test
    fun skipNodeDbFalseRestoresTheTwoStageHandshake() = runTest {
        val transport = ScriptedDevice(TransportIdentity("fake:skip-false"))
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .clock(SchedulerClock { currentTime })
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .rpcTimeout(60.seconds)
            .skipNodeDb(false)
            .build()

        client.connect()

        assertTrue(
            transport.outboundToRadio().any { it.want_config_id == NONCE_STAGE2 },
            "skipNodeDb(false) must keep the NodeDB request",
        )
    }

    // ── Harness ───────────────────────────────────────────────────────────────

    private fun TestScope.buildClient(transport: RadioTransport, skipNodeDb: Boolean): RadioClient =
        RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .clock(SchedulerClock { currentTime })
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .rpcTimeout(60.seconds)
            .skipNodeDb(skipNodeDb)
            .build()

    private fun adminOf(packet: MeshPacket): AdminMessage? {
        val payload = packet.decoded?.payload ?: return null
        return runCatching { AdminMessage.ADAPTER.decode(payload) }.getOrNull()
    }

    private class SchedulerClock(private val nowMs: () -> Long) : kotlin.time.Clock {
        override fun now(): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(nowMs())
    }

    /**
     * Scripted radio that mirrors the firmware's nonce split: `69420` streams metadata, config,
     * module config, channels and the device's own `NodeInfo`; `69421` streams the peer NodeDB.
     */
    private class ScriptedDevice(override val identity: TransportIdentity) : RadioTransport {

        private val stateFlow = MutableStateFlow<TransportState>(TransportState.Disconnected)
        private val inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 128)
        private val sent = mutableListOf<Frame>()

        override val state: StateFlow<TransportState> = stateFlow

        override suspend fun connect() {
            stateFlow.value = TransportState.Connecting
            stateFlow.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            stateFlow.value = TransportState.Disconnected
        }

        override suspend fun send(frame: Frame) {
            sent += frame
            val toRadio = decodeToRadio(frame) ?: return
            when (toRadio.want_config_id) {
                NONCE_STAGE1 -> streamStage1()
                NONCE_STAGE2 -> streamStage2()
            }
            toRadio.packet?.let(::answerGetOwner)
        }

        override fun frames(): Flow<Frame> = inbound

        fun outboundToRadio(): List<ToRadio> = sent.mapNotNull(::decodeToRadio)

        private fun streamStage1() {
            emit(FromRadio(my_info = MyNodeInfo(my_node_num = DEVICE_NODE_NUM)))
            emit(FromRadio(node_info = NodeInfo(num = DEVICE_NODE_NUM)))
            emit(FromRadio(metadata = DeviceMetadata(firmware_version = "2.7.0")))
            emit(FromRadio(channel = Channel(index = 0, role = Channel.Role.PRIMARY)))
            emit(FromRadio(config = Config(lora = Config.LoRaConfig(use_preset = true))))
            emit(FromRadio(moduleConfig = ModuleConfig(mqtt = ModuleConfig.MQTTConfig(enabled = false))))
            emit(FromRadio(config_complete_id = NONCE_STAGE1))
        }

        private fun streamStage2() {
            for (num in PEER_NODE_NUMS) emit(FromRadio(node_info = NodeInfo(num = num)))
            emit(FromRadio(config_complete_id = NONCE_STAGE2))
        }

        private fun answerGetOwner(packet: MeshPacket) {
            val payload = packet.decoded?.payload ?: return
            val admin = runCatching { AdminMessage.ADAPTER.decode(payload) }.getOrNull() ?: return
            if (admin.get_owner_request != true) return
            val response = AdminMessage(
                get_owner_response = User(
                    id = "!0000002a",
                    long_name = "ScriptedNode",
                    short_name = "SN",
                    hw_model = HardwareModel.UNSET,
                ),
                session_passkey = byteArrayOf(1, 2, 3, 4).toByteString(),
            )
            emit(
                FromRadio(
                    packet = MeshPacket(
                        from = DEVICE_NODE_NUM,
                        to = 0,
                        decoded = Data(
                            portnum = PortNum.ADMIN_APP,
                            payload = AdminMessage.ADAPTER.encode(response).toByteString(),
                            request_id = packet.id,
                        ),
                    ),
                ),
            )
        }

        private fun emit(fromRadio: FromRadio) {
            inbound.tryEmit(fromRadio.toFrame())
        }

        private fun decodeToRadio(frame: Frame): ToRadio? {
            val bytes = frame.bytes.toByteArray()
            if (bytes.size < 4) return null
            return runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull()
        }
    }

    private companion object {
        const val NONCE_STAGE1 = 69420
        const val NONCE_STAGE2 = 69421
        const val DEVICE_NODE_NUM = 42
        val PEER_NODE_NUMS = listOf(43, 44, 45)
    }
}
