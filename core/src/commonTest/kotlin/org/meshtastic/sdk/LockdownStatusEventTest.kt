/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.LockdownAuth
import org.meshtastic.proto.LockdownStatus
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Inbound `FromRadio.lockdown_status` (hardened `MESHTASTIC_LOCKDOWN` firmware builds) must surface
 * as a typed [MeshEvent.LockdownStatusChanged] rather than a generic `ProtocolWarning`. The same
 * status can arrive during the initial config download or once the session is Ready.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockdownStatusEventTest {

    @Test
    fun lockdownStatusSurfacesAsTypedEvent() = runTest {
        val (transport, client) = connectedClient()
        val events = mutableListOf<MeshEvent>()
        val job = backgroundScope.launch { client.events.collect { events.add(it) } }
        client.connect()
        runCurrent()

        val status = LockdownStatus.Builder().also { wb ->
            wb.state = LockdownStatus.State.LOCKED
            wb.lock_reason = "needs_auth"
            wb.backoff_seconds = 0
        }.build()
        transport.injectFrame(
            FromRadio.Builder().also { wb ->
                wb.lockdown_status = status
            }.build().toFrame(),
        )
        runCurrent()

        val event = events.filterIsInstance<MeshEvent.LockdownStatusChanged>().singleOrNull()
        assertTrue(event != null, "expected a typed LockdownStatusChanged event, got: $events")
        assertEquals(status, event.status)
        // No generic protocol warning should be emitted for a recognized variant.
        assertTrue(events.none { it is MeshEvent.ProtocolWarning })

        job.cancel()
        runCatching { client.disconnect() }
    }

    @Test
    fun lockedHandshakeWaitsForAuthorizationThenRedownloadsOnTheSameLink() = runTest {
        val fake = FakeRadioTransport(TransportIdentity("fake:locked-handshake"), autoHandshake = true)
        var connectCount = 0
        val configRequests = mutableListOf<Int>()
        val transport = object : RadioTransport by fake {
            override suspend fun connect() {
                connectCount++
                fake.connect()
            }

            override suspend fun send(frame: Frame) {
                val request = runCatching {
                    ToRadio.ADAPTER.decode(frame.bytes.substring(4))
                }.getOrNull()
                request?.want_config_id?.let(configRequests::add)
                if (request?.want_config_id == 69420 && configRequests.size == 1) {
                    fake.injectFrame(
                        FromRadio.Builder().also {
                            it.config = Config.Builder().also { builder ->
                                builder.bluetooth = Config.BluetoothConfig.Builder().build()
                            }.build()
                        }.build().toFrame(),
                    )
                }
                if (request?.want_config_id == 69420 && configRequests.size == 2) {
                    fake.injectFrame(
                        FromRadio.Builder().also {
                            it.metadata = DeviceMetadata.Builder().also { metadata ->
                                metadata.firmware_version = "2.8.4"
                            }.build()
                        }.build().toFrame(),
                    )
                }
                fake.send(frame)
                if (request?.want_config_id == 69420 && configRequests.size == 1) {
                    fake.reportLockdown(LockdownStatus.State.LOCKED)
                }
            }
        }
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .sessionReceive()
            .build()
        val statuses = mutableListOf<LockdownStatus.State>()
        val sessionEvents = mutableListOf<RadioSessionEvent>()
        backgroundScope.launch {
            client.events.collect { event ->
                if (event is MeshEvent.LockdownStatusChanged) statuses.add(event.status.state)
            }
        }
        backgroundScope.launch {
            assertNotNull(client.sessionEvents).collect { sessionEvents.add(it) }
        }
        val connecting = async { client.connect() }
        runCurrent()
        assertEquals(listOf(LockdownStatus.State.LOCKED), statuses)
        advanceTimeBy(60.seconds)
        runCurrent()

        assertFalse(connecting.isCompleted)
        assertEquals(LockdownStatus.State.LOCKED, (sessionEvents.single() as RadioSessionEvent.Lockdown).status.state)
        assertEquals(listOf(69420), configRequests)
        val auth = LockdownAuth.Builder().also { it.passphrase = "example".encodeUtf8() }.build()
        assertEquals(AdminResult.Unauthorized, client.admin.forNode(NodeId(2)).lockdown(auth))
        assertEquals(AdminResult.Success(Unit), client.admin.lockdown(auth))
        runCurrent()
        val authPacket = fake.outboundPackets().single()
        assertEquals(fake.nodeNum, authPacket.to)
        assertEquals(auth, AdminMessage.ADAPTER.decode(assertNotNull(authPacket.decoded).payload).lockdown_auth)

        fake.reportLockdown(LockdownStatus.State.UNLOCK_FAILED)
        runCurrent()
        assertFalse(connecting.isCompleted)
        fake.reportLockdown(LockdownStatus.State.UNLOCKED)
        connecting.await()
        runCurrent()

        assertEquals(1, connectCount)
        assertEquals(listOf(69420, 69420, 69421), configRequests)
        assertEquals(ConnectionState.Connected, client.connection.value)
        val snapshot = sessionEvents.filterIsInstance<RadioSessionEvent.Ready>().single()
        val controls = sessionEvents.takeWhile { it != snapshot }.filterIsInstance<RadioSessionEvent.Lockdown>()
        assertEquals(
            listOf(LockdownStatus.State.LOCKED, LockdownStatus.State.UNLOCK_FAILED, LockdownStatus.State.UNLOCKED),
            controls.map { it.status.state },
        )
        assertEquals(listOf(1L, 2L, 3L), controls.map { it.sequence })
        assertTrue(controls.all { it.sessionId == snapshot.sessionId })
        val bundle = assertNotNull(snapshot.configBundle)
        assertEquals("2.8.4", bundle.metadata.firmware_version)
        assertTrue(bundle.configs.none { it.bluetooth != null })
        client.disconnect()
    }

    @Test
    fun queuedLockdownCommandDoesNotCrossAReconnect() = runTest {
        val fake = FakeRadioTransport(TransportIdentity("fake:replaced-lockdown-link"), autoHandshake = true)
        val releaseWriter = CompletableDeferred<Unit>()
        var blockNextWrite = false
        var connectCount = 0
        val transport = object : RadioTransport by fake {
            override suspend fun connect() {
                connectCount++
                fake.connect()
            }

            override suspend fun send(frame: Frame) {
                if (blockNextWrite) {
                    blockNextWrite = false
                    releaseWriter.await()
                    return
                }
                fake.send(frame)
            }
        }
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .autoReconnect(AutoReconnectConfig(initialBackoff = 1.seconds, jitter = 0.0))
            .build()
        client.connect()

        blockNextWrite = true
        val heartbeat = Heartbeat.Builder().also { it.nonce = 731 }.build()
        assertTrue(client.trySendRaw(ToRadio.Builder().also { it.heartbeat = heartbeat }.build()))
        runCurrent()
        assertFalse(blockNextWrite)

        val auth = LockdownAuth.Builder().also { it.passphrase = "example".encodeUtf8() }.build()
        assertEquals(AdminResult.Success(Unit), client.admin.lockdown(auth))
        runCurrent()
        fake.simulateError(IllegalStateException("link replaced"))
        advanceTimeBy(2.seconds)
        assertEquals(2, connectCount)
        releaseWriter.complete(Unit)
        advanceTimeBy(1.seconds)
        assertEquals(ConnectionState.Connected, client.connection.value)
        assertTrue(
            fake.outboundPackets().none { packet ->
                packet.decoded?.let { AdminMessage.ADAPTER.decode(it.payload).lockdown_auth } != null
            },
        )
        client.disconnect()
    }

    /**
     * Firmware answers a heartbeat with a QueueStatus for packet id 0 whether or not the link is
     * authorized (PhoneAPI::handleToRadio takes the heartbeat case outside the access-control gate), so
     * a locked link that stops answering is dead and fails the connect after the 60 s liveness bound.
     */
    @Test
    fun aLockedLinkThatGoesSilentFailsTheConnectAfterTheLivenessTimeout() = runTest {
        val fake = FakeRadioTransport(TransportIdentity("fake:locked-half-open"), autoHandshake = true)
        var answering = true
        var heartbeats = 0
        val transport = object : RadioTransport by fake {
            override suspend fun send(frame: Frame) {
                val request = runCatching { ToRadio.ADAPTER.decode(frame.bytes.substring(4)) }.getOrNull()
                fake.send(frame)
                if (request?.want_config_id == 69420) fake.reportLockdown(LockdownStatus.State.LOCKED)
                if (request?.heartbeat != null) {
                    heartbeats++
                    if (answering) {
                        fake.injectFrame(
                            FromRadio.Builder().also {
                                it.queueStatus = QueueStatus.Builder().also { status ->
                                    status.free = 16
                                    status.maxlen = 16
                                }.build()
                            }.build().toFrame(),
                        )
                    }
                }
            }
        }
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        val connecting = async { runCatching { client.connect() } }
        runCurrent()
        // Sent on entering the wait, before any 30 s tick, so the first reply arms the watchdog at once.
        assertEquals(1, heartbeats)
        advanceTimeBy(5.minutes)
        assertFalse(connecting.isCompleted)
        assertTrue(heartbeats >= 10, "heartbeats=$heartbeats")

        answering = false
        advanceTimeBy(29.seconds)
        assertFalse(connecting.isCompleted)
        advanceTimeBy(32.seconds)
        runCurrent()

        assertTrue(connecting.isCompleted)
        val error = assertIs<MeshtasticException.Transport>(connecting.await().exceptionOrNull())
        assertEquals("liveness timeout", error.message)
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    /** A socket that goes half-open right after the lockdown status never answers a heartbeat. */
    @Test
    fun aLockedLinkThatNeverAnswersFailsTheConnectAfterTheLivenessTimeout() = runTest {
        val fake = FakeRadioTransport(TransportIdentity("fake:locked-never-answers"), autoHandshake = true)
        val transport = object : RadioTransport by fake {
            override suspend fun send(frame: Frame) {
                val request = runCatching { ToRadio.ADAPTER.decode(frame.bytes.substring(4)) }.getOrNull()
                fake.send(frame)
                if (request?.want_config_id == 69420) fake.reportLockdown(LockdownStatus.State.LOCKED)
            }
        }
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        val connecting = async { runCatching { client.connect() } }
        runCurrent()

        advanceTimeBy(60.seconds)
        runCurrent()
        assertFalse(connecting.isCompleted)
        advanceTimeBy(5.seconds)
        runCurrent()

        assertTrue(connecting.isCompleted)
        val error = assertIs<MeshtasticException.Transport>(connecting.await().exceptionOrNull())
        assertEquals("liveness timeout", error.message)
    }

    private fun FakeRadioTransport.reportLockdown(state: LockdownStatus.State) {
        injectFrame(
            FromRadio.Builder().also {
                it.lockdown_status = LockdownStatus.Builder().also { status -> status.state = state }.build()
            }.build().toFrame(),
        )
    }

    private fun kotlinx.coroutines.test.TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:lockdown"),
            autoHandshake = true,
            nodeNum = 0x11111111,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .build()
        return transport to client
    }
}
