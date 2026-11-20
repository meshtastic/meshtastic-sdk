/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.websocket

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportState
import org.meshtastic.sdk.WireFraming
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmOverloads
import io.ktor.websocket.Frame as WsFrame

/**
 * WebSocket transport for firmware's WebSocket phone API: one `ToRadio` or `FromRadio` protobuf
 * per binary message at `ws://host[:port]/api/v1/ws` (or `wss://`), with no stream header.
 *
 * Frames given to [send] and produced by [frames] carry the 4-byte stream header like every other
 * transport; it is stripped before a message is sent and prepended to each received one. Text
 * messages are ignored. No subprotocol is offered and no WebSocket ping frames are sent: the
 * engine's 30 second heartbeat keeps the session under the node's 15 minute idle close.
 *
 * The node serves one client at a time. A newer client takes the slot and the old one is closed
 * with code 4001, which surfaces as [TransportState.Error] with `recoverable = false`. Close
 * code 1009 (message over 512 bytes) is not recoverable either; 1000, 1013 (node out of memory)
 * and a drop without a close frame are.
 *
 * This transport does not reconnect: it reports [state] and errors and lets the engine's
 * `AutoReconnectConfig` decide.
 *
 * **Identity:** `ws:<scheme>://<host lowercased>:<port><path>` with the default port made explicit
 * and a blank or `/` path normalised to `/api/v1/ws`, so `ws://Node.local` and
 * `ws://node.local:80/api/v1/ws` share storage.
 *
 * @param url a `ws://` or `wss://` URL. A blank or `/` path becomes `/api/v1/ws`. User info, a query,
 *   a fragment, any other scheme or a port outside 1..65535 throws [IllegalArgumentException].
 * @param httpClient a client to connect with, for example one that trusts a self-signed
 *   certificate. It must have the Ktor WebSockets plugin installed and should leave its ping
 *   interval unset. It keeps its own `maxFrameSize`; set one near 1 KiB to bound memory, since
 *   the SDK-created client does (messages over 512 bytes are refused either way). The SDK never closes a client it was given; when `null` the SDK creates a
 *   client per connection and closes it on [disconnect].
 * @since 0.3.0
 */
