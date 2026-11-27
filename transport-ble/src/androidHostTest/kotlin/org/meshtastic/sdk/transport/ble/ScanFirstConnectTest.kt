/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.TransportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Expected values are Meshtastic-Android's BleDeviceLocator: SCAN_TIMEOUT = 5 s, one scan for a
// bonded radio before its bonded handle, SCAN_RETRY_COUNT = 3 scans SCAN_RETRY_DELAY = 1 s apart
// for an unbonded one, then BleDeviceNotFoundException.
@OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
class ScanFirstConnectTest {

    @Test
    fun bondedRadioNotHeardGetsOneFiveSecondScanThenTheBondedHandle() = runTest {
        var scans = 0

        val located = locateRadio<String>(ADDRESS, bonded = true) {
            scans++
            awaitCancellation()
        }

        assertSame(LocatedRadio.BondedHandle, located)
        assertEquals(1, scans)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun bondedRadioWhoseScanFailsStillFallsBackToTheBondedHandle() = runTest {
        val located = locateRadio<String>(ADDRESS, bonded = true) { throw IllegalStateException("scan refused") }

        assertSame(LocatedRadio.BondedHandle, located)
    }

    @Test
    fun unbondedRadioNotHeardGetsThreeScansASecondApartThenFails() = runTest {
        var scans = 0
        val refused = IllegalStateException("scan refused")

        val error = assertFailsWith<MeshtasticException.Transport> {
            locateRadio<String>(ADDRESS, bonded = false) {
                scans++
                if (scans == 3) throw refused
                awaitCancellation()
            }
        }

        assertEquals(3, scans)
        // Two full windows, two pauses, and a third window that failed at once.
        assertEquals(5_000L + 1_000L + 5_000L + 1_000L, testScheduler.currentTime)
        // Coroutine stack-trace recovery may hand back a copy, so compare by type and message.
        assertEquals(refused.message, assertIs<IllegalStateException>(error.cause).message)
    }

    @Test
    fun unbondedRadioHeardInTheSecondScanUsesThatAdvertisement() = runTest {
        var scans = 0

        val located = locateRadio(ADDRESS, bonded = false) {
            scans++
            if (scans == 1) awaitCancellation()
            "advertisement-2"
        }

        assertEquals("advertisement-2", assertIs<LocatedRadio.Advertised<String>>(located).advertisement)
        assertEquals(2, scans)
        assertEquals(6_000L, testScheduler.currentTime)
    }

    @Test
    fun everyConnectScansAgainAndConnectsFromTheFreshAdvertisement() = runTest {
        var scans = 0
        val located = mutableListOf<LocatedRadio<String>>()
        val peripherals = mutableListOf<FakeBlePeripheral>()
        val transport = createScanFirstBleTransport(
            address = ADDRESS,
            resilience = BleResilience.None,
            builderAction = {},
            isBonded = { true },
            scanOnce = { "advertisement-${++scans}" },
        ) { radio, _ ->
            located += radio
            FakeBlePeripheral().also { peripherals += it }
        }

        transport.connect()
        transport.disconnect()
        transport.connect()

        assertEquals(TransportState.Connected, transport.state.value)
        assertEquals(
            listOf("advertisement-1", "advertisement-2"),
            located.map { assertIs<LocatedRadio.Advertised<String>>(it).advertisement },
        )
        assertEquals(listOf(1, 1), peripherals.map { it.connectCalls })
        assertEquals(1, peripherals[0].closeCalls)
        transport.shutdown()
        assertEquals(1, peripherals[1].closeCalls)
    }

    @Test
    fun requestedRefreshRefreshesAScanFirstLinkAndWaitsThreeSecondsBeforeReconnecting() = runTest {
        var scans = 0
        var refreshedAt = -1L
        val connectedAt = mutableListOf<Long>()
        val peripheral = FakeBlePeripheral(
            connectBehavior = { connectedAt += testScheduler.currentTime },
            servicesAfterConnect = { meshtasticServices() },
        )
        val transport = createScanFirstBleTransport(
            address = ADDRESS,
            resilience = BleResilience.None,
            builderAction = {},
            isBonded = { true },
            scanOnce = { "advertisement-${++scans}" },
        ) { _, _ -> peripheral }
        transport.gattCacheRefresher = GattCacheRefresher {
            refreshedAt = testScheduler.currentTime
            true
        }
        transport.refreshGattCacheOnNextConnect()

        transport.connect()

        assertEquals(listOf("connect#1", "disconnect", "connect#2"), peripheral.events)
        assertEquals(1, scans)
        assertEquals(2, connectedAt.size)
        // Meshtastic-Android settles 3 s before the rediscovery connect (BleReconnectPolicy.DEFAULT_SETTLE_DELAY).
        val settled = connectedAt[1] - refreshedAt
        assertTrue(refreshedAt >= 0 && settled >= 3_000L, "rediscovery connect started $settled ms after the refresh")
        transport.shutdown()
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
