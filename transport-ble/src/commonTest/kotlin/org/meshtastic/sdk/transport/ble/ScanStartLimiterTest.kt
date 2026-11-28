/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlinx.coroutines.test.runTest
import org.meshtastic.sdk.MeshtasticException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ScanStartLimiterTest {

    // Regression: the unguarded scan hung silently on Android's sixth start inside 30 s.
    @Test
    fun `the sixth start inside the window is rejected with a transport exception`() = runTest {
        val limiter = ScanStartLimiter(TestTimeSource())
        repeat(5) { limiter.reserve() }
        assertFailsWith<MeshtasticException.Transport> { limiter.reserve() }
    }

    // Regression: the window never expiring would lock scans out for the process lifetime.
    @Test
    fun `a start is allowed again once the oldest start leaves the window`() = runTest {
        val time = TestTimeSource()
        val limiter = ScanStartLimiter(time)
        repeat(5) { limiter.reserve() }
        time += 30.seconds
        limiter.reserve()
    }

    // Regression: counting rejections as starts would make the quota drift down.
    @Test
    fun `a rejected start reserves nothing`() = runTest {
        val time = TestTimeSource()
        val limiter = ScanStartLimiter(time)
        repeat(5) { limiter.reserve() }
        time += 10.seconds
        assertFailsWith<MeshtasticException.Transport> { limiter.reserve() }
        time += 20.seconds
        repeat(5) { limiter.reserve() }
        assertFailsWith<MeshtasticException.Transport> { limiter.reserve() }
    }
}
