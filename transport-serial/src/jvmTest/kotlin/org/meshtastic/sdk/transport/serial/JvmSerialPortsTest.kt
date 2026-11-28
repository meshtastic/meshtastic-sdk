/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial

import org.meshtastic.sdk.MeshtasticException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSerialPortsTest {

    // Regression: a port that is gone threw jSerialComm's SerialPortInvalidPortException, a plain
    // RuntimeException, where open documents MeshtasticException.Transport as Android's does.
    @Test
    fun openOnAPortThatDoesNotExistFailsAsATransportError() {
        // jSerialComm does not check the name on Windows; there the failure comes at connect.
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        assertFailsWith<MeshtasticException.Transport> {
            JvmSerialPorts.open("/dev/meshtastic-sdk-test-no-such-port")
        }
    }

    // Regression: the macOS filter tested a device-path prefix that jSerialComm's system port names
    // never carry, so every tty.* port stayed in the picker next to its cu.* twin.
    @Test
    fun `on macOS the dial-in tty ports are dropped and the call-out cu ports kept`() {
        val names = listOf(
            "cu.debug-console",
            "tty.debug-console",
            "cu.Bluetooth-Incoming-Port",
            "tty.Bluetooth-Incoming-Port",
        )
        assertEquals(
            listOf("cu.debug-console", "cu.Bluetooth-Incoming-Port"),
            withoutDialInDuplicates(names, isMacOs = true),
        )
    }

    // Regression: applying the macOS filter on Linux or Windows would drop the real ports, such as
    // ttyACM0 and ttyUSB0, and any name that merely contains "tty".
    @Test
    fun `other hosts keep every port including ones named tty`() {
        val names = listOf("ttyACM0", "ttyUSB0", "COM3", "tty.something")
        assertEquals(names, withoutDialInDuplicates(names, isMacOs = false))
    }
}
