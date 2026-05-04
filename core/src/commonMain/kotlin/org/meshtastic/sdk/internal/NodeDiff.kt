/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.NodeField

/**
 * Compares [previous] and [current] [NodeInfo] instances and returns the set of [NodeField]s
 * that differ. Returns an empty set only if both instances are semantically identical.
 */
internal fun diffNodeFields(previous: NodeInfo, current: NodeInfo): Set<NodeField> {
    val changed = mutableSetOf<NodeField>()

    if (previous.user != current.user) {
        changed += NodeField.User
        // Name is a subset of User — flag it if the display identifiers changed.
        if (previous.user?.long_name != current.user?.long_name ||
            previous.user?.short_name != current.user?.short_name
        ) {
            changed += NodeField.Name
        }
    }

    if (previous.position != current.position) {
        changed += NodeField.Position
    }

    if (previous.snr != current.snr || previous.hops_away != current.hops_away ||
        previous.via_mqtt != current.via_mqtt
    ) {
        changed += NodeField.SignalQuality
    }

    if (previous.device_metrics != current.device_metrics) {
        // Split battery from general telemetry for finer-grained UI updates.
        if (previous.device_metrics?.battery_level != current.device_metrics?.battery_level ||
            previous.device_metrics?.voltage != current.device_metrics?.voltage
        ) {
            changed += NodeField.Battery
        }
        changed += NodeField.Telemetry
    }

    if (previous.last_heard != current.last_heard) {
        changed += NodeField.LastSeen
    }

    if (previous.channel != current.channel) {
        changed += NodeField.Other
    }

    if (previous.is_favorite != current.is_favorite ||
        previous.is_ignored != current.is_ignored ||
        previous.is_muted != current.is_muted ||
        previous.is_key_manually_verified != current.is_key_manually_verified
    ) {
        changed += NodeField.Other
    }

    // Defensive fallback: if the objects aren't equal but nothing was categorized, flag Other.
    if (changed.isEmpty() && previous != current) {
        changed += NodeField.Other
    }

    return changed
}
