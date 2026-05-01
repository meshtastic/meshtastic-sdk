/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import kotlinx.coroutines.channels.Channel
import org.meshtastic.sdk.Frame
import java.util.concurrent.atomic.AtomicLong

/**
 * Non-blocking publisher that pushes a [Frame] into [target] via [Channel.trySend]
 * and tracks overflow drops. Lifted out of `JSerialCommTransport.runReader` so the
 * "frames must not vanish silently under backpressure" contract can be unit-tested.
 *
 * On overflow, [dropCount] is incremented and [onDrop] is invoked with the new
 * total. Drops on a *closed* target channel are ignored (the transport is being
 * torn down — counting them would be misleading).
 */
internal class FrameChannelPublisher(
    private val target: Channel<Frame>,
    val dropCount: AtomicLong = AtomicLong(0),
    private val onDrop: (Long) -> Unit = {},
) {
    fun publish(frame: Frame) {
        val result = target.trySend(frame)
        if (result.isFailure && !result.isClosed) {
            onDrop(dropCount.incrementAndGet())
        }
    }
}
