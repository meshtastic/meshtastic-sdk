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
}
