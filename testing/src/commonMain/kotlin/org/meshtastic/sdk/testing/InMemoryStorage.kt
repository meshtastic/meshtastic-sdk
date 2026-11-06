/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.testing

/**
 * Moved to `:core` as [org.meshtastic.sdk.InMemoryStorage] so production hosts can use it
 * without this module's test dependencies.
 */
@Deprecated(
    message = "Moved to :core as org.meshtastic.sdk.InMemoryStorage",
    replaceWith = ReplaceWith("InMemoryStorage", "org.meshtastic.sdk.InMemoryStorage"),
)
public typealias InMemoryStorage = org.meshtastic.sdk.InMemoryStorage

/**
 * Moved to `:core` as [org.meshtastic.sdk.InMemoryStorageProvider] so production hosts can use
 * it without this module's test dependencies.
 */
@Deprecated(
    message = "Moved to :core as org.meshtastic.sdk.InMemoryStorageProvider",
    replaceWith = ReplaceWith("InMemoryStorageProvider", "org.meshtastic.sdk.InMemoryStorageProvider"),
)
public typealias InMemoryStorageProvider = org.meshtastic.sdk.InMemoryStorageProvider
