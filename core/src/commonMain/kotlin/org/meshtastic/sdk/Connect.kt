/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Run [block] against a connected session, guaranteeing teardown.
 *
 * Connects, executes [block], and always disconnects afterwards — on success, on exception,
 * and on cancellation (the disconnect runs under [NonCancellable] so a cancelled caller still
 * tears the session down cleanly). This is the structured-concurrency replacement for a
 * blocking `use { }` idiom, which `RadioClient` deliberately does not offer:
 *
 * ```kotlin
 * val owner = client.withConnection {
 *     admin.getOwner().getOrThrow()
 * }
 * ```
 *
 * The client is **single-use**: after [withConnection] returns, build a new [RadioClient]
 * for the next session.
 *
 * @param teardownTimeout upper bound on the guaranteed disconnect (default 10s) — protects the
 *   caller from a wedged transport teardown while still covering the polite-goodbye flush and
 *   storage close on every healthy path
 * @return whatever [block] returns
 * @throws MeshtasticException any failure surfaced by [RadioClient.connect]
 * @since 0.2.0
 */
public suspend fun <T> RadioClient.withConnection(
    teardownTimeout: Duration = 10.seconds,
    block: suspend RadioClient.() -> T,
): T {
    connect()
    return try {
        block()
    } finally {
        withContext(NonCancellable) {
            // Bound the guaranteed teardown: a hung transport disconnect (e.g. a wedged
            // Android BLE stack) must not pin the caller uncancellably forever.
            withTimeoutOrNull(teardownTimeout) {
                disconnect()
            }
        }
    }
}

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
 * @since 0.1.0
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
