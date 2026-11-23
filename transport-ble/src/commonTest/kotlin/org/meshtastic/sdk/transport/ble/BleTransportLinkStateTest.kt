/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.TransportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BleTransportLinkStateTest {

    // The engine reconnects only on TransportState.Error, so a peer drop must surface as one.
    // Kable's JVM backend reports the drop as Disconnecting before Disconnected.
    @Test
    fun peripheralDroppingTheLinkWhileConnectedPublishesARecoverableError() = runTest {
        val peripheral = FakeBlePeripheral()
        val transport = BleTransport(peripheral, address = ADDRESS)
        transport.connect()
        assertEquals(TransportState.Connected, transport.state.value)

        peripheral.emitState(State.Disconnecting)

        // The transport's scope runs on Dispatchers.Default, outside runTest's virtual clock.
        val dropped = withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { transport.state.first { it != TransportState.Connected } }
        }
        assertTrue(dropped is TransportState.Error && dropped.recoverable, "got $dropped")

        peripheral.emitState(State.Disconnected())
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals(dropped, transport.state.value, "the error must outlast Kable's Disconnected")
        transport.shutdown()
    }

    @OptIn(ExperimentalCoroutinesApi::class) // currentTime
    @Test
    fun connectThatNeverCompletesFailsAsARecoverableTransportError() = runTest {
        val peripheral = FakeBlePeripheral(connectBehavior = { awaitCancellation() })
        val transport = BleTransport(peripheral, address = ADDRESS)

        // Without a bound connect() never returns; this virtual-time cap fails the test instead.
        val failure = withTimeoutOrNull(10.minutes) {
            assertFailsWith<MeshtasticException.Transport> { transport.connect() }
        }
        assertNotNull(failure, "connect() never returned")

        assertEquals(BleTransport.GATT_RETRY_ATTEMPTS, peripheral.connectCalls)
        assertTrue(currentTime >= BleTransport.GATT_RETRY_ATTEMPTS * BleTransport.DIRECT_CONNECT_TIMEOUT_MS)
        val state = transport.state.value
        assertTrue(state is TransportState.Error && state.recoverable, "got $state")
        transport.shutdown()
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
