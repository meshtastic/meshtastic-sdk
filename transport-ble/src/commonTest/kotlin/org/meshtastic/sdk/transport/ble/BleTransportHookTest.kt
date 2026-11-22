/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.sdk.TransportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Lifecycle contract of [BleTransport.postConnectHook]: it runs exactly once per connect
 * cycle, its failures never fail the connect, and any work it launches into its receiver
 * scope is cancelled at [BleTransport.disconnect] — a stale tuning timer from session N
 * must never fire into session N+1 when the transport is reused after disconnect (the
 * engine's auto-reconnect path).
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy/runCurrent (virtual-time control)
class BleTransportHookTest {

    @Test
    fun shutdownClosesOnlyOwnedPeripheralsAndOnlyOnce() {
        val owned = FakeBlePeripheral()
        val borrowed = FakeBlePeripheral()
        val pickerTransport = BleTransport(owned, address = TEST_ADDRESS).also { it.ownsPeripheral = true }
        val hostTransport = BleTransport(borrowed, address = TEST_ADDRESS)

        try {
            pickerTransport.shutdown()
            pickerTransport.shutdown()
            hostTransport.shutdown()
            assertEquals(1, owned.closeCalls)
            assertEquals(0, borrowed.closeCalls)
        } finally {
            borrowed.close()
        }
    }

    @Test
    fun postConnectHookRunsOncePerConnectAndFailureIsNonFatal() = runTest {
        val transport = BleTransport(FakeBlePeripheral(), address = TEST_ADDRESS)
        var invocations = 0
        transport.postConnectHook = {
            invocations++
            error("tune failed")
        }

        transport.connect()
        assertEquals(TransportState.Connected, transport.state.value)
        assertEquals(1, invocations)

        transport.disconnect()
        transport.connect()
        assertEquals(TransportState.Connected, transport.state.value)
        assertEquals(2, invocations)

        transport.shutdown()
    }

    @Test
    fun hookScheduledWorkIsCancelledOnDisconnect() = runTest {
        val transport = BleTransport(FakeBlePeripheral(), address = TEST_ADDRESS)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var fired = false
        transport.postConnectHook = {
            // Mirrors the Android factory's delayed priority downgrade. The explicit test
            // dispatcher gives the delay virtual time; the job parent comes from the
            // receiver scope, which is what this test is about.
            launch(testDispatcher) {
                delay(30_000)
                fired = true
            }
        }

        transport.connect()
        transport.disconnect()

        advanceTimeBy(31_000)
        runCurrent()
        assertFalse(fired, "work launched into the hook scope must be cancelled at disconnect()")

        transport.shutdown()
    }

    @Test
    fun hookWorkFromPriorSessionDoesNotLeakIntoNext() = runTest {
        val transport = BleTransport(FakeBlePeripheral(), address = TEST_ADDRESS)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var session1Fired = false
        var connects = 0
        transport.postConnectHook = {
            connects++
            if (connects == 1) {
                launch(testDispatcher) {
                    delay(30_000)
                    session1Fired = true
                }
            }
        }

        transport.connect() // session 1 — schedules delayed tuning work
        transport.disconnect()
        transport.connect() // session 2 — same instance (engine auto-reconnect reuse)

        advanceTimeBy(31_000) // past session 1's original deadline, well inside session 2
        runCurrent()
        assertFalse(session1Fired, "session-1 tuning work must not fire into session 2")
        assertEquals(2, connects)

        transport.shutdown()
    }

    private companion object {
        const val TEST_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
