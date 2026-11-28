/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial

import com.fazecast.jSerialComm.SerialPortInvalidPortException
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportSpec
import org.meshtastic.sdk.transport.serial.internal.JSerialCommTransport
import java.nio.file.Files
import java.nio.file.Path
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
        val kept = withoutDialInDuplicates(ports.map { it.name }, isMacOs()).toSet()
        return ports.filter { it.name in kept }
    }

    /**
     * Build a [RadioTransport] that opens the named serial port at [baudRate].
     * Default baud is 115 200 — the rate used by Meshtastic firmware.
     *
     * @throws MeshtasticException.Transport if no port by that name exists (the device was
     *   unplugged, or the name is wrong). jSerialComm checks the name on Linux and macOS; on
     *   Windows a missing port fails at [RadioTransport.connect] with the same exception.
     */
    @Throws(MeshtasticException.Transport::class)
    public fun open(portName: String, baudRate: Int = DEFAULT_BAUD): RadioTransport =
        openTransport(portName, baudRate, onTextLine = null)

    /**
     * Like [open], and [onTextLine] receives each line of text the device prints between frames,
     * which carries the firmware's serial debug log. It runs on the reader thread, so it must not
     * block; an exception it throws is swallowed.
     *
     * @since 0.3.0
     */
    @Throws(MeshtasticException.Transport::class)
    public fun open(portName: String, baudRate: Int, onTextLine: (String) -> Unit): RadioTransport =
        openTransport(portName, baudRate, onTextLine)

    private fun openTransport(portName: String, baudRate: Int, onTextLine: ((String) -> Unit)?): RadioTransport {
        val port = try {
            JSerialPort.getCommPort(portName)
        } catch (e: SerialPortInvalidPortException) {
            throw MeshtasticException.Transport("Serial port `$portName` not found", e)
        }
        val identity = TransportIdentity.of(TransportSpec.SerialJvm(portName))
        return JSerialCommTransport(port, identity, baudRate, onTextLine = onTextLine)
    }

    /**
     * The ports from [list] that look like Meshtastic radios, by USB vendor ID
     * ([MESHTASTIC_USB_VENDOR_IDS]).
     */
    public fun findMeshtastic(): List<SerialPortInfo> = list().filter { it.isLikelyMeshtastic() }

    /**
     * The stable `/dev/serial/by-id/...` path for [portName], or `null` when there is none (any
     * non-Linux host, or a port the kernel gives no by-id entry). `ttyACM*` numbers change across
     * replugs and reboots; the by-id name embeds the device serial and does not, so a bench that
     * addresses radios by it never opens the wrong one.
     */
    public fun byIdPath(portName: String): String? = runCatching {
        val byId = Path.of(BY_ID_DIR)
        if (!Files.isDirectory(byId)) return@runCatching null
        val device = Path.of(if (portName.startsWith("/")) portName else "/dev/$portName").toRealPath()
        Files.list(byId).use { entries ->
            entries.filter { runCatching { it.toRealPath() == device }.getOrDefault(false) }
                .map { it.toString() }
                .findFirst()
                .orElse(null)
        }
    }.getOrNull()

    public const val DEFAULT_BAUD: Int = 115_200

    private const val BY_ID_DIR = "/dev/serial/by-id"

    private fun isMacOs(): Boolean = System.getProperty("os.name")?.startsWith("Mac", ignoreCase = true) == true
}

/** [names] without the macOS `tty.*` siblings of the `cu.*` call-out ports; any other host keeps every port. */
internal fun withoutDialInDuplicates(names: List<String>, isMacOs: Boolean): List<String> =
    if (isMacOs) names.filterNot { it.startsWith("tty.") } else names
