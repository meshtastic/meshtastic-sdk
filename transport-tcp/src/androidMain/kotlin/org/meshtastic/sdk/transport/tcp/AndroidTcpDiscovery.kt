/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.tcp

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.MeshtasticException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.resume

private const val RESOLVE_TIMEOUT_MS = 10_000L
private const val API_SERVICE_INFO_CALLBACK = 34

/**
 * [TcpDiscovery] for Android, backed by [NsdManager].
 *
 * Resolution is API-level dependent. On API 34 and later each found service gets a
 * [NsdManager.ServiceInfoCallback], so later address and TXT changes arrive as further
 * [TcpDiscoveryEvent.Found] events. On API 26 to 33 the deprecated `resolveService` is used,
 * which the platform allows one at a time, so services resolve in sequence with a 10 s timeout
 * each and are reported once.
 *
 * Host requirements, none of which this class acquires:
 * - `android.permission.INTERNET` in the manifest.
 * - `NsdManager` runs mDNS in a system daemon, so no `WifiManager.MulticastLock` is needed for
 *   it. A host that also runs its own multicast listener needs one.
 * - `ACCESS_LOCAL_NETWORK` where the platform enforces local-network protection for the app's
 *   target SDK; without it the flow fails or stays silent.
 *
 * @param context any [Context]; only its application context is retained.
 * @since 0.3.0
 */
public class AndroidTcpDiscovery(context: Context) : TcpDiscovery {

    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @SuppressLint("NewApi")
    override fun discover(): Flow<TcpDiscoveryEvent> = callbackFlow {
        // ADR-012: native-handle ownership - NSD callbacks arrive on binder threads.
        val known = ConcurrentHashMap.newKeySet<String>()
        // ADR-012: native-handle ownership - one registered callback per service on API 34+.
        val infoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
        val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val inline = Executor { it.run() }

        fun emitResolved(info: NsdServiceInfo) {
            if (info.serviceName !in known) return
            info.toRadio()?.let { trySend(TcpDiscoveryEvent.Found(it)) }
        }

        fun watch(service: NsdServiceInfo) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    infoCallbacks.remove(service.serviceName)
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = emitResolved(serviceInfo)

                override fun onServiceLost() = Unit

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            infoCallbacks.put(service.serviceName, callback)?.let {
                runCatching { nsdManager.unregisterServiceInfoCallback(it) }
            }
            nsdManager.registerServiceInfoCallback(service, inline, callback)
        }

        launch {
            for (service in resolveQueue) {
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { nsdManager.resolveOnce(service) }
                if (resolved != null) emitResolved(resolved)
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(MeshtasticException.Transport("NSD discovery failed to start (error $errorCode)"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!isMeshtasticServiceType(serviceInfo.serviceType)) return
                known.add(serviceInfo.serviceName)
                if (Build.VERSION.SDK_INT >= API_SERVICE_INFO_CALLBACK) {
                    runCatching { watch(serviceInfo) }
                } else {
                    resolveQueue.trySend(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                known.remove(serviceInfo.serviceName)
                infoCallbacks.remove(serviceInfo.serviceName)?.let {
                    runCatching { nsdManager.unregisterServiceInfoCallback(it) }
                }
                trySend(TcpDiscoveryEvent.Lost(serviceInfo.serviceName))
            }
        }

        try {
            nsdManager.discoverServices(TcpDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: IllegalArgumentException) {
            throw MeshtasticException.Transport("NSD discovery failed to start: ${e.message}", e)
        } catch (e: SecurityException) {
            throw MeshtasticException.Transport("NSD discovery not permitted: ${e.message}", e)
        }

        awaitClose {
            resolveQueue.close()
            infoCallbacks.values.forEach { runCatching { nsdManager.unregisterServiceInfoCallback(it) } }
            infoCallbacks.clear()
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
    }.distinctDiscoveryEvents()
}

@Suppress("DEPRECATION")
@SuppressLint("NewApi")
private fun NsdServiceInfo.toRadio(): DiscoveredTcpRadio? {
    val addresses = if (Build.VERSION.SDK_INT >= API_SERVICE_INFO_CALLBACK) {
        hostAddresses.mapNotNull { it.hostAddress }
    } else {
        listOfNotNull(host?.hostAddress)
    }
    return buildDiscoveredRadio(
        serviceName = serviceName,
        serviceType = serviceType,
        addresses = addresses,
        port = port,
        txt = attributes,
    )
}

/** Pre-API-34 one-shot resolve; `null` when the platform reports failure. */
@Suppress("DEPRECATION")
private suspend fun NsdManager.resolveOnce(service: NsdServiceInfo): NsdServiceInfo? =
    suspendCancellableCoroutine { continuation ->
        resolveService(
            service,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(serviceInfo)
                }
            },
        )
    }
