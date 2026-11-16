/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.tcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val IPV4_OCTETS = 4
private const val MAX_PORT = 65_535
private const val AF_INET_DARWIN = 2
private const val AF_INET6_DARWIN = 30
private const val SOCKADDR_IN_SIZE = 8
private const val SOCKADDR_IN6_SIZE = 24
private const val IPV6_GROUPS = 8
private const val BYTE_MASK = 0xFF
private const val BYTE_BITS = 8
private const val LINK_LOCAL_MASK = 0xFFC0
private const val LINK_LOCAL_PREFIX = 0xFE80

/**
 * True when [type] names `_meshtastic._tcp`, tolerating the spellings platforms report: a
 * leading dot (some Android releases), a trailing dot, a `.local` suffix and any letter case.
 */
internal fun isMeshtasticServiceType(type: String?): Boolean {
    if (type == null) return false
    val normalized = type.trim().lowercase().trim('.').removeSuffix(".local").trim('.')
    return normalized == TcpDiscovery.SERVICE_TYPE
}

/** Decodes TXT records as UTF-8; a key-only record (null or empty value) maps to `""`. */
internal fun decodeTxtAttributes(raw: Map<String, ByteArray?>): Map<String, String> =
    raw.entries.associate { (key, value) -> key to (value?.decodeToString() ?: "") }

internal fun isIpv4Literal(address: String): Boolean {
    val parts = address.split('.')
    return parts.size == IPV4_OCTETS && parts.all { p -> p.toIntOrNull()?.let { it in 0..BYTE_MASK } == true }
}

/** First hextet of an IPv6 literal, or `null` when [address] is not parseable as one. */
private fun firstHextet(address: String): Int? {
    if (':' !in address) return null
    val head = address.substringBefore(':')
    if (head.isEmpty()) return 0 // "::" and "::1"
    return head.toIntOrNull(radix = 16)
}

internal fun isLinkLocalIpv6(address: String): Boolean =
    firstHextet(address)?.let { it and LINK_LOCAL_MASK == LINK_LOCAL_PREFIX } == true

/**
 * Cleans a platform's address strings into dialable literals: brackets and `%scope` zones
 * stripped, duplicates and blanks removed, link-local IPv6 dropped, IPv4 ordered before IPv6.
 */
internal fun normalizeAddresses(raw: List<String>): List<String> {
    val cleaned = raw
        .map { it.trim().removePrefix("[").removeSuffix("]").substringBefore('%') }
        .filter { it.isNotEmpty() && (isIpv4Literal(it) || ':' in it) && !isLinkLocalIpv6(it) }
        .distinct()
    return cleaned.filter(::isIpv4Literal) + cleaned.filterNot(::isIpv4Literal)
}

/**
 * Builds the public type from one platform-resolved service, or `null` when it is not a usable
 * Meshtastic radio: wrong service type, no dialable address, or a port outside 1..65535.
 */
internal fun buildDiscoveredRadio(
    serviceName: String,
    serviceType: String?,
    addresses: List<String>,
    port: Int,
    txt: Map<String, ByteArray?>,
): DiscoveredTcpRadio? {
    if (!isMeshtasticServiceType(serviceType)) return null
    if (serviceName.isEmpty() || port !in 1..MAX_PORT) return null
    val usable = normalizeAddresses(addresses)
    if (usable.isEmpty()) return null
    return DiscoveredTcpRadio(serviceName, usable, port, decodeTxtAttributes(txt))
}

/**
 * Formats a Darwin `sockaddr` (`sockaddr_in` / `sockaddr_in6`, layout `len, family, port, ...`)
 * as an IP literal, or `null` for other families or truncated input. Scope IDs are not part of
 * the result.
 */
internal fun parseDarwinSockaddr(bytes: ByteArray): String? {
    if (bytes.size < 2) return null
    return when (bytes[1].toInt() and BYTE_MASK) {
        AF_INET_DARWIN -> if (bytes.size < SOCKADDR_IN_SIZE) {
            null
        } else {
            (4..7).joinToString(".") { (bytes[it].toInt() and BYTE_MASK).toString() }
        }

        AF_INET6_DARWIN -> if (bytes.size < SOCKADDR_IN6_SIZE) {
            null
        } else {
            (0 until IPV6_GROUPS).joinToString(":") { group ->
                val hi = bytes[8 + group * 2].toInt() and BYTE_MASK
                val lo = bytes[8 + group * 2 + 1].toInt() and BYTE_MASK
                ((hi shl BYTE_BITS) or lo).toString(radix = 16)
            }
        }

        else -> null
    }
}

/**
 * Drops a `Found` that repeats the last one reported for the same service name. Platform browsers
 * re-announce a resolved service unchanged; only a changed radio is a new event. Single-collector
 * state, so it needs no synchronization.
 */
internal class DiscoveryEventFilter {
    private val reported = HashMap<String, DiscoveredTcpRadio>()

    /** Returns [event] when it should reach the collector, else `null`. */
    fun accept(event: TcpDiscoveryEvent): TcpDiscoveryEvent? = when (event) {
        is TcpDiscoveryEvent.Found ->
            if (reported[event.radio.serviceName] == event.radio) {
                null
            } else {
                reported[event.radio.serviceName] = event.radio
                event
            }

        is TcpDiscoveryEvent.Lost -> if (reported.remove(event.serviceName) != null) event else null
    }
}

/** Applies a fresh [DiscoveryEventFilter] to each collection. */
internal fun Flow<TcpDiscoveryEvent>.distinctDiscoveryEvents(): Flow<TcpDiscoveryEvent> = flow {
    val filter = DiscoveryEventFilter()
    collect { event -> filter.accept(event)?.let { emit(it) } }
}
