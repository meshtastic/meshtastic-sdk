/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.bytestring.ByteString

/**
 * A framed packet ready for transmission or received from the device.
 *
 * Framing (sync bytes, length, CRC) is handled by the SDK codec; transport layers work with raw
 * frames. Equality is structural (delegates to [ByteString.equals]).
 *
 * @param bytes the wire data (including all framing overhead).
 * @since 0.1.0
 */
public data class Frame(public val bytes: ByteString)

/**
 * Deterministic identity for a transport configuration.
 *
 * Derived from the transport spec (address, host:port, etc.) and used as the storage key. Remains
 * stable for the same consumer-supplied transport config; the NodeNum learned at handshake is
 * *recorded* in storage but is not the storage key.
 *
 * **TCP/HTTP caveat:** Identity is computed from the literal host string. Connecting via
 * `"meshtastic.local"` and `"192.168.1.42"` produces two distinct identities, each with its own
 * storage. See [DeviceStorage.recordOwnNode] for the reset rule that bounds this risk.
 *
 * **Java interop:** properties returning [TransportIdentity] expose
 * non-mangled boxed accessors (e.g. `transport.getIdentity()` instead of
 * `getIdentity-dT_YJr8()`) thanks to `-Xjvm-expose-boxed`. Java callers can
 * read the underlying string via `identity.getRaw()`.
 *
 * @since 0.1.0
 */
@kotlin.jvm.JvmInline
public value class TransportIdentity(public val raw: String) {
    public companion object {
        /**
         * Derive a deterministic identity from a [TransportSpec].
         */
        public fun of(spec: TransportSpec): TransportIdentity = when (spec) {
            is TransportSpec.Ble -> TransportIdentity("ble:${spec.address.uppercase()}")
            is TransportSpec.Tcp -> TransportIdentity("tcp:${spec.host.lowercase()}:${spec.port}")
            is TransportSpec.Http -> TransportIdentity("http:${spec.baseUrl.lowercase()}")
            is TransportSpec.SerialAndroid -> TransportIdentity("serial-android:${spec.deviceName}")
            is TransportSpec.SerialJvm -> TransportIdentity("serial-jvm:${spec.portName}")
        }
    }
}

/**
 * Specification of which radio to connect to and how.
 *
 * The SDK supports multiple transports (BLE, TCP, HTTP, serial) depending on the target platform.
 * Each transport implementation provides its own constructor; consumers pick the matching
 * [TransportSpec] for their platform and pass it to [RadioClient.Builder.transport].
 *
 * @since 0.1.0
 */
public sealed interface TransportSpec {
    /** Deterministic identity for storage keying. */
    public val identity: TransportIdentity
        get() = TransportIdentity.of(this)

    /**
     * Bluetooth Low Energy.
     *
     * @param address the device's BLE MAC address (platform-specific format).
     */
    public data class Ble(val address: String) : TransportSpec

    /**
     * TCP socket to a Meshtastic device or router running PhoneAPI on a network.
     *
     * @param host hostname or IP address
     * @param port TCP port (default 4403)
     */
    public data class Tcp(val host: String, val port: Int = 4403) : TransportSpec

    /**
     * HTTP transport (PhoneAPI over HTTP; see `protocol.md` §4).
     *
     * @param baseUrl the device's HTTP base URL (e.g., `"http://192.168.1.42:8080"`)
     */
    public data class Http(val baseUrl: String) : TransportSpec

    /**
     * Android USB serial (via usb-serial-for-android).
     *
     * @param deviceName the USB device path (e.g., `/dev/ttyUSB0` or a UsbDevice ID)
     */
    public data class SerialAndroid(val deviceName: String) : TransportSpec

    /**
     * JVM serial (via jSerialComm).
     *
     * @param portName the serial port name (e.g., `"COM3"` or `"/dev/ttyUSB0"`)
     */
    public data class SerialJvm(val portName: String) : TransportSpec
}

/**
 * State of the transport connection.
 *
 * The engine observes [RadioTransport.state] to detect transport availability. Reaching
 * [Connected] does not mean the SDK session is ready — [ConnectionState.Connected] is only
 * reached after the handshake completes.
 *
 * @since 0.1.0
 */
public sealed interface TransportState {
    /**
     * Transport is disconnected or never started.
     */
    public data object Disconnected : TransportState

    /**
     * Connection attempt is in progress.
     */
    public data object Connecting : TransportState

