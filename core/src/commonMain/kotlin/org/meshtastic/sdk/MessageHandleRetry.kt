/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.MessageHandle

/**
 * Re-enqueue the same packet that produced this [MessageHandle]. The engine assigns
 * a fresh `MessageId` and returns a new handle, leaving the original handle's terminal state
 * unchanged.
 *
 * Throws [MeshtasticException.Protocol] if the handle was constructed without a stashed
 * packet (e.g. by a test double); built-in `RadioClient.send()` always populates one.
 */
public suspend fun MessageHandle.retry(): MessageHandle {
    val pkt = packet
    val resend = resendFn
    if (pkt == null || resend == null) {
        throw MeshtasticException.Protocol(
            "MessageHandle.retry() requires a handle produced by RadioClient.send()",
        )
    }
    return resend(pkt)
}
