/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial.internal

import kotlinx.coroutines.channels.SendChannel
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.transport.serial.SerialFrameAssembler

/** Hands off decoded frames in order, staging at most the frames completed by one port read. */
internal class SerialFrameReader(private val frames: SendChannel<Frame>, onTextLine: ((String) -> Unit)?) {
    private val decoded = mutableListOf<Frame>()
    private val assembler = SerialFrameAssembler { frame -> decoded.add(frame) }.also { it.onTextLine = onTextLine }

    suspend fun publish(bytes: ByteArray, length: Int) {
        assembler.feed(bytes, length)
        for (frame in decoded) frames.send(frame)
        decoded.clear()
    }
}
