/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.XModem
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.FileTransferApi
import org.meshtastic.sdk.FileTransferDirection
import org.meshtastic.sdk.FileTransferFailure
import org.meshtastic.sdk.FileTransferProgress
import org.meshtastic.sdk.FileTransferResult
import org.meshtastic.sdk.MeshEvent
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioClient
import kotlin.time.TimeSource

internal class FileTransferApiImpl(
    private val client: RadioClient,
    private val maxRetries: Int = XModemProtocol.DEFAULT_MAX_RETRIES,
    private val blockTimeoutMs: Long = XModemProtocol.DEFAULT_BLOCK_TIMEOUT_MS,
    private val maxBytes: Int = FileTransferApi.MAX_FILE_BYTES,
) : FileTransferApi {
    private val busy = Mutex()

    // Guarded by [busy]: whether the last abandoned attempt got no answer to its request at all.
    private var lastResyncSilent = false

    // Guarded by [busy]: replies owed to the last attempt's cleanup frames that never arrived in time.
    private var staleReplies: Set<XModem.Control> = emptySet()
    private val _progress = MutableStateFlow<FileTransferProgress?>(null)
    override val progress: StateFlow<FileTransferProgress?> = _progress.asStateFlow()

    override suspend fun download(path: String): FileTransferResult<ByteString> {
        if (!XModemProtocol.validPath(path)) return FileTransferResult.Failure(FileTransferFailure.InvalidPath)
        return run(path, FileTransferDirection.Download, MAX_RESTARTS) {
            XModemDownload(
                path,
                maxBytes = maxBytes,
                maxRetries = maxRetries,
                blockTimeoutMs = blockTimeoutMs,
                staleReplies = it,
            )
        }
    }

    override suspend fun upload(path: String, data: ByteString): FileTransferResult<Unit> {
        if (!XModemProtocol.validPath(path)) return FileTransferResult.Failure(FileTransferFailure.InvalidPath)
        if (data.size > FileTransferApi.MAX_FILE_BYTES) return FileTransferResult.Failure(FileTransferFailure.TooLarge)
        return run(path, FileTransferDirection.Upload, restarts = 0) {
            XModemUpload(path, data, maxRetries = maxRetries, blockTimeoutMs = blockTimeoutMs)
        }
    }

    private companion object {
        /** A desynchronized download is restarted at most twice: a third failure in a row is not a transient. */
        const val MAX_RESTARTS = 2
    }

    private sealed interface Inbound {
        class Frame(val frame: XModem) : Inbound

        data object Disconnected : Inbound
    }

    private suspend fun <T> run(
        path: String,
        direction: FileTransferDirection,
        restarts: Int,
        newMachine: (Set<XModem.Control>) -> XModemMachine<T>,
    ): FileTransferResult<T> {
        if (!busy.tryLock()) return FileTransferResult.Failure(FileTransferFailure.Busy)
        try {
            if (client.connection.value !is ConnectionState.Connected) {
                return FileTransferResult.Failure(FileTransferFailure.NotConnected)
            }
            // The slot stays owned across restarts; each attempt discards what the last one received.
            lastResyncSilent = false
            repeat(restarts + 1) {
                _progress.value = null
                val machine = newMachine(staleReplies)
                try {
                    drive(path, direction, machine)?.let { return it }
                } finally {
                    staleReplies = machine.pendingReply
                }
            }
            val failure = if (lastResyncSilent) FileTransferFailure.Timeout else FileTransferFailure.RetriesExhausted
            return FileTransferResult.Failure(failure)
        } finally {
            _progress.value = null
            busy.unlock()
        }
    }

    private suspend fun <T> drive(
        path: String,
        direction: FileTransferDirection,
        machine: XModemMachine<T>,
    ): FileTransferResult<T>? {
        val inbound = Channel<Inbound>(Channel.UNLIMITED)
        // The collectors live in their own scope so they outlast the caller's cancellation while the
        // transfer waits for the device's reply to its cleanup frames. UNDISPATCHED registers both
        // subscriptions before the first frame is sent.
        val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { if (it is MeshEvent.XmodemPacket) inbound.trySend(Inbound.Frame(it.packet)) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.connection.first { it !is ConnectionState.Connected }
            inbound.trySend(Inbound.Disconnected)
        }
        val mark = TimeSource.Monotonic.markNow()
        var now = 0L
        fun tick(): Long {
            now = maxOf(now, mark.elapsedNow().inWholeMilliseconds)
            return now
        }

        suspend fun loop(first: XModemStep<T>): FileTransferResult<T>? {
            var step = first
            var result: FileTransferResult<T>? = null
            var resync = false
            while (result == null && !resync) {
                step.bytes?.let { _progress.value = FileTransferProgress(path, direction, it, machine.totalBytes) }
                if (!send(step.spacerNonce, step.send)) {
                    result = FileTransferResult.Failure(FileTransferFailure.NotConnected)
                    continue
                }
                when (val outcome = step.outcome) {
                    is XModemOutcome.Done -> result = FileTransferResult.Success(outcome.value)

                    is XModemOutcome.Failed -> result = FileTransferResult.Failure(outcome.failure)

                    is XModemOutcome.Resync -> {
                        resync = true
                        lastResyncSilent = outcome.silent
                    }

                    null -> {
                        val wait = (machine.deadlineMs - tick()).coerceAtLeast(0)
                        when (val message = withTimeoutOrNull(wait) { inbound.receive() }) {
                            null -> {
                                now = maxOf(now, machine.deadlineMs)
                                step = machine.onTick(now)
                            }

                            is Inbound.Frame -> step = machine.onFrame(message.frame, tick())

                            Inbound.Disconnected ->
                                result = FileTransferResult.Failure(FileTransferFailure.NotConnected)
                        }
                    }
                }
            }
            return result
        }

        return try {
            loop(machine.start(tick()))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { loop(machine.cancel(tick())) }
            throw e
        } finally {
            scope.cancel()
            inbound.close()
        }
    }

    private fun send(spacerNonce: Long?, frames: List<XModem>): Boolean {
        try {
            if (spacerNonce != null) {
                val beat = Heartbeat.Builder().also { wb -> wb.nonce = spacerNonce.toInt() }.build()
                client.sendRaw(ToRadio.Builder().also { wb -> wb.heartbeat = beat }.build())
            }
            for (frame in frames) {
                client.sendRaw(ToRadio.Builder().also { wb -> wb.xmodemPacket = frame }.build())
            }
        } catch (_: MeshtasticException.NotConnected) {
            return false
        }
        return true
    }
}
