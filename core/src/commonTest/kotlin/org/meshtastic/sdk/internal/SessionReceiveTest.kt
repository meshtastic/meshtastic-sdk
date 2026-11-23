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
import org.meshtastic.proto.MeshPacket
import org.meshtastic.sdk.RadioSessionEvent
import org.meshtastic.sdk.RadioSessionOverflowException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class SessionReceiveTest {

    @Test
    fun anEndedThatOverflowsOpensTheReplacementStream() = runTest {
        val receive = SessionReceive(capacity = 2, restartOnOverflow = true)
        val release = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<Throwable?>()
        // A collector that takes Ready and stalls, so the queue fills behind it.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            failure.complete(runCatching { receive.events.collect { release.await() } }.exceptionOrNull())
        }
        receive.begin()
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
