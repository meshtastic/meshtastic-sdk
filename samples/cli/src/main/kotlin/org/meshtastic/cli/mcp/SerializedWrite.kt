/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

private val writeTail = AtomicReference(CompletableDeferred<Unit>().apply { complete(Unit) })

/** Runs [block] after every earlier call has finished: a whole-section commit built on a stale read would revert a concurrent edit. */
internal suspend fun <T> serializedWrite(block: suspend () -> T): T {
    val mine = CompletableDeferred<Unit>()
    val previous = writeTail.getAndSet(mine)
    try {
        previous.await()
        return block()
    } finally {
        mine.complete(Unit)
    }
}