    /**
     * Transport link is up but the platform is negotiating encryption / pairing
     * with the device. Emitted by transports that do encrypted I/O (notably
     * [BLE][org.meshtastic.sdk.transport.ble.BleTransport]) when the OS-level pairing dialog
     * may be visible to the user. While in this state, the transport's
     * `connect()` is still suspended — the engine has not yet started the
     * handshake clock, so callers can render a "Confirm pairing on your device"
     * UI without racing the handshake timeout.
     */
    public data object Bonding : TransportState

    /**
     * Transport is connected and frames can flow.
     */
    public data object Connected : TransportState

    /**
     * Transport encountered an error.
     *
     * @param cause the underlying exception
     * @param recoverable if `true`, the engine will attempt automatic reconnect; if `false`, the
     *   error is likely terminal (credentials, unsupported device, etc.)
     */
    public data class Error(val cause: Throwable, val recoverable: Boolean) : TransportState
}

/**
 * Interface that transports (BLE, TCP, serial, etc.) must implement.
 *
 * Transports are responsible for physical framing, buffering, and connection lifecycle.
 * The SDK codec handles higher-level protocol framing (sync bytes, length, CRC).
 *
 * **Single collector rule:** [frames] returns a cold flow that the SDK collects exactly once.
 * Transports must not support multiple concurrent collectors (by design; the SDK owns the reader).
 *
 * **Thread safety:** the SDK's engine actor calls [connect], [disconnect], [send],
 * and collects [frames]/[state] from a single coroutine context at a time. Transport
 * implementations therefore do not need internal locking against the engine, but they
 * **must** tolerate `disconnect()` being invoked at any point — including while a `connect()`,
 * `send()`, or frame collection is in flight on another coroutine — and clean up promptly.
 *
 * @since 0.1.0
 */
public interface RadioTransport {
    /**
     * The deterministic identity of this transport configuration.
     *
     * Used to key storage. Must remain the same for the lifetime of this transport instance.
     */
    public val identity: TransportIdentity

    /**
     * Initiate connection. May throw [MeshtasticException.Transport] on fatal errors.
     *
     * The transport should transition [state] to [TransportState.Connecting], then
     * [TransportState.Connected] when ready. State changes are observed by the engine to
     * coordinate handshake timing.
     *
     * **Cancellation:** if the calling coroutine is cancelled while [connect] is suspended,
     * the implementation must release any partially acquired OS resources (sockets, BLE
     * connections, USB handles) and rethrow
     * [kotlin.coroutines.cancellation.CancellationException]. The transport's [state] should
     * settle on [TransportState.Disconnected] (or [TransportState.Error]) after a cancelled
     * connect; it must not leak a "Connecting" state after the coroutine returns.
     */
    public suspend fun connect()

    /**
     * Disconnect gracefully.
     *
     * Should transition [state] to [TransportState.Disconnected].
     * Idempotent: safe to call when already disconnected.
     *
     * **Cancellation:** [disconnect] should run to completion even if the calling coroutine
     * is cancelled — implementations are encouraged to use
     * [kotlinx.coroutines.NonCancellable] internally for the resource-release path so the
     * physical link is always closed. Cancellation may still be observed before any suspending
     * I/O begins.
     */
    public suspend fun disconnect()

    /**
     * Send a framed packet synchronously to the device.
     *
     * May throw [MeshtasticException.Transport] for I/O failures.
     * The SDK sends frames sequentially; transports do not need to serialize themselves.
     *
     * **Cancellation:** if the caller's coroutine is cancelled mid-send the implementation
     * must rethrow [kotlin.coroutines.cancellation.CancellationException]. The frame may have
     * been partially written to the wire — the engine treats a cancelled send as "delivery
     * outcome unknown" and surfaces it via the corresponding [MessageHandle].
     */
    public suspend fun send(frame: Frame)

    /**
     * Observe inbound framed packets.
     *
     * Returns a cold flow that the SDK collects exactly once (at engine startup).
     * Each element is a complete frame as received from the device.
     *
     * The SDK handles frame buffering, overflow, and backpressure; transports should emit
     * frames promptly and let the flow system apply backpressure.
     */
    public fun frames(): Flow<Frame>

    /**
     * Observe transport connection state.
     *
     * A [StateFlow] so subscribers always see the current state and receive updates when it
     * changes. The engine collects this from startup to interpret handshake timing and detect
     * disconnects.
     */
    public val state: StateFlow<TransportState>
}
