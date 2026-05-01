/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:Suppress("LongParameterList", "TooManyFunctions")

package org.meshtastic.sdk

/**
 * Sealed hierarchy of Meshtastic SDK exceptions.
 *
 * Every subclass exposes optional [transportIdentity] and [operation] fields populated from the
 * engine and transport modules so callers can attribute a failure without parsing the message
 * string. Use [tag] to add context fluently when constructing from helpers.
 */
public sealed class MeshtasticException(message: String, cause: Throwable?) : Exception(message, cause) {

    /** The [TransportIdentity] that produced this failure, if known. */
    public var transportIdentity: TransportIdentity? = null
        internal set

    /** A short operation tag (e.g. `"connect"`, `"engine.disconnect"`). */
    public var operation: String? = null
        internal set

    /** Attach diagnostic context. Returns `this` for fluent use. */
    @Suppress("UNCHECKED_CAST")
    public fun <T : MeshtasticException> T.withContext(
        transportIdentity: TransportIdentity? = null,
        operation: String? = null,
    ): T {
        if (transportIdentity != null) this.transportIdentity = transportIdentity
        if (operation != null) this.operation = operation
        return this
    }

    /** Transport-level error: connection refused, network unreachable, I/O failure, etc. */
    public class Transport(reason: String, cause: Throwable? = null) : MeshtasticException(reason, cause)

    /** Protocol violation: malformed wire data, handshake failure, unexpected packet structure. */
    public class Protocol(reason: String, cause: Throwable? = null) : MeshtasticException(reason, cause)

    /** Handshake timed out before completing. */
    public class HandshakeTimeout(public val stage: String) :
        MeshtasticException("Handshake timed out during stage: $stage", null)

    /** Storage backend became unavailable during operation. */
    public class StorageUnavailable(message: String = "Storage unavailable", cause: Throwable? = null) :
        MeshtasticException(message, cause)

    /** Device reports a firmware version incompatible with this SDK. */
    public class FirmwareTooOld(public val required: Int, public val present: Int) :
        MeshtasticException("Firmware requires newer client (need $required, have $present)", null)

    /** Operation attempted while the client is not connected. */
    public class NotConnected : MeshtasticException("Client not connected", null)

    /** `connect()` called while already connected. */
    public class AlreadyConnected : MeshtasticException("Client already connected", null)

    /** Payload exceeds the device's maximum data length (typically 233 bytes). */
    public class PayloadTooLarge(public val maxBytes: Int) :
        MeshtasticException("Payload exceeds $maxBytes bytes", null)

    /** Helpers for attaching diagnostic context. */
    public companion object {
        /**
         * Attach diagnostic context to an exception. Returns the same instance for chaining.
         *
         * Example:
         * ```
         * throw MeshtasticException.tag(MeshtasticException.Transport("io error"), identity, "connect")
         * ```
         */
        public fun <T : MeshtasticException> tag(
            error: T,
            transportIdentity: TransportIdentity? = null,
            operation: String? = null,
        ): T {
            if (transportIdentity != null) error.transportIdentity = transportIdentity
            if (operation != null) error.operation = operation
            return error
        }
    }
}
