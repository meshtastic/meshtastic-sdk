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

    @Test fun emptyPayloadWorks() {
        assertEquals(16, SfppHash.compute(byteArrayOf(), 1, 2, 3).size)
    }
}
