/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.Frame
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the ADR-012 back-pressure contract for the serial reader: a full channel blocks the
 * publishing thread instead of dropping frames, and cancelling the channel releases it.
 */
class FrameChannelPublisherTest {

    private fun frame(i: Int): Frame = Frame(byteArrayOf(i.toByte()).toByteString())

    @Test
    fun fullChannelBlocksInsteadOfDropping() = runBlocking {
        val channel = Channel<Frame>(capacity = 2)
        val publisher = FrameChannelPublisher(channel)
        val total = 50
        val producer = thread(name = "publisher-test") {
            repeat(total) { publisher.publish(frame(it)) }
        }

        val received = withTimeout(TIMEOUT_MS) {
            List(total) {
                delay(1) // slow consumer: the producer must wait, not drop
                channel.receive()
            }
        }
        producer.join(TIMEOUT_MS)

        assertEquals(List(total) { frame(it) }, received)
    }

    @Test
    fun closedChannelReturnsFalseWithoutBlocking() {
        val channel = Channel<Frame>(capacity = 1)
        channel.close()
        val publisher = FrameChannelPublisher(channel)

        assertFalse(publisher.publish(frame(1)))
        assertFalse(publisher.publish(frame(2)))
    }

    @Test
    fun cancellingChannelReleasesBlockedPublisher() {
        val channel = Channel<Frame>(capacity = 1)
        val publisher = FrameChannelPublisher(channel)
        assertTrue(publisher.publish(frame(1)))

        var second: Boolean? = null
        val producer = thread(name = "publisher-test") { second = publisher.publish(frame(2)) }
        Thread.sleep(PARK_MS)
        assertTrue(producer.isAlive, "publisher should block on a full channel")

        channel.cancel()
        producer.join(TIMEOUT_MS)

        assertFalse(producer.isAlive)
        assertEquals(false, second)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val PARK_MS = 100L
    }
}