public class WebSocketTransport @JvmOverloads constructor(url: String, private val httpClient: HttpClient? = null) :
    RadioTransport {

    private val endpoint = parseWebSocketEndpoint(url)

    override val identity: TransportIdentity = TransportIdentity("ws:${endpoint.identityKey}")

    private val _state: MutableStateFlow<TransportState> = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val inbound = Channel<Frame>(INBOUND_CAPACITY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Guards the fields below and `generation`; held only for short non-network sections.
    private val lock = Mutex()
    private var generation = 0L

    @kotlin.concurrent.Volatile
    private var session: DefaultClientWebSocketSession? = null
    private var ownedClient: HttpClient? = null
    private var pump: Job? = null
    private var attempt: Job? = null

    internal var pumpProbe: (suspend (String) -> Unit)? = null
    internal var connectProbe: (suspend (String) -> Unit)? = null
    internal var attemptContext: CoroutineContext = EmptyCoroutineContext

    private class Detached(val session: DefaultClientWebSocketSession?, val jobs: List<Job?>, val client: HttpClient?)

    override suspend fun connect() {
        connectProbe?.invoke("entering")
        val upgraded = CompletableDeferred<DefaultClientWebSocketSession>()
        val (gen, attemptJob, previous) = lock.withLock {
            val next = ++generation
            val previous = detachLocked()
            _state.value = TransportState.Connecting
            @Suppress("ControlFlowWithEmptyBody")
            while (inbound.tryReceive().isSuccess) {
            }
            val client = httpClient ?: createDefaultHttpClient().also { ownedClient = it }
            val job = scope.launch(attemptContext) {
                try {
                    client.webSocket(endpoint.url) {
                        upgraded.complete(this)
                        runCatching { closeReason.await() }
                    }
                } catch (t: Throwable) {
                    upgraded.completeExceptionally(t)
                }
            }
            job.invokeOnCompletion { cause ->
                upgraded.completeExceptionally(cause ?: CancellationException("WebSocket attempt ended"))
            }
            attempt = job
            Triple(next, job, previous)
        }
        withContext(NonCancellable) { previous.close() }
        val opened = try {
            upgraded.await()
        } catch (e: CancellationException) {
            abandon(gen, TransportState.Disconnected)
            currentCoroutineContext().ensureActive()
            throw MeshtasticException.Transport("WebSocket connect was interrupted by disconnect()")
        } catch (e: Exception) {
            val failure = MeshtasticException.Transport(describeConnectFailure(endpoint, e), e)
            if (!abandon(gen, TransportState.Error(failure, recoverable = true))) {
                throw MeshtasticException.Transport("WebSocket connect was interrupted by disconnect()")
            }
            throw failure
        }
        val installed = try {
            connectProbe?.invoke("upgraded")
            lock.withLock {
                if (generation != gen) {
                    false
                } else {
                    session = opened
                    _state.value = TransportState.Connected
                    pump = scope.launch { readUntilClosed(opened, gen) }
                    true
                }
            }
        } catch (e: CancellationException) {
            abandon(gen, TransportState.Disconnected)
            withContext(NonCancellable) {
                runCatching { opened.cancel() }
                attemptJob.cancel()
            }
            throw e
        }
        if (!installed) {
            withContext(NonCancellable) {
                runCatching { opened.cancel() }
                attemptJob.cancel()
            }
            throw MeshtasticException.Transport("WebSocket connect was interrupted by disconnect()")
        }
    }

    override suspend fun disconnect() {
        withContext(NonCancellable) {
            val detached = lock.withLock {
                generation++
                _state.value = TransportState.Disconnected
                detachLocked()
            }
            detached.close()
        }
    }

    override suspend fun send(frame: Frame) {
        val raw = frame.bytes
        if (raw.size < WireFraming.HEADER_SIZE) {
            throw MeshtasticException.Transport(
                "WebSocket send needs a framed packet from WireCodec (got ${raw.size} bytes)",
            )
        }
        val payloadSize = raw.size - WireFraming.HEADER_SIZE
        if (payloadSize > WireFraming.MAX_PAYLOAD_SIZE) {
            throw MeshtasticException.Transport(
                "WebSocket payload $payloadSize B exceeds ${WireFraming.MAX_PAYLOAD_SIZE} B; the node would close with 1009",
            )
        }
        val open = session ?: throw MeshtasticException.Transport("WebSocket not connected")
        try {
            open.send(WsFrame.Binary(true, raw.substring(WireFraming.HEADER_SIZE).toByteArray()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MeshtasticException.Transport("WebSocket send failed: ${e.message}", e)
        }
    }

    override fun frames(): Flow<Frame> = inbound.receiveAsFlow()

    private fun detachLocked(): Detached {
        val d = Detached(session, listOf(pump, attempt), ownedClient)
        session = null
        pump = null
        attempt = null
        ownedClient = null
        return d
    }

    private suspend fun Detached.close() {
        jobs[0]?.cancel()
        val open = session
        if (open != null) {
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                runCatching { open.close(CloseReason(CloseReason.Codes.NORMAL, "")) }
            }
            runCatching { open.cancel() }
        }
        jobs[1]?.cancel()
        client?.let { runCatching { it.close() } }
    }

    /** Tears down attempt [gen] and publishes [final], unless a newer lifecycle call already owns the state. */
    private suspend fun abandon(gen: Long, final: TransportState): Boolean = withContext(NonCancellable) {
        val detached = lock.withLock {
            if (generation != gen) {
                null
            } else {
                generation++
                _state.value = final
                detachLocked()
            }
        }
        detached?.close()
        detached != null
    }

    private suspend fun readUntilClosed(open: DefaultClientWebSocketSession, gen: Long) {
        var dropped: Throwable? = null
        var oversize = -1L
        pumpProbe?.invoke("started")
        try {
            for (message in open.incoming) {
                if (message !is WsFrame.Binary) continue
                val size = message.data.size.toLong()
                if (size > WireFraming.MAX_PAYLOAD_SIZE) {
                    oversize = size
                    break
                }
                inbound.send(Frame(withHeader(message.readBytes())))
            }
        } catch (e: CancellationException) {
            // Ktor cancels `incoming` itself once the attempt's block returns; that is a session end, not ours.
            currentCoroutineContext().ensureActive()
            dropped = e
        } catch (e: FrameTooBigException) {
            oversize = e.frameSize
        } catch (e: Exception) {
            dropped = e
        }
        val ended: TransportState.Error
        if (oversize >= 0) {
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                runCatching { open.close(CloseReason(CloseReason.Codes.TOO_BIG, "")) }
            }
            val failure = MeshtasticException.Transport(
                "WebSocket message of $oversize bytes exceeds ${WireFraming.MAX_PAYLOAD_SIZE}; closed with 1009",
            )
            ended = TransportState.Error(failure, recoverable = false)
        } else {
            val reason = try {
                withTimeoutOrNull(CLOSE_TIMEOUT_MS) { open.closeReason.await() }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                null
            }
            val (message, recoverable) = describeSessionEnd(reason?.code?.toInt(), reason?.message, dropped)
            val cause = dropped?.takeUnless { it is CancellationException }
            ended = TransportState.Error(MeshtasticException.Transport(message, cause), recoverable)
        }
        pumpProbe?.invoke("ending")
        try {
            lock.withLock {
                if (generation == gen && session === open) {
                    session = null
                    _state.value = ended
                }
            }
        } finally {
            pumpProbe?.invoke("ended")
        }
    }

    internal companion object {
        private const val INBOUND_CAPACITY = 256
        private const val CLOSE_TIMEOUT_MS = 2_000L

        const val CLOSE_NORMAL: Int = 1000
        const val CLOSE_TOO_BIG: Int = 1009
        const val CLOSE_TRY_AGAIN_LATER: Int = 1013
        const val CLOSE_REPLACED: Int = 4001

        fun withHeader(payload: ByteArray): ByteString {
            val out = ByteArray(WireFraming.HEADER_SIZE + payload.size)
            out[0] = WireFraming.MAGIC_0
            out[1] = WireFraming.MAGIC_1
            out[2] = (payload.size shr 8).toByte()
            out[3] = (payload.size and 0xFF).toByte()
            payload.copyInto(out, destinationOffset = WireFraming.HEADER_SIZE)
            return out.toByteString()
        }

        /** Maps a close code, or `null` for a drop with no close frame, to a message and whether to retry. */
        fun describeSessionEnd(code: Int?, text: String?, dropped: Throwable?): Pair<String, Boolean> = when (code) {
            CLOSE_REPLACED -> "WebSocket closed: another client took the node's only WebSocket slot (4001)" to false

            CLOSE_TOO_BIG -> "WebSocket closed: the node rejected a message over 512 bytes (1009)" to false

            CLOSE_TRY_AGAIN_LATER -> "WebSocket closed: the node is out of memory, try again later (1013)" to true

            CLOSE_NORMAL -> "WebSocket closed by the node (1000), usually the 15 minute idle limit" to true

            null -> "WebSocket dropped without a close frame" + (dropped?.message?.let { ": $it" } ?: "") to true

            else -> "WebSocket closed by the node ($code${text?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""})" to
                true
        }

        fun describeConnectFailure(endpoint: WebSocketEndpoint, error: Throwable): String {
            val chain = generateSequence(error) { it.cause }.take(8).toList()
            val text = chain.joinToString(" | ") { "${it::class.simpleName}: ${it.message}" }
            val lower = text.lowercase()
            val status = STATUS_PATTERN.find(text)?.groupValues?.get(1)
            val target = "${endpoint.host}:${endpoint.port}"
            return when {
                status != null && status != "101" ->
                    "WebSocket upgrade refused by $target with HTTP $status; " +
                        "the node's firmware may not support the WebSocket phone API at ${endpoint.path}"

                endpoint.scheme == "wss" && TLS_HINTS.any { it in lower } ->
                    "TLS certificate for $target is not trusted; try ws:// or meshtasticd's plain HTTPPort, " +
                        "or pass an HttpClient that trusts the node's certificate"

                else -> "could not reach $target: ${error.message ?: error::class.simpleName}"
            }
        }

        private val STATUS_PATTERN =
            Regex("""(?:status code 101 but was:?|status:?|HTTP)\s*(\d{3})""", RegexOption.IGNORE_CASE)
        private val TLS_HINTS = listOf("certificate", "pkix", "ssl", "tls", "trust")
    }
}
