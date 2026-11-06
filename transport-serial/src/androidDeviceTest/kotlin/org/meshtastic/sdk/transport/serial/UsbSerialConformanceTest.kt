/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.InMemoryStorageProvider
import org.meshtastic.sdk.LogLevel
import org.meshtastic.sdk.LogSink
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SendOutcome

/**
 * Real-hardware check of [AndroidUsbSerialPorts]: a Meshtastic radio on the phone's USB-C
 * (OTG), opened through usb-serial-for-android, handshake to `Connected`, config bundle,
 * a text to the radio's own node (handled locally, nothing goes over LoRa), disconnect.
 *
 * Skips when no device a driver claims is attached. The USB permission dialog cannot be
 * driven from adb; the test requests it and waits [PERMISSION_WAIT_MS] for a tap.
 */
class UsbSerialConformanceTest {

    @Test
    fun conformance_listOpenHandshakeSendDisconnect() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ports = AndroidUsbSerialPorts.list(context)
        Log.i(TAG, "USB serial candidates: $ports")
        assumeTrue("No usb-serial device attached — skipping", ports.isNotEmpty())
        val info = ports.first()

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = assertNotNull(manager.deviceList[info.name])
        if (!manager.hasPermission(device)) {
            Log.i(TAG, "Requesting USB permission for ${info.name}; tap Allow on the phone")
            assertTrue(
                "USB permission not granted within ${PERMISSION_WAIT_MS}ms",
                awaitPermission(context, manager, device),
            )
        }

        val client = RadioClient {
            transport(AndroidUsbSerialPorts.open(context, device, logger = logcatSink))
            storage(InMemoryStorageProvider())
            logger(logcatSink)
            protocolLogging(LogLevel.DEBUG)
        }
        val startMs = System.currentTimeMillis()
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { client.connect() }
            Log.i(TAG, "handshake completed in ${System.currentTimeMillis() - startMs}ms")
            assertEquals(ConnectionState.Connected, client.connection.value)

            val bundle = assertNotNull(client.configBundle.value)
            val myNode = NodeId(bundle.myInfo.my_node_num)
            Log.i(TAG, "own node $myNode firmware ${bundle.metadata.firmware_version}")
            assertTrue(bundle.myInfo.my_node_num != 0)

            val handle = client.sendText("usb-serial conformance", to = myNode)
            val outcome = withTimeoutOrNull(SEND_TIMEOUT_MS) { handle.await() }
            Log.i(TAG, "send to own node: $outcome")
            assertTrue("send to own node did not succeed: $outcome", outcome is SendOutcome.Success)
        } finally {
            client.disconnect()
        }
        assertEquals(ConnectionState.Disconnected, client.connection.value)
    }

    private suspend fun awaitPermission(
        context: Context,
        manager: UsbManager,
        device: android.hardware.usb.UsbDevice,
    ): Boolean {
        val granted = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    granted.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        try {
            manager.requestPermission(
                device,
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                    PendingIntent.FLAG_MUTABLE,
                ),
            )
            return withTimeoutOrNull(PERMISSION_WAIT_MS) { granted.await() } == true
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private val logcatSink = LogSink { level, tag, message, throwable ->
        when (level) {
            LogLevel.ERROR -> Log.e(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            else -> Log.d(tag, message, throwable)
        }
    }

    private companion object {
        const val TAG = "UsbSerialConformance"
        const val ACTION_USB_PERMISSION = "org.meshtastic.sdk.transport.serial.USB_PERMISSION"
        const val PERMISSION_WAIT_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 90_000L
        const val SEND_TIMEOUT_MS = 30_000L
    }
}
