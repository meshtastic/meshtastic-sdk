/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.GattRequestRejectedException
import com.juul.kable.GattStatusException
import com.juul.kable.NotConnectedException
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.Frame
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportSpec
import org.meshtastic.sdk.TransportState
import org.meshtastic.sdk.WireFraming
import org.meshtastic.sdk.transport.ble.internal.AndroidGattStatus
import org.meshtastic.sdk.transport.ble.internal.DrainCoordinator
import org.meshtastic.sdk.transport.ble.internal.classifyGattError
import org.meshtastic.sdk.transport.ble.internal.classifyGattStatus
import org.meshtastic.sdk.transport.ble.internal.gattStatusCodeOrNull
import org.meshtastic.sdk.transport.ble.internal.retryTransient
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * BLE transport for Meshtastic radios using JuulLabs Kable.
 *
 * Implements GATT framing: subscribes to `fromnum` notifications, drains `fromradio` until
 * empty on each notification, writes to `toradio` with acknowledged writes (the
 * firmware declares write-with-response only).
 *
 * Strips/prepends the 4-byte stream header so the engine stays transport-agnostic.
 * [frames] allows one active collector at a time (re-collectable after disconnect);
 * [shutdown] is idempotent and cannot be undone.
 *
 * @param peripheral Kable peripheral pointing at the target radio.
 * @param address stable platform-specific address for storage identity keying.
 */
