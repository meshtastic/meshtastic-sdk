/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.MeshtasticException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Maps the JVM's `os.name` to the btleplug backend Kable loads for it. */
internal fun desktopBleOs(osName: String = System.getProperty("os.name").orEmpty()): DesktopBleOs = when {
    osName.startsWith("Linux", ignoreCase = true) -> DesktopBleOs.Linux
    osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> DesktopBleOs.MacOs
    osName.startsWith("Windows", ignoreCase = true) -> DesktopBleOs.Windows
    else -> DesktopBleOs.Other
}

/** The desktop OS families whose Kable btleplug backend parses identifiers differently. */
internal enum class DesktopBleOs { Linux, MacOs, Windows, Other }

private val MAC_COLON = Regex("""[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}""")
private val MAC_PLAIN = Regex("""[0-9A-Fa-f]{12}""")
private val UUID_HYPHENATED = Regex("""[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}""")
private val UUID_PLAIN = Regex("""[0-9A-Fa-f]{32}""")

// JSON whitespace only, no escapes, and a D-Bus object path of one or more `[A-Za-z0-9_]` elements.
private val BLUEZ_IDENTIFIER_JSON =
    Regex("""[ \t\n\r]*\{[ \t\n\r]*"object_path"[ \t\n\r]*:[ \t\n\r]*"(?:/[A-Za-z0-9_]+)+"[ \t\n\r]*}[ \t\n\r]*""")

private val BLUEZ_DEVICE_SEGMENT = Regex("""/dev_([0-9A-Fa-f]{2}(?:_[0-9A-Fa-f]{2}){5})(?![0-9A-Za-z_])""")

/**
 * True when Kable's `String.toIdentifier()` accepts [address] on [os] without its native layer
 * panicking. Each accepted form is a strict subset of what kable-btleplug-ffi 0.46.0
 * (`peripheral_id.rs`) parses with `unwrap()`:
 *
 * - Linux: `serde_json::from_str` of btleplug's BlueZ `PeripheralId`, which serializes as
 *   `{"object_path":"/org/bluez/hci0/dev_C2_5D_2D_C4_E5_6E"}` (bluez-async `DeviceId`, whose
 *   path must be a valid D-Bus object path). A bare MAC is not JSON and panics.
 * - macOS: `uuid::Uuid::parse_str`, hyphenated or 32 hex digits.
 * - Windows: btleplug `BDAddr::from_str`, `AA:BB:CC:DD:EE:FF` or 12 hex digits.
 *
 * Kable ships no backend for any other OS, so nothing is accepted there.
 */
internal fun isKableIdentifier(address: String, os: DesktopBleOs): Boolean = when (os) {
    DesktopBleOs.Linux -> BLUEZ_IDENTIFIER_JSON.matches(address)
    DesktopBleOs.MacOs -> UUID_HYPHENATED.matches(address) || UUID_PLAIN.matches(address)
    DesktopBleOs.Windows -> MAC_COLON.matches(address) || MAC_PLAIN.matches(address)
    DesktopBleOs.Other -> false
}

/** The 12 upper-case hex digits of the MAC that [value] spells, as a MAC or a BlueZ device path. */
private fun macDigits(value: String): String? {
    val trimmed = value.trim()
    val digits = when {
        MAC_COLON.matches(trimmed) -> trimmed.replace(":", "")
        MAC_PLAIN.matches(trimmed) -> trimmed
        else -> BLUEZ_DEVICE_SEGMENT.find(trimmed)?.groupValues?.get(1)?.replace("_", "")
    }
    return digits?.uppercase()
}

/**
 * True when a scanned [identifier] (a `DiscoveredRadio.address`) names the radio [target] asks
 * for: the same string ignoring case, or the same whole MAC, however each side spells it.
 */
internal fun desktopBleAddressMatches(target: String, identifier: String): Boolean {
    if (target.trim().equals(identifier.trim(), ignoreCase = true)) return true
    val wanted = macDigits(target) ?: return false
    return wanted == macDigits(identifier)
}

/** How long `BleTransport(address)` scans for a radio it cannot address directly. */
internal val DESKTOP_SCAN_TIMEOUT: Duration = 15.seconds

/**
 * The first radio in [radios] whose address matches [target], scanning for at most [timeout].
 *
 * @throws MeshtasticException.Transport when none matches in time, or when the scan fails.
 */
internal suspend fun findAdvertisedRadio(
    target: String,
    radios: Flow<DiscoveredRadio>,
    timeout: Duration = DESKTOP_SCAN_TIMEOUT,
    os: DesktopBleOs = desktopBleOs(),
): DiscoveredRadio = withTimeoutOrNull(timeout) {
    radios.firstOrNull { desktopBleAddressMatches(target, it.address) }
} ?: throw MeshtasticException.Transport(
    buildString {
        append("No Meshtastic radio advertising as ").append(target).append(" within ").append(timeout)
        if (os == DesktopBleOs.MacOs && macDigits(target) != null) {
            append("; macOS identifies BLE peripherals by CoreBluetooth UUID, not MAC, so pass the address ")
            append("BleScanner.scan() reports")
        }
    },
)
