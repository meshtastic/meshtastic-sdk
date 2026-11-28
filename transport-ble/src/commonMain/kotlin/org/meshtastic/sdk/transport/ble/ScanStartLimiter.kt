/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.meshtastic.sdk.MeshtasticException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Android allows [maxStarts] BLE scan starts per rolling [window] and may then silence the scan callback, leaving a scan
 * flow waiting with no advertisement and no error. Counting starts here turns that into a [MeshtasticException.Transport]
 * the caller can retry.
 */
internal class ScanStartLimiter(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val maxStarts: Int = ANDROID_SCAN_START_LIMIT,
    private val window: Duration = ANDROID_SCAN_START_WINDOW,
) {
    private val lock = Mutex() // ADR-012: process-wide scan quota, one reservation at a time
    private val starts = ArrayDeque<TimeMark>()

    /** Reserves one scan start, or throws [MeshtasticException.Transport] when the window is full; a rejection reserves nothing. */
    suspend fun reserve() = lock.withLock {
        while (starts.firstOrNull()?.let { it.elapsedNow() >= window } == true) starts.removeFirst()
        if (starts.size >= maxStarts) {
            val retryIn = (window - starts.first().elapsedNow()).coerceAtLeast(Duration.ZERO)
            throw MeshtasticException.Transport(
                "BLE scan not started: Android allows $maxStarts scan starts per $window; retry in $retryIn",
                IllegalStateException("scan start quota exhausted"),
            )
        }
        starts.addLast(timeSource.markNow())
    }
}

internal const val ANDROID_SCAN_START_LIMIT: Int = 5
internal val ANDROID_SCAN_START_WINDOW: Duration = 30.seconds

/** Reserves a scan start on the platform that limits them (Android); a no-op elsewhere. */
internal expect suspend fun reserveScanStart()
