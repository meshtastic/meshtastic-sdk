/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.cli.internal.NoDeviceException
import org.meshtastic.cli.internal.openTransport
import org.meshtastic.sdk.AutoReconnectConfig
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.TransportTarget
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider

/** A tool-level failure whose [message] is returned to the MCP client as an `isError` result. */
internal class ToolFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Fails the current tool call with [message]. */
internal fun toolError(message: String, cause: Throwable? = null): Nothing = throw ToolFailure(message, cause)

/** One live radio session owned by the MCP server. */
internal data class LiveRadio(
    val ref: TransportTarget,
    val displayName: String,
    val client: RadioClient,
    val connectedAtMs: Long,
    val connectMs: Long,
)

/**
 * Owns at most one [RadioClient] for the life of the `mcp` process. Session replacement runs
 * through a single consumer coroutine, so concurrent `connect` calls never interleave. Each
 * queued call runs as a child of its caller, so a call cancelled while it waits never replaces
 * the live session; reads take the current value without waiting.
 */
internal class RadioHolder(scope: CoroutineScope) {
    private val current = MutableStateFlow<LiveRadio?>(null)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in commands) command()
        }
    }

    val live: LiveRadio? get() = current.value

    suspend fun connect(ref: TransportTarget, timeoutMs: Long): LiveRadio = serialized {
        current.value?.let { old ->
            current.value = null
            runCatching { old.client.disconnect() }
        }
        val started = System.nanoTime()
        val opened = try {
            openTransport(ref)
        } catch (e: NoDeviceException) {
            toolError("no-device: ${e.message}", e)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            toolError("transport-open: ${e::class.simpleName}: ${e.message}", e)
        }
        val client = RadioClient.Builder()
            .autoReconnect(AutoReconnectConfig.Disabled)
            .transport(opened.transport)
            .storage(SqlDelightStorageProvider(baseDir = ""))
            .build()
        val outcome = try {
            withTimeoutOrNull(timeoutMs) {
                try {
                    client.connect()
                    client.connection.first { it is ConnectionState.Connected }
                    OK
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    "handshake: ${e::class.simpleName}: ${e.message}"
                }
            } ?: "timeout: no handshake in ${timeoutMs}ms"
        } catch (e: CancellationException) {
            // The caller went away mid-handshake: do not leave a half-open client behind.
            withContext(NonCancellable) { runCatching { client.disconnect() } }
            throw e
        }
        val error = outcome.takeUnless { it == OK }
        if (error != null) {
            runCatching { client.disconnect() }
            toolError(error)
        }
        val radio = LiveRadio(
            ref = ref,
            displayName = opened.displayName,
            client = client,
            connectedAtMs = System.currentTimeMillis(),
            connectMs = (System.nanoTime() - started) / NANOS_PER_MS,
        )
        current.value = radio
        radio
    }

    suspend fun close() {
        current.value?.let { runCatching { it.client.disconnect() } }
        current.value = null
        commands.close()
    }

    private suspend fun <T> serialized(block: suspend () -> T): T {
        val caller = currentCoroutineContext().job
        val result = CompletableDeferred<T>()
        commands.send {
            if (!caller.isActive) {
                result.cancel()
                return@send
            }
            try {
                // A child of the caller: its cancellation stops this work, not the consumer.
                result.complete(withContext(caller) { block() })
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result.await()
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val OK = "ok"
    }
}
