/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble.internal

import com.juul.kable.GattStatusException
import com.juul.kable.State

/**
 * Well-known Android `BluetoothGatt` status codes that surface through Kable
 * as [GattStatusException.status] or [State.Disconnected.Status.Unknown.status].
 *
 * Constants mirror the Android `BluetoothGatt.GATT_*` values and the
 * platform-private link-layer codes seen in `gatt_api.h`. Centralising them
 * here keeps the BLE transport's classification logic readable and lets
 * platform-specific call sites refer to them by name. Cross-ref: TX-14 in
 * `audit-report.md`.
 */
internal object AndroidGattStatus {
    /** Operation completed successfully. Treat as "no error". */
    const val SUCCESS: Int = 0

    /** `GATT_CONN_TIMEOUT` — link-layer supervision timeout. Recoverable; back off exponentially. */
    const val CONN_TIMEOUT: Int = 8

    /** `GATT_CONN_TERMINATE_PEER_USER` — peer cleanly disconnected. Recoverable; retry promptly. */
    const val CONN_TERMINATE_PEER_USER: Int = 19

    /** `GATT_CONN_TERMINATE_LOCAL_HOST` — local stack tore the link down. Recoverable; normal backoff. */
    const val CONN_TERMINATE_LOCAL_HOST: Int = 22

    /**
     * `GATT_ERROR` — the Android BLE catch-all. Almost always indicates a
     * pairing-cache mismatch, MTU negotiation failure, or timing issue. Code
     * paths that hit 133 should consider clearing the OS bond before retrying.
     */
    const val ERROR_133: Int = 133
}

/**
 * Coarse classification of a GATT failure for retry/backoff selection.
 *
 * Each category carries a default backoff schedule (milliseconds, indexed by
 * failed-attempt number) tuned to the underlying failure mode:
 *
 *  - [PEER_TERMINATED] — the peer disconnected cleanly; retry almost
 *    immediately so a quick power-cycle or app-side reconnect succeeds.
 *  - [LOCAL_TERMINATED] — the local host tore the link down (often during
 *    aggressive scan/connect interleaving); brief backoff is enough.
 *  - [CONN_TIMEOUT] — supervision timeout; the peer may be out of range or
 *    under congestion. Exponential backoff is appropriate.
 *  - [BOND_OR_MTU] — Android's GATT_ERROR (133); pairing/MTU/timing. Retry a
 *    couple of times with moderate backoff so a transient stack hiccup
 *    recovers, but flag the case for higher-level "consider clearing bond".
 *  - [GENERIC] — unmapped / unknown. Use the legacy fixed backoff.
 */
internal enum class GattErrorCategory(val backoffMs: LongArray) {
    PEER_TERMINATED(longArrayOf(0L, 250L)),
    LOCAL_TERMINATED(longArrayOf(250L, 750L)),
    CONN_TIMEOUT(longArrayOf(500L, 1_500L, 3_000L)),
    BOND_OR_MTU(longArrayOf(500L, 1_500L)),
    GENERIC(longArrayOf(250L, 750L)),
    ;

    /**
     * Backoff for the *i*-th failed attempt. Falls back to the last entry if
     * the caller exceeds the table — defensively bounded so a misconfigured
     * retry loop never indexes out of range.
     */
    fun backoffFor(attemptIndex: Int): Long = backoffMs[attemptIndex.coerceIn(0, backoffMs.size - 1)]
}

/** Map a numeric Android GATT status code to an error category. */
internal fun classifyGattStatus(status: Int): GattErrorCategory = when (status) {
    AndroidGattStatus.CONN_TIMEOUT -> GattErrorCategory.CONN_TIMEOUT
    AndroidGattStatus.CONN_TERMINATE_PEER_USER -> GattErrorCategory.PEER_TERMINATED
    AndroidGattStatus.CONN_TERMINATE_LOCAL_HOST -> GattErrorCategory.LOCAL_TERMINATED
    AndroidGattStatus.ERROR_133 -> GattErrorCategory.BOND_OR_MTU
    else -> GattErrorCategory.GENERIC
}

/**
 * Classify an arbitrary throwable. Walks the cause chain shallowly so a
 * wrapped [GattStatusException] (e.g. boxed inside Kable's higher-level
 * exceptions) is still recognised.
 */
internal fun classifyGattError(t: Throwable): GattErrorCategory {
    var cur: Throwable? = t
    var hops = 0
    while (cur != null && hops < 4) {
        if (cur is GattStatusException) return classifyGattStatus(cur.status)
        cur = cur.cause
        hops++
    }
    return GattErrorCategory.GENERIC
}

/**
 * Extract a numeric Android GATT status from a Kable [State.Disconnected.Status],
 * if one is present. Returns `null` for typed disconnect reasons that don't
 * carry a raw status code (e.g. [State.Disconnected.Status.Cancelled]).
 */
internal fun State.Disconnected.Status?.gattStatusCodeOrNull(): Int? = when (this) {
    is State.Disconnected.Status.Unknown -> status
    else -> null
}
