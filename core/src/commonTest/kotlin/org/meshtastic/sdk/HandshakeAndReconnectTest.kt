/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Data
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HandshakeAndReconnectTest {

    @Test
    fun handshakeHappyPathTransitionsStage1Stage2AndConnected() = runTest {
        val transport = ScriptedTransport(TransportIdentity("fake:handshake-happy"), nowMs = { currentTime })
        val client = buildClient(transport)

        client.connection.test {
            assertEquals(ConnectionState.Disconnected, awaitItem())

            val connectJob = backgroundScope.async { client.connect() }

            assertIs<ConnectionState.Connecting>(awaitItem())
            assertEquals(ConfigPhase.Stage1, assertIs<ConnectionState.Configuring>(awaitItem()).phase)
            assertEquals(ConfigPhase.Settling, assertIs<ConnectionState.Configuring>(awaitItem()).phase)
            assertEquals(ConfigPhase.Stage2, assertIs<ConnectionState.Configuring>(awaitItem()).phase)
            assertEquals(ConnectionState.Connected, awaitItem())

            connectJob.await()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun handshakeHappyPathSendsStage1HeartbeatAndStage2InOrder() = runTest {
        val transport = ScriptedTransport(TransportIdentity("fake:handshake-wire"), nowMs = { currentTime })
        val client = buildClient(transport)

        client.connect()

        val outbound = transport.outboundFrames().mapNotNull(::decodeToRadioOrNull)
        val stage1Index = outbound.indexOfFirst { it.want_config_id == NONCE_STAGE1 }
        val heartbeatIndex = outbound.indexOfFirst { it.heartbeat?.nonce == 0 }
        val stage2Index = outbound.indexOfFirst { it.want_config_id == NONCE_STAGE2 }

        assertTrue(stage1Index >= 0, "Stage 1 nonce must be sent")
        assertTrue(heartbeatIndex >= 0, "Inter-stage heartbeat must be sent")
        assertTrue(stage2Index >= 0, "Stage 2 nonce must be sent")
        assertTrue(stage1Index < heartbeatIndex, "Heartbeat must follow Stage 1")
        assertTrue(heartbeatIndex < stage2Index, "Stage 2 must follow the heartbeat settle")
    }

    @Test
    fun stage2WatchdogDoesNotFireBeforeTickDeadline() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:stage2-pre-tick"),
            nowMs = { currentTime },
            autoCompleteStage2 = false,
        )
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { runCatching { client.connect() } }

        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }
        val stage2StartedAt = currentTime

        advanceTimeBy(STAGE2_PROGRESS_TICK_MS - 1)
        runCurrent()

        val state = assertIs<ConnectionState.Configuring>(client.connection.value)
        assertEquals(ConfigPhase.Stage2, state.phase)
        assertFalse(connectJob.isCompleted, "Connect must still be pending before the watchdog tick")
        assertEquals(STAGE2_PROGRESS_TICK_MS - 1, currentTime - stage2StartedAt)

        client.disconnect()
        assertIs<CancellationException>(connectJob.await().exceptionOrNull())
    }

    @Test
    fun stage2WatchdogAbortsSilentHandshake() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:stage2-silent"),
            nowMs = { currentTime },
            autoCompleteStage2 = false,
        )
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { runCatching { client.connect() } }

        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }
        val stage2StartedAt = currentTime

        advanceTimeBy(STAGE2_PROGRESS_TICK_MS + 1)
        runCurrent()

        val error = connectJob.await().exceptionOrNull()
        val timeout = assertIs<MeshtasticException.HandshakeTimeout>(error)
        assertEquals("Stage 2", timeout.stage)
        assertEquals(ConnectionState.Disconnected, client.connection.value)
        assertTrue(currentTime - stage2StartedAt >= STAGE2_PROGRESS_TICK_MS)
    }

    @Test
    fun stage2WatchdogSlidesWhileProgressFramesArrive() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:stage2-sliding"),
            nowMs = { currentTime },
            autoCompleteStage2 = false,
        )
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { runCatching { client.connect() } }

        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }
        val stage2StartedAt = currentTime

        repeat(2) { index ->
            advanceTimeBy(STAGE2_PROGRESS_TICK_MS - 1_000L)
            runCurrent()
            transport.injectStage2Progress(node = 0x1200 + index)
            runCurrent()
            val state = assertIs<ConnectionState.Configuring>(client.connection.value)
            assertEquals(ConfigPhase.Stage2, state.phase)
            assertFalse(connectJob.isCompleted, "Progress frame #${index + 1} should keep Stage 2 alive")
        }

        assertEquals(2 * (STAGE2_PROGRESS_TICK_MS - 1_000L), currentTime - stage2StartedAt)

        client.disconnect()
        assertIs<CancellationException>(connectJob.await().exceptionOrNull())
    }

    @Test
    fun stage2HardCapAbortsEvenWithProgress() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:stage2-hard-cap"),
            nowMs = { currentTime },
            autoCompleteStage2 = false,
        )
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { runCatching { client.connect() } }

        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }
        val stage2StartedAt = currentTime

        advanceTimeBy(STAGE2_PROGRESS_TICK_MS - 1_000L)
        runCurrent()
        transport.injectStage2Progress(node = 0x2001)
        runCurrent()

        advanceTimeBy(STAGE2_PROGRESS_TICK_MS - 1_000L)
        runCurrent()
        transport.injectStage2Progress(node = 0x2002)
        runCurrent()
        assertFalse(connectJob.isCompleted)

        advanceTimeBy(2_000L)
        runCurrent()

        val error = connectJob.await().exceptionOrNull()
        val timeout = assertIs<MeshtasticException.HandshakeTimeout>(error)
        assertEquals("Stage 2", timeout.stage)
        assertTrue(currentTime - stage2StartedAt >= STAGE2_HARD_CAP_MS)
    }

    @Test
    fun connectionLostDuringStage1ResetsCleanlyAndAllowsRetry() = runTest {
        val firstTransport = ScriptedTransport(TransportIdentity("fake:drop-stage1"), nowMs = { currentTime })
        firstTransport.dropStage1OnNextHandshake = true
        val firstClient = buildClient(firstTransport)

        val firstConnect = backgroundScope.async { runCatching { firstClient.connect() } }
        firstClient.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage1 }
        runCurrent()

        assertIs<MeshtasticException.Transport>(firstConnect.await().exceptionOrNull())
        assertEquals(ConnectionState.Disconnected, firstClient.connection.value)

        val retryClient =
            buildClient(ScriptedTransport(TransportIdentity("fake:drop-stage1-retry"), nowMs = { currentTime }))
        retryClient.connect()
        assertEquals(ConnectionState.Connected, retryClient.connection.value)
    }

    @Test
    fun connectionLostDuringStage2ResetsCleanlyAndCancelsOldWatchdog() = runTest {
        val firstTransport = ScriptedTransport(
            identity = TransportIdentity("fake:drop-stage2"),
            nowMs = { currentTime },
            autoCompleteStage2 = false,
        )
        firstTransport.dropStage2OnNextHandshake = true
        val firstClient = buildClient(firstTransport)

        val firstConnect = backgroundScope.async { runCatching { firstClient.connect() } }
        firstClient.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }
        runCurrent()

        assertIs<MeshtasticException.Transport>(firstConnect.await().exceptionOrNull())
        assertEquals(ConnectionState.Disconnected, firstClient.connection.value)

        val retryClient =
            buildClient(ScriptedTransport(TransportIdentity("fake:drop-stage2-retry"), nowMs = { currentTime }))
        retryClient.connect()
        advanceTimeBy(STAGE2_PROGRESS_TICK_MS + 5_000L)
        runCurrent()

        assertEquals(ConnectionState.Connected, retryClient.connection.value)
    }

    @Test
    fun failedStage2TimeoutCanBeRetriedWithoutDanglingCoroutines() = runTest {
        val firstTransport = ScriptedTransport(
            identity = TransportIdentity("fake:stage2-timeout-retry"),
            nowMs = { currentTime },
            autoCompleteStage2 = false,
        )
        val firstClient = buildClient(firstTransport)

        val firstConnect = backgroundScope.async { runCatching { firstClient.connect() } }
        firstClient.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }
        advanceTimeBy(STAGE2_PROGRESS_TICK_MS + 1)
        runCurrent()

        val error = firstConnect.await().exceptionOrNull()
        assertIs<MeshtasticException.HandshakeTimeout>(error)
        assertEquals(ConnectionState.Disconnected, firstClient.connection.value)

        val retryClient =
            buildClient(
                ScriptedTransport(TransportIdentity("fake:stage2-timeout-retry-success"), nowMs = {
                    currentTime
                }),
            )
        retryClient.connect()
        advanceTimeBy(STAGE2_PROGRESS_TICK_MS + 5_000L)
        runCurrent()

        assertEquals(ConnectionState.Connected, retryClient.connection.value)
    }

    @Test
    fun stage1RedrainDoesNotDuplicateConfigOrChannels() = runTest {
        // A want_config_id retry restarts the firmware's config drain from scratch, so the
        // same config sections / channels can stream twice. The committed bundle must hold
        // one entry per section/index, with the latest occurrence winning.
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:stage1-redrain"),
            nowMs = { currentTime },
            autoCompleteStage1 = false,
        )
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { client.connect() }
        runCurrent()

        val channel0 = org.meshtastic.proto.Channel(
            index = 0,
            role = org.meshtastic.proto.Channel.Role.PRIMARY,
            settings = org.meshtastic.proto.ChannelSettings(name = "LongFast"),
        )
        transport.injectFromRadio(org.meshtastic.proto.FromRadio(my_info = MyNodeInfo(my_node_num = transport.nodeNum)))
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio(
                config = org.meshtastic.proto.Config(
                    lora = org.meshtastic.proto.Config.LoRaConfig(use_preset = true),
                ),
            ),
        )
        transport.injectFromRadio(org.meshtastic.proto.FromRadio(channel = channel0))
        // Re-drain: the same section + channel stream again (latest value wins).
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio(
                config = org.meshtastic.proto.Config(
                    lora = org.meshtastic.proto.Config.LoRaConfig(use_preset = false),
                ),
            ),
        )
        transport.injectFromRadio(org.meshtastic.proto.FromRadio(channel = channel0))
        transport.injectFromRadio(org.meshtastic.proto.FromRadio(config_complete_id = NONCE_STAGE1))
        drainCurrent()

        // Settle windows (100 ms each) → heartbeat → Stage 2 (auto-completed) → seeding → Ready.
        repeat(4) {
            advanceTimeBy(150L)
            drainCurrent()
        }
        connectJob.await()
        assertEquals(ConnectionState.Connected, client.connection.value)

        val bundle = assertNotNull(client.configBundle.value)
        assertEquals(1, bundle.configs.size, "Re-drained config sections must not duplicate")
        assertEquals(false, bundle.configs.single().lora?.use_preset, "Latest occurrence must win")
        assertEquals(1, client.channels.value?.size, "Re-drained channels must not duplicate")
    }

    @Test
    fun packetsReceivedMidHandshakeAreDeliveredAfterConnected() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:handshake-packet-buffer"),
            nowMs = { currentTime },
            autoCompleteStage2 = false,
        )
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { client.connect() }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }

        client.packets.test {
            // Live mesh traffic interleaved with the Stage 2 drain must not be lost — and
            // must not be delivered before the session is Ready.
            transport.injectAlivePacket(packetId = 777)
            drainCurrent()
            expectNoEvents()

            transport.injectFromRadio(org.meshtastic.proto.FromRadio(config_complete_id = NONCE_STAGE2))
            drainCurrent()
            connectJob.await()
            assertEquals(ConnectionState.Connected, client.connection.value)

            assertEquals(777, awaitItem().id, "Buffered handshake packet must flush at Ready")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun remoteAdminUsesPasskeyIssuedByTheTargetNode() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:session-passkey"),
            nowMs = { currentTime },
            sessionPasskey = SEEDED_PASSKEY,
        )
        val client = buildClient(transport)
        val remoteNode = NodeId(0x0BEEF)
        client.connect()
        runCurrent()

        val outboundBefore = transport.outboundPackets().size
        val expected = org.meshtastic.proto.Config(
            lora = org.meshtastic.proto.Config.LoRaConfig(use_preset = true),
        )
        val deferred = backgroundScope.async {
            client.admin.forNode(remoteNode).getConfig(AdminMessage.ConfigType.LORA_CONFIG)
        }
        runCurrent()

        // Session passkeys are per-node: the target has never issued us one, and the *local*
        // node's seeded passkey must NOT leak onto a remote-bound admin packet.
        val request = transport.outboundPackets().drop(outboundBefore)
            .last { it.to == remoteNode.raw && adminOf(it)?.get_config_request == AdminMessage.ConfigType.LORA_CONFIG }
        val admin = assertNotNull(adminOf(request))
        assertEquals(0, admin.session_passkey.size, "Local passkey must not be stamped on a remote target")

        // The response carries the remote node's own passkey (firmware refreshes it in every
        // admin response); the engine latches it keyed by the responder.
        transport.injectAdminResponse(
            requestId = request.id,
            response = AdminMessage(get_config_response = expected, session_passkey = REMOTE_PASSKEY.toByteString()),
            fromNode = remoteNode.raw,
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<org.meshtastic.proto.Config>>(result)
        assertEquals(expected, result.value)

        // A follow-up RPC to the same remote node is stamped with the passkey *it* issued.
        val outboundBeforeSecond = transport.outboundPackets().size
        val second = backgroundScope.async {
            client.admin.forNode(remoteNode).getConfig(AdminMessage.ConfigType.LORA_CONFIG)
        }
        runCurrent()
        val secondRequest = transport.outboundPackets().drop(outboundBeforeSecond)
            .last { it.to == remoteNode.raw && adminOf(it)?.get_config_request == AdminMessage.ConfigType.LORA_CONFIG }
        assertContentEquals(REMOTE_PASSKEY, adminOf(secondRequest)?.session_passkey?.toByteArray())

        transport.injectAdminResponse(
            requestId = secondRequest.id,
            response = AdminMessage(get_config_response = expected),
            fromNode = remoteNode.raw,
        )
        runCurrent()
        assertIs<AdminResult.Success<org.meshtastic.proto.Config>>(second.await())
    }

    @Test
    fun sessionKeyExpiryAfterThreeHundredSecondsReauthenticatesTransparently() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:session-expiry"),
            nowMs = { currentTime },
            sessionPasskey = SEEDED_PASSKEY,
        )
        val client = buildClient(transport)
        val remoteNode = NodeId(0x0CAFE)
        client.connect()
        runCurrent()

        keepSessionAlive(transport, 300.seconds.inWholeMilliseconds)

        val outboundBefore = transport.outboundPackets().size
        val expected = org.meshtastic.proto.Config(
            lora = org.meshtastic.proto.Config.LoRaConfig(
                region = org.meshtastic.proto.Config.LoRaConfig.RegionCode.US,
            ),
        )
        val deferred = backgroundScope.async {
            client.admin.forNode(remoteNode).getConfig(AdminMessage.ConfigType.LORA_CONFIG)
        }
        runCurrent()

        val firstRemote = transport.outboundPackets().drop(outboundBefore)
            .first { it.to == remoteNode.raw && adminOf(it)?.get_config_request == AdminMessage.ConfigType.LORA_CONFIG }
        assertEquals(0, adminOf(firstRemote)?.session_passkey?.size ?: -1)

        transport.injectRoutingError(firstRemote.id, Routing.Error.ADMIN_BAD_SESSION_KEY, fromNode = remoteNode.raw)
        drainCurrent()

        // Re-seed targets the *remote* node — its passkey is the one the retry needs.
        val reseed = transport.outboundPackets().drop(outboundBefore).firstOrNull {
            it.to == remoteNode.raw && adminOf(it)?.get_owner_request == true
        }
        assertNotNull(reseed, "Session expiry must trigger a get_owner re-seed against the target node")

        transport.injectAdminResponse(
            requestId = reseed.id,
            response = AdminMessage(
                get_owner_response = User(id = "!0000beef", long_name = "Remote", short_name = "RN"),
                session_passkey = REMOTE_PASSKEY.toByteString(),
            ),
            fromNode = remoteNode.raw,
        )
        drainCurrent()

        val replay = transport.outboundPackets().drop(outboundBefore).last {
            it.to == remoteNode.raw && adminOf(it)?.get_config_request == AdminMessage.ConfigType.LORA_CONFIG
        }
        assertTrue(replay.id != firstRemote.id, "Replay must use a fresh wire id")
        assertContentEquals(REMOTE_PASSKEY, adminOf(replay)?.session_passkey?.toByteArray())

        transport.injectAdminResponse(
            requestId = replay.id,
            response = AdminMessage(get_config_response = expected),
            fromNode = remoteNode.raw,
        )
        runCurrent()

        val result = deferred.await()
        assertIs<AdminResult.Success<org.meshtastic.proto.Config>>(result)
        assertEquals(expected, result.value)
    }

    @Test
    fun sessionKeyExpiryRetriesOnlyOnceThenSurfacesFailure() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:session-expiry-single-shot"),
            nowMs = { currentTime },
            sessionPasskey = SEEDED_PASSKEY,
        )
        val client = buildClient(transport)
        val remoteNode = NodeId(0x0D00D)
        client.connect()
        runCurrent()

        keepSessionAlive(transport, 300.seconds.inWholeMilliseconds)

        val outboundBefore = transport.outboundPackets().size
        val deferred = backgroundScope.async {
            client.admin.forNode(remoteNode).getConfig(AdminMessage.ConfigType.LORA_CONFIG)
        }
        runCurrent()

        val firstRemote = transport.outboundPackets().drop(outboundBefore)
            .first { it.to == remoteNode.raw && adminOf(it)?.get_config_request == AdminMessage.ConfigType.LORA_CONFIG }
        transport.injectRoutingError(firstRemote.id, Routing.Error.ADMIN_BAD_SESSION_KEY, fromNode = remoteNode.raw)
        drainCurrent()

        // Answer the (remote-targeted) re-seed so the single retry can proceed.
        val reseed = transport.outboundPackets().drop(outboundBefore)
            .first { it.to == remoteNode.raw && adminOf(it)?.get_owner_request == true }
        transport.injectAdminResponse(
            requestId = reseed.id,
            response = AdminMessage(
                get_owner_response = User(id = "!0000d00d", long_name = "Remote", short_name = "RN"),
                session_passkey = REMOTE_PASSKEY.toByteString(),
            ),
            fromNode = remoteNode.raw,
        )
        drainCurrent()

        val replay = transport.outboundPackets().drop(outboundBefore)
            .last { it.to == remoteNode.raw && adminOf(it)?.get_config_request == AdminMessage.ConfigType.LORA_CONFIG }
        assertTrue(replay.id != firstRemote.id, "Replay must use a fresh wire id")
        transport.injectRoutingError(replay.id, Routing.Error.ADMIN_BAD_SESSION_KEY, fromNode = remoteNode.raw)
        drainCurrent()

        assertEquals(AdminResult.SessionKeyExpired, deferred.await())
        assertEquals(
            1,
            transport.outboundPackets().drop(outboundBefore).count {
                it.to == remoteNode.raw && adminOf(it)?.get_owner_request == true
            },
            "Re-authentication must be single-shot",
        )
    }

    @Test
    fun autoReconnectWaitsInitialBackoffBeforeRetrying() = runTest {
        val transport = ScriptedTransport(TransportIdentity("fake:reconnect-initial"), nowMs = { currentTime })
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(
                enabled = true,
                initialBackoff = 1.seconds,
                maxBackoff = 8.seconds,
                jitter = 0.0,
            ),
        )
        client.connect()
        runCurrent()

        val droppedAt = currentTime
        transport.simulateRecoverableError("drop-once")
        runCurrent()
        assertReconnectState(client.connection.value, attempt = 1)

        advanceTimeBy(999L)
        runCurrent()
        assertEquals(0, transport.reconnectAttemptTimes().size)

        advanceTimeBy(1L)
        drainCurrent()

        awaitConnected(client)

        assertEquals(listOf(1_000L), transport.reconnectAttemptTimes().map { it - droppedAt })
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun autoReconnectBackoffDoublesAcrossFailures() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:reconnect-double"),
            nowMs = { currentTime },
            reconnectOutcomes = listOf(
                ConnectOutcome.Fail("attempt-1"),
                ConnectOutcome.Fail("attempt-2"),
                ConnectOutcome.Success,
            ),
        )
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(
                enabled = true,
                initialBackoff = 1.seconds,
                maxBackoff = 8.seconds,
                jitter = 0.0,
            ),
        )
        client.connect()
        runCurrent()

        val droppedAt = currentTime
        transport.simulateRecoverableError("backoff-double")
        runCurrent()

        advanceTimeBy(1_000L)
        drainCurrent()
        advanceTimeBy(2_000L)
        drainCurrent()
        advanceTimeBy(4_000L)
        drainCurrent()
        awaitConnected(client)

        assertEquals(listOf(1_000L, 3_000L, 7_000L), transport.reconnectAttemptTimes().map { it - droppedAt })
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun autoReconnectBackoffRespectsMaxCap() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:reconnect-cap"),
            nowMs = { currentTime },
            reconnectOutcomes = listOf(
                ConnectOutcome.Fail("attempt-1"),
                ConnectOutcome.Fail("attempt-2"),
                ConnectOutcome.Fail("attempt-3"),
                ConnectOutcome.Success,
            ),
        )
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(
                enabled = true,
                initialBackoff = 1.seconds,
                maxBackoff = 4.seconds,
                jitter = 0.0,
            ),
        )
        client.connect()
        runCurrent()

        val droppedAt = currentTime
        transport.simulateRecoverableError("backoff-cap")
        runCurrent()

        advanceTimeBy(1_000L)
        drainCurrent()
        advanceTimeBy(2_000L)
        drainCurrent()
        advanceTimeBy(4_000L)
        drainCurrent()
        advanceTimeBy(4_000L)
        drainCurrent()
        awaitConnected(client)

        assertEquals(listOf(1_000L, 3_000L, 7_000L, 11_000L), transport.reconnectAttemptTimes().map { it - droppedAt })
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun maxReconnectAttemptsStopAfterConfiguredCap() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:reconnect-max-attempts"),
            nowMs = { currentTime },
            reconnectOutcomes = listOf(
                ConnectOutcome.Fail("attempt-1"),
                ConnectOutcome.Fail("attempt-2"),
                ConnectOutcome.Fail("attempt-3"),
                ConnectOutcome.Success,
            ),
        )
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(
                enabled = true,
                initialBackoff = 1.seconds,
                maxBackoff = 8.seconds,
                maxAttempts = 3,
                jitter = 0.0,
            ),
        )
        client.connect()
        runCurrent()

        transport.simulateRecoverableError("retry-cap")
        runCurrent()

        advanceTimeBy(1_000L)
        drainCurrent()
        advanceTimeBy(2_000L)
        drainCurrent()
        advanceTimeBy(4_000L)
        drainCurrent()
        advanceTimeBy(20_000L)
        drainCurrent()

        assertEquals(3, transport.reconnectAttemptTimes().size)
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    @Test
    fun reconnectAfterSuccessStartsFreshBackoffCounter() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:reconnect-reset"),
            nowMs = { currentTime },
            reconnectOutcomes = listOf(
                ConnectOutcome.Fail("attempt-1"),
                ConnectOutcome.Success,
                ConnectOutcome.Success,
            ),
        )
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(
                enabled = true,
                initialBackoff = 1.seconds,
                maxBackoff = 8.seconds,
                jitter = 0.0,
            ),
        )
        client.connect()
        runCurrent()

        val firstDropAt = currentTime
        transport.simulateRecoverableError("first-drop")
        runCurrent()

        advanceTimeBy(1_000L)
        drainCurrent()
        advanceTimeBy(2_000L)
        drainCurrent()
        awaitConnected(client)
        assertEquals(listOf(1_000L, 3_000L), transport.reconnectAttemptTimes().map { it - firstDropAt })
        assertEquals(ConnectionState.Connected, client.connection.value)

        val secondDropAt = currentTime
        transport.simulateRecoverableError("second-drop")
        runCurrent()
        assertReconnectState(client.connection.value, attempt = 1)

        advanceTimeBy(1_000L)
        drainCurrent()
        awaitConnected(client)

        val attempts = transport.reconnectAttemptTimes()
        assertEquals(1_000L, attempts.last() - secondDropAt)
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    private fun TestScope.buildClient(
        transport: RadioTransport,
        autoReconnect: AutoReconnectConfig = AutoReconnectConfig.Disabled,
    ): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .clock(SchedulerClock { currentTime })
        .coroutineContext(backgroundScope.coroutineContext)
        .autoSyncTimeOnConnect(false)
        .rpcTimeout(60.seconds)
        .autoReconnect(autoReconnect)
        .build()

    private fun assertReconnectState(state: ConnectionState, attempt: Int) {
        val reconnecting = assertIs<ConnectionState.Reconnecting>(state)
        assertEquals(attempt, reconnecting.attempt)
    }

    private fun TestScope.drainCurrent(times: Int = 20) {
        repeat(times) { runCurrent() }
    }

    private fun TestScope.keepSessionAlive(transport: ScriptedTransport, totalMs: Long) {
        var remaining = totalMs
        var packetId = 10_000
        while (remaining > 0) {
            val step = minOf(25_000L, remaining)
            advanceTimeBy(step)
            drainCurrent()
            transport.injectAlivePacket(packetId++)
            drainCurrent()
            remaining -= step
        }
    }

    private fun TestScope.awaitConnected(client: RadioClient) {
        repeat(10) {
            if (client.connection.value == ConnectionState.Connected) return
            advanceTimeBy(100L)
            drainCurrent()
        }
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    private class SchedulerClock(private val nowMs: () -> Long) : kotlin.time.Clock {
        override fun now(): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(nowMs())
    }

    private fun adminOf(packet: MeshPacket): AdminMessage? {
        val payload = packet.decoded?.payload ?: return null
        return runCatching { AdminMessage.ADAPTER.decode(payload) }.getOrNull()
    }

    private fun decodeToRadioOrNull(frame: Frame): ToRadio? {
        val bytes = frame.bytes.toByteArray()
        if (bytes.size < 4) return null
        return runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull()
    }

    private fun encodeFromRadio(fromRadio: org.meshtastic.proto.FromRadio): Frame = fromRadio.toFrame()

    private sealed interface ConnectOutcome {
        data object Success : ConnectOutcome
        data class Fail(val message: String) : ConnectOutcome
    }

    private inner class ScriptedTransport(
        override val identity: TransportIdentity,
        private val nowMs: () -> Long,
        reconnectOutcomes: List<ConnectOutcome> = emptyList(),
        var autoCompleteStage1: Boolean = true,
        var autoCompleteStage2: Boolean = true,
        val nodeNum: Int = DEFAULT_NODE_NUM,
        private val sessionPasskey: ByteArray = SEEDED_PASSKEY,
    ) : RadioTransport {
        private val connectPlan = ArrayDeque(reconnectOutcomes)
        private val connectTimes = mutableListOf<Long>()
        private val outboundFrames = mutableListOf<Frame>()
        private val stateFlow = MutableStateFlow<TransportState>(TransportState.Disconnected)
        private val inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 128)

        var dropStage1OnNextHandshake: Boolean = false
        var dropStage2OnNextHandshake: Boolean = false

        override val state: StateFlow<TransportState> = stateFlow

        override suspend fun connect() {
            stateFlow.value = TransportState.Connecting
            val shouldFail = if (connectTimes.isEmpty()) {
                ConnectOutcome.Success
            } else {
                connectPlan.removeFirstOrNull()
                    ?: ConnectOutcome.Success
            }
            val attemptedAt = nowMs()
            if (shouldFail is ConnectOutcome.Fail) {
                connectTimes += attemptedAt
                stateFlow.value = TransportState.Disconnected
                throw MeshtasticException.Transport(shouldFail.message)
            }
            connectTimes += attemptedAt
            stateFlow.value = TransportState.Connected
        }

        override suspend fun disconnect() {
            stateFlow.value = TransportState.Disconnected
        }

        override suspend fun send(frame: Frame) {
            outboundFrames += frame
            val toRadio = decodeToRadio(frame) ?: return
            when (toRadio.want_config_id) {
                NONCE_STAGE1 -> handleStage1Request()
                NONCE_STAGE2 -> handleStage2Request()
            }
            toRadio.packet?.let(::handleAdminPacket)
        }

        override fun frames(): Flow<Frame> = inbound

        fun outboundFrames(): List<Frame> = outboundFrames.toList()

        fun outboundPackets(): List<MeshPacket> = outboundFrames.mapNotNull { frame ->
            decodeToRadio(frame)?.packet
        }

        fun reconnectAttemptTimes(): List<Long> = connectTimes.drop(1)

        fun injectStage2Progress(node: Int) {
            inbound.tryEmit(encodeFromRadioFrame(org.meshtastic.proto.FromRadio(node_info = NodeInfo(num = node))))
        }

        fun injectFromRadio(fromRadio: org.meshtastic.proto.FromRadio) {
            inbound.tryEmit(encodeFromRadioFrame(fromRadio))
        }

        fun injectAdminResponse(requestId: Int, response: AdminMessage, fromNode: Int = nodeNum) {
            val payload = AdminMessage.ADAPTER.encode(response).toByteString()
            val packet = MeshPacket(
                from = fromNode,
                to = 0,
                decoded = Data(
                    portnum = PortNum.ADMIN_APP,
                    payload = payload,
                    request_id = requestId,
                ),
            )
            inbound.tryEmit(encodeFromRadioFrame(org.meshtastic.proto.FromRadio(packet = packet)))
        }

        fun injectRoutingError(requestId: Int, error: Routing.Error, fromNode: Int = nodeNum) {
            val payload = Routing.ADAPTER.encode(Routing(error_reason = error)).toByteString()
            val packet = MeshPacket(
                from = fromNode,
                to = 0,
                decoded = Data(
                    portnum = PortNum.ROUTING_APP,
                    payload = payload,
                    request_id = requestId,
                ),
            )
            inbound.tryEmit(encodeFromRadioFrame(org.meshtastic.proto.FromRadio(packet = packet)))
        }

        fun injectAlivePacket(packetId: Int) {
            val packet = MeshPacket(
                id = packetId,
                from = nodeNum,
                to = 0,
                decoded = Data(
                    portnum = PortNum.TEXT_MESSAGE_APP,
                    payload = okio.ByteString.EMPTY,
                ),
            )
            inbound.tryEmit(encodeFromRadioFrame(org.meshtastic.proto.FromRadio(packet = packet)))
        }

        fun simulateRecoverableError(message: String, recoverable: Boolean = true) {
            stateFlow.value = TransportState.Error(MeshtasticException.Transport(message), recoverable)
        }

        private fun handleStage1Request() {
            if (dropStage1OnNextHandshake) {
                dropStage1OnNextHandshake = false
                stateFlow.value = TransportState.Error(MeshtasticException.Transport("stage1 dropped"), true)
                return
            }
            if (!autoCompleteStage1) return
            inbound.tryEmit(
                encodeFromRadioFrame(org.meshtastic.proto.FromRadio(my_info = MyNodeInfo(my_node_num = nodeNum))),
            )
            inbound.tryEmit(encodeFromRadioFrame(org.meshtastic.proto.FromRadio(config_complete_id = NONCE_STAGE1)))
        }

        private fun handleStage2Request() {
            if (dropStage2OnNextHandshake) {
                dropStage2OnNextHandshake = false
                stateFlow.value = TransportState.Error(MeshtasticException.Transport("stage2 dropped"), true)
                return
            }
            if (!autoCompleteStage2) return
            inbound.tryEmit(encodeFromRadioFrame(org.meshtastic.proto.FromRadio(config_complete_id = NONCE_STAGE2)))
        }

        private fun handleAdminPacket(packet: MeshPacket) {
            val admin = decodeAdmin(packet) ?: return
            if (packet.to != nodeNum || admin.get_owner_request != true) return
            val user = User(
                id = "!00000001",
                long_name = "ScriptedNode",
                short_name = "SN",
                hw_model = HardwareModel.UNSET,
            )
            val response = AdminMessage(
                get_owner_response = user,
                session_passkey = sessionPasskey.toByteString(),
            )
            injectAdminResponse(requestId = packet.id, response = response, fromNode = nodeNum)
        }

        private fun decodeToRadio(frame: Frame): ToRadio? {
            val bytes = frame.bytes.toByteArray()
            if (bytes.size < 4) return null
            return runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.getOrNull()
        }

        private fun encodeFromRadioFrame(fromRadio: org.meshtastic.proto.FromRadio): Frame = fromRadio.toFrame()

        private fun decodeAdmin(packet: MeshPacket): AdminMessage? {
            val payload = packet.decoded?.payload ?: return null
            return runCatching { AdminMessage.ADAPTER.decode(payload) }.getOrNull()
        }
    }

    private companion object {
        const val NONCE_STAGE1 = 69420
        const val NONCE_STAGE2 = 69421
        const val DEFAULT_NODE_NUM = 1
        const val STAGE2_PROGRESS_TICK_MS = 30_000L
        const val STAGE2_HARD_CAP_MS = 60_000L
        val SEEDED_PASSKEY = byteArrayOf(1, 2, 3, 4)
        val REMOTE_PASSKEY = byteArrayOf(9, 8, 7, 6)
    }
}
