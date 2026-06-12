/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.io.bytestring.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals

class ByteStringsTest {

    @Test
    fun okioToKotlinxRoundTripsBytes() {
        val okio = byteArrayOf(0x01, 0x7F, -0x80, 0x00).toByteString()
        val kotlinx = okio.toKotlinxByteString()
        assertEquals(ByteString(0x01, 0x7F, -0x80, 0x00), kotlinx)
        assertEquals(okio, kotlinx.toOkioByteString())
    }

    @Test
    fun emptyByteStringsConvertCleanly() {
        assertEquals(ByteString(), okio.ByteString.EMPTY.toKotlinxByteString())
        assertEquals(okio.ByteString.EMPTY, ByteString().toOkioByteString())
    }
}
