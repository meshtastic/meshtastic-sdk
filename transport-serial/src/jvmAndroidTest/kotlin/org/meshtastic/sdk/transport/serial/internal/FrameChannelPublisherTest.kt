/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import kotlinx.coroutines.channels.Channel
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the "frames don't vanish silently under backpressure" contract for
 * the serial transport. Drops are counted; the [FrameChannelPublisher]'s
 * `onDrop` callback fires for every overflow and stops firing once the channel
 * is closed.
 */
class FrameChannelPublisherTest {

    private fun frame(b: Byte): Frame = Frame(byteArrayOf(b).toByteString())

    @Test
    fun successfulPublishDoesNotIncrementDropCount() {
        val channel = Channel<Frame>(capacity = 4)
        val publisher = FrameChannelPublisher(channel)

        publisher.publish(frame(1))
        publisher.publish(frame(2))

        assertEquals(0, publisher.dropCount.get())
    }

    @Test
    fun overflowIncrementsDropCountAndInvokesCallback() {
        val channel = Channel<Frame>(capacity = 1)
        val drops = mutableListOf<Long>()
        val publisher = FrameChannelPublisher(channel, onDrop = { drops += it })

        publisher.publish(frame(1)) // fills channel
        publisher.publish(frame(2)) // overflow #1
        publisher.publish(frame(3)) // overflow #2
        publisher.publish(frame(4)) // overflow #3

        assertEquals(3L, publisher.dropCount.get())
        assertEquals(listOf(1L, 2L, 3L), drops)
    }

    @Test
    fun closedChannelDoesNotCountAsDrop() {
        val channel = Channel<Frame>(capacity = 1)
        channel.close()
        val drops = mutableListOf<Long>()
        val publisher = FrameChannelPublisher(channel, onDrop = { drops += it })

        publisher.publish(frame(1))
        publisher.publish(frame(2))

        assertEquals(0L, publisher.dropCount.get())
        assertTrue(drops.isEmpty())
    }
}
