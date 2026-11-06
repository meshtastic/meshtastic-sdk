/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.meshtastic.sdk.LogSink
import org.meshtastic.sdk.MeshtasticException
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportSpec
import org.meshtastic.sdk.transport.serial.internal.UsbSerialForAndroidTransport
import java.io.IOException

/**
 * Android USB serial over [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android),
 * the driver stack Meshtastic-Android ships. The alternative to [AndroidSerialPorts] (jSerialComm)
 * for hosts that already carry usb-serial-for-android or need its driver table.
 *
 * The library is a `compileOnly` dependency of this module because it is published on JitPack,
 * not Maven Central: a host that calls into this object adds
 * `com.github.mik3y:usb-serial-for-android` and the JitPack repository itself. Hosts that use
 * [AndroidSerialPorts] or no serial at all need neither.
 *
 * The host owns the `UsbManager.requestPermission(device, pendingIntent)` flow; [open] fails with
 * [MeshtasticException.Transport] when permission has not been granted. The manifest needs
 * `<uses-feature android:name="android.hardware.usb.host" />`.
 *
 * @since 0.3.0
 */
public object AndroidUsbSerialPorts {

    /** The Meshtastic firmware's USB CDC / UART rate. */
    public const val DEFAULT_BAUD: Int = 115_200

    private const val VID_ADAFRUIT = 0x239A
    private const val PID_RAK4631 = 0x8029
    private const val PID_NRF52840_CDC = 0x4405
    private const val VID_WCH = 0x1A86
    private const val PID_TBEAM_V11 = 0x55D4

    /**
     * usb-serial-for-android's default table plus the native USB-CDC boards Meshtastic-Android
     * adds: RAK4631, LilyGo T-Beam v1.1, and the nRF52840 CDC identity shared by the T-Echo and
     * the Elecrow ThinkNode M3/M4/M6.
     */
    private val prober: UsbSerialProber by lazy {
        UsbSerialProber(
            UsbSerialProber.getDefaultProbeTable().apply {
                addProduct(VID_ADAFRUIT, PID_RAK4631, CdcAcmSerialDriver::class.java)
                addProduct(VID_WCH, PID_TBEAM_V11, CdcAcmSerialDriver::class.java)
                addProduct(VID_ADAFRUIT, PID_NRF52840_CDC, CdcAcmSerialDriver::class.java)
            },
        )
    }

    /**
     * Attached USB devices a usb-serial-for-android driver claims, permission or not. Permission
     * gates [open], not listing, so this is the picker's source; `usbSerialNumber` is `null`
     * until permission is granted because Android withholds it.
     */
    public fun list(context: Context): List<SerialPortInfo> =
        prober.findAllDrivers(context.usbManager()).map { it.device.toSerialPortInfo() }

    /**
     * Open [device] after the host has been granted permission for it.
     *
     * @throws MeshtasticException.Transport if no driver claims the device or permission is missing
     */
    public fun open(
        context: Context,
        device: UsbDevice,
        baudRate: Int = DEFAULT_BAUD,
        logger: LogSink = LogSink.Silent,
    ): RadioTransport {
        val manager = context.usbManager()
        val driver = prober.probeDevice(device) ?: throw MeshtasticException.Transport(
            "No usb-serial driver for ${device.deviceName} " +
                "(VID ${device.vendorId.hex()} PID ${device.productId.hex()})",
        )
        val openPort: () -> UsbSerialPort = {
            val connection = manager.openDevice(device) ?: throw MeshtasticException.Transport(
                "USB permission not granted for ${device.deviceName}",
            )
            val port = driver.ports.first()
            try {
                port.open(connection)
            } catch (e: IOException) {
                connection.close()
                throw e
            }
            port
        }
        return UsbSerialForAndroidTransport(
            openPort = openPort,
            identity = TransportIdentity.of(TransportSpec.SerialAndroid(device.deviceName)),
            baudRate = baudRate,
            logger = logger,
        )
    }

    /**
     * Open the device whose `UsbDevice.getDeviceName()` is [deviceName], the [SerialPortInfo.name]
     * that [list] reports.
     *
     * @throws MeshtasticException.Transport if the device is no longer attached
     */
    public fun open(
        context: Context,
        deviceName: String,
        baudRate: Int = DEFAULT_BAUD,
        logger: LogSink = LogSink.Silent,
    ): RadioTransport {
        val device = context.usbManager().deviceList[deviceName]
            ?: throw MeshtasticException.Transport("USB device `$deviceName` is not attached")
        return open(context, device, baudRate, logger)
    }

    private fun Context.usbManager(): UsbManager = getSystemService(Context.USB_SERVICE) as UsbManager

    private fun UsbDevice.toSerialPortInfo(): SerialPortInfo = SerialPortInfo(
        name = deviceName,
        description = productName,
        transport = SerialPortTransport.Usb,
        usbVendorId = vendorId,
        usbProductId = productId,
        usbManufacturer = manufacturerName,
        usbProduct = productName,
        // Android 10+ throws SecurityException here until the host holds permission.
        usbSerialNumber = runCatching { serialNumber }.getOrNull(),
    )

    private fun Int.hex(): String = "0x" + toString(HEX_RADIX).uppercase().padStart(HEX_WIDTH, '0')

    private const val HEX_RADIX = 16
    private const val HEX_WIDTH = 4
}
