/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.WireFraming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SerialFrameReaderTest {
    @Test
    fun fullHandoffWaitsAndKeepsEveryCompletedFrameInOrder() = runTest {
        val channel = Channel<Frame>(capacity = 1)
        val reader = SerialFrameReader(channel, onTextLine = null)
        val expected = (1..3).map { byteArrayOf(WireFraming.MAGIC_0, WireFraming.MAGIC_1, 0, 1, it.toByte()) }
        val bytes = expected[0] + expected[1] + expected[2]

        val publishing = async(start = CoroutineStart.UNDISPATCHED) { reader.publish(bytes, bytes.size) }

        assertFalse(publishing.isCompleted)
        assertEquals(expected.map { Frame(it.toByteString()) }, List(expected.size) { channel.receive() })
        publishing.await()
    }
}
