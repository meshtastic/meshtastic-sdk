/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.internal

/**
 * OS-level BLE bonding introspection for `scan ble` and `probe ble`.
 *
 * Returns `null` when we cannot reliably correlate OS-reported bond state to Kable's peripheral
 * identifier (e.g. macOS exposes MACs while Kable's identifier is a CoreBluetooth UUID).
 * Callers should treat `null` as "don't enforce, just remind the user".
 */
internal object BleBonding {
    private var cached: Set<String>? = null
    private var computed: Boolean = false

    fun addresses(): Set<String>? {
        if (computed) return cached
        val os = System.getProperty("os.name").lowercase()
        cached = when {
            os.contains("mac") -> null
            os.contains("linux") -> readBluetoothctl()
            else -> null
        }
        computed = true
        return cached
    }

    private fun readBluetoothctl(): Set<String> = try {
        val proc = ProcessBuilder("bluetoothctl", "paired-devices")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        val macPattern = Regex("""Device\s+([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})""")
        output.lineSequence()
            .mapNotNull { macPattern.find(it)?.groupValues?.get(1)?.lowercase() }
            .toSet()
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
        emptySet()
    }
}
