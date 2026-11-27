/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.LockdownStatus
import org.meshtastic.proto.MeshPacket
import org.meshtastic.sdk.RadioSessionEvent
import org.meshtastic.sdk.RadioSessionOverflowException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class SessionReceiveTest {

    // Regression: a lockdown-only session ended silently, so the app had to infer the close.
    @Test
    fun aSessionThatStoppedAtLockdownEndsWithEnded() = runTest {
        val receive = SessionReceive(capacity = 8)
        val seen = mutableListOf<RadioSessionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { receive.events.collect { seen += it } }
        val status = LockdownStatus.Builder().also { it.state = LockdownStatus.State.LOCKED }.build()

        receive.begin(1L)
        receive.lockdown(status)
        receive.end()
        runCurrent()
        val expected = listOf<RadioSessionEvent>(
            RadioSessionEvent.Lockdown(1L, 1L, status),
            RadioSessionEvent.Ended(1L),
        )
        assertEquals(expected, seen)

        // A session that delivered nothing has no id the consumer saw, so it gets no Ended.
        receive.begin(2L)
        receive.end()
        runCurrent()
        assertEquals(2, seen.size)
    }

    @Test
    fun anEndedThatOverflowsOpensTheReplacementStream() = runTest {
        val receive = SessionReceive(capacity = 2, restartOnOverflow = true)
        val release = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<Throwable?>()
        // A collector that takes Ready and stalls, so the queue fills behind it.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            failure.complete(runCatching { receive.events.collect { release.await() } }.exceptionOrNull())
        }
        receive.begin(1L)
        receive.ready(RadioSessionEvent.Ready(1L, null, null, null, emptyMap()))
        repeat(2) { index -> receive.packet(MeshPacket.Builder().also { it.id = 10 + index }.build(), false) }

        // The queue is full, so Ended is the event that overflows.
        receive.end()
        release.complete(Unit)
        runCurrent()

        assertIs<RadioSessionOverflowException>(failure.await())
        val resumed = mutableListOf<RadioSessionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { receive.events.collect { resumed += it } }
        runCurrent()
        assertEquals(listOf<RadioSessionEvent>(RadioSessionEvent.Ended(1L)), resumed)
    }
}
