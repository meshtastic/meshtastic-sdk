/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.tcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.meshtastic.sdk.MeshtasticException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * [TcpDiscovery] for the JVM, backed by JmDNS.
 *
 * Each collection of [discover] opens its own `JmDNS` instance bound to [bindAddress], so
 * multicast reaches the LAN interface rather than loopback. Opening and closing the instance
 * block, so both run on [Dispatchers.IO] (ADR-012).
 *
 * @param bindAddress local interface address to browse on. When `null`, the first up,
 *   non-loopback, multicast-capable IPv4 address is used, falling back to the host's own
 *   address. Pass one explicitly on hosts with several active interfaces.
 * @since 0.3.0
 */
public class JvmTcpDiscovery(private val bindAddress: InetAddress? = null) : TcpDiscovery {

    override fun discover(): Flow<TcpDiscoveryEvent> = callbackFlow {
        val jmdns = try {
            JmDNS.create(bindAddress ?: findLanAddress() ?: InetAddress.getLocalHost())
        } catch (e: IOException) {
            throw MeshtasticException.Transport("mDNS discovery could not start: ${e.message}", e)
        }
        val type = "${TcpDiscovery.SERVICE_TYPE}.local."

        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // persistent = true keeps serviceResolved firing for later address and TXT changes.
                jmdns.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                trySend(TcpDiscoveryEvent.Lost(event.name))
            }

            override fun serviceResolved(event: ServiceEvent) {
                event.info.toRadio()?.let { trySend(TcpDiscoveryEvent.Found(it)) }
            }
        }
        jmdns.addServiceListener(type, listener)

        awaitClose {
            runCatching { jmdns.removeServiceListener(type, listener) }
            runCatching { jmdns.close() }
        }
    }.flowOn(Dispatchers.IO)

    private fun ServiceInfo.toRadio(): DiscoveredTcpRadio? {
        val txt = HashMap<String, ByteArray?>()
        for (key in propertyNames) txt[key] = getPropertyBytes(key)
        return buildDiscoveredRadio(
            serviceName = name,
            serviceType = type,
            addresses = inet4Addresses.map { it.hostAddress.orEmpty() } +
                inet6Addresses.map { it.hostAddress.orEmpty() },
            port = port,
            txt = txt,
        )
    }

    private companion object {
        /** First up, non-loopback, multicast-capable interface with an IPv4 address. */
        fun findLanAddress(): InetAddress? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        } catch (_: java.net.SocketException) {
            null
        }
    }
}
