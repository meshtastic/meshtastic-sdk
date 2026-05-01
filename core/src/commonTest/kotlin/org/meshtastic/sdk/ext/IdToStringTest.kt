/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.MessageId
import org.meshtastic.sdk.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals

class IdToStringTest {
    @Test fun nodeIdToStringIsBangHex() {
        assertEquals("!a1b2c3d4", NodeId(0xa1b2c3d4.toInt()).toString())
        assertEquals("!00000001", NodeId(1).toString())
        assertEquals("!ffffffff", NodeId.BROADCAST.toString())
    }

    @Test fun channelIndexToStringIsChN() {
        assertEquals("ch0", ChannelIndex(0).toString())
        assertEquals("ch7", ChannelIndex(7).toString())
    }

    @Test fun messageIdToStringIsUnsignedDecimal() {
        assertEquals("0", MessageId(0).toString())
        assertEquals("1", MessageId(1).toString())
        assertEquals("4294967295", MessageId(-1).toString())
    }
}
