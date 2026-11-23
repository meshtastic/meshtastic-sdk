/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.InMemoryStorageProvider
import org.meshtastic.sdk.LogSink
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.FakeRadioTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A `disconnect()` that lands while `connect()` is still installing its run must not let a second
 * `connect()` start a run that the first one then overwrites with its cancelled scope (declined
 * Minor on #284). Each connect runs on its own thread, as on `RadioClient`'s default
 * `Dispatchers.Default`.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class ConnectAdmissionRaceTest {

    /** Holds every `connect()` until [gate] opens, after reporting that one reached the transport. */
    private class GatedTransport(private val delegate: FakeRadioTransport) : RadioTransport by delegate {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        override suspend fun connect() {
            entered.complete(Unit)
            gate.await()
            delegate.connect()
        }
    }

    @Test
    fun disconnectDuringStartupThenConnectLeavesAWorkingSession() = runBlocking {
        val transport = GatedTransport(
            FakeRadioTransport(TransportIdentity("fake:admission-race"), autoHandshake = true),
        )
        val parent = SupervisorJob()
        val engine = MeshEngine(
            identity = transport.identity,
            transportProvider = { _, _ -> transport },
            storageProvider = InMemoryStorageProvider(),
            logger = LogSink.Silent,
            bleHeartbeatEnabled = false,
            parentContext = parent + Dispatchers.Default,
        )
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val first = AtomicBoolean(true)
        engine.runStartProbe = {
            if (first.getAndSet(false)) {
                paused.countDown()
                resume.await(10, TimeUnit.SECONDS)
            }
        }
        val threadA = newSingleThreadContext("connect-a")
        val threadC = newSingleThreadContext("connect-c")
        try {
            val a = async(threadA) { runCatching { engine.connect() } }
            assertTrue(paused.await(5, TimeUnit.SECONDS), "first connect() never reached run start")

            withTimeout(5_000) { engine.disconnect() }
            val c = async(threadC) { runCatching { engine.connect() } }
            // The second connect is either refused at once or has started a run that reached the transport.
            withTimeout(5_000) {
                select {
                    c.onAwait { }
                    transport.entered.onAwait { }
                }
            }
            resume.countDown()
            assertTrue(withTimeout(5_000) { a.await() }.isFailure)
            transport.gate.complete(Unit)

            if (c.isCompleted) {
                assertIs<MeshtasticException.AlreadyConnected>(c.await().exceptionOrNull())
                withTimeout(10_000) { engine.connect() }
            } else {
                withTimeout(10_000) { c.await() }.getOrThrow()
            }
            assertEquals(ConnectionState.Connected, engine.connectionState.value)
        } finally {
            resume.countDown()
            transport.gate.complete(Unit)
            withTimeout(5_000) { engine.disconnect() }
            parent.cancel()
            threadA.close()
            threadC.close()
        }
    }
}
