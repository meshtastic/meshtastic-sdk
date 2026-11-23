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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.LogRecord
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.Routing
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
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
        val heartbeatIndex = outbound.indexOfFirst { it.heartbeat?.nonce == 2 }
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
    fun injectedLogRecordReachesDeviceLogsWithItsFields() = runTest {
        val transport = ScriptedTransport(TransportIdentity("fake:log-fields"), nowMs = { currentTime })
        val client = buildClient(transport)
        client.connect()
        client.deviceLogs.test {
            transport.injectFromRadio(
                org.meshtastic.proto.FromRadio.Builder().also { wb ->
                    wb.log_record = LogRecord.Builder().also { lr ->
                        lr.message = "hello"
                        lr.source = "Router"
                        lr.time = 1_700_000_000
                        lr.level = LogRecord.Level.WARNING
                    }.build()
                }.build(),
            )
            drainCurrent()
            val record = awaitItem()
            assertEquals("hello", record.message)
            assertEquals("Router", record.source)
            assertEquals(1_700_000_000, record.time)
            assertEquals(LogRecord.Level.WARNING, record.level)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun logFloodWithStalledCollectorDoesNotStallEngineOrPackets() = runTest {
        val transport = ScriptedTransport(TransportIdentity("fake:log-flood"), nowMs = { currentTime })
        val client = buildClient(transport)
        client.connect()
        backgroundScope.launch { client.deviceLogs.collect { kotlinx.coroutines.awaitCancellation() } }
        runCurrent()

        client.packets.test {
            repeat(2_000) { index ->
                transport.injectFromRadio(
                    org.meshtastic.proto.FromRadio.Builder().also { wb ->
                        wb.log_record = LogRecord.Builder().also { lr -> lr.message = "log $index" }.build()
                    }.build(),
                )
                if (index % 50 == 49) drainCurrent()
            }
            transport.injectAlivePacket(packetId = 4242)
            drainCurrent()
            assertEquals(4242, awaitItem().id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun logRecordsDuringHandshakeAreDeliveredAndLeaveHandshakeBuffersAlone() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:log-handshake"),
            nowMs = { currentTime },
            autoCompleteStage2 = false,
        )
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { client.connect() }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }

        val logs = mutableListOf<LogRecord>()
        backgroundScope.launch { client.deviceLogs.collect { logs += it } }
        client.events.test {
            client.packets.test {
                repeat(HANDSHAKE_PACKET_CAP) { index -> transport.injectAlivePacket(packetId = 3000 + index) }
                drainCurrent()
                repeat(300) { index ->
                    transport.injectFromRadio(
                        org.meshtastic.proto.FromRadio.Builder().also { wb ->
                            wb.log_record = LogRecord.Builder().also { lr -> lr.message = "log $index" }.build()
                        }.build(),
                    )
                    if (index % 20 == 19) drainCurrent()
                }
                drainCurrent()
                assertEquals(300, logs.size, "records arrive live, during the handshake")
                transport.injectFromRadio(
                    org.meshtastic.proto.FromRadio.Builder().also { wb ->
                        wb.config_complete_id = NONCE_STAGE2
                    }.build(),
                )
                drainCurrent()
                connectJob.await()
                repeat(HANDSHAKE_PACKET_CAP) { index -> assertEquals(3000 + index, awaitItem().id) }
                cancelAndIgnoreRemainingEvents()
            }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun firmwareBelowFloorFailsConnectWithoutReconnecting() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:fw-too-old"),
            nowMs = { currentTime },
            firmwareVersion = "2.3.14.abcdef0",
        )
        val client = buildClient(transport, autoReconnect = AutoReconnectConfig(enabled = true))
        val errors = mutableListOf<MeshEvent.TransportError>()
        backgroundScope.launch { client.events.collect { if (it is MeshEvent.TransportError) errors += it } }
        runCurrent()

        val connectJob = backgroundScope.async { runCatching { client.connect() } }
        repeat(4) {
            advanceTimeBy(150L)
            drainCurrent()
        }

        val error = assertIs<MeshtasticException.FirmwareTooOld>(connectJob.await().exceptionOrNull())
        assertEquals(20315, error.required)
        assertEquals(20314, error.present)
        assertEquals(ConnectionState.Disconnected, client.connection.value)
        assertEquals(1, errors.size)
        assertIs<MeshtasticException.FirmwareTooOld>(errors.single().error)

        advanceTimeBy(120_000L)
        drainCurrent()
        assertTrue(transport.reconnectAttemptTimes().isEmpty(), "FirmwareTooOld must not be retried")
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    @Test
    fun firmwareAtOrAboveAbsoluteFloorConnectsAndWarnsOnlyBelowRecommended() = runTest {
        val expectWarning = mapOf(
            "2.3.15" to true,
            "2.5.13" to true,
            "2.5.14" to false,
            "2.8.3" to false,
            "2.8.3.68f47ef" to false,
            "garbage" to false,
            "" to false,
        )
        for ((version, warns) in expectWarning) {
            val transport = ScriptedTransport(
                identity = TransportIdentity("fake:fw-ok-$version"),
                nowMs = { currentTime },
                firmwareVersion = version,
            )
            val client = buildClient(transport)
            val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
            backgroundScope.launch {
                client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
            }
            runCurrent()
            val connectJob = backgroundScope.async { client.connect() }
            repeat(4) {
                advanceTimeBy(150L)
                drainCurrent()
            }
            connectJob.await()
            assertEquals(ConnectionState.Connected, client.connection.value, "version '$version'")
            if (warns) {
                val warning = warnings.single()
                assertEquals("firmware should be updated", warning.message)
                assertEquals(version, warning.details["present"])
                assertEquals("2.5.14", warning.details["recommended"])
            } else {
                assertTrue(warnings.isEmpty(), "version '$version' got $warnings")
            }
            client.disconnect()
        }
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

        // A frame inside every progress tick, so only the cap can end the stage.
        val pumpInterval = STAGE2_PROGRESS_TICK_MS - 1_000L
        var node = 0x2000
        while (currentTime - stage2StartedAt + pumpInterval < STAGE2_HARD_CAP_MS) {
            advanceTimeBy(pumpInterval)
            runCurrent()
            transport.injectStage2Progress(node = node++)
            runCurrent()
        }
        advanceTimeBy(STAGE2_HARD_CAP_MS - (currentTime - stage2StartedAt) - 1)
        runCurrent()
        assertFalse(connectJob.isCompleted, "a chatty Stage 2 must run to the cap")

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

        val channel0 = org.meshtastic.proto.Channel.Builder().also { wb ->
            wb.index = 0
            wb.role = org.meshtastic.proto.Channel.Role.PRIMARY
            wb.settings = org.meshtastic.proto.ChannelSettings.Builder().also { wb ->
                wb.name = "LongFast"
            }.build()
        }.build()
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.my_info = MyNodeInfo.Builder().also { wb ->
                    wb.my_node_num = transport.nodeNum
                }.build()
            }.build(),
        )
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.config = org.meshtastic.proto.Config.Builder().also { wb ->
                    wb.lora = org.meshtastic.proto.Config.LoRaConfig.Builder().also { wb ->
                        wb.use_preset = true
                    }.build()
                }.build()
            }.build(),
        )
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.channel = channel0
            }.build(),
        )
        // Re-drain: the same section + channel stream again (latest value wins).
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.config = org.meshtastic.proto.Config.Builder().also { wb ->
                    wb.lora = org.meshtastic.proto.Config.LoRaConfig.Builder().also { wb ->
                        wb.use_preset = false
                    }.build()
                }.build()
            }.build(),
        )
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.channel = channel0
            }.build(),
        )
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.config_complete_id = NONCE_STAGE1
            }.build(),
        )
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
    fun unrecognizedFromRadioDuringStage1IsToleratedNotFatal() = runTest {
        // A device's serial debug console interleaves plain-text log lines with the protobuf
        // frame stream, which can resync the framer onto a spurious boundary and surface a
        // FromRadio whose payload_variant is unset (also the case for a newer-firmware variant
        // this build doesn't model). Such a frame must NOT abort the handshake — the real
        // config_complete_id still arrives. Regression: config_complete_id is nullable and
        // `null != 0` is true in Kotlin, so an unset id used to fatal as a "mismatch".
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:stage1-unrecognized"),
            nowMs = { currentTime },
            autoCompleteStage1 = false,
        )
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { client.connect() }
        runCurrent()

        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.my_info = MyNodeInfo.Builder().also { wb ->
                    wb.my_node_num = transport.nodeNum
                }.build()
            }.build(),
        )
        // An empty FromRadio — every oneof arm unset (the unrecognized / misframed case).
        transport.injectFromRadio(org.meshtastic.proto.FromRadio.Builder().build())
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.config_complete_id = NONCE_STAGE1
            }.build(),
        )
        drainCurrent()

        // Settle → Stage 2 (auto-completed) → seeding → Ready.
        repeat(4) {
            advanceTimeBy(150L)
            drainCurrent()
        }
        connectJob.await()
        assertEquals(ConnectionState.Connected, client.connection.value)
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

            transport.injectFromRadio(
                org.meshtastic.proto.FromRadio.Builder().also { wb ->
                    wb.config_complete_id = NONCE_STAGE2
                }.build(),
            )
            drainCurrent()
            connectJob.await()
            assertEquals(ConnectionState.Connected, client.connection.value)

            assertEquals(777, awaitItem().id, "Buffered handshake packet must flush at Ready")

            // A mesh retransmission of the same packet after Ready must dedupe against the
            // flushed copy — delivered exactly once.
            transport.injectAlivePacket(packetId = 777)
            drainCurrent()
            expectNoEvents()
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
        val expected = org.meshtastic.proto.Config.Builder().also { wb ->
            wb.lora = org.meshtastic.proto.Config.LoRaConfig.Builder().also { wb ->
                wb.use_preset = true
            }.build()
        }.build()
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
            response = AdminMessage.Builder().also { wb ->
                wb.get_config_response = expected
                wb.session_passkey = REMOTE_PASSKEY.toByteString()
            }.build(),
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
            response = AdminMessage.Builder().also { wb ->
                wb.get_config_response = expected
            }.build(),
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
        val expected = org.meshtastic.proto.Config.Builder().also { wb ->
            wb.lora = org.meshtastic.proto.Config.LoRaConfig.Builder().also { wb ->
                wb.region = org.meshtastic.proto.Config.LoRaConfig.RegionCode.US
            }.build()
        }.build()
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
            response = AdminMessage.Builder().also { wb ->
                wb.get_owner_response = User.Builder().also { wb ->
                    wb.id = "!0000beef"
                    wb.long_name = "Remote"
                    wb.short_name = "RN"
                }.build()
                wb.session_passkey = REMOTE_PASSKEY.toByteString()
            }.build(),
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
            response = AdminMessage.Builder().also { wb ->
                wb.get_config_response = expected
            }.build(),
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
            response = AdminMessage.Builder().also { wb ->
                wb.get_owner_response = User.Builder().also { wb ->
                    wb.id = "!0000d00d"
                    wb.long_name = "Remote"
                    wb.short_name = "RN"
                }.build()
                wb.session_passkey = REMOTE_PASSKEY.toByteString()
            }.build(),
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
    fun aReadFailureWithAutoReconnectReconnectsInsteadOfEndingTheSession() = runTest {
        val transport = ScriptedTransport(
            TransportIdentity("fake:reader-eof"),
            nowMs = { currentTime },
            failableReader = true,
        )
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(enabled = true, initialBackoff = 1.seconds, jitter = 0.0),
        )
        client.connect()
        runCurrent()

        transport.failReader("TCP peer closed the connection")
        runCurrent()
        assertReconnectState(client.connection.value, attempt = 1)

        advanceTimeBy(1_000L)
        drainCurrent()
        awaitConnected(client)
        assertEquals(1, transport.reconnectAttemptTimes().size)
    }

    @Test
    fun aRebootFrameQueuedBeforeTheHandshakeDoesNotFailIt() = runTest {
        val staleReboot = FromRadio.Builder().also { it.rebooted = true }.build().toFrame()
        val transport = org.meshtastic.sdk.testing.FakeRadioTransport(
            TransportIdentity("fake:stale-reboot"),
            frames = listOf(staleReboot),
            autoHandshake = true,
        )
        val client = buildClient(transport)

        client.connect()

        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun recoveryStepRunsAfterBackoffAndBeforeEachConnect() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:reconnect-recovery"),
            nowMs = { currentTime },
            reconnectOutcomes = listOf(ConnectOutcome.Fail("attempt-1"), ConnectOutcome.Success),
        )
        val recoveries = mutableListOf<Pair<Int, Long>>()
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(
                enabled = true,
                initialBackoff = 1.seconds,
                maxBackoff = 8.seconds,
                jitter = 0.0,
                recoveryStep = { attempt, cause ->
                    assertIs<MeshtasticException.Transport>(cause)
                    recoveries += attempt to currentTime
                },
            ),
        )
        client.connect()
        runCurrent()

        val droppedAt = currentTime
        transport.simulateRecoverableError("recovery")
        runCurrent()
        advanceTimeBy(1_000L)
        drainCurrent()
        advanceTimeBy(2_000L)
        drainCurrent()
        awaitConnected(client)

        assertEquals(listOf(1 to 1_000L, 2 to 3_000L), recoveries.map { (n, at) -> n to at - droppedAt })
        assertEquals(listOf(1_000L, 3_000L), transport.reconnectAttemptTimes().map { it - droppedAt })
    }

    @Test
    fun recoveryStepFailureCountsAsFailedAttempt() = runTest {
        val transport = ScriptedTransport(TransportIdentity("fake:reconnect-recovery-fail"), nowMs = { currentTime })
        var calls = 0
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(
                enabled = true,
                initialBackoff = 1.seconds,
                maxBackoff = 8.seconds,
                jitter = 0.0,
                recoveryStep = { _, _ ->
                    calls += 1
                    if (calls == 1) throw IllegalStateException("usb permission pending")
                },
            ),
        )
        client.connect()
        runCurrent()

        val droppedAt = currentTime
        transport.simulateRecoverableError("recovery-fail")
        runCurrent()
        advanceTimeBy(1_000L)
        drainCurrent()
        assertReconnectState(client.connection.value, attempt = 2)
        assertEquals(0, transport.reconnectAttemptTimes().size)

        advanceTimeBy(2_000L)
        drainCurrent()
        awaitConnected(client)

        assertEquals(2, calls)
        assertEquals(listOf(3_000L), transport.reconnectAttemptTimes().map { it - droppedAt })
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

    @Test
    fun remotePasskeysAreIsolatedPerTargetNode() = runTest {
        val transport = ScriptedTransport(TransportIdentity("fake:two-remotes"), nowMs = { currentTime })
        val client = buildClient(transport)
        val nodeA = NodeId(0x0AAAA)
        val nodeB = NodeId(0x0BBBB)
        client.connect()
        runCurrent()

        // RPC to A; its response latches PASSKEY_A keyed under A.
        val firstA = backgroundScope.async { client.admin.forNode(nodeA).getDeviceMetadata() }
        runCurrent()
        val requestA = transport.outboundPackets().last { it.to == nodeA.raw }
        transport.injectAdminResponse(
            requestId = requestA.id,
            response = AdminMessage.Builder().also { wb ->
                wb.get_device_metadata_response = org.meshtastic.proto.DeviceMetadata.Builder().also { wb ->
                    wb.firmware_version = "a"
                }.build()
                wb.session_passkey = PASSKEY_A.toByteString()
            }.build(),
            fromNode = nodeA.raw,
        )
        runCurrent()
        assertIs<AdminResult.Success<*>>(firstA.await())

        // First RPC to B must NOT carry A's passkey — the cross-contamination bug class.
        val firstB = backgroundScope.async { client.admin.forNode(nodeB).getDeviceMetadata() }
        runCurrent()
        val requestB = transport.outboundPackets().last { it.to == nodeB.raw }
        assertEquals(0, adminOf(requestB)?.session_passkey?.size, "Node A's passkey must not leak to node B")
        transport.injectAdminResponse(
            requestId = requestB.id,
            response = AdminMessage.Builder().also { wb ->
                wb.get_device_metadata_response = org.meshtastic.proto.DeviceMetadata.Builder().also { wb ->
                    wb.firmware_version = "b"
                }.build()
                wb.session_passkey = PASSKEY_B.toByteString()
            }.build(),
            fromNode = nodeB.raw,
        )
        runCurrent()
        assertIs<AdminResult.Success<*>>(firstB.await())

        // Follow-up RPCs carry each node's OWN passkey.
        val secondA = backgroundScope.async { client.admin.forNode(nodeA).getDeviceMetadata() }
        runCurrent()
        val requestA2 = transport.outboundPackets().last { it.to == nodeA.raw && it.id != requestA.id }
        assertContentEquals(PASSKEY_A, adminOf(requestA2)?.session_passkey?.toByteArray())
        secondA.cancel()

        val secondB = backgroundScope.async { client.admin.forNode(nodeB).getDeviceMetadata() }
        runCurrent()
        val requestB2 = transport.outboundPackets().last { it.to == nodeB.raw && it.id != requestB.id }
        assertContentEquals(PASSKEY_B, adminOf(requestB2)?.session_passkey?.toByteArray())
        secondB.cancel()
    }

    @Test
    fun staleRemotePasskeyIsPrunedNotStamped() = runTest {
        val transport = ScriptedTransport(TransportIdentity("fake:passkey-ttl"), nowMs = { currentTime })
        val client = buildClient(transport)
        val remoteNode = NodeId(0x0AB1)
        client.connect()
        runCurrent()

        val first = backgroundScope.async { client.admin.forNode(remoteNode).getDeviceMetadata() }
        runCurrent()
        val request = transport.outboundPackets().last { it.to == remoteNode.raw }
        transport.injectAdminResponse(
            requestId = request.id,
            response = AdminMessage.Builder().also { wb ->
                wb.get_device_metadata_response = org.meshtastic.proto.DeviceMetadata.Builder().also { wb ->
                    wb.firmware_version = "x"
                }.build()
                wb.session_passkey = REMOTE_PASSKEY.toByteString()
            }.build(),
            fromNode = remoteNode.raw,
        )
        runCurrent()
        assertIs<AdminResult.Success<*>>(first.await())

        // Cross the 4-minute client-side TTL while keeping the session alive.
        keepSessionAlive(transport, 300.seconds.inWholeMilliseconds)

        val before = transport.outboundPackets().size
        val second = backgroundScope.async { client.admin.forNode(remoteNode).getDeviceMetadata() }
        runCurrent()
        val stale = transport.outboundPackets().drop(before).last { it.to == remoteNode.raw }
        assertEquals(0, adminOf(stale)?.session_passkey?.size, "Expired passkey must be pruned, not stamped")
        second.cancel()
    }

    @Test
    fun identityRebindDropsLatchedRemotePasskeys() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:rebind-passkeys"),
            nowMs = { currentTime },
            reconnectOutcomes = listOf(ConnectOutcome.Success),
        )
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(
                enabled = true,
                initialBackoff = 1.seconds,
                maxBackoff = 2.seconds,
                jitter = 0.0,
            ),
        )
        val remoteNode = NodeId(0x0AB2)
        client.connect()
        runCurrent()

        val first = backgroundScope.async { client.admin.forNode(remoteNode).getDeviceMetadata() }
        runCurrent()
        val request = transport.outboundPackets().last { it.to == remoteNode.raw }
        transport.injectAdminResponse(
            requestId = request.id,
            response = AdminMessage.Builder().also { wb ->
                wb.get_device_metadata_response = org.meshtastic.proto.DeviceMetadata.Builder().also { wb ->
                    wb.firmware_version = "x"
                }.build()
                wb.session_passkey = REMOTE_PASSKEY.toByteString()
            }.build(),
            fromNode = remoteNode.raw,
        )
        runCurrent()
        assertIs<AdminResult.Success<*>>(first.await())

        // The device comes back from a reconnect with a DIFFERENT NodeNum (factory reset /
        // radio swap) — passkeys issued under the old identity are meaningless.
        transport.nodeNum = DEFAULT_NODE_NUM + 7
        transport.simulateRecoverableError("rebind")
        runCurrent()
        advanceTimeBy(1_000L)
        drainCurrent()
        awaitConnected(client)

        val before = transport.outboundPackets().size
        val second = backgroundScope.async { client.admin.forNode(remoteNode).getDeviceMetadata() }
        runCurrent()
        val postRebind = transport.outboundPackets().drop(before).last { it.to == remoteNode.raw }
        assertEquals(0, adminOf(postRebind)?.session_passkey?.size, "Rebind must clear latched remote passkeys")
        second.cancel()
    }

    @Test
    fun handshakeBufferOverflowDropsOldestAndSignalsPacketsDropped() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:buffer-overflow"),
            nowMs = { currentTime },
            autoCompleteStage2 = false,
        )
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { client.connect() }
        client.connection.first { it is ConnectionState.Configuring && it.phase == ConfigPhase.Stage2 }

        client.events.test {
            // Two past the cap: the two oldest are dropped, each observably. The fake's inbound buffer holds 128.
            repeat(HANDSHAKE_PACKET_CAP + 2) { index ->
                transport.injectAlivePacket(packetId = 1000 + index)
                if (index % 64 == 63) drainCurrent()
            }
            drainCurrent()
            var drops = 0
            while (drops < 2) {
                val dropped = awaitItem() as? MeshEvent.PacketsDropped ?: continue
                assertEquals(DroppedFlow.Packets, dropped.flow)
                drops++
            }
            cancelAndIgnoreRemainingEvents()
        }

        client.packets.test {
            transport.injectFromRadio(
                org.meshtastic.proto.FromRadio.Builder().also { wb ->
                    wb.config_complete_id = NONCE_STAGE2
                }.build(),
            )
            drainCurrent()
            connectJob.await()
            assertEquals(1002, awaitItem().id, "Drop-oldest: the flush must start at the third packet")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun packetsAfterStage2CompletionFlowBeforeTheSeedReply() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:seeding-buffer"),
            nowMs = { currentTime },
            autoRespondGetOwner = false,
        )
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { client.connect() }
        drainCurrent()
        // Walk the virtual-time settle windows (Stage-1 settle + heartbeat settle) so the
        // engine completes Stage 2 and sends its get_owner_request.
        repeat(3) {
            advanceTimeBy(INTER_STAGE_SETTLE_MS + 1)
            drainCurrent()
        }

        client.packets.test {
            connectJob.await()
            assertEquals(ConnectionState.Connected, client.connection.value)
            // Live traffic and a stray admin packet before the seed reply go out as they arrive.
            transport.injectAlivePacket(packetId = 555)
            transport.injectFromRadio(
                org.meshtastic.proto.FromRadio.Builder().also { wb ->
                    wb.packet = MeshPacket.Builder().also { wb ->
                        wb.from = 0x0DEAD
                        wb.to = transport.nodeNum
                        wb.decoded = Data.Builder().also { wb ->
                            wb.portnum = PortNum.ADMIN_APP
                            wb.payload = AdminMessage.ADAPTER
                                .encode(
                                    AdminMessage.Builder().also { wb ->
                                        wb.set_owner =
                                            User.Builder().also { wb ->
                                                wb.long_name =
                                                    "stray"
                                            }.build()
                                    }.build(),
                                )
                                .toByteString()
                        }.build()
                    }.build()
                }.build(),
            )
            drainCurrent()
            assertEquals(555, awaitItem().id)
            assertEquals(PortNum.ADMIN_APP, awaitItem().decoded?.portnum)

            // The seed reply latches the passkey and is not offered as a packet.
            val seedRequest = transport.outboundPackets().first { adminOf(it)?.get_owner_request == true }
            transport.injectAdminResponse(
                requestId = seedRequest.id,
                response = AdminMessage.Builder().also { wb ->
                    wb.get_owner_response = User.Builder().also { wb ->
                        wb.id = "!00000001"
                        wb.long_name = "ScriptedNode"
                        wb.short_name = "SN"
                    }.build()
                    wb.session_passkey = SEEDED_PASSKEY.toByteString()
                }.build(),
                fromNode = transport.nodeNum,
            )
            drainCurrent()
            expectNoEvents()
            assertIs<AdminSessionStatus.Active>(client.adminSessions.value[NodeId(transport.nodeNum)])
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun settleReplayPreservesFramesAfterDuplicateStage1Completion() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:settle-replay"),
            nowMs = { currentTime },
            autoCompleteStage1 = false,
        )
        val client = buildClient(transport)
        val connectJob = backgroundScope.async { client.connect() }
        runCurrent()

        // Manual Stage 1, then a duplicate completion AND a live packet inside the settle window
        // (the firmware re-drains from scratch on a want_config retry, producing exactly this).
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.my_info = MyNodeInfo.Builder().also { wb ->
                    wb.my_node_num = transport.nodeNum
                }.build()
            }.build(),
        )
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.config_complete_id = NONCE_STAGE1
            }.build(),
        )
        runCurrent()
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.config_complete_id = NONCE_STAGE1
            }.build(),
        )
        transport.injectAlivePacket(packetId = 777)
        runCurrent()

        client.packets.test {
            // Settle #1 replays the duplicate completion (re-arms the window) — the packet
            // behind it must be RE-BUFFERED, not dropped. Settle #2 then processes it.
            repeat(4) {
                advanceTimeBy(INTER_STAGE_SETTLE_MS + 1)
                drainCurrent()
            }
            connectJob.await()
            assertEquals(ConnectionState.Connected, client.connection.value)
            assertEquals(777, awaitItem().id, "Settle replay must not drop frames behind a duplicate completion")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aPacketAfterTheReconnectHeartbeatReachesTheNewSessionOnce() = runTest {
        val transport = ScriptedTransport(
            TransportIdentity("fake:heartbeat-window"),
            nowMs = { currentTime },
            replaceInboundOnConnect = true,
        )
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(enabled = true, initialBackoff = 1.seconds, jitter = 0.0),
            sessionReceiveCapacity = 16,
        )

        assertNotNull(client.sessionEvents).test {
            client.connect()
            val first = assertIs<RadioSessionEvent.Ready>(awaitItem())

            // A reconnecting radio hands over its queued phone packets right after Stage 1
            // completes, so one lands between the inter-stage heartbeat and the Stage 2 request.
            transport.packetAfterNextHeartbeat = 4242
            transport.simulateRecoverableError("link drop")
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            awaitConnected(client)
            assertEquals(null, transport.packetAfterNextHeartbeat, "the reconnect handshake sent its heartbeat")
            transport.injectAlivePacket(4243)
            drainCurrent()

            assertIs<MeshEvent.TransportError>(assertIs<RadioSessionEvent.Event>(awaitSkippingNodes()).event)
            assertEquals(RadioSessionEvent.Ended(first.sessionId), awaitItem())
            val second = assertIs<RadioSessionEvent.Ready>(awaitItem())
            val held = assertIs<RadioSessionEvent.Packet>(awaitSkippingNodes())
            assertEquals(second.sessionId to 4242, held.sessionId to held.packet.id)
            val live = assertIs<RadioSessionEvent.Packet>(awaitSkippingNodes())
            assertEquals(4243, live.packet.id, "the held packet is delivered exactly once")
            client.disconnect()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun logRecordsDuringTheSettleWindowDoNotEvictAHeldPacket() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:settle-logs"),
            nowMs = { currentTime },
            autoCompleteStage1 = false,
        )
        val client = buildClient(transport)
        val logs = mutableListOf<LogRecord>()
        backgroundScope.launch { client.deviceLogs.collect { logs += it } }
        val connectJob = backgroundScope.async { client.connect() }
        runCurrent()
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb ->
                wb.my_info = MyNodeInfo.Builder().also { wb -> wb.my_node_num = transport.nodeNum }.build()
            }.build(),
        )
        transport.injectFromRadio(
            org.meshtastic.proto.FromRadio.Builder().also { wb -> wb.config_complete_id = NONCE_STAGE1 }.build(),
        )
        runCurrent()

        client.packets.test {
            // Inside the settle window: one packet, then more log records than the settle buffer holds.
            transport.injectAlivePacket(packetId = 778)
            repeat(SETTLE_BUFFER_CAP + 6) { index ->
                transport.injectFromRadio(
                    org.meshtastic.proto.FromRadio.Builder().also { wb ->
                        wb.log_record = LogRecord.Builder().also { it.message = "settle $index" }.build()
                    }.build(),
                )
                // deviceLogs drops the oldest past 64 for a collector that falls behind; let it keep up.
                if (index % 16 == 15) runCurrent()
            }
            repeat(4) {
                advanceTimeBy(INTER_STAGE_SETTLE_MS + 1)
                drainCurrent()
            }
            connectJob.await()
            assertEquals(778, awaitItem().id, "the held packet survives the log burst")
            assertEquals(SETTLE_BUFFER_CAP + 6, logs.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun firmwareAdvisoryRepeatsOncePerReconnectedSession() = runTest {
        val transport = ScriptedTransport(
            identity = TransportIdentity("fake:fw-advice-reconnect"),
            nowMs = { currentTime },
            firmwareVersion = "2.4.0",
        )
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(enabled = true, initialBackoff = 1.seconds, jitter = 0.0),
        )
        val warnings = mutableListOf<MeshEvent.ProtocolWarning>()
        backgroundScope.launch {
            client.events.collect { if (it is MeshEvent.ProtocolWarning) warnings += it }
        }
        runCurrent()
        client.connect()
        runCurrent()
        assertEquals(1, warnings.size, "first session")

        transport.simulateRecoverableError("drop")
        runCurrent()
        advanceTimeBy(1_000L)
        drainCurrent()
        awaitConnected(client)

        assertEquals(2, warnings.size, "the reconnected session warns again: $warnings")
    }

    @Test
    fun sessionReceiveStartsWithSnapshotAndKeepsOrderAcrossReconnect() = runTest {
        val transport = ScriptedTransport(
            TransportIdentity("fake:session-order"),
            nowMs = { currentTime },
            autoCompleteStage2 = false,
            replaceInboundOnConnect = true,
        )
        val client = buildClient(
            transport = transport,
            autoReconnect = AutoReconnectConfig(enabled = true, initialBackoff = 1.seconds, jitter = 0.0),
            sessionReceiveCapacity = 8,
        )

        assertNotNull(client.sessionEvents).test {
            val connect = backgroundScope.async { client.connect() }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(ConfigPhase.Stage2, assertIs<ConnectionState.Configuring>(client.connection.value).phase)
            transport.injectStage2Progress(transport.nodeNum)
            transport.injectAlivePacket(101)
            transport.injectFromRadio(
                org.meshtastic.proto.FromRadio.Builder().also { it.config_complete_id = NONCE_STAGE2 }.build(),
            )
            connect.await()
            val first = assertIs<RadioSessionEvent.Ready>(awaitItem())
            assertEquals(transport.nodeNum, first.myInfo?.my_node_num)
            assertTrue(NodeId(transport.nodeNum) in first.nodes)

            transport.injectAlivePacket(102)
            runCurrent()
            transport.autoCompleteStage2 = true
            transport.simulateRecoverableError("reconnect")
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            awaitConnected(client)
            transport.injectAlivePacket(103)
            runCurrent()

            val p101 = assertIs<RadioSessionEvent.Packet>(awaitSkippingNodes())
            val p102 = assertIs<RadioSessionEvent.Packet>(awaitSkippingNodes())
            assertEquals(listOf(101, 102), listOf(p101.packet.id, p102.packet.id))
            assertEquals(listOf(first.sessionId, first.sessionId), listOf(p101.sessionId, p102.sessionId))
            assertEquals(1L, p101.sequence)
            assertTrue(p102.sequence > p101.sequence)
            val dropped = assertIs<RadioSessionEvent.Event>(awaitSkippingNodes())
            assertIs<MeshEvent.TransportError>(dropped.event)
            assertEquals(RadioSessionEvent.Ended(first.sessionId), awaitItem())
            val second = assertIs<RadioSessionEvent.Ready>(awaitItem())
            assertTrue(second.sessionId > first.sessionId)
            assertEquals(setOf(NodeId(transport.nodeNum)), second.nodes.keys)
            val packet = assertIs<RadioSessionEvent.Packet>(awaitItem())
            assertEquals(Triple(second.sessionId, 1L, 103), Triple(packet.sessionId, packet.sequence, packet.packet.id))
            client.disconnect()
            assertEquals(RadioSessionEvent.Ended(second.sessionId), awaitSkippingNodes())
            awaitComplete()
        }
    }

    @Test
    fun nodeDbRefreshKeepsPacketsFlowingAndCoalescesFollowUp() = runTest {
        val transport = ScriptedTransport(TransportIdentity("fake:node-db-refresh"), nowMs = { currentTime })
        val client = buildClient(transport, sessionReceiveCapacity = 16)

        assertNotNull(client.sessionEvents).test {
            assertFalse(client.requestNodeDbRefresh())
            client.connect()
            val ready = assertIs<RadioSessionEvent.Ready>(awaitItem())
            transport.autoCompleteStage2 = false
            repeat(3) { assertTrue(client.requestNodeDbRefresh()) }
            runCurrent()
            fun stage2Count() = transport.outboundFrames().count {
                decodeToRadioOrNull(it)?.want_config_id ==
                    NONCE_STAGE2
            }
            assertEquals(2, stage2Count())

            transport.injectFromRadio(FromRadio.Builder().also { it.config_complete_id = NONCE_STAGE1 }.build())
            // Queued before the request reached the radio: live traffic, not part of the dump.
            transport.injectStage2Progress(0x9999)
            val live = assertIs<NodeChange.Added>(assertIs<RadioSessionEvent.NodeChanged>(awaitItem()).change)
            assertEquals(0x9999, live.node.num)
            // The dump opens with our own node_info.
            transport.injectStage2Progress(transport.nodeNum)
            transport.injectStage2Progress(0x1234)
            transport.injectAlivePacket(201)
            val packet = assertIs<RadioSessionEvent.Packet>(awaitSkippingNodes())
            assertEquals(201, packet.packet.id)
            assertEquals(ConnectionState.Connected, client.connection.value)
            assertEquals(2, stage2Count())

            val complete = FromRadio.Builder().also { it.config_complete_id = NONCE_STAGE2 }.build()
            transport.injectFromRadio(complete)
            val first = assertIs<RadioSessionEvent.NodeDbRefreshed>(awaitSkippingNodes())
            assertEquals(ready.sessionId, first.sessionId)
            assertTrue(first.sequence > packet.sequence)
            assertEquals(setOf(NodeId(transport.nodeNum), NodeId(0x1234)), first.nodes.keys)
            runCurrent()
            assertEquals(3, stage2Count())

            transport.injectStage2Progress(transport.nodeNum)
            transport.injectStage2Progress(0x4321)
            transport.injectFromRadio(complete)
            val second = assertIs<RadioSessionEvent.NodeDbRefreshed>(awaitSkippingNodes())
            assertTrue(second.sequence > first.sequence)
            assertEquals(setOf(NodeId(transport.nodeNum), NodeId(0x4321)), second.nodes.keys)
            assertEquals(3, stage2Count())
            client.disconnect()
            assertEquals(RadioSessionEvent.Ended(ready.sessionId), awaitSkippingNodes())
            awaitComplete()
        }
    }

    @Test
    fun stalledNodeDbRefreshEndsSessionEvenWhenPacketsKeepArriving() = runTest {
        val transport = ScriptedTransport(TransportIdentity("fake:node-db-timeout"), nowMs = { currentTime })
        val client = buildClient(transport, sessionReceiveCapacity = 8)

        assertNotNull(client.sessionEvents).test {
            client.connect()
            val ready = assertIs<RadioSessionEvent.Ready>(awaitItem())
            transport.autoCompleteStage2 = false
            assertTrue(client.requestNodeDbRefresh())
            runCurrent()

            repeat(5) { index ->
                advanceTimeBy(11_000)
                transport.injectAlivePacket(301 + index)
                assertIs<RadioSessionEvent.Packet>(awaitSkippingNodes())
                assertEquals(ConnectionState.Connected, client.connection.value)
            }
            advanceTimeBy(6_000)
            runCurrent()
            val stalled = assertIs<RadioSessionEvent.Event>(awaitSkippingNodes())
            assertIs<MeshEvent.TransportError>(stalled.event)
            assertEquals(RadioSessionEvent.Ended(ready.sessionId), awaitItem())
            awaitComplete()
            assertFalse(client.requestNodeDbRefresh())
        }
    }

    @Test
    fun sessionReceiveOrdersEventsWithPacketsAndKeepsQueueStatusOut() = runTest {
        val transport = ScriptedTransport(TransportIdentity("fake:session-events"), nowMs = { currentTime })
        val client = buildClient(transport, sessionReceiveCapacity = 8)
        val status = QueueStatus.Builder().also { it.mesh_packet_id = 201 }.build()
        val notice = org.meshtastic.proto.ClientNotification.Builder().also { it.message = "low battery" }.build()

        assertNotNull(client.sessionEvents).test {
            client.connect()
            val snapshot = assertIs<RadioSessionEvent.Ready>(awaitItem())
            transport.injectAlivePacket(201)
            transport.injectFromRadio(FromRadio.Builder().also { it.queueStatus = status }.build())
            transport.injectFromRadio(FromRadio.Builder().also { it.clientNotification = notice }.build())
            transport.injectAlivePacket(202)
            runCurrent()

            val received = cancelAndConsumeRemainingEvents().mapNotNull { (it as? app.cash.turbine.Event.Item)?.value }
            val ordered = received.filterNot { it is RadioSessionEvent.NodeChanged }
            val first = assertIs<RadioSessionEvent.Packet>(ordered[0])
            val notification = assertIs<MeshEvent.Notification>(assertIs<RadioSessionEvent.Event>(ordered[1]).event)
            val second = assertIs<RadioSessionEvent.Packet>(ordered[2])
            assertEquals(3, ordered.size, "queue status stays off the session stream: $ordered")
            assertEquals(notice, notification.notification)
            assertEquals(listOf(201, 202), listOf(first.packet.id, second.packet.id))
            assertTrue(received.all { it.sessionId == snapshot.sessionId })
            val eventSequence = assertIs<RadioSessionEvent.Event>(ordered[1]).sequence
            assertTrue(first.sequence < eventSequence && eventSequence < second.sequence)
        }
    }

    @Test
    fun sessionReceiveOverflowDrainsPrefixWhileAckAndRpcContinue() = runTest {
        val transport = ScriptedTransport(TransportIdentity("fake:session-overflow"), nowMs = { currentTime })
        val client = buildClient(transport, sessionReceiveCapacity = 1)
        val releaseCollector = CompletableDeferred<Unit>()
        val events = mutableListOf<RadioSessionEvent>()
        val collection = backgroundScope.async {
            runCatching {
                assertNotNull(client.sessionEvents).collect {
                    events += it
                    releaseCollector.await()
                }
            }
        }
        try {
            client.connect()
            runCurrent()
            val snapshot = assertIs<RadioSessionEvent.Ready>(events.single())
            val ack = client.sendText("outbound", to = NodeId(42))
            val rpc = async { client.admin.getDeviceMetadata() }
            runCurrent()
            val request = transport.outboundPackets().last { adminOf(it)?.get_device_metadata_request == true }

            transport.injectAlivePacket(201)
            val status = QueueStatus.Builder().also { it.mesh_packet_id = ack.id.raw }.build()
            transport.injectFromRadio(FromRadio.Builder().also { it.queueStatus = status }.build())
            runCurrent()

            transport.injectRoutingError(ack.id.raw, Routing.Error.NONE, fromNode = 42)
            val metadata = DeviceMetadata.Builder().also { it.firmware_version = "2.8.0" }.build()
            transport.injectAdminResponse(
                request.id,
                AdminMessage.Builder().also { it.get_device_metadata_response = metadata }.build(),
            )
            runCurrent()
            assertEquals(SendOutcome.Success, ack.await())
            assertEquals(AdminResult.Success(metadata), rpc.await())
            assertEquals(snapshot, events.single())

            releaseCollector.complete(Unit)
            val overflow = assertIs<RadioSessionOverflowException>(collection.await().exceptionOrNull())
            val packet = assertIs<RadioSessionEvent.Packet>(events.last())
            assertEquals(2, events.size)
            assertEquals(
                Triple(snapshot.sessionId, 1L, 201),
                Triple(packet.sessionId, packet.sequence, packet.packet.id),
            )
            assertEquals(snapshot.sessionId, overflow.sessionId)
            assertEquals(2L, overflow.firstMissedSequence)
        } finally {
            releaseCollector.complete(Unit)
            client.disconnect()
        }
    }

    @Test
    fun handshakeOverflowFailsSessionReceiveWithoutPublishingSnapshot() = runTest {
        val transport = ScriptedTransport(
            TransportIdentity("fake:session-handshake-overflow"),
            nowMs = { currentTime },
            autoCompleteStage2 = false,
        )
        val client = buildClient(transport, sessionReceiveCapacity = 256)

        assertNotNull(client.sessionEvents).test {
            val connect = backgroundScope.async { client.connect() }
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(ConfigPhase.Stage2, assertIs<ConnectionState.Configuring>(client.connection.value).phase)
            repeat(HANDSHAKE_PACKET_CAP + 1) { index ->
                transport.injectAlivePacket(1_000 + index)
                runCurrent()
            }

            val overflow = assertIs<RadioSessionOverflowException>(awaitError())
            assertEquals(1L, overflow.sessionId)
            assertEquals(null, overflow.firstMissedSequence)

            transport.injectFromRadio(
                org.meshtastic.proto.FromRadio.Builder().also { it.config_complete_id = NONCE_STAGE2 }.build(),
            )
            connect.await()
            assertEquals(ConnectionState.Connected, client.connection.value)
            client.disconnect()
        }
    }

    private fun TestScope.buildClient(
        transport: RadioTransport,
        autoReconnect: AutoReconnectConfig = AutoReconnectConfig.Disabled,
        sessionReceiveCapacity: Int? = null,
    ): RadioClient = RadioClient.Builder()
        .transport(transport)
        .storage(InMemoryStorageProvider())
        .clock(SchedulerClock { currentTime })
        .coroutineContext(backgroundScope.coroutineContext)
        .autoSyncTimeOnConnect(false)
        .rpcTimeout(60.seconds)
        .autoReconnect(autoReconnect)
        .apply {
            sessionReceiveCapacity?.let { sessionReceive(it) }
        }
        .build()

    private fun assertReconnectState(state: ConnectionState, attempt: Int) {
        val reconnecting = assertIs<ConnectionState.Reconnecting>(state)
        assertEquals(attempt, reconnecting.attempt)
    }

    // Live packets and dump frames also publish NodeChanged; these tests follow the other events.
    private suspend fun app.cash.turbine.ReceiveTurbine<RadioSessionEvent>.awaitSkippingNodes(): RadioSessionEvent {
        while (true) {
            val event = awaitItem()
            if (event !is RadioSessionEvent.NodeChanged) return event
        }
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
        var nodeNum: Int = DEFAULT_NODE_NUM,
        var firmwareVersion: String? = null,
        var autoRespondGetOwner: Boolean = true,
        private val sessionPasskey: ByteArray = SEEDED_PASSKEY,
        private val replaceInboundOnConnect: Boolean = false,
        private val failableReader: Boolean = false,
    ) : RadioTransport {
        private var readerFailure = CompletableDeferred<Throwable>()
        private val connectPlan = ArrayDeque(reconnectOutcomes)
        private val connectTimes = mutableListOf<Long>()
        private val outboundFrames = mutableListOf<Frame>()
        private val stateFlow = MutableStateFlow<TransportState>(TransportState.Disconnected)
        private var inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 128)

        var dropStage1OnNextHandshake: Boolean = false
        var dropStage2OnNextHandshake: Boolean = false

        // One-shot: answers the next heartbeat with this live packet id.
        var packetAfterNextHeartbeat: Int? = null

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
            if (replaceInboundOnConnect) inbound = MutableSharedFlow(extraBufferCapacity = 128)
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
            if (toRadio.heartbeat != null) {
                packetAfterNextHeartbeat?.let { packetId ->
                    packetAfterNextHeartbeat = null
                    injectAlivePacket(packetId)
                }
            }
        }

        override fun frames(): Flow<Frame> = if (!failableReader) {
            inbound
        } else {
            val failure = readerFailure
            // Subscribes to inbound as soon as the engine collects, so the reply to the single
            // want_config_id is not lost to a dispatched channelFlow producer.
            flow {
                coroutineScope {
                    launch { throw failure.await() }
                    inbound.collect { emit(it) }
                }
            }
        }

        // Ends the current frames() collection with [message], as a TCP read does on EOF.
        fun failReader(message: String) {
            readerFailure.complete(MeshtasticException.Transport(message))
            readerFailure = CompletableDeferred()
        }

        fun outboundFrames(): List<Frame> = outboundFrames.toList()

        fun outboundPackets(): List<MeshPacket> = outboundFrames.mapNotNull { frame ->
            decodeToRadio(frame)?.packet
        }

        fun reconnectAttemptTimes(): List<Long> = connectTimes.drop(1)

        fun injectStage2Progress(node: Int) {
            inbound.tryEmit(
                encodeFromRadioFrame(
                    org.meshtastic.proto.FromRadio.Builder().also { wb ->
                        wb.node_info = NodeInfo.Builder().also { wb ->
                            wb.num = node
                        }.build()
                    }.build(),
                ),
            )
        }

        fun injectFromRadio(fromRadio: org.meshtastic.proto.FromRadio) {
            inbound.tryEmit(encodeFromRadioFrame(fromRadio))
        }

        fun injectAdminResponse(requestId: Int, response: AdminMessage, fromNode: Int = nodeNum) {
            val payload = AdminMessage.ADAPTER.encode(response).toByteString()
            val packet = MeshPacket.Builder().also { wb ->
                wb.from = fromNode
                wb.to = 0
                wb.pki_encrypted = fromNode != nodeNum
                wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.ADMIN_APP
                    wb.payload = payload
                    wb.request_id = requestId
                }.build()
            }.build()
            inbound.tryEmit(
                encodeFromRadioFrame(
                    org.meshtastic.proto.FromRadio.Builder().also { wb ->
                        wb.packet = packet
                    }.build(),
                ),
            )
        }

        fun injectRoutingError(requestId: Int, error: Routing.Error, fromNode: Int = nodeNum) {
            val payload = Routing.ADAPTER.encode(
                Routing.Builder().also { wb ->
                    wb.error_reason = error
                }.build(),
            ).toByteString()
            val packet = MeshPacket.Builder().also { wb ->
                wb.from = fromNode
                wb.to = 0
                wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.ROUTING_APP
                    wb.payload = payload
                    wb.request_id = requestId
                }.build()
            }.build()
            inbound.tryEmit(
                encodeFromRadioFrame(
                    org.meshtastic.proto.FromRadio.Builder().also { wb ->
                        wb.packet = packet
                    }.build(),
                ),
            )
        }

        fun injectAlivePacket(packetId: Int) {
            val packet = MeshPacket.Builder().also { wb ->
                wb.id = packetId
                wb.from = nodeNum
                wb.to = 0
                wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.TEXT_MESSAGE_APP
                    wb.payload = okio.ByteString.EMPTY
                }.build()
            }.build()
            inbound.tryEmit(
                encodeFromRadioFrame(
                    org.meshtastic.proto.FromRadio.Builder().also { wb ->
                        wb.packet = packet
                    }.build(),
                ),
            )
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
                encodeFromRadioFrame(
                    org.meshtastic.proto.FromRadio.Builder().also { wb ->
                        wb.my_info = MyNodeInfo.Builder().also { wb ->
                            wb.my_node_num = nodeNum
                        }.build()
                    }.build(),
                ),
            )
            firmwareVersion?.let { version ->
                inbound.tryEmit(
                    encodeFromRadioFrame(
                        org.meshtastic.proto.FromRadio.Builder().also { wb ->
                            wb.metadata = DeviceMetadata.Builder().also { md -> md.firmware_version = version }.build()
                        }.build(),
                    ),
                )
            }
            inbound.tryEmit(
                encodeFromRadioFrame(
                    org.meshtastic.proto.FromRadio.Builder().also { wb ->
                        wb.config_complete_id = NONCE_STAGE1
                    }.build(),
                ),
            )
        }

        private fun handleStage2Request() {
            if (dropStage2OnNextHandshake) {
                dropStage2OnNextHandshake = false
                stateFlow.value = TransportState.Error(MeshtasticException.Transport("stage2 dropped"), true)
                return
            }
            if (!autoCompleteStage2) return
            // Firmware starts a nodes-only dump at its own node_info.
            injectStage2Progress(nodeNum)
            inbound.tryEmit(
                encodeFromRadioFrame(
                    org.meshtastic.proto.FromRadio.Builder().also { wb ->
                        wb.config_complete_id = NONCE_STAGE2
                    }.build(),
                ),
            )
        }

        private fun handleAdminPacket(packet: MeshPacket) {
            val admin = decodeAdmin(packet) ?: return
            if (!autoRespondGetOwner) return
            if (packet.to != nodeNum || admin.get_owner_request != true) return
            val user = User.Builder().also { wb ->
                wb.id = "!00000001"
                wb.long_name = "ScriptedNode"
                wb.short_name = "SN"
                wb.hw_model = HardwareModel.UNSET
            }.build()
            val response = AdminMessage.Builder().also { wb ->
                wb.get_owner_response = user
                wb.session_passkey = sessionPasskey.toByteString()
            }.build()
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

        // MeshEngine.HANDSHAKE_PACKET_BUFFER_CAP
        const val HANDSHAKE_PACKET_CAP = 128

        // MeshEngine.SETTLE_BUFFER_CAP
        const val SETTLE_BUFFER_CAP = 128
        const val NONCE_STAGE2 = 69421
        const val DEFAULT_NODE_NUM = 1
        const val STAGE2_PROGRESS_TICK_MS = 30_000L

        // Android: 60 s Stage 2 budget x STAGE_GUARD_TOTAL_CAP_FACTOR 10 (MeshConnectionManagerImpl).
        const val STAGE2_HARD_CAP_MS = 600_000L
        val SEEDED_PASSKEY = byteArrayOf(1, 2, 3, 4)
        val REMOTE_PASSKEY = byteArrayOf(9, 8, 7, 6)
        val PASSKEY_A = byteArrayOf(10, 11, 12, 13)
        val PASSKEY_B = byteArrayOf(20, 21, 22, 23)
        const val INTER_STAGE_SETTLE_MS = 100L
    }
}
