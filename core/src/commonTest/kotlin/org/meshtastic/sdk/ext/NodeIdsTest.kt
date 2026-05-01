/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.sdk.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeIdsTest {
    @Test fun toHexIsZeroPaddedLowercase() {
        assertEquals("00000001", NodeId(1).toHex())
        assertEquals("a1b2c3d4", NodeId(0xa1b2c3d4.toInt()).toHex())
    }

    @Test fun fromHexAcceptsAllCommonForms() {
        assertEquals(NodeId(0xa1b2c3d4.toInt()), NodeId.fromHex("!a1b2c3d4"))
        assertEquals(NodeId(0xa1b2c3d4.toInt()), NodeId.fromHex("a1b2c3d4"))
        assertEquals(NodeId(0xa1b2c3d4.toInt()), NodeId.fromHex("0xA1B2C3D4"))
    }

    @Test fun fromHexRejectsBadInput() {
        assertNull(NodeId.fromHex("xyz"))
        assertNull(NodeId.fromHex("!a1b2c3"))
        assertNull(NodeId.fromHex(""))
    }

    @Test fun predicates() {
        assertTrue(NodeId.BROADCAST.isBroadcast)
        assertTrue(NodeId.LOCAL.isLocal())
        assertTrue(NodeId(42).isLocal(own = NodeId(42)))
        assertFalse(NodeId(42).isLocal(own = NodeId(7)))
        assertTrue(NodeId(42).isUnicast)
        assertFalse(NodeId.BROADCAST.isUnicast)
        assertFalse(NodeId.LOCAL.isUnicast)
    }
}
