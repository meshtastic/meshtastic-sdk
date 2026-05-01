/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial

/** Physical transport that backs an enumerated serial port. */
public enum class SerialPortTransport {
    /** Native platform serial port (e.g. an on-board UART). */
    Native,

    /** USB serial port adapter (CP210x, FTDI, CH34x, etc.). */
    Usb,

    /** Bluetooth serial port adapter. */
    Bluetooth,

    /** Unknown / not classified by the platform. */
    Unknown,
}

/**
 * Metadata for a single serial port discovered via jSerialComm.
 *
 * Identifier shape differs between targets:
 *   - On the JVM, [name] is a port-name string (e.g. `/dev/cu.usbserial-1410`,
 *     `COM3`) suitable for `SerialPort.getCommPort`.
 *   - On Android, [name] is the USB device name from `UsbDevice.getDeviceName`
 *     (e.g. `/dev/bus/usb/001/004`), and the host must additionally hold
 *     `UsbManager` permission for that device to open it.
 *
 * All non-name fields are best-effort: jSerialComm returns empty strings
 * when it cannot determine a value, and we surface that as `null` so consumers
 * can distinguish "unknown" from "intentionally empty".
 */
public data class SerialPortInfo(
    public val name: String,
    public val description: String? = null,
    public val transport: SerialPortTransport = SerialPortTransport.Unknown,
    public val usbVendorId: Int? = null,
    public val usbProductId: Int? = null,
    public val usbManufacturer: String? = null,
    public val usbProduct: String? = null,
    public val usbSerialNumber: String? = null,
)
