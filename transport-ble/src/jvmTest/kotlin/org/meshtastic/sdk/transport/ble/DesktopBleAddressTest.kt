/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.meshtastic.sdk.MeshtasticException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Identifier strings follow kable-btleplug-ffi 0.46.0 peripheral_id.rs: Linux renders btleplug's
// PeripheralId with serde_json (bluez-async DeviceId { object_path }), Windows with BDAddr's
// upper-case colon Display, macOS with uuid's hyphenated Display.
class DesktopBleAddressTest {

    @Test
    fun linuxAcceptsOnlyTheSerializedBluezPath() {
        assertTrue(isKableIdentifier(LINUX_ID, DesktopBleOs.Linux))
        assertFalse(isKableIdentifier(MAC, DesktopBleOs.Linux), "a bare MAC panics serde_json on Linux")
        assertFalse(isKableIdentifier("/org/bluez/hci0/dev_C2_5D_2D_C4_E5_6E", DesktopBleOs.Linux))
        assertFalse(isKableIdentifier("""{"object_path":"/org/bluez/hci0/dev-C2"}""", DesktopBleOs.Linux))
        assertFalse(isKableIdentifier("""{"object_path":"/org/bluez/hci0/"}""", DesktopBleOs.Linux))
    }

    @Test
    fun windowsAcceptsAMac() {
        assertTrue(isKableIdentifier(MAC, DesktopBleOs.Windows))
        assertTrue(isKableIdentifier("c25d2dc4e56e", DesktopBleOs.Windows))
        assertFalse(isKableIdentifier(LINUX_ID, DesktopBleOs.Windows))
        assertFalse(isKableIdentifier("C2:5D:2D:C4:E5", DesktopBleOs.Windows))
    }

    @Test
    fun macOsAcceptsOnlyAUuid() {
        assertTrue(isKableIdentifier(MAC_UUID, DesktopBleOs.MacOs))
        assertFalse(isKableIdentifier(MAC, DesktopBleOs.MacOs))
        assertFalse(isKableIdentifier(LINUX_ID, DesktopBleOs.MacOs))
    }

    @Test
    fun osNameSelectsTheBackend() {
        assertEquals(DesktopBleOs.Linux, desktopBleOs("Linux"))
        assertEquals(DesktopBleOs.MacOs, desktopBleOs("Mac OS X"))
        assertEquals(DesktopBleOs.Windows, desktopBleOs("Windows 11"))
        assertEquals(DesktopBleOs.Other, desktopBleOs("FreeBSD"))
        assertFalse(isKableIdentifier(MAC, DesktopBleOs.Other))
    }

    @Test
    fun aMacMatchesTheBluezIdentifierOfThatDeviceOnly() {
        assertTrue(desktopBleAddressMatches(MAC, LINUX_ID))
        assertTrue(desktopBleAddressMatches(MAC.lowercase(), LINUX_ID))
        assertFalse(desktopBleAddressMatches(MAC, LINUX_ID.replace("E5_6E", "E5_6F")))
        assertFalse(desktopBleAddressMatches("2D:C4:E5:6E", LINUX_ID), "a partial MAC must not match")
    }

    @Test
    fun findAdvertisedRadioPicksTheMatchingAdvertisement() = runTest {
        val other = radio(LINUX_ID.replace("E5_6E", "00_01"))
        val wanted = radio(LINUX_ID)
        assertEquals(wanted, findAdvertisedRadio(MAC, flowOf(other, wanted)))
    }

    @Test
    fun findAdvertisedRadioFailsWithTransportWhenNothingMatchesInTime() = runTest {
        val scan = flow {
            emit(radio(LINUX_ID.replace("E5_6E", "00_01")))
            awaitCancellation()
        }
        val error = assertFailsWith<MeshtasticException.Transport> {
            findAdvertisedRadio(MAC, scan, timeout = 15.seconds, os = DesktopBleOs.Linux)
        }
        assertTrue(error.message.orEmpty().contains(MAC), error.message)
    }

    @Test
    fun aMacFactoryDoesNotReachKableUntilConnect() {
        // Windows takes the MAC as an identifier, which builds a native peripheral; skip it there.
        if (desktopBleOs() == DesktopBleOs.Windows) return
        // Before the fix this threw from Kable's native layer while parsing the MAC as JSON.
        val transport = BleTransport(address = MAC)
        assertEquals("ble:$MAC", transport.identity.raw)
        transport.shutdown()
    }

    private fun radio(address: String) =
        DiscoveredRadio(name = null, rssi = -60, address = address, advertisement = null)

    private companion object {
        const val MAC = "C2:5D:2D:C4:E5:6E"
        const val LINUX_ID = """{"object_path":"/org/bluez/hci0/dev_C2_5D_2D_C4_E5_6E"}"""
        const val MAC_UUID = "4f0b7b2e-6a1d-4c2b-9e57-1c8f2b3a9d10"
    }
}
