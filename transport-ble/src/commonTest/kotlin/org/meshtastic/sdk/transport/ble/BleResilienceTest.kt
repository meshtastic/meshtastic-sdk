/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.TransportState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleResilienceTest {

    private class ScriptedBond(private val script: List<BondProgress>, private val createResult: Boolean = true) :
        BondControl {
        var reads = 0
        var creates = 0

        override fun progress(): BondProgress = script[minOf(reads++, script.lastIndex)]

        override fun create(): Boolean {
            creates++
            return createResult
        }
    }

    private fun transport(peripheral: FakeBlePeripheral) = BleTransport(peripheral, address = ADDRESS)

    @Test
    fun bondFirstPublishesBondingThenConnectsAfterBond() = runTest {
        val events = mutableListOf<String>()
        val peripheral = FakeBlePeripheral(events = events)
        val transport = transport(peripheral)
        val bond = ScriptedBond(
            listOf(BondProgress.None, BondProgress.Bonding, BondProgress.Bonding, BondProgress.Bonded),
        )
        transport.bondControl = bond
        val seen = mutableListOf<TransportState>()
        val collector = launch { transport.state.toList(seen) }

        transport.connect()
        collector.cancel()

        assertEquals(1, bond.creates)
        assertTrue(seen.contains(TransportState.Bonding), "Bonding must be published: $seen")
        assertEquals(TransportState.Connected, transport.state.value)
        assertEquals("connect#1", events.first())
        transport.shutdown()
    }

    @Test
    fun alreadyBondedSkipsCreateBond() = runTest {
        val transport = transport(FakeBlePeripheral())
        val bond = ScriptedBond(listOf(BondProgress.Bonded))
        transport.bondControl = bond

        transport.connect()

        assertEquals(0, bond.creates)
        assertEquals(TransportState.Connected, transport.state.value)
        transport.shutdown()
    }

    @Test
    fun rejectedBondFailsConnectWithoutTouchingGatt() = runTest {
        val peripheral = FakeBlePeripheral()
        val transport = transport(peripheral)
        transport.bondControl = ScriptedBond(listOf(BondProgress.None))

        assertFailsWith<MeshtasticException.Transport> { transport.connect() }

        assertEquals(0, peripheral.connectCalls)
        assertTrue(transport.state.value is TransportState.Error)
        transport.shutdown()
    }

    @Test
    fun bondThatNeverCompletesTimesOut() = runTest {
        val transport = transport(FakeBlePeripheral())
        transport.bondControl = ScriptedBond(listOf(BondProgress.Bonding))

        assertFailsWith<MeshtasticException.Transport> { transport.connect() }

        transport.shutdown()
    }

    @Test
    fun stalledDirectConnectFallsBackToAutoConnectAndNextConnectStartsDirect() = runTest {
        val switch = AutoConnectSwitch()
        val seenOnConnect = mutableListOf<Boolean>()
        val peripheral = FakeBlePeripheral(connectBehavior = { call ->
            seenOnConnect += switch.isOn
            if (call == 1) awaitCancellation()
        })
        val transport = transport(peripheral)
        transport.autoConnectSwitch = switch

        transport.connect()

        assertEquals(listOf(false, true), seenOnConnect)
        assertEquals(TransportState.Connected, transport.state.value)
        assertTrue(switch.isOn)

        transport.disconnect()
        seenOnConnect.clear()
        transport.connect()
        assertEquals(listOf(false), seenOnConnect.take(1))
        assertFalse(switch.isOn)
        transport.shutdown()
    }

    @Test
    fun noSwitchMeansNoFallback() = runTest {
        val seen = mutableListOf<Int>()
        val peripheral = FakeBlePeripheral(connectBehavior = { call -> seen += call })
        val transport = transport(peripheral)

        transport.connect()

        assertEquals(listOf(1), seen)
        transport.shutdown()
    }

    @Test
    fun staleGattIsRefreshedAndRediscoveredOnce() = runTest {
        val events = mutableListOf<String>()
        val peripheral = FakeBlePeripheral(
            servicesAfterConnect = { call -> if (call == 1) emptyList() else meshtasticServices() },
            events = events,
        )
        val transport = transport(peripheral)
        var refreshes = 0
        transport.gattCacheRefresher = GattCacheRefresher {
            refreshes++
            events += "refresh"
            true
        }

        transport.connect()

        assertEquals(1, refreshes)
        assertEquals(listOf("connect#1", "refresh", "disconnect", "connect#2"), events)
        assertEquals(TransportState.Connected, transport.state.value)
        transport.shutdown()
    }

    @Test
    fun completeGattTableIsNotRefreshed() = runTest {
        val peripheral = FakeBlePeripheral(servicesAfterConnect = { meshtasticServices() })
        val transport = transport(peripheral)
        var refreshes = 0
        transport.gattCacheRefresher = GattCacheRefresher { refreshes++ > 0 }

        transport.connect()

        assertEquals(0, refreshes)
        assertEquals(1, peripheral.connectCalls)
        transport.shutdown()
    }

    @Test
    fun requestedRefreshRunsAfterLinkAndBeforePhoneApiAndIsConsumedOnce() = runTest {
        val events = mutableListOf<String>()
        val peripheral = FakeBlePeripheral(servicesAfterConnect = { meshtasticServices() }, events = events)
        val transport = transport(peripheral)
        transport.gattCacheRefresher = GattCacheRefresher {
            events += "refresh"
            true
        }
        transport.postConnectHook = { events += "connected hook" }
        transport.refreshGattCacheOnNextConnect()

        transport.connect()

        assertEquals(listOf("connect#1", "refresh", "disconnect", "connect#2", "connected hook"), events)

        transport.disconnect()
        events.clear()
        transport.connect()

        assertEquals(listOf("connect#3", "connected hook"), events)
        transport.shutdown()
    }

    @Test
    fun aRefreshRequestSurvivesAConnectThatNeverReachedTheRadio() = runTest {
        var failing = true
        val peripheral = FakeBlePeripheral(
            connectBehavior = { if (failing) throw MeshtasticException.Transport("no link") },
            servicesAfterConnect = { meshtasticServices() },
        )
        val transport = transport(peripheral)
        var refreshes = 0
        transport.gattCacheRefresher = GattCacheRefresher {
            refreshes++
            true
        }
        transport.refreshGattCacheOnNextConnect()

        assertFailsWith<MeshtasticException.Transport> { transport.connect() }
        assertEquals(0, refreshes)

        failing = false
        transport.connect()
        assertEquals(1, refreshes)
        transport.shutdown()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun failureStreakRefreshesOncePerStreakAndAStableLinkRearmsIt() = runTest {
        var failing = false
        val peripheral = FakeBlePeripheral(
            connectBehavior = { if (failing) throw MeshtasticException.Transport("no link") },
            servicesAfterConnect = { meshtasticServices() },
        )
        val transport = transport(peripheral)
        transport.linkClock = testScheduler.timeSource
        transport.refreshGattCacheAfterFailures = 2
        var refreshes = 0
        transport.gattCacheRefresher = GattCacheRefresher {
            refreshes++
            true
        }
        suspend fun failConnects(times: Int) {
            failing = true
            repeat(times) { assertFailsWith<MeshtasticException.Transport> { transport.connect() } }
            failing = false
        }

        // The radio drops a link at once, and the engine disconnects only after its backoff.
        suspend fun dropAfterBackoff() {
            peripheral.dropLink()
            transport.state.first { it is TransportState.Error }
            advanceTimeBy(BleTransport.LINK_STABLE_MS + 3_000L)
            transport.disconnect()
        }

        failConnects(1)
        transport.connect()
        assertEquals(0, refreshes, "one failure is below the threshold of two")
        dropAfterBackoff()

        transport.connect()
        assertEquals(1, refreshes, "the dropped link was the second failure, however late the disconnect")
        dropAfterBackoff()
        transport.connect()
        assertEquals(1, refreshes, "once per streak")

        advanceTimeBy(BleTransport.LINK_STABLE_MS + 1_000L)
        transport.disconnect()
        failConnects(2)
        transport.connect()
        assertEquals(2, refreshes, "a link that stayed up re-armed the refresh")
        transport.shutdown()
    }

    @Test
    fun shutdownClosesFactoryOwnedPeripheralOnlyOnce() {
        val factoryPeripheral = FakeBlePeripheral()
        val borrowedPeripheral = FakeBlePeripheral()
        val owned = createBleTransport(ADDRESS, kotlin.coroutines.EmptyCoroutineContext, BleResilience.None, {}) {
            factoryPeripheral
        }

        owned.shutdown()
        owned.shutdown()
        transport(borrowedPeripheral).shutdown()

        assertEquals(1, factoryPeripheral.closeCalls)
        assertEquals(0, borrowedPeripheral.closeCalls)
        borrowedPeripheral.close()
    }

    @Test
    fun refusedRefreshLeavesTheConnectUnchanged() = runTest {
        val peripheral = FakeBlePeripheral(servicesAfterConnect = { emptyList() })
        val transport = transport(peripheral)
        transport.gattCacheRefresher = GattCacheRefresher { false }

        transport.connect()

        assertEquals(1, peripheral.connectCalls)
        assertEquals(TransportState.Connected, transport.state.value)
        transport.shutdown()
    }

    @Test
    fun serviceStillMissingAfterRefreshFailsTheConnect() = runTest {
        val transport = transport(FakeBlePeripheral(servicesAfterConnect = { emptyList() }))
        transport.gattCacheRefresher = GattCacheRefresher { true }

        assertFailsWith<MeshtasticException.Transport> { transport.connect() }

        transport.shutdown()
    }

    @Test
    fun missingCharacteristicCountsAsStale() {
        val partial = fakeServices(BleConstants.MESH_SERVICE_UUID, BleConstants.FROMRADIO_UUID)
        assertTrue(partial.isMissingMeshtasticGatt())
        assertFalse(meshtasticServices().isMissingMeshtasticGatt())
        assertFalse((null as List<com.juul.kable.DiscoveredService>?).isMissingMeshtasticGatt())
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
