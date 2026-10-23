/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.trySendBlocking
import org.meshtastic.sdk.Frame

/**
 * Pushes frames from the serial reader thread into [target], honouring back-pressure as
 * ADR-012 requires: when [target] is full, [publish] blocks the calling thread until the
 * consumer makes room, so no frame is ever dropped.
 *
 * Blocking is safe here because the only caller is `JSerialCommTransport`'s reader, which runs
 * on its own [kotlinx.coroutines.Dispatchers.IO] thread, never on the engine dispatcher. A
 * publisher parked on a full channel is released when the channel is cancelled (the transport
 * does that on teardown if the reader does not stop on its own).
 */
internal class FrameChannelPublisher(private val target: SendChannel<Frame>) {
    /**
     * Deliver [frame], blocking while [target] is full. Returns `false` when [target] is closed
     * or cancelled, i.e. the transport is being torn down or the collector went away.
     */
    fun publish(frame: Frame): Boolean = target.trySendBlocking(frame).isSuccess
}
