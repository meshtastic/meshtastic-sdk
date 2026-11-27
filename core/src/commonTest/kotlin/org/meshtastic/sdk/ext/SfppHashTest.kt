/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.ext

import org.meshtastic.sdk.SfppHash
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SfppHashTest {
    @Test fun outputIsAlways16Bytes() {
        assertEquals(16, SfppHash.compute("payload".encodeToByteArray(), 1, 2, 3).size)
    }

    @Test fun hashIsDeterministic() {
        val first = SfppHash.compute("payload".encodeToByteArray(), 1, 2, 3)
        val second = SfppHash.compute("payload".encodeToByteArray(), 1, 2, 3)

        assertContentEquals(first, second)
    }

    @Test fun differentInputsProduceDifferentHashes() {
        val first = SfppHash.compute("payload".encodeToByteArray(), 1, 2, 3)
        val second = SfppHash.compute("payload".encodeToByteArray(), 1, 2, 4)

        assertFalse(first.contentEquals(second))
    }

    // Firmware test_sfpp_frame test_messageHashMatchesTheWireLayout, the hash SF++ replay frames are keyed on.
    @Test fun matchesTheFirmwareFrameHashVector() {
        val expected = intArrayOf(
            0x1f, 0x15, 0xd3, 0x65, 0x6f, 0x2e, 0xd5, 0xcc, 0xc0, 0xa7, 0x5f, 0x0e, 0xe2, 0x33, 0x85, 0x30,
        ).map { it.toByte() }.toByteArray()
        val hash = SfppHash.compute(byteArrayOf(0, 1, 2, 3), 0xAABBCCDD.toInt(), 0x11223344, 0x55667788)

        assertContentEquals(expected, hash)
    }

    @Test fun emptyPayloadWorks() {
        assertEquals(16, SfppHash.compute(byteArrayOf(), 1, 2, 3).size)
    }
}
