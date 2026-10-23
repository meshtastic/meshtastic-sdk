/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.TransportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Link-loss contract of [BleTransport]: a drop we did not initiate becomes a recoverable
 * [TransportState.Error] whether Kable reports it as `Disconnecting` then `Disconnected` or
 * goes straight to `Disconnected`; the Error is not overwritten by the later `Disconnected`;
 * our own [BleTransport.disconnect] is never an error; and every connect cycle reports again.
 */
class BleTransportLinkLossTest {

    @Test
    fun dropViaDisconnectingPublishesRecoverableError() = runTest {
        val peripheral = FakePeripheral()
        val transport = BleTransport(peripheral, address = TEST_ADDRESS)
        transport.connect()

        peripheral.emitState(State.Disconnecting)
        val error = awaitError(transport)
        assertTrue(error.recoverable)
        assertIs<MeshtasticException.Transport>(error.cause)

        // Kable's follow-up Disconnected must not hide the Error behind Disconnected.
        peripheral.emitState(State.Disconnected())
        settle()
        assertEquals<TransportState>(error, transport.state.value)

        transport.shutdown()
    }

    @Test
    fun dropWithoutDisconnectingPublishesRecoverableError() = runTest {
        val peripheral = FakePeripheral()
        val transport = BleTransport(peripheral, address = TEST_ADDRESS)
        transport.connect()

        peripheral.emitState(State.Disconnected())

        assertTrue(awaitError(transport).recoverable)
        transport.shutdown()
    }

    @Test
    fun disconnectIsNotReportedAsError() = runTest {
        val peripheral = FakePeripheral()
        val transport = BleTransport(peripheral, address = TEST_ADDRESS)
        transport.connect()

        transport.disconnect()
        settle()

        assertEquals(TransportState.Disconnected, transport.state.value)
        transport.shutdown()
    }

    @Test
    fun eachConnectCycleReportsItsOwnDrop() = runTest {
        val peripheral = FakePeripheral()
        val transport = BleTransport(peripheral, address = TEST_ADDRESS)
        transport.connect()
        peripheral.emitState(State.Disconnecting)
        awaitError(transport)

        // The engine's auto-reconnect path on the same instance.
        transport.disconnect()
        transport.connect()
        assertEquals(TransportState.Connected, transport.state.value)

        peripheral.emitState(State.Disconnecting)
        assertTrue(awaitError(transport).recoverable)
        transport.shutdown()
    }

    // The state bridge runs on Dispatchers.Default, so wait in real time, not virtual time.
    private suspend fun awaitError(transport: BleTransport): TransportState.Error = withContext(Dispatchers.Default) {
        withTimeout(TIMEOUT_MS) { transport.state.first { it is TransportState.Error } } as TransportState.Error
    }

    private suspend fun settle() = withContext(Dispatchers.Default) { delay(SETTLE_MS) }

    private companion object {
        const val TEST_ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 200L
    }
}
