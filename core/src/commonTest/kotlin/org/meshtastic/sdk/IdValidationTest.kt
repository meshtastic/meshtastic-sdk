/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdValidationTest {

    @Test
    fun channelIndex_acceptsValidRange() {
        for (raw in 0..ChannelIndex.MAX_CHANNEL_INDEX) {
            val ch = ChannelIndex(raw)
            assertEquals(raw, ch.raw)
        }
    }

    @Test
    fun channelIndex_rejectsNegative() {
        assertFailsWith<IllegalArgumentException> { ChannelIndex(-1) }
    }

    @Test
    fun channelIndex_rejectsAboveMax() {
        assertFailsWith<IllegalArgumentException> { ChannelIndex(ChannelIndex.MAX_CHANNEL_INDEX + 1) }
        assertFailsWith<IllegalArgumentException> { ChannelIndex(255) }
    }

    @Test
    fun nodeId_acceptsFullUInt32Range() {
        assertEquals(0, NodeId(0).raw)
        assertEquals(1, NodeId(1).raw)
        assertEquals(0xFFFFFFFF.toInt(), NodeId(0xFFFFFFFF.toInt()).raw)
        assertEquals(0xFFFFFFFF.toInt(), NodeId.BROADCAST.raw)
    }

    @Test
    fun messageId_acceptsFullUInt32Range() {
        assertEquals(0, MessageId(0).raw)
        assertEquals(0xFFFFFFFF.toInt(), MessageId(0xFFFFFFFF.toInt()).raw)
    }
}
