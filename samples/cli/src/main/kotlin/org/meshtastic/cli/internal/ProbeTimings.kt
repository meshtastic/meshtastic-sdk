/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.TransportState

/**
 * Captures the wall-clock offset of the first occurrence of each distinct state on a transport's
 * `state` flow and a client's `connection` flow. Used by all three transport probes to attribute
 * the per-run latency to specific phases (connect, bonding, configuring, ...).
 *
 * Construct just before starting a connect attempt. Call [stop] before disconnecting and [format]
 * to get a single-line summary suitable for printing alongside the run result.
 */
internal class ProbeTimings(transportState: StateFlow<TransportState>, connectionState: StateFlow<ConnectionState>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timings = linkedMapOf<String, Long>()
    private val start = System.currentTimeMillis()

    private val transportJob: Job = scope.launch {
        transportState.collect { record("transport.${it::class.simpleName ?: "?"}") }
    }
    private val connectionJob: Job = scope.launch {
        connectionState.collect { record("conn.${it::class.simpleName ?: "?"}") }
    }

    private fun record(key: String) {
        synchronized(timings) {
            if (key !in timings) timings[key] = System.currentTimeMillis() - start
        }
    }

    fun stop() {
        transportJob.cancel()
        connectionJob.cancel()
        scope.cancel()
    }

    /** Snapshot of state-name → ms since construction, sorted by occurrence. For live display. */
    fun snapshot(): List<Pair<String, Long>> = synchronized(timings) {
        timings.entries.sortedBy { it.value }.map { it.key to it.value }
    }

    fun format(): String = synchronized(timings) {
        timings.entries.sortedBy { it.value }.joinToString(" ") { "${it.key}=${it.value}ms" }
    }
}