public class BleTransport(
    private val peripheral: Peripheral,
    address: String,
    parentContext: CoroutineContext = EmptyCoroutineContext,
) : RadioTransport {

    override val identity: TransportIdentity =
        TransportIdentity.of(TransportSpec.Ble(address))

    private val _state: MutableStateFlow<TransportState> =
        MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    // Child SupervisorJob inherits caller's context; siblings fail independently.
    //
    // The handler is required, not defensive: SupervisorJob stops a failing child cancelling its
    // siblings, but does not make an unhandled exception non-fatal. Without it, anything escaping a
    // child reaches the platform default handler, which on Android kills the consuming app.
    private val scope = CoroutineScope(
        SupervisorJob(parent = parentContext[Job]) + (parentContext.minusKey(Job)) + Dispatchers.Default +
            CoroutineExceptionHandler { _, cause -> publishScopeFailure(cause) },
    )

    private var drainJob: Job? = null
    private var stateBridgeJob: Job? = null
    private var observeJob: Job? = null
    private var coordinator: DrainCoordinator? = null

    // Per-connect-cycle scope handed to [postConnectHook]; cancelled by disconnect()/shutdown()
    // so stale tuning work (e.g. a delayed connection-priority downgrade) can't leak into the
    // next session when the transport is reused after disconnect.
    private var tuningScope: CoroutineScope? = null

    // Recreated per connect cycle: a completed/cancelled collector leaves the previous
    // channel unusable (consumeAsFlow cancels it), and reuse-after-disconnect needs a live one.
    private var frameChannel = Channel<Frame>(capacity = 64)

    // ADR-012: lifecycle idempotency — idempotent shutdown flag.
    private val shuttingDown = atomic(false)

    // ADR-012: lifecycle idempotency — enforces a single ACTIVE collector of frames().
    // Reset when a collector completes so reuse-after-disconnect can collect again.
    private val framesCollected = atomic(false)

    // ADR-012: lifecycle idempotency — while false, bridgeKableState() won't promote to Connected (warmup read pending).
    private val warmupComplete = atomic(false)

    // ADR-012: lifecycle idempotency — only one Error transition per connect cycle.
    private val errorPublished = atomic(false)

    /**
     * Platform hook invoked once per successful link establishment, after the GATT connection
     * (and Kable's service discovery) completes and before the warmup read. Platform factories
     * use it for link tuning — e.g. the Android factory negotiates the ATT MTU and requests a
     * faster connection interval here. Receives a **per-connect-cycle** [CoroutineScope] as
     * receiver for scheduling follow-ups (e.g. a delayed priority downgrade). That scope is
     * cancelled at [disconnect] (and [shutdown]), so anything launched into it must not assume
     * it outlives the session. Best-effort: failures are swallowed and never fail the connect.
     */
    internal var postConnectHook: suspend CoroutineScope.(Peripheral) -> Unit = {}

    override suspend fun connect() {
        check(!shuttingDown.value) { "BleTransport has been shut down; construct a new instance" }
        // Reset per-connect lifecycle flags so reuse-after-disconnect works.
        warmupComplete.value = false
        errorPublished.value = false
        // The previous session's collector cancelled its channel on completion; start each
        // cycle with a fresh one. Safe unconditionally: drain/warmup senders for this cycle
        // are launched later in connect(), and prior-cycle senders were cancelled by
        // disconnect().
        frameChannel = Channel(capacity = 64)
        _state.value = TransportState.Connecting
        try {
            connectWithRetry()

            // Child of the transport scope (dies with it on shutdown), but independently
            // cancellable per connect cycle via cancelJobs()/disconnect().
            val tScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            tuningScope = tScope
            try {
                postConnectHook(tScope, peripheral)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // best-effort platform tuning (MTU / connection priority); never fatal.
            }

            stateBridgeJob = peripheral.state
                .onEach { kableState -> bridgeKableState(kableState) }
                .launchIn(scope)

            val drain = DrainCoordinator(
                readPayload = {
                    try {
                        peripheral.read(BleConstants.FROMRADIO)
                    } catch (_: NotConnectedException) {
                        null
                    } catch (_: GattRequestRejectedException) {
                        // Android rejects a GATT op when one is outstanding or the link is going
                        // away: routine as a peer drops, not fatal. Ending the drain lets
                        // publishDrainTerminatedError() report a recoverable error.
                        null
                    }
                },
            )
            coordinator = drain

            observeJob = peripheral.observe(BleConstants.FROMNUM)
                .onEach { drain.wake() }
                .launchIn(scope)

            // Warmup read: triggers the OS pairing dialog (if needed) before the engine's
            // handshake clock starts. Timeout is generous for user interaction.
            try {
                withTimeout(BOND_WARMUP_TIMEOUT_MS) {
                    val payload = warmupRead()
                    if (payload != null && payload.isNotEmpty()) {
                        frameChannel.send(Frame(prependFraming(payload)))
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // Timeout: user likely still confirming pairing; drain will retry.
            }
            // Warmup window closed; allow bridgeKableState to publish Connected.
            warmupComplete.value = true

            drainJob = scope.launch {
                drain.run { payload ->
                    if (shuttingDown.value) return@run
                    try {
                        frameChannel.send(Frame(prependFraming(payload)))
                    } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
                        if (!shuttingDown.value) throw e
                    }
                }
                publishDrainTerminatedError()
            }

            if (shuttingDown.value) {
                throw CancellationException("BleTransport shut down during connect")
            }
            _state.value = TransportState.Connected
        } catch (e: CancellationException) {
            try {
                peripheral.disconnect()
            } catch (_: Exception) { /* idempotent */ }
            throw e
        } catch (e: Exception) {
            _state.value = TransportState.Error(e, recoverable = true)
            try {
                peripheral.disconnect()
            } catch (_: Exception) { /* idempotent */ }
            throw MeshtasticException.Transport(
                "BLE connect to ${identity.raw} failed: ${e.message}",
                e,
            )
        }
    }

    /**
     * Encrypted warmup read with bonding-aware retry. Publishes [TransportState.Bonding]
     * on auth failure so UIs can hint. Returns null if link dropped.
     */
    private suspend fun warmupRead(): ByteArray? {
        var attempts = 0
        while (true) {
            try {
                return peripheral.read(BleConstants.FROMRADIO)
            } catch (_: NotConnectedException) {
                return null
            } catch (e: GattStatusException) {
                if (isAuthRequiredStatus(e.status) && attempts < BOND_RETRY_LIMIT) {
                    _state.value = TransportState.Bonding
                    attempts++
                    delay(BOND_RETRY_BACKOFF_MS)
                    continue
                }
                throw e
            }
        }
    }

    /** Report a failure that escaped a child coroutine as a recoverable error. Idempotent. */
    private fun publishScopeFailure(cause: Throwable) {
        if (shuttingDown.value) return
        if (!errorPublished.compareAndSet(expect = false, update = true)) return
        _state.value = TransportState.Error(
            cause = MeshtasticException.Transport("BLE transport failure: ${cause.message}", cause),
            recoverable = true,
        )
    }

    /**
     * Publish recoverable Error if drain terminated unexpectedly. Idempotent.
     */
    private fun publishDrainTerminatedError() {
        if (shuttingDown.value) return
        if (!errorPublished.compareAndSet(expect = false, update = true)) return
        val current = _state.value
        if (current is TransportState.Connected ||
            current is TransportState.Bonding ||
            current is TransportState.Connecting
        ) {
            _state.value = TransportState.Error(
                cause = MeshtasticException.Transport(
                    "BLE drain terminated: peripheral not connected",
                ),
                recoverable = true,
            )
        }
    }

    override suspend fun disconnect() {
        _state.value = TransportState.Disconnected
        // Pre-arm error guard so in-flight drain treats this as normal teardown.
        errorPublished.value = true
        warmupComplete.value = false
        cancelJobs()
        try {
            peripheral.disconnect()
        } catch (_: Exception) { /* idempotent */ }
    }

    override suspend fun send(frame: Frame) {
        val raw = frame.bytes.toByteArray()
        require(raw.size >= 4) {
            "BLE transport requires framed input from WireCodec (got ${raw.size} bytes)"
        }
        // Strip 4-byte stream header — BLE delivers one ToRadio per write.
        val payload = raw.copyOfRange(4, raw.size)
        if (payload.size > MAX_TO_FROM_RADIO_SIZE) {
            throw MeshtasticException.Transport(
                "BLE payload exceeds MAX_TO_FROM_RADIO_SIZE " +
                    "(got=${payload.size}, max=$MAX_TO_FROM_RADIO_SIZE)",
            )
        }
        try {
            // Acknowledged write: firmware exposes `toradio` as CHR_PROPS_WRITE only
            // (NRF52Bluetooth.cpp:213, NimBLE equivalent) — write-without-response is NOT
            // in the characteristic's properties and Android/Kable refuses the write
            // outright. Verified against real radios via MeshtasticBleConformanceTest.
            peripheral.write(BleConstants.TORADIO, payload, WriteType.WithResponse)
        } catch (e: NotConnectedException) {
            throw MeshtasticException.Transport(
                "BLE write dropped — peripheral not connected: ${e.message}",
                e,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: GattStatusException) {
            throw MeshtasticException.Transport(
                "BLE write to ${identity.raw} failed (gattStatus=${e.status}, " +
                    "category=${classifyGattStatus(e.status)}): ${e.message}",
                e,
            )
        } catch (e: Exception) {
            throw MeshtasticException.Transport(
                "BLE write to ${identity.raw} failed: ${e.message}",
                e,
            )
        }
        // Some firmware variants don't notify after writes; proactively wake the drain.
        coordinator?.wake()
    }

    /** Cold flow over received frames. Single-collection only. */
    override fun frames(): Flow<Frame> = flow {
        check(framesCollected.compareAndSet(expect = false, update = true)) {
            "BleTransport.frames() already has an active collector"
        }
        // Resolve frameChannel at collection time (not call time) so a collector started
        // after reconnect picks up the per-cycle replacement channel.
        emitAll(frameChannel.consumeAsFlow())
    }.onCompletion { framesCollected.value = false }

    /** Release internal coroutines and frame channel. Idempotent; does not close [Peripheral]. */
    public fun shutdown() {
        if (!shuttingDown.compareAndSet(expect = false, update = true)) return
        drainJob = null
        observeJob = null
        stateBridgeJob = null
        // tuningScope's SupervisorJob is a child of scope's Job, so scope.cancel() cancels it.
        tuningScope = null
        coordinator = null
        scope.cancel()
        frameChannel.close()
    }

    /** Cancel tracked jobs. Used by [disconnect]. */
    private fun cancelJobs() {
        drainJob?.cancel()
        drainJob = null
        observeJob?.cancel()
        observeJob = null
        stateBridgeJob?.cancel()
        stateBridgeJob = null
        tuningScope?.cancel()
        tuningScope = null
        coordinator = null
    }

    /** Connect with bounded retry on transient GATT failures (e.g. status 133). */
    private suspend fun connectWithRetry() {
        retryTransient(
            attempts = GATT_RETRY_ATTEMPTS,
            backoffSelector = { i, e -> classifyGattError(e).backoffFor(i) },
            backoffSlots = GATT_RETRY_ATTEMPTS - 1,
            onAttemptFailure = {
                try {
                    peripheral.disconnect()
                } catch (_: Exception) { /* idempotent */ }
            },
        ) {
            if (shuttingDown.value) throw CancellationException("BleTransport shut down")
            peripheral.connect()
        }
    }

    private fun bridgeKableState(kableState: State) {
        // Don't let Kable's link-layer state downgrade Bonding; hold Connecting
        // until warmupComplete so the engine's handshake clock doesn't start early.
        val current = _state.value
        _state.value = when (kableState) {
            is State.Connected -> when {
                current is TransportState.Bonding -> current
                !warmupComplete.value -> current
                else -> TransportState.Connected
            }

            is State.Connecting -> when {
                current is TransportState.Bonding -> current
                !warmupComplete.value && current is TransportState.Connecting -> current
                else -> TransportState.Connecting
            }

            State.Disconnecting -> TransportState.Disconnected

            is State.Disconnected -> {
                if (current is TransportState.Connected ||
                    current is TransportState.Bonding ||
                    (current is TransportState.Connecting && warmupComplete.value)
                ) {
                    if (errorPublished.compareAndSet(expect = false, update = true)) {
                        TransportState.Error(
                            cause = buildDisconnectError(kableState.status),
                            recoverable = true,
                        )
                    } else {
                        current
                    }
                } else {
                    TransportState.Disconnected
                }
            }
        }
    }

    /**
     * Build a transport exception surfacing the GATT status, category, and hints.
     */
    private fun buildDisconnectError(status: State.Disconnected.Status?): MeshtasticException.Transport {
        val code = status.gattStatusCodeOrNull()
        val category = code?.let { classifyGattStatus(it) }
        val suffix = buildString {
            if (status != null) append(": ").append(status.toString())
            if (code != null) append(" (gattStatus=").append(code).append(")")
            if (category != null) append(" [category=").append(category).append("]")
            if (code == AndroidGattStatus.ERROR_133) {
                append(" — bond/MTU/timing error; consider clearing the OS bond cache")
            }
        }
        return MeshtasticException.Transport("BLE peripheral disconnected$suffix")
    }

    internal companion object {
        private const val START1: Byte = WireFraming.MAGIC_0
        private const val START2: Byte = WireFraming.MAGIC_1

        /** Max protobuf size for ToRadio/FromRadio (`mesh.proto MAX_TO_FROM_RADIO_SIZE`). */
        internal const val MAX_TO_FROM_RADIO_SIZE: Int = 512

        private const val BOND_WARMUP_TIMEOUT_MS: Long = 120_000L
        private const val BOND_RETRY_LIMIT: Int = 1
        private const val BOND_RETRY_BACKOFF_MS: Long = 2_000L

        /** Total connect attempts (1 initial + N-1 retries) for transient GATT errors. */
        internal const val GATT_RETRY_ATTEMPTS: Int = 3

        /** GATT statuses indicating encryption/auth required (triggers bonding flow). */
        private fun isAuthRequiredStatus(status: Int): Boolean = status == 5 || status == 15 || status == 137

        /** Prepend synthetic 4-byte stream-framing header for engine compatibility. */
        internal fun prependFraming(payload: ByteArray): ByteString {
            val out = ByteArray(4 + payload.size)
            out[0] = START1
            out[1] = START2
            out[2] = (payload.size shr 8).toByte()
            out[3] = (payload.size and 0xFF).toByte()
            payload.copyInto(out, destinationOffset = 4)
            return out.toByteString()
        }
    }
}
