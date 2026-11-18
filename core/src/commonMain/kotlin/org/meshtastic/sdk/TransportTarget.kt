/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

/**
 * A transport named by a compact string, the form a CLI flag or an agent tool argument carries.
 * [parse] reads it; opening the matching transport is the host's job, since only the host knows
 * which transport modules it links.
 *
 * Syntax:
 *  - `ble:NEEDLE` — substring match against the advertised name, peripheral name or platform
 *    identifier; an empty needle means the first Meshtastic advertisement.
 *  - `tcp:HOST[:PORT]` — [DEFAULT_TCP_PORT] unless given.
 *  - `serial:PORT[:BAUD]` — [DEFAULT_SERIAL_BAUD] unless given. PORT is a bare name or an
 *    absolute path.
 *
 * @since 0.3.0
 */
public sealed interface TransportTarget {
    /** Human-readable label for logs. */
    public val label: String

    /** A BLE peripheral found by scanning for [needle]. */
    public data class Ble(val needle: String, val scanSeconds: Int = DEFAULT_BLE_SCAN_SECONDS) : TransportTarget {
        override val label: String get() = "ble ${needle.ifBlank { "<any>" }}"
    }

    /** A TCP endpoint, normally `meshtasticd` or a WiFi-enabled radio. */
    public data class Tcp(val host: String, val port: Int = DEFAULT_TCP_PORT) : TransportTarget {
        override val label: String get() = "tcp $host:$port"
    }

    /** A serial port. */
    public data class Serial(val port: String, val baud: Int = DEFAULT_SERIAL_BAUD) : TransportTarget {
        override val label: String get() = "serial $port@$baud"
    }

    public companion object {
        /** The Meshtastic PhoneAPI TCP port. */
        public const val DEFAULT_TCP_PORT: Int = 4403

        /** The baud rate Meshtastic firmware uses on its serial API. */
        public const val DEFAULT_SERIAL_BAUD: Int = 115_200

        /** How long a BLE target scans before giving up. */
        public const val DEFAULT_BLE_SCAN_SECONDS: Int = 8

        /** Parses [spec]. Returns `null` for an unknown scheme, a missing host or port name, or a malformed number. */
        public fun parse(spec: String): TransportTarget? {
            val colon = spec.indexOf(':')
            if (colon < 0) return null
            val rest = spec.substring(colon + 1)
            return when (spec.substring(0, colon).lowercase()) {
                "ble" -> Ble(needle = rest)
                "tcp" -> parseTcp(rest)
                "serial" -> parseSerial(rest)
                else -> null
            }
        }

        private fun parseTcp(rest: String): TransportTarget? {
            val parts = rest.split(":")
            val host = parts[0].takeIf { it.isNotBlank() } ?: return null
            val port = parts.getOrNull(1)?.let { it.toIntOrNull() ?: return null } ?: DEFAULT_TCP_PORT
            return Tcp(host, port)
        }

        private fun parseSerial(rest: String): TransportTarget? {
            val parts = rest.split(":")
            val port = parts[0].takeIf { it.isNotBlank() } ?: return null
            val baud = parts.getOrNull(1)?.let { it.toIntOrNull() ?: return null } ?: DEFAULT_SERIAL_BAUD
            return Serial(port, baud)
        }
    }
}
