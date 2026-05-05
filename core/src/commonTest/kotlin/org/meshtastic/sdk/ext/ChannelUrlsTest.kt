/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelUrlsTest {
    @Test fun roundTripChannelSet() {
        val set = ChannelSet(
            settings = listOf(
                ChannelSettings(name = "LongFast", psk = byteArrayOf(0x01).toByteString()),
            ),
        )
        val url = set.toUrl()
        assertTrue(url.startsWith(ChannelUrl.PREFIX))
        val decoded = ChannelUrl.parse(url)
        assertNotNull(decoded)
        assertEquals(1, decoded.settings.size)
        assertEquals("LongFast", decoded.settings[0].name)
    }

    @Test fun parseToleratesMissingPrefix() {
        val url = ChannelSet().toUrl()
        val payload = url.removePrefix(ChannelUrl.PREFIX)
        assertNotNull(ChannelUrl.parse("#$payload"))
    }

    @Test fun parseRejectsGarbage() {
        assertNull(ChannelUrl.parse("https://example.com/foo"))
        assertNull(ChannelUrl.parse("https://meshtastic.org/e/#@@@"))
    }

    @Test fun defaultChannelIsPrimaryWithDefaultPsk() {
        val ch = Channel.default()
        assertEquals(0, ch.index)
        assertEquals(Channel.Role.PRIMARY, ch.role)
        assertEquals(DefaultPsk.toByteString(), ch.settings!!.psk)
    }

    @Test fun channelHashXorsBytes() {
        // empty → 0
        assertEquals(0, ChannelSettings.hash("", byteArrayOf()))
        val expected = "abc".encodeToByteArray().fold(0) { a, b -> a xor (b.toInt() and 0xff) } xor
            byteArrayOf(0x01, 0x02).fold(0) { a, b -> a xor (b.toInt() and 0xff) }
        assertEquals(expected and 0xff, ChannelSettings.hash("abc", byteArrayOf(0x01, 0x02)))
    }

    @Test fun channelNameHashUsesDjb2() {
        assertEquals(130429955u, channelNameHashDjb2("LongFast"))
    }
}
