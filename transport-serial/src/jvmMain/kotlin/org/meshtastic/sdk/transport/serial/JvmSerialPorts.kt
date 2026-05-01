/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial

import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportSpec
import org.meshtastic.sdk.transport.serial.internal.JSerialCommTransport
import com.fazecast.jSerialComm.SerialPort as JSerialPort

/**
 * JVM-side serial port enumeration and transport construction via jSerialComm.
 *
 * Discovery uses `SerialPort.getCommPorts()`. On macOS the OS exposes USB serial
 * adapters under both `/dev/cu.*` (call-out, non-blocking open) and `/dev/tty.*`
 * (DCD-blocking dial-in) — for outbound radio traffic we always want the `cu.*`
 * variant; opening `tty.*` against a USB-CDC radio blocks until DCD asserts and
 * never returns. [list] therefore filters the `tty.*` duplicates out on macOS.
 * Linux and Windows are unaffected.
 */
public object JvmSerialPorts {

    /** Enumerate all serial ports visible to the host operating system. */
    public fun list(): List<SerialPortInfo> {
        val ports = JSerialPort.getCommPorts().map { it.toSerialPortInfo() }
        // TX-12 (audit): drop /dev/tty.* on macOS — they're the DCD-blocking
        // siblings of the /dev/cu.* call-out devices and would hang openPort()
        // forever against a USB-CDC Meshtastic radio.
        return if (isMacOs()) {
            ports.filterNot { it.name.startsWith("/dev/tty.") }
        } else {
            ports
        }
    }

    /**
     * Build a [RadioTransport] that opens the named serial port at [baudRate].
     * Default baud is 115 200 — the rate used by Meshtastic firmware.
     */
    public fun open(portName: String, baudRate: Int = DEFAULT_BAUD): RadioTransport {
        val port = JSerialPort.getCommPort(portName)
        val identity = TransportIdentity.of(TransportSpec.SerialJvm(portName))
        return JSerialCommTransport(port, identity, baudRate)
    }

    public const val DEFAULT_BAUD: Int = 115_200

    private fun isMacOs(): Boolean = System.getProperty("os.name")?.startsWith("Mac", ignoreCase = true) == true
}
