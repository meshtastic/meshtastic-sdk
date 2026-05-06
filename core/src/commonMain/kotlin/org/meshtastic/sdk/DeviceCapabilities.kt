/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

/**
 * Parsed firmware version value used for capability comparisons.
 *
 * @since 0.1.0
 */
public data class DeviceVersion(val versionString: String) : Comparable<DeviceVersion> {
    /** Integer representation (e.g., "2.7.12" → 20712). */
    public val asInt: Int = parseVersion(versionString)

    override fun compareTo(other: DeviceVersion): Int = asInt.compareTo(other.asInt)

    public companion object {
        public val MIN_SUPPORTED: DeviceVersion = DeviceVersion("2.5.14")
        public val ABS_MIN_SUPPORTED: DeviceVersion = DeviceVersion("2.3.15")

        private fun parseVersion(s: String): Int {
            val normalized = if (s.count { it == '.' } == 1) "$s.0" else s
            val match = Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{1,2})").find(normalized) ?: return 0
            val (major, minor, patch) = match.destructured
            return major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
        }
    }
}

/**
 * Firmware capability flags derived from a device's reported firmware version.
 *
 * @since 0.1.0
 */
public data class DeviceCapabilities(val firmwareVersion: String?) {
    private val version: DeviceVersion? = firmwareVersion?.let { DeviceVersion(it) }

    private fun atLeast(min: DeviceVersion): Boolean = version != null && version >= min

    /** Node muting via admin messages. Since 2.7.18. */
    public val canMuteNode: Boolean get() = atLeast(V2_7_18)

    /** Verified shared contacts. Since 2.7.12. */
    public val canSendVerifiedContacts: Boolean get() = atLeast(V2_7_12)

    /** Device telemetry toggle in module config. Since 2.7.12. */
    public val canToggleTelemetryEnabled: Boolean get() = atLeast(V2_7_12)

    /** is_unmessageable flag. Since 2.6.9. */
    public val canToggleUnmessageable: Boolean get() = atLeast(V2_6_9)

    /** QR code contact sharing. Since 2.6.8. */
    public val supportsQrCodeSharing: Boolean get() = atLeast(V2_6_8)

    /** Status message module. Since 2.8.0. */
    public val supportsStatusMessage: Boolean get() = atLeast(V2_8_0)

    /** Traffic management config. Since 3.0.0. */
    public val supportsTrafficManagementConfig: Boolean get() = atLeast(V3_0_0)

    /** TAK module config. Since 2.7.19. */
    public val supportsTakConfig: Boolean get() = atLeast(V2_7_19)

    /** Location sharing on secondary channels. Since 2.6.10. */
    public val supportsSecondaryChannelLocation: Boolean get() = atLeast(V2_6_10)

    /** ESP32 unified OTA. Since 2.7.18. */
    public val supportsEsp32Ota: Boolean get() = atLeast(V2_7_18)

    private companion object {
        val V2_6_8: DeviceVersion = DeviceVersion("2.6.8")
        val V2_6_9: DeviceVersion = DeviceVersion("2.6.9")
        val V2_6_10: DeviceVersion = DeviceVersion("2.6.10")
        val V2_7_12: DeviceVersion = DeviceVersion("2.7.12")
        val V2_7_18: DeviceVersion = DeviceVersion("2.7.18")
        val V2_7_19: DeviceVersion = DeviceVersion("2.7.19")
        val V2_8_0: DeviceVersion = DeviceVersion("2.8.0")
        val V3_0_0: DeviceVersion = DeviceVersion("3.0.0")
    }
}
