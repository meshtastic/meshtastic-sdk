/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransportTargetTest {

    @Test
    fun tcpDefaultsThePort() {
        assertEquals(TransportTarget.Tcp("meshtastic.local", 4403), TransportTarget.parse("tcp:meshtastic.local"))
        assertEquals(TransportTarget.Tcp("10.0.0.5", 4404), TransportTarget.parse("tcp:10.0.0.5:4404"))
    }

    @Test
    fun serialDefaultsTheBaudAndKeepsPaths() {
        assertEquals(TransportTarget.Serial("cu.usbmodem101", 115_200), TransportTarget.parse("serial:cu.usbmodem101"))
        assertEquals(
            TransportTarget.Serial("/dev/ttyACM0", 921_600),
            TransportTarget.parse("serial:/dev/ttyACM0:921600"),
        )
    }

    @Test
    fun bleKeepsTheWholeNeedleIncludingColons() {
        assertEquals(TransportTarget.Ble("34:B7:DA:62:18:C5"), TransportTarget.parse("ble:34:B7:DA:62:18:C5"))
        assertEquals(TransportTarget.Ble(""), TransportTarget.parse("ble:"))
    }

    @Test
    fun schemeIsCaseInsensitive() {
        assertEquals(TransportTarget.Tcp("h", 4403), TransportTarget.parse("TCP:h"))
    }

    @Test
    fun malformedSpecsReturnNull() {
        assertNull(TransportTarget.parse("no-scheme"))
        assertNull(TransportTarget.parse("usb:thing"))
        assertNull(TransportTarget.parse("tcp:"))
        assertNull(TransportTarget.parse("tcp:host:notaport"))
        assertNull(TransportTarget.parse("serial:"))
        assertNull(TransportTarget.parse("serial:/dev/ttyACM0:fast"))
    }
}
