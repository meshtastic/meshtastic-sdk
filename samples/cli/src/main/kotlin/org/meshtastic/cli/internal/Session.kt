/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.internal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider

/** Default timeout for one-shot connects (handshake completion). */
internal const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 45_000L

/**
 * Live client + display name, plus a coroutine-safe [close] that best-effort disconnects.
 * Callers MUST close the handle; use `try { … } finally { session.close() }`.
 */
internal data class SessionHandle(val client: RadioClient, val displayName: String) {
    suspend fun close() {
        runCatching { client.disconnect() }
    }
}

/** Discriminated result: either a live session or an exit code (never both). */
internal sealed interface SessionResult {
    data class Ready(val session: SessionHandle) : SessionResult
    data class Failed(val exitCode: Int) : SessionResult
}

/**
 * Opens [ref], builds a [RadioClient], waits for the handshake, and returns a ready session.
 * On failure, emits the appropriate `error`/`done` envelopes via [out] and returns [SessionResult.Failed].
 */
internal suspend fun connect(ref: TransportRef, timeoutMs: Long, out: Output): SessionResult {
    val opened = try {
        openTransport(ref)
    } catch (e: NoDeviceException) {
        out.error("no-device", e.message ?: "no device found")
        out.done("no-device", ExitCodes.NO_DEVICE)
        return SessionResult.Failed(ExitCodes.NO_DEVICE)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        out.error("transport-open", "${e::class.simpleName}: ${e.message}")
        out.done("transport-open", ExitCodes.FAILURE)
        return SessionResult.Failed(ExitCodes.FAILURE)
    }

    val client = RadioClient.Builder()
        .transport(opened.transport)
        .storage(SqlDelightStorageProvider(baseDir = ""))
        .build()

    out.human("Connecting via ${ref.label} (${opened.displayName}) …")

    val outcome = withTimeoutOrNull(timeoutMs) {
        try {
            client.connect()
            client.connection.first { it is ConnectionState.Connected }
            Outcome.OK
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            out.error("handshake", "${e::class.simpleName}: ${e.message}")
            Outcome.HandshakeFailed
        }
    } ?: Outcome.Timeout

    return when (outcome) {
        Outcome.OK -> {
            out.human("Connected.")
            SessionResult.Ready(SessionHandle(client, opened.displayName))
        }

        Outcome.Timeout -> {
            runCatching { client.disconnect() }
            out.error("timeout", "handshake did not complete within ${timeoutMs}ms")
            out.done("timeout", ExitCodes.TIMEOUT)
            SessionResult.Failed(ExitCodes.TIMEOUT)
        }

        Outcome.HandshakeFailed -> {
            runCatching { client.disconnect() }
            out.done("handshake", ExitCodes.FAILURE)
            SessionResult.Failed(ExitCodes.FAILURE)
        }
    }
}

private enum class Outcome { OK, Timeout, HandshakeFailed }
