/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)

package org.meshtastic.sdk.transport.tcp

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.meshtastic.sdk.MeshtasticException
import platform.Foundation.NSData
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSRunLoop
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

private const val RESOLVE_TIMEOUT_SECONDS = 10.0

/**
 * [TcpDiscovery] for iOS, backed by `NSNetServiceBrowser` on the main run loop.
 *
 * Info.plist requirements, which a host must add (iOS 14 and later enforce them):
 * - `NSLocalNetworkUsageDescription`, the text of the local-network permission prompt.
 * - `NSBonjourServices` containing `_meshtastic._tcp`.
 *
 * When the user denies the permission the flow fails with
 * [MeshtasticException.Transport]. This implementation compiles for every iOS target but has
 * not been run on a device or simulator by the SDK's automated tests.
 *
 * @since 0.3.0
 */
public class IosTcpDiscovery : TcpDiscovery {

    override fun discover(): Flow<TcpDiscoveryEvent> = callbackFlow {
        val delegate = BrowserDelegate(this)
        val browser = NSNetServiceBrowser()
        browser.delegate = delegate
        // NSNetServiceBrowser delivers on the run loop it is scheduled on; keep it and the
        // delegate on the main thread for start, callbacks and stop.
        dispatch_async(dispatch_get_main_queue()) {
            browser.scheduleInRunLoop(NSRunLoop.mainRunLoop, forMode = NSDefaultRunLoopMode)
            browser.searchForServicesOfType("${TcpDiscovery.SERVICE_TYPE}.", inDomain = "local.")
        }
        awaitClose {
            dispatch_async(dispatch_get_main_queue()) {
                browser.stop()
                browser.delegate = null
                delegate.stopResolving()
            }
        }
    }
}

private class BrowserDelegate(private val scope: ProducerScope<TcpDiscoveryEvent>) :
    NSObject(),
    NSNetServiceBrowserDelegateProtocol,
    NSNetServiceDelegateProtocol {

    // Touched only on the main thread, where the run loop delivers every callback.
    private val services = mutableMapOf<String, NSNetService>()

    fun stopResolving() {
        services.values.forEach {
            it.stop()
            it.delegate = null
        }
        services.clear()
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
        services[didFindService.name]?.let {
            it.stop()
            it.delegate = null
        }
        services[didFindService.name] = didFindService
        didFindService.delegate = this
        didFindService.resolveWithTimeout(RESOLVE_TIMEOUT_SECONDS)
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didRemoveService: NSNetService, moreComing: Boolean) {
        services.remove(didRemoveService.name)?.let {
            it.stop()
            it.delegate = null
        }
        scope.trySend(TcpDiscoveryEvent.Lost(didRemoveService.name))
    }

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
        scope.close(MeshtasticException.Transport("NSNetServiceBrowser failed to search: $didNotSearch"))
    }

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        val addresses = sender.addresses.orEmpty().mapNotNull {
            (it as? NSData)?.toByteArray()?.let(::parseDarwinSockaddr)
        }
        val txt = sender.TXTRecordData()?.let { NSNetService.dictionaryFromTXTRecordData(it) }.orEmpty()
        val attributes = HashMap<String, ByteArray?>()
        for ((key, value) in txt) {
            if (key is String) attributes[key] = (value as? NSData)?.toByteArray()
        }
        buildDiscoveredRadio(
            serviceName = sender.name,
            serviceType = sender.type,
            addresses = addresses,
            port = sender.port.toInt(),
            txt = attributes,
        )?.let { scope.trySend(TcpDiscoveryEvent.Found(it)) }
    }

    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
        // The service stays known so a later re-announcement can resolve it; nothing to report.
    }
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) {
        out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
    return out
}
