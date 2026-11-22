/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

/**
 * True when a BLE advertisement matches [needle], the text after `ble:` in a transport target.
 *
 * An empty needle matches everything. Otherwise the match is a case-insensitive substring of the
 * advertised [name], the [peripheralName] or the platform [identifier]. A MAC needle also matches
 * where the identifier holds a BlueZ D-Bus object path (Kable's JVM identifier on Linux is
 * `{"object_path":"/org/bluez/hci0/dev_34_B7_DA_62_18_C5"}`), which spells the MAC with underscores.
 *
 * @since 0.3.0
 */
public fun bleAdvertisementMatches(
    needle: String,
    name: String?,
    peripheralName: String?,
    identifier: String,
): Boolean {
    if (needle.isBlank()) return true
    val wanted = needle.lowercase()
    val wantedAsPath = wanted.replace(':', '_')
    val id = identifier.lowercase()
    return name?.lowercase()?.contains(wanted) == true ||
        peripheralName?.lowercase()?.contains(wanted) == true ||
        id.contains(wanted) ||
        id.contains(wantedAsPath)
}
