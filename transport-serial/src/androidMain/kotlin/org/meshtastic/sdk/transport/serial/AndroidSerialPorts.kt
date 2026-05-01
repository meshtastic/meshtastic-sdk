/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:Suppress("TooManyFunctions")

package org.meshtastic.sdk.transport.serial

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportSpec
import org.meshtastic.sdk.transport.serial.internal.JSerialCommTransport
import com.fazecast.jSerialComm.SerialPort as JSerialPort

/**
 * Android-side USB serial discovery and transport construction.
 *
 * jSerialComm 2.11.x on Android relies on
 * [JSerialPort.setAndroidContext] being called once with an `Application`
 * (or other long-lived) [Context] so the library can resolve the system
 * `UsbManager` and request opens against permissioned [UsbDevice]s. The
 * caller is responsible for the standard
 * `UsbManager.requestPermission(device, pendingIntent)` flow before the
 * device shows up in [list] and can be opened.
 *
 * Required `AndroidManifest.xml` entries for the host app:
 * ```xml
 * <uses-feature android:name="android.hardware.usb.host" />
 * <intent-filter>
 *   <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
 * </intent-filter>
 * ```
 */
public object AndroidSerialPorts {

    /**
     * Bind jSerialComm to the supplied Android [Context]. Safe to call more
     * than once; only the most recent context is retained. Should typically
     * be passed an Application context.
     */
    public fun init(context: Context) {
        JSerialPort.setAndroidContext(context.applicationContext)
    }

    /**
     * Enumerate USB serial ports the host app currently has permission for.
     *
     * Devices the user has not yet granted permission for will not appear
     * here — call [usbDevices] to discover candidates and prompt the user
     * via `UsbManager.requestPermission` before re-listing.
     */
    public fun list(): List<SerialPortInfo> = JSerialPort.getCommPorts().map { it.toSerialPortInfo() }

    /**
     * Convenience pass-through for enumerating raw [UsbDevice]s the system
     * exposes (regardless of permission state). Useful for building a
     * permission-prompt picker UI.
     */
    public fun usbDevices(context: Context): Collection<UsbDevice> {
        val mgr = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return mgr.deviceList.values
    }

    /**
     * Open the named serial port. The port name is the
     * [SerialPortInfo.name] value returned by [list]; under the hood
     * jSerialComm derives this from the underlying USB device.
     *
     * @throws MeshtasticException.Transport if the port cannot be located
     *   (most often because permission has not yet been granted, or the
     *   device has been physically detached).
     */
    public fun open(portName: String, baudRate: Int = DEFAULT_BAUD): RadioTransport {
        val port = runCatching { JSerialPort.getCommPort(portName) }.getOrElse { e ->
            throw MeshtasticException.Transport(
                "Serial port `$portName` not found (missing USB permission?)",
                e,
            )
        }
        val identity = TransportIdentity.of(TransportSpec.SerialAndroid(portName))
        return JSerialCommTransport(port, identity, baudRate)
    }

    public const val DEFAULT_BAUD: Int = 115_200
}
