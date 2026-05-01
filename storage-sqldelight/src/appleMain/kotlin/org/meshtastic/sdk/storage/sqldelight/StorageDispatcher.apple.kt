/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Apple (iOS/macOS): `kotlinx.coroutines.Dispatchers.IO` is declared as a public extension
 * property on Native since 1.7, but it is shadowed by an `internal val IO` member inside the
 * `Dispatchers` object, so references from outside `kotlinx.coroutines` fail resolution. Fall
 * back to a capped view of [Dispatchers.Default] — `NativeSqliteDriver` serialises all access
 * through a single connection regardless, so the underlying dispatcher's identity has limited
 * practical effect on throughput.
 */
internal actual val defaultStorageDispatcher: CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(parallelism = 4, name = "sqldelight-storage")
