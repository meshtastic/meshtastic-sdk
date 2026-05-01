/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Connect and suspend until the handshake settles, returning the resolved [ConfigBundle] (HLP-34).
 *
 * Convenience for one-shot callers that just want to "connect and read configuration":
 *
 * ```kotlin
 * val bundle = client.connectAndAwaitReady()
 * println("device firmware ${bundle.metadata?.firmware_version}")
 * ```
 *
 * Calls [RadioClient.connect] and then awaits both [ConnectionState.Connected] and the
 * first non-null value of [RadioClient.configBundle], whichever lands later. Throws
 * [MeshtasticException.HandshakeTimeout] if the combined wait exceeds [timeout].
 *
 * @throws MeshtasticException any failure surfaced by [RadioClient.connect]
 * @throws MeshtasticException.HandshakeTimeout if [timeout] elapses first
 */
public suspend fun RadioClient.connectAndAwaitReady(timeout: Duration = 30.seconds): ConfigBundle {
    val bundle = withTimeoutOrNull(timeout) {
        connect()
        connection.first { it is ConnectionState.Connected }
        configBundle.first { it != null }
    } ?: throw MeshtasticException.tag(
        MeshtasticException.HandshakeTimeout(stage = "connectAndAwaitReady"),
        operation = "connectAndAwaitReady",
    )
    return bundle
}
