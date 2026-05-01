/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble.internal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Implements the BLE drain protocol per `docs/protocol.md §3`.
 *
 * On each wake signal (a `fromnum` notification, or any other trigger), the
 * coordinator pumps `read(fromradio)` in a loop until an empty buffer is
 * returned. If a wake signal arrives mid-drain, it is coalesced into a single
 * pending re-drain so we never run concurrent drains (which would race the GATT
 * read queue) but never miss a wake either.
 *
 * The coordinator is single-collector by design — call [run] exactly once per
 * connection. It exits when the upstream [readPayload] returns `null`,
 * signalling the connection has dropped.
 *
 * Each individual `readPayload()` call is wrapped in [readTimeoutMs] so a stuck
 * GATT read (e.g. firmware hang, link-layer stall before Kable observes the
 * disconnect) can't pin the drain loop indefinitely. On timeout the loop breaks
 * out of the current drain pass and waits for the next wake signal — matching
 * how we treat an empty buffer. Cross-ref: audit code-vs-spec §2.7.
 *
 * @param readTimeoutMs per-read deadline in milliseconds for the steady-state
 *   drain. Default is [DEFAULT_READ_TIMEOUT_MS]; tests can shorten it with
 *   virtual time.
 * @param firstReadTimeoutMs per-read deadline applied to the *first* read of
 *   the *first* drain pass after [run] starts. Defaults to
 *   [DEFAULT_FIRST_READ_TIMEOUT_MS] which is comfortably longer than
 *   [readTimeoutMs] so the post-connect bond/encryption warmup window — where
 *   a single GATT read on Android can take 5–30 s while the OS finishes
 *   pairing — does not silently break the inner loop. Cross-ref: TX-11 in
 *   `audit-report.md`.
 * @param readPayload reads one payload from the `fromradio` characteristic.
 *   - return a non-empty `ByteArray` to deliver one `FromRadio` payload.
 *   - return an empty `ByteArray` to signal the device's outbound queue is drained.
 *   - return `null` to terminate the drain loop (connection closed).
 */
internal class DrainCoordinator(
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    private val firstReadTimeoutMs: Long = DEFAULT_FIRST_READ_TIMEOUT_MS,
    private val readPayload: suspend () -> ByteArray?,
) {
    private val wakeSignals = Channel<Unit>(capacity = Channel.CONFLATED)

    /** Signal that the device may have new data. Idempotent; coalesces. */
    fun wake() {
        // CONFLATED channel: latest signal replaces any pending one.
        wakeSignals.trySend(Unit)
    }

    /**
     * Run the drain loop. Suspends until [readPayload] returns `null` (closed)
     * or the surrounding coroutine is cancelled.
     *
     * Emits each non-empty payload exactly once, in arrival order.
     */
    suspend fun run(emit: suspend (ByteArray) -> Unit) {
        // Trigger an initial drain on entry — the device may have queued data
        // before subscription started, and `fromnum` only fires on *new* writes.
        wake()
        var firstReadPending = true
        for (signal in wakeSignals) {
            // Pump until the device returns empty (queue drained), the
            // connection reports closed, or a single read exceeds [readTimeoutMs].
            while (true) {
                // Wrap the read in a Result-like Any? sentinel so we can tell
                // "read source closed" (return null from the lambda — we must
                // exit the whole `run()`) apart from "read timed out" (the
                // outer withTimeoutOrNull returned null — we break the inner
                // loop and wait for the next wake).
                val closedMarker: Any = Closed
                val emptyMarker: Any = Empty
                val deadline = if (firstReadPending) firstReadTimeoutMs else readTimeoutMs
                val outcome: Any? = withTimeoutOrNull(deadline) {
                    val b = readPayload()
                    when {
                        b == null -> closedMarker
                        b.isEmpty() -> emptyMarker
                        else -> b
                    }
                }
                firstReadPending = false
                when (outcome) {
                    // timeout — break the inner loop and wait for the next wake
                    null -> break

                    // source closed — terminate the whole drain loop
                    closedMarker -> return

                    // device reports queue drained — wait for the next wake
                    emptyMarker -> break

                    // payload arrived — emit and pump again
                    else -> emit(outcome as ByteArray)
                }
            }
        }
    }

    /** Convenience: expose wake signals as a Flow for testing/observability. */
    fun wakeSignalsAsFlow(): Flow<Unit> = wakeSignals.consumeAsFlow()

    private object Closed
    private object Empty

    internal companion object {
        /**
         * Default per-read timeout. 5 s comfortably exceeds a typical GATT read
         * round-trip on Android/iOS even under congestion, but is short enough
         * that a truly stuck read is surfaced within a single handshake attempt
         * rather than blocking the drain forever.
         */
        const val DEFAULT_READ_TIMEOUT_MS: Long = 5_000L

        /**
         * Default deadline applied to the very first read after [run] starts.
         * Kept generous (30 s) because the post-connect drain races the
         * platform's bonding/encryption warmup — on Android the first
         * encrypted GATT read after pairing routinely takes 5–15 s while the
         * stack reissues the request once the link is encrypted. After the
         * first successful (or timed-out) read the loop drops to
         * [DEFAULT_READ_TIMEOUT_MS]. Cross-ref: TX-11 in `audit-report.md`.
         */
        const val DEFAULT_FIRST_READ_TIMEOUT_MS: Long = 30_000L
    }
}
