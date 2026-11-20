/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.tcp

import kotlinx.coroutines.flow.Flow
import org.meshtastic.sdk.TransportSpec

/**
 * Discovers Meshtastic radios that advertise their TCP PhoneAPI over mDNS / DNS-SD.
 *
 * Firmware with WiFi or Ethernet publishes a `_meshtastic._tcp` service on port 4403 with the TXT
 * records `shortname`, `id` and `pio_env`. Each platform has its own implementation, built with
 * that platform's constructor:
 *
 * | Target | Implementation | Backend |
 * |---|---|---|
 * | JVM | `JvmTcpDiscovery` | JmDNS |
 * | Android | `AndroidTcpDiscovery(context)` | `NsdManager` |
 * | iOS | `IosTcpDiscovery` | `NSNetServiceBrowser` |
 *
 * "Discover, pick, connect" is `discovery.discover()`, then [DiscoveredTcpRadio.toTransport].
 *
 * @since 0.3.0
 */
public interface TcpDiscovery {

    /**
     * Cold flow of discovery events. Browsing starts when the flow is collected and stops, with
     * the platform resources released, when the collector cancels. Each collection runs its own
     * browse session.
     *
     * Events are deltas, not snapshots, so a consumer building a picker handles each radio once:
     * [TcpDiscoveryEvent.Found] when a service is resolved to an address and
     * [TcpDiscoveryEvent.Lost] when it disappears. A radio whose address or TXT records change
     * emits a further `Found` with the same [DiscoveredTcpRadio.serviceName], which replaces the
     * earlier entry. Services that fail to resolve, or that carry no usable address, are not
     * reported.
     *
     * The flow never completes on its own. It fails with
     * [org.meshtastic.sdk.MeshtasticException.Transport] when browsing cannot start (platform
     * permission or network failure) or when the target has no implementation.
     */
    public fun discover(): Flow<TcpDiscoveryEvent>

    /** Service type and port constants shared by every implementation. */
    public companion object {
        /** DNS-SD service type the firmware advertises (`MDNS.addService("meshtastic", "tcp", ...)`). */
        public const val SERVICE_TYPE: String = "_meshtastic._tcp"

        /** Port the firmware advertises and the TCP PhoneAPI listens on. */
        public const val DEFAULT_PORT: Int = 4403
    }
}

/**
 * One change in the set of radios a [TcpDiscovery] has seen.
 *
 * @since 0.3.0
 */
public sealed interface TcpDiscoveryEvent {

    /**
     * A radio was resolved, or an already-known radio's address or TXT records changed.
     *
     * @property radio the resolved radio; replaces any earlier entry with the same
     *   [DiscoveredTcpRadio.serviceName].
     * @since 0.3.0
     */
    public data class Found(val radio: DiscoveredTcpRadio) : TcpDiscoveryEvent

    /**
     * A previously advertised radio went away (goodbye packet or TTL expiry).
     *
     * @property serviceName the [DiscoveredTcpRadio.serviceName] of the radio that disappeared.
     * @since 0.3.0
     */
    public data class Lost(val serviceName: String) : TcpDiscoveryEvent
}

/**
 * A Meshtastic radio found over mDNS, resolved to at least one address.
 *
 * @property serviceName DNS-SD instance name, unique on the link (the firmware uses `Meshtastic`,
 *   so the platform appends a suffix when several radios share a network).
 * @property addresses IP literals, IPv4 first. Link-local IPv6 addresses are dropped because
 *   they cannot be dialed without a scope ID. Never empty.
 * @property port TCP port of the PhoneAPI.
 * @property attributes TXT records decoded as UTF-8. A key with no value maps to the empty string.
 * @since 0.3.0
 */
public data class DiscoveredTcpRadio(
    val serviceName: String,
    val addresses: List<String>,
    val port: Int,
    val attributes: Map<String, String>,
) {
    init {
        require(addresses.isNotEmpty()) { "A discovered radio needs at least one address" }
    }

    /** First entry of [addresses]; the address [toTransportSpec] and [toTransport] connect to. */
    public val host: String
        get() = addresses.first()

    /** The node's short name from the `shortname` TXT record, or `null` when absent. */
    public val shortName: String?
        get() = attributes[TXT_SHORTNAME]

    /** The node ID (`!aabbccdd`) from the `id` TXT record, or `null` when absent. */
    public val nodeId: String?
        get() = attributes[TXT_ID]

    /** The firmware build environment from the `pio_env` TXT record, or `null` when absent. */
    public val pioEnv: String?
        get() = attributes[TXT_PIO_ENV]

    /** The [TransportSpec.Tcp] for this radio, for `RadioClient` builders and storage identity. */
    public fun toTransportSpec(): TransportSpec.Tcp = TransportSpec.Tcp(host, port)

    /** A new, unconnected [TcpTransport] aimed at [host] and [port]. */
    public fun toTransport(): TcpTransport = TcpTransport(host, port)

    internal companion object {
        const val TXT_SHORTNAME: String = "shortname"
        const val TXT_ID: String = "id"
        const val TXT_PIO_ENV: String = "pio_env"
    }
}
