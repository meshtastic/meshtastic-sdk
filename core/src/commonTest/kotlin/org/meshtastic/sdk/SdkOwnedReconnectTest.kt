/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Expected backoffs follow AutoReconnectConfig: initialBackoff * multiplier^(n-1), capped, jitter 0.
@OptIn(ExperimentalCoroutinesApi::class)
class SdkOwnedReconnectTest {

    @Test
    fun aSessionShorterThanStableAfterKeepsTheBackoffGrowingAndALongerOneResetsIt() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config(stableAfter = 5.seconds))
        client.connect()

        link.drop()
        runCurrent()
        assertReconnecting(client, attempt = 1, retryInMs = 1_000)
        advanceTimeBy(1_000L)
        settle()
        assertEquals(ConnectionState.Connected, client.connection.value)

        advanceTimeBy(4_000L)
        drain()
        link.drop()
        runCurrent()
        assertReconnecting(client, attempt = 2, retryInMs = 2_000)
        advanceTimeBy(2_000L)
        settle()
        assertEquals(ConnectionState.Connected, client.connection.value)

        advanceTimeBy(6_000L)
        drain()
        link.drop()
        runCurrent()
        assertReconnecting(client, attempt = 1, retryInMs = 1_000)
    }

    @Test
    fun theFourthHandshakeTimeoutInARowFailsAndACompletedHandshakeStartsTheCountOver() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config(maxBackoff = 2.seconds, maxHandshakeRecoveries = 3))
        client.connect()

        // Drop at 0; attempts at 1, 23, 45 and 67 s; each Stage 1 times out 20 s after its attempt.
        link.answerHandshake = false
        link.drop()
        runCurrent()
        advanceTimeBy(66_000L)
        drain()
        assertIs<ConnectionState.Reconnecting>(client.connection.value, "three timeouts are restarted")
        link.answerHandshake = true
        advanceTimeBy(2_000L)
        settle()
        assertEquals(ConnectionState.Connected, client.connection.value)

        link.answerHandshake = false
        link.drop()
        runCurrent()
        advanceTimeBy(86_000L)
        drain()
        assertFalse(client.connection.value is ConnectionState.Failed, "the count restarted at the handshake")
        advanceTimeBy(2_000L)
        drain()
        val failed = assertIs<ConnectionState.Failed>(client.connection.value)
        assertIs<MeshtasticException.HandshakeTimeout>(failed.cause)
    }

    @Test
    fun aNonRecoverableErrorAfterConnectedFailsWithoutReconnecting() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config())
        client.connect()
        val states = recordStates(client)
        val connects = link.connects.size

        val fatal = MeshtasticException.Transport("unsupported device")
        link.drop(fatal, recoverable = false)
        drain()
        advanceTimeBy(60_000L)
        drain()

        assertTrue(states.none { it is ConnectionState.Reconnecting }, "states after the error: $states")
        assertSame(fatal, assertIs<ConnectionState.Failed>(client.connection.value).cause)
        assertEquals(connects, link.connects.size, "no attempt after a non-recoverable error")
    }

    @Test
    fun aFreshTransportPerAttemptOpensOnlyAfterThePreviousIsDisconnectedAndItsReaderEnded() = runTest {
        val log = mutableListOf<String>()
        val first = ScriptLink("a", log, { currentTime })
        val opened = mutableListOf(first)
        val client = buildClient(
            first,
            config(),
            provider = { attempt, _ ->
                log += "open#$attempt"
                if (attempt == 0) first else ScriptLink("link$attempt", log, { currentTime }).also { opened += it }
            },
        )
        client.connect()

        first.drop()
        runCurrent()
        advanceTimeBy(1_000L)
        settle()

        assertEquals(ConnectionState.Connected, client.connection.value)
        val second = opened[1]
        val reopenedAt = log.indexOf("open#1")
        assertTrue(log.indexOf("a.disconnect") in 0 until reopenedAt, "log: $log")
        assertTrue(log.indexOf("a.reader-done") in 0 until reopenedAt, "log: $log")
        assertTrue(second.sent.any { it.want_config_id != null }, "the new session runs on the new transport")

        first.drop()
        drain()
        assertEquals(ConnectionState.Connected, client.connection.value, "a replaced transport's late error is stale")
    }

    @Test
    fun aSuspendedProviderLeavesRetryAtNullAndUsesNoAttempt() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val ready = CompletableDeferred<Unit>()
        val opens = mutableListOf<Int>()
        val client = buildClient(
            link,
            config(maxAttempts = 1),
            provider = { attempt, _ ->
                opens += attempt
                if (attempt > 0) ready.await()
                link
            },
        )
        client.connect()

        link.drop()
        runCurrent()
        assertReconnecting(client, attempt = 1, retryInMs = 1_000)
        advanceTimeBy(1_000L)
        drain()
        assertReconnecting(client, attempt = 1, retryInMs = null)

        advanceTimeBy(600_000L)
        drain()
        assertReconnecting(client, attempt = 1, retryInMs = null)
        assertEquals(listOf(0, 1), opens)
        assertFalse(client.retryNow(), "an attempt in flight has no wait to end")

        ready.complete(Unit)
        settle()
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun retryNowEndsTheBackoffWaitAndDoesNothingWhileConnected() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config(initialBackoff = 30.seconds, maxBackoff = 30.seconds))
        client.connect()
        val connects = link.connects.size

        assertFalse(client.retryNow())
        drain()
        assertEquals(connects, link.connects.size)

        val droppedAt = currentTime
        link.drop()
        runCurrent()
        advanceTimeBy(5_000L)
        drain()
        assertEquals(connects, link.connects.size, "still inside the 30 s backoff")

        assertTrue(client.retryNow())
        drain()

        assertEquals(connects + 1, link.connects.size)
        assertEquals(5_000L, link.connects.last() - droppedAt)
        settle()
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun endedIsPublishedAtTheDropBeforeReconnectingAndTheNextSessionFollows() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config(), sessionCapacity = 256)
        val log = mutableListOf<String>()
        val sessions = mutableListOf<RadioSessionEvent>()
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(unconfined) {
            checkNotNull(client.sessionEvents).collect { event ->
                sessions += event
                log += "${event::class.simpleName}(${event.sessionId})"
            }
        }
        backgroundScope.launch(unconfined) {
            client.connection.collect { state -> log += state::class.simpleName.orEmpty() }
        }
        client.connect()
        drain()

        link.drop()
        runCurrent()
        advanceTimeBy(1_000L)
        settle()

        val ended = log.indexOf("Ended(1)")
        val reconnecting = log.indexOf("Reconnecting")
        val nextReady = log.indexOf("Ready(2)")
        assertTrue(ended in 0 until reconnecting, "log: $log")
        assertTrue(reconnecting < nextReady, "log: $log")
        val afterDrop = sessions.drop(sessions.indexOfFirst { it is RadioSessionEvent.Ended } + 1)
        assertTrue(afterDrop.isNotEmpty() && afterDrop.all { it.sessionId == 2L }, "after the drop: $afterDrop")
    }

    @Test
    fun aReceiveOverflowRestartsTheSessionAndACollectorResumesOnAFreshStream() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config(), sessionCapacity = SMALL_QUEUE)
        val stream = checkNotNull(client.sessionEvents)
        val stalled = stalledCollector(stream)
        client.connect()
        drain()
        val connects = link.connects.size

        flood(link)
        assertReconnecting(client, attempt = 1, retryInMs = 1_000)
        advanceTimeBy(1_000L)
        settle()
        assertEquals(ConnectionState.Connected, client.connection.value)
        assertEquals(connects + 1, link.connects.size, "the restart reconnects through the provider")

        stalled.release.complete(Unit)
        drain()
        assertEquals(1L, assertIs<RadioSessionOverflowException>(stalled.failure.await()).sessionId)
        val resumed = mutableListOf<RadioSessionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stream.collect { resumed += it } }
        drain()
        assertEquals(RadioSessionEvent.Ended(1), resumed.firstOrNull(), "resumed: $resumed")
        assertEquals(2L, assertIs<RadioSessionEvent.Ready>(resumed[1]).sessionId)
    }

    @Test
    fun overflowRestartsCountAgainstMaxHandshakeRecoveriesThoughEachSessionReachedReady() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config(maxHandshakeRecoveries = 1), sessionCapacity = SMALL_QUEUE)
        stalledCollector(checkNotNull(client.sessionEvents))
        client.connect()
        drain()

        flood(link)
        assertIs<ConnectionState.Reconnecting>(client.connection.value, "the first overflow is restarted")
        advanceTimeBy(1_000L)
        settle()
        assertEquals(ConnectionState.Connected, client.connection.value)

        flood(link)
        val failed = assertIs<ConnectionState.Failed>(client.connection.value)
        assertTrue(failed.cause.message.orEmpty().contains("overflowed"), "cause: ${failed.cause}")
    }

    @Test
    fun withoutMaxHandshakeRecoveriesTheFourthOverflowInARowFails() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config(), sessionCapacity = SMALL_QUEUE)
        stalledCollector(checkNotNull(client.sessionEvents))
        client.connect()
        drain()

        repeat(3) { round ->
            flood(link)
            assertIs<ConnectionState.Reconnecting>(client.connection.value, "overflow ${round + 1} is restarted")
            advanceTimeBy(1_000L)
            settle()
            assertEquals(ConnectionState.Connected, client.connection.value)
        }
        flood(link)
        assertIs<ConnectionState.Failed>(client.connection.value)
    }

    @Test
    fun anOverflowWithNoCollectorFailsTheStreamAndKeepsTheSession() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config(), sessionCapacity = SMALL_QUEUE)
        client.connect()
        drain()
        val connects = link.connects.size

        flood(link)
        advanceTimeBy(5_000L)
        drain()

        assertEquals(ConnectionState.Connected, client.connection.value)
        assertEquals(connects, link.connects.size)
        val overflow = runCatching { checkNotNull(client.sessionEvents).collect { } }.exceptionOrNull()
        assertIs<RadioSessionOverflowException>(overflow)
    }

    private class Stalled(val release: CompletableDeferred<Unit>, val failure: CompletableDeferred<Throwable?>)

    // A collector that stops at its first event until released, so the queue fills behind it.
    private fun TestScope.stalledCollector(stream: Flow<RadioSessionEvent>): Stalled {
        val stalled = Stalled(CompletableDeferred(), CompletableDeferred())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stalled.failure.complete(runCatching { stream.collect { stalled.release.await() } }.exceptionOrNull())
        }
        return stalled
    }

    @Test
    fun anAttemptThatFailsWithAnErrorStateAndAThrowAdvancesTheAttemptOnce() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config())
        client.connect()

        link.failNextConnects = 1
        link.drop()
        runCurrent()
        advanceTimeBy(1_000L)
        drain()

        assertReconnecting(client, attempt = 2, retryInMs = 2_000)
    }

    @Test
    fun aProviderThatCancelsItselfFailsTheAttemptAndTheEngineRetries() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(
            link,
            config(),
            provider = { attempt, _ ->
                // What a provider's withTimeout around a USB permission prompt throws.
                if (attempt == 1) throw CancellationException("permission prompt timed out")
                link
            },
        )
        client.connect()

        link.drop()
        runCurrent()
        advanceTimeBy(1_000L)
        drain()
        assertReconnecting(client, attempt = 2, retryInMs = 2_000)
        assertTrue(client.retryNow())
        settle()
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun connectWaitsThroughAFailedOrCancelledFirstAttempt() = runTest {
        for (failure in listOf(MeshtasticException.Transport("no route"), CancellationException("timed out"))) {
            val link = ScriptLink("a", mutableListOf(), { currentTime })
            var opens = 0
            val client = buildClient(
                link,
                config(),
                provider = { _, _ ->
                    if (opens++ == 0) throw failure
                    link
                },
            )
            val connecting = backgroundScope.async { client.connect() }
            drain()
            assertReconnecting(client, attempt = 1, retryInMs = 1_000)
            assertFalse(connecting.isCompleted, "connect() waits through a failed first attempt (${failure::class})")
            advanceTimeBy(1_000L)
            settle()
            connecting.await()
            assertEquals(ConnectionState.Connected, client.connection.value)
            client.disconnect()
        }
    }

    @Test
    fun disconnectEndsASuspendedProviderAndABackoffWait() = runTest {
        for (suspendProvider in listOf(true, false)) {
            val link = ScriptLink("a", mutableListOf(), { currentTime })
            val never = CompletableDeferred<Unit>()
            val client = buildClient(
                link,
                config(initialBackoff = 30.seconds),
                provider = { attempt, _ ->
                    if (attempt > 0 && suspendProvider) never.await()
                    link
                },
            )
            client.connect()
            val connects = link.connects.size
            link.drop()
            runCurrent()
            if (suspendProvider) assertTrue(client.retryNow())
            drain()

            client.disconnect()
            assertEquals(ConnectionState.Disconnected, client.connection.value)
            advanceTimeBy(120_000L)
            drain()
            assertEquals(connects, link.connects.size, "no attempt after disconnect() (suspended=$suspendProvider)")
            assertEquals(ConnectionState.Disconnected, client.connection.value)
        }
    }

    @Test
    fun connectRightAfterFailedStartsANewRun() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config())
        client.connect()
        var reconnect: Result<Unit>? = null
        // Unconfined: runs inside the actor's publication of Failed, before that run is torn down.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.connection.first { it is ConnectionState.Failed }
            reconnect = runCatching { client.connect() }
        }

        link.drop(MeshtasticException.Transport("unsupported"), recoverable = false)
        settle()

        assertTrue(reconnect?.isSuccess == true, "connect() after Failed: $reconnect")
        assertEquals(ConnectionState.Connected, client.connection.value)
    }

    @Test
    fun aLinkOpenedAsTheSessionEndsIsDisconnected() = runTest {
        val log = mutableListOf<String>()
        val first = ScriptLink("a", log, { currentTime })
        lateinit var client: RadioClient
        val client0 = buildClient(
            first,
            config(),
            provider = { attempt, _ ->
                if (attempt == 0) {
                    first
                } else {
                    // Its connect() returns as disconnect() begins, so the result reaches a closing engine.
                    ScriptLink("link$attempt", log, { currentTime }, onConnect = {
                        backgroundScope.launch { client.disconnect() }
                    })
                }
            },
        )
        client = client0
        client.connect()

        first.drop()
        runCurrent()
        advanceTimeBy(1_000L)
        drain()

        assertEquals(ConnectionState.Disconnected, client.connection.value)
        assertTrue("link1.disconnect" in log, "log: $log")
    }

    @Test
    fun disconnectFromConnectedShowsDisconnectingThenDisconnected() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config())
        client.connect()
        val states = recordStates(client)

        client.disconnect()

        assertEquals(
            listOf(ConnectionState.Connected, ConnectionState.Disconnecting, ConnectionState.Disconnected),
            states,
        )
    }

    @Test
    fun disconnectInABackoffWaitShowsDisconnectingThenDisconnected() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config(initialBackoff = 30.seconds, maxBackoff = 30.seconds))
        client.connect()
        link.drop()
        runCurrent()
        val states = recordStates(client)

        client.disconnect()

        assertIs<ConnectionState.Reconnecting>(states.first())
        assertEquals(listOf(ConnectionState.Disconnecting, ConnectionState.Disconnected), states.drop(1))
    }

    @Test
    fun disconnectMidHandlerHoldsDisconnectingThoughTheHandlerPublishesOn() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config())
        val states = recordStates(client)
        // Unconfined: disconnect() runs inside the actor's publication of Connecting, which then goes on to
        // connect the transport and publish Configuring.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.connection.first { it is ConnectionState.Connecting }
            client.disconnect()
        }

        runCatching { client.connect() }
        drain()

        val after = states.drop(states.indexOf(ConnectionState.Disconnecting))
        assertEquals(listOf(ConnectionState.Disconnecting, ConnectionState.Disconnected), after, "states: $states")
    }

    @Test
    fun aLinkLossNeverShowsDisconnecting() = runTest {
        for (enabled in listOf(true, false)) {
            val link = ScriptLink("a", mutableListOf(), { currentTime })
            val client = buildClient(link, config().copy(enabled = enabled))
            client.connect()
            val states = recordStates(client)

            link.drop()
            runCurrent()
            advanceTimeBy(1_000L)
            settle()
            link.drop(MeshtasticException.Transport("unsupported"), recoverable = false)
            settle()

            assertTrue(states.none { it == ConnectionState.Disconnecting }, "auto-reconnect $enabled: $states")
        }
    }

    @Test
    fun disconnectWhileDisconnectedPublishesNothing() = runTest {
        val link = ScriptLink("a", mutableListOf(), { currentTime })
        val client = buildClient(link, config())
        val states = recordStates(client)

        client.disconnect()

        assertEquals(listOf<ConnectionState>(ConnectionState.Disconnected), states)
    }

    // Each packet from a peer queues a Packet and a NodeChanged; nothing collects, so the queue overflows.
    private fun TestScope.flood(link: ScriptLink) {
        repeat(SMALL_QUEUE * 2) { index ->
            link.inject(
                MeshPacket.Builder().also { packet ->
                    packet.id = PACKET_ID_BASE + index + link.connects.size * SMALL_QUEUE * 2
                    packet.from = PEER_NODE
                    packet.to = BROADCAST
                    packet.decoded = Data.Builder().also { it.portnum = PortNum.TEXT_MESSAGE_APP }.build()
                }.build(),
            )
        }
        drain()
    }

    private fun config(
        initialBackoff: Duration = 1.seconds,
        maxBackoff: Duration = 30.seconds,
        maxAttempts: Int? = null,
        stableAfter: Duration = Duration.ZERO,
        maxHandshakeRecoveries: Int? = null,
    ) = AutoReconnectConfig(
        enabled = true,
        initialBackoff = initialBackoff,
        maxBackoff = maxBackoff,
        maxAttempts = maxAttempts,
        jitter = 0.0,
        stableAfter = stableAfter,
        maxHandshakeRecoveries = maxHandshakeRecoveries,
    )

    private fun TestScope.assertReconnecting(client: RadioClient, attempt: Int, retryInMs: Long?) {
        val state = assertIs<ConnectionState.Reconnecting>(client.connection.value)
        assertEquals(attempt, state.attempt)
        if (retryInMs == null) {
            assertNull(state.retryAt)
        } else {
            assertEquals(Instant.fromEpochMilliseconds(currentTime + retryInMs), state.retryAt)
        }
    }

    private fun TestScope.buildClient(
        link: ScriptLink,
        config: AutoReconnectConfig,
        provider: RadioTransportProvider? = null,
        sessionCapacity: Int? = null,
    ): RadioClient = RadioClient.Builder()
        .transport(link.identity, provider ?: RadioTransportProvider { _, _ -> link })
        .storage(InMemoryStorageProvider())
        .clock(
            object : kotlin.time.Clock {
                override fun now(): Instant = Instant.fromEpochMilliseconds(currentTime)
            },
        )
        .coroutineContext(backgroundScope.coroutineContext)
        .autoSyncTimeOnConnect(false)
        .autoReconnect(config)
        .apply { sessionCapacity?.let { sessionReceive(it) } }
        .build()

    private fun TestScope.recordStates(client: RadioClient): MutableList<ConnectionState> {
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.connection.collect { states += it } }
        return states
    }

    private fun TestScope.drain() {
        repeat(20) { runCurrent() }
    }

    // The fake answers at once; the handshake still waits out its two 100 ms settle windows.
    private fun TestScope.settle() {
        advanceTimeBy(300L)
        drain()
    }

    /** A [FakeRadioTransport] that answers the handshake on demand and logs its lifecycle to [log]. */
    private class ScriptLink(
        private val name: String,
        private val log: MutableList<String>,
        private val nowMs: () -> Long,
        private val onConnect: () -> Unit = {},
    ) : RadioTransport {
        private val inner = FakeRadioTransport(TransportIdentity("fake:sdk-reconnect"), autoHandshake = true)
        private val stateFlow = MutableStateFlow<TransportState>(TransportState.Disconnected)

        /** When false the radio never answers `want_config_id`, so Stage 1 times out. */
        var answerHandshake = true
        var failNextConnects = 0
        val connects = mutableListOf<Long>()
        val sent = mutableListOf<ToRadio>()

        override val identity: TransportIdentity = inner.identity
        override val state: StateFlow<TransportState> = stateFlow

        override suspend fun connect() {
            log += "$name.connect"
            connects += nowMs()
            if (failNextConnects > 0) {
                // A BLE connect timeout: the transport publishes its Error, then connect() throws.
                failNextConnects -= 1
                val failure = MeshtasticException.Transport("connect timed out")
                stateFlow.value = TransportState.Error(failure, recoverable = true)
                throw failure
            }
            inner.connect()
            stateFlow.value = TransportState.Connected
            onConnect()
        }

        override suspend fun disconnect() {
            log += "$name.disconnect"
            inner.disconnect()
            stateFlow.value = TransportState.Disconnected
        }

        override suspend fun send(frame: Frame) {
            val bytes = frame.bytes.toByteArray()
            if (bytes.size > 4) {
                runCatching { ToRadio.ADAPTER.decode(bytes.copyOfRange(4, bytes.size)) }.onSuccess { sent += it }
            }
            if (answerHandshake) inner.send(frame)
        }

        override fun frames(): Flow<Frame> = inner.frames().onCompletion { log += "$name.reader-done" }

        fun inject(packet: MeshPacket) = inner.injectPacket(packet)

        fun drop(cause: MeshtasticException = MeshtasticException.Transport("link lost"), recoverable: Boolean = true) {
            stateFlow.value = TransportState.Error(cause, recoverable)
        }
    }

    private companion object {
        const val SMALL_QUEUE = 4
        const val PEER_NODE = 0x1234
        const val BROADCAST = -1
        const val PACKET_ID_BASE = 500
    }
}
