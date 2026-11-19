/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial

/**
 * USB vendor IDs that Meshtastic radios enumerate with: the nRF52 and RP2040 native USB stacks,
 * Espressif native USB, Seeed's boards, and the CP210x, CH34x and FTDI USB-UART bridges most ESP32
 * boards carry. The bridge chips are generic, so a match means "plausibly a Meshtastic radio", not
 * proof.
 *
 * @since 0.3.0
 */
public val MESHTASTIC_USB_VENDOR_IDS: Set<Int> = setOf(
    0x239A, // Adafruit / RAK nRF52 TinyUSB
    0x303A, // Espressif native USB / USB-Serial-JTAG
    0x10C4, // Silicon Labs CP210x
    0x1A86, // QinHeng CH340 / CH9102
    0x0403, // FTDI
    0x2886, // Seeed Studio
    0x2E8A, // Raspberry Pi RP2040
)

/**
 * True when this port is a USB serial device whose vendor ID is one Meshtastic radios use.
 *
 * @since 0.3.0
 */
public fun SerialPortInfo.isLikelyMeshtastic(): Boolean =
    usbVendorId != null && usbVendorId in MESHTASTIC_USB_VENDOR_IDS
