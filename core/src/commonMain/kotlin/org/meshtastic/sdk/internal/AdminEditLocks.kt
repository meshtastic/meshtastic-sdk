/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One FIFO lock per admin target, shared by every [AdminApiImpl] of a client, so two
 * `begin_edit_settings` … `commit_edit_settings` envelopes never interleave on one node.
 *
 * Firmware keeps a single open edit transaction per node (`hasOpenEditTransaction` in
 * `AdminModule.cpp`): a second begin is absorbed, and the first commit saves both callers' writes.
 * The connected node has one lock whatever its number, so a renumber does not split it.
 *
 * These locks are held by callers' coroutines, never by the engine actor (ADR-002).
 */
internal class AdminEditLocks {
    private val local = Mutex()

    // Copy-on-write so concurrent first use of a remote target agrees on one lock.
    private val remote = atomic(emptyMap<Int, Mutex>())

    fun local(): Mutex = local

    fun remote(node: Int): Mutex {
        while (true) {
            val current = remote.value
            current[node]?.let { return it }
            val created = Mutex()
            if (remote.compareAndSet(current, current + (node to created))) return created
        }
    }
}

/**
 * Run [envelope] holding this lock. Calling this again for the same lock from inside [envelope]
 * throws instead of waiting on itself forever.
 */
internal suspend fun <R> Mutex.withEditLock(envelope: suspend () -> R): R {
    val held = currentCoroutineContext()[HeldEditLocks]?.locks.orEmpty()
    check(this !in held) { "editSettings and batch cannot be nested for the same node" }
    return withLock { withContext(HeldEditLocks(held + this)) { envelope() } }
}

private class HeldEditLocks(val locks: Set<Mutex>) : AbstractCoroutineContextElement(HeldEditLocks) {
    companion object Key : CoroutineContext.Key<HeldEditLocks>
}
