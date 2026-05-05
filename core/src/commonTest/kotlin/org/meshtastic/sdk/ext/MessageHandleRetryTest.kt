/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.ext

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.MeshPacket
import org.meshtastic.sdk.MessageHandle
import org.meshtastic.sdk.MessageId
import org.meshtastic.sdk.RetryPolicy
import org.meshtastic.sdk.SendFailure
import org.meshtastic.sdk.SendOutcome
import org.meshtastic.sdk.SendState
import org.meshtastic.sdk.retryWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class MessageHandleRetryTest {
    private fun fakeHandle(
        terminal: SendState,
        packet: MeshPacket? = MeshPacket(id = 1),
        resendFn: ((MeshPacket) -> MessageHandle)? = null,
    ): MessageHandle {
        val state = MutableStateFlow<SendState>(terminal)
        return MessageHandle(
            id = MessageId(1),
            _state = state,
            cancelFn = {},
            packet = packet,
            resendFn = resendFn,
        )
    }

    @Test
    fun successOnFirstAttemptDoesNotRetry() = runTest {
        var resendCalled = false
        val handle = fakeHandle(
            SendState.Acked,
            resendFn = {
                resendCalled = true
                fakeHandle(SendState.Acked)
            },
        )

        val result = handle.retryWith(RetryPolicy.Fixed(maxAttempts = 3, delay = 100.milliseconds))

        assertEquals(SendOutcome.Success, result)
        assertFalse(resendCalled)
    }

    @Test
    fun retriesOnAckTimeoutAfterDelay() = runTest {
        var attempts = 0
        lateinit var resendFn: (MeshPacket) -> MessageHandle
        resendFn = { pkt ->
            attempts++
            fakeHandle(SendState.Acked, pkt, resendFn)
        }

        val deferred = async {
            fakeHandle(
                SendState.Failed(SendFailure.AckTimeout),
                resendFn = resendFn,
            ).retryWith(RetryPolicy.Fixed(maxAttempts = 3, delay = 10.milliseconds))
        }

        runCurrent()
        assertEquals(0, attempts)

        advanceTimeBy(9.milliseconds)
        runCurrent()
        assertEquals(0, attempts)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(SendOutcome.Success, deferred.await())
    }

    @Test
    fun doesNotRetryOnDisconnected() = runTest {
        var resendCalled = false
        val handle = fakeHandle(
            SendState.Failed(SendFailure.Disconnected),
            resendFn = {
                resendCalled = true
                fakeHandle(SendState.Acked)
            },
        )
        val result = handle.retryWith(RetryPolicy.Fixed(maxAttempts = 3, delay = 10.milliseconds))
        assertEquals(SendOutcome.Failure(SendFailure.Disconnected), result)
        assertFalse(resendCalled)
    }

    @Test
    fun maxAttemptsExhaustedReturnsLastFailure() = runTest {
        var attempts = 0
        lateinit var resendFn: (MeshPacket) -> MessageHandle
        resendFn = { pkt ->
            attempts++
            when (attempts) {
                1 -> fakeHandle(SendState.Failed(SendFailure.Timeout), pkt, resendFn)
                else -> throw AssertionError("retryWith exceeded configured retry limit")
            }
        }

        val result = fakeHandle(
            SendState.Failed(SendFailure.AckTimeout),
            resendFn = resendFn,
        ).retryWith(RetryPolicy.Fixed(maxAttempts = 1, delay = 10.milliseconds))

        assertEquals(SendOutcome.Failure(SendFailure.Timeout), result)
        assertEquals(1, attempts)
    }

    @Test
    fun nonePolicyDoesNotRetry() = runTest {
        var resendCalled = false
        val handle = fakeHandle(
            SendState.Failed(SendFailure.AckTimeout),
            resendFn = {
                resendCalled = true
                fakeHandle(SendState.Acked)
            },
        )

        val result = handle.retryWith(RetryPolicy.None)

        assertEquals(SendOutcome.Failure(SendFailure.AckTimeout), result)
        assertFalse(resendCalled)
    }

    @Test
    fun exponentialBackoffDelaysIncrease() = runTest {
        var attempts = 0
        lateinit var resendFn: (MeshPacket) -> MessageHandle
        resendFn = { pkt ->
            attempts++
            if (attempts >= 3) {
                fakeHandle(SendState.Acked, pkt, resendFn)
            } else {
                fakeHandle(SendState.Failed(SendFailure.AckTimeout), pkt, resendFn)
            }
        }

        val deferred = async {
            fakeHandle(
                SendState.Failed(SendFailure.AckTimeout),
                resendFn = resendFn,
            ).retryWith(
                RetryPolicy.ExponentialBackoff(
                    maxAttempts = 3,
                    initialDelay = 10.milliseconds,
                    maxDelay = 100.milliseconds,
                    multiplier = 2.0,
                    jitterFactor = 0.0,
                ),
            )
        }

        runCurrent()
        assertEquals(0, attempts)

        advanceTimeBy(9.milliseconds)
        runCurrent()
        assertEquals(0, attempts)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(1, attempts)

        advanceTimeBy(19.milliseconds)
        runCurrent()
        assertEquals(1, attempts)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(2, attempts)

        advanceTimeBy(39.milliseconds)
        runCurrent()
        assertEquals(2, attempts)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(3, attempts)
        assertEquals(SendOutcome.Success, deferred.await())
    }
}
