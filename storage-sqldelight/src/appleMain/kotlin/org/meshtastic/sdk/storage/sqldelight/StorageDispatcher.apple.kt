/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext

// newFixedThreadPoolContext rather than limitedParallelism(4): see StorageDispatcher.jvm.kt.
@OptIn(DelicateCoroutinesApi::class)
internal actual val defaultStorageDispatcher: CoroutineDispatcher =
    newFixedThreadPoolContext(STORAGE_PARALLELISM, "sqldelight-storage")
