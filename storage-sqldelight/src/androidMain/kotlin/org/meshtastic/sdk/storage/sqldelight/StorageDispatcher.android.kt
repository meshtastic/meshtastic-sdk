/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

// A fixed pool rather than Dispatchers.IO.limitedParallelism(4): the SDK compiles against
// coroutines 1.8.1, which has no such overload. Daemon threads keep a forgotten storage
// from pinning the JVM.
internal actual val defaultStorageDispatcher: CoroutineDispatcher =
    Executors.newFixedThreadPool(STORAGE_PARALLELISM) { runnable ->
        Thread(runnable, "sqldelight-storage").apply { isDaemon = true }
    }.asCoroutineDispatcher()
