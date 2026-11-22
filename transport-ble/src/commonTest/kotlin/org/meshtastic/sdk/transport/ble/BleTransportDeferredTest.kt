/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.TransportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BleTransportDeferredTest {

    @Test
    fun connectResolvesThePeripheralOnceAndShutdownClosesIt() = runTest {
        val peripheral = FakeBlePeripheral()
        var resolutions = 0
        val transport = createDeferredBleTransport(ADDRESS, builderAction = {}) {
            resolutions++
            peripheral
        }

        transport.connect()
        transport.disconnect()
        transport.connect()

        assertEquals(1, resolutions)
        assertEquals(2, peripheral.connectCalls)
        transport.shutdown()
        assertEquals(1, peripheral.closeCalls)
    }

    @Test
    fun aFailedResolutionFailsConnectWithTransport() = runTest {
        val transport = createDeferredBleTransport(ADDRESS, builderAction = {}) {
            throw MeshtasticException.Transport("no radio")
        }

        assertFailsWith<MeshtasticException.Transport> { transport.connect() }

        assertTrue(transport.state.value is TransportState.Error)
        transport.shutdown()
    }

    @Test
    fun aPeripheralResolvedAfterShutdownIsClosed() = runTest {
        val peripheral = FakeBlePeripheral()
        val release = CompletableDeferred<Unit>()
        val transport = createDeferredBleTransport(ADDRESS, builderAction = {}) {
            release.await()
            peripheral
        }

        val connecting = async { runCatching { transport.connect() } }
        yield()
        transport.shutdown()
        release.complete(Unit)
        connecting.await()

        assertEquals(1, peripheral.closeCalls)
        assertEquals(0, peripheral.connectCalls)
    }

    @Test
    fun disconnectDuringResolutionOpensNoLinkAndALaterConnectWorks() = runTest {
        val peripheral = FakeBlePeripheral()
        val release = CompletableDeferred<Unit>()
        val transport = createDeferredBleTransport(ADDRESS, builderAction = {}) {
            release.await()
            peripheral
        }

        val connecting = async { runCatching { transport.connect() } }
        yield()
        transport.disconnect()
        release.complete(Unit)
        connecting.await()

        assertEquals(0, peripheral.connectCalls)
        assertEquals(TransportState.Disconnected, transport.state.value)

        transport.connect()

        assertEquals(1, peripheral.connectCalls)
        assertEquals(TransportState.Connected, transport.state.value)
        transport.shutdown()
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
