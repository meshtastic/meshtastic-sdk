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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.meshtastic.proto.Channel

class ChannelHelpersTest {
    @Test
    fun validChannelValidates() {
        val result = ChannelHelpers.validate(
            name = "LongFast",
            psk = ByteArray(ChannelHelpers.MIN_PSK_LENGTH) { 0x42 },
        )

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun nameTooLongFailsValidation() {
        val result = ChannelHelpers.validate(
            name = "123456789012",
            psk = byteArrayOf(0x01),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("exceeds") })
    }

    @Test
    fun invalidPskLengthsFailValidation() {
        val result = ChannelHelpers.validate(
            name = "mesh",
            psk = ByteArray(8),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("PSK") })
    }

    @Test
    fun findEmptySlotUsesFirstDisabledOrMissingSecondarySlot() {
        val channels = listOf(
            Channel(index = 0, role = Channel.Role.PRIMARY),
            Channel(index = 1, role = Channel.Role.SECONDARY),
            Channel(index = 2, role = Channel.Role.DISABLED),
        )

        assertEquals(2, ChannelHelpers.findEmptySlot(channels))
        assertEquals(2, ChannelHelpers.findEmptySlot(channels.take(2)))
    }

    @Test
    fun findEmptySlotReturnsNullWhenFull() {
        val channels = listOf(
            Channel(index = 0, role = Channel.Role.PRIMARY),
            Channel(index = 1, role = Channel.Role.SECONDARY),
            Channel(index = 2, role = Channel.Role.SECONDARY),
            Channel(index = 3, role = Channel.Role.SECONDARY),
            Channel(index = 4, role = Channel.Role.SECONDARY),
            Channel(index = 5, role = Channel.Role.SECONDARY),
            Channel(index = 6, role = Channel.Role.SECONDARY),
            Channel(index = 7, role = Channel.Role.SECONDARY),
        )

        assertNull(ChannelHelpers.findEmptySlot(channels))
    }
}
