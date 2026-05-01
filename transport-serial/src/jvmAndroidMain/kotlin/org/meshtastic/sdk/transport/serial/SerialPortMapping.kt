/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial

import com.fazecast.jSerialComm.SerialPort as JSerialPort

/**
 * Maps a jSerialComm `SerialPort` instance to our public [SerialPortInfo].
 *
 * Shared between JVM (where ports come from `SerialPort.getCommPorts()`) and
 * Android (where the port is constructed from a permissioned
 * `UsbDeviceConnection`).
 */
internal fun JSerialPort.toSerialPortInfo(name: String = systemPortName): SerialPortInfo {
    val vid = vendorID.takeIf { it >= 0 }
    val pid = productID.takeIf { it >= 0 }
    val transport = if (vid != null || pid != null) SerialPortTransport.Usb else SerialPortTransport.Native
    return SerialPortInfo(
        name = name,
        description = portDescription?.takeUnless { it.isBlank() }
            ?: descriptivePortName?.takeUnless { it.isBlank() },
        transport = transport,
        usbVendorId = vid,
        usbProductId = pid,
        usbManufacturer = manufacturer?.takeUnless { it.isBlank() },
        usbProduct = null,
        usbSerialNumber = serialNumber?.takeUnless { it.isBlank() },
    )
}
