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
 * Run [envelope] holding the lock [resolve] names for the target.
 *
 * The target is resolved again once the lock is held: a node that became the connected one while
 * the caller queued, by a live renumber, moves it to the connected node's lock. Inside another
 * envelope, the lock is taken only if free, since waiting while holding one deadlocks against a
 * caller nesting the other way; a contended or same-node nested call throws [IllegalStateException].
 */
internal suspend fun <R> withEditLock(resolve: () -> Mutex, envelope: suspend () -> R): R {
    val held = currentCoroutineContext()[HeldEditLocks]?.locks.orEmpty()
    while (true) {
        val lock = resolve()
        check(lock !in held) { "editSettings and batch cannot be nested for the same node" }
        if (held.isEmpty()) {
            lock.lock()
        } else {
            check(lock.tryLock()) { "A nested editSettings or batch cannot wait for another caller's edit" }
        }
        try {
            if (resolve() === lock) return withContext(HeldEditLocks(held + lock)) { envelope() }
        } finally {
            lock.unlock()
        }
    }
}

private class HeldEditLocks(val locks: Set<Mutex>) : AbstractCoroutineContextElement(HeldEditLocks) {
    companion object Key : CoroutineContext.Key<HeldEditLocks>
}
