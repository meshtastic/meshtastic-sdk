/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.tcp

import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.TransportSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryMappingTest {

    private val firmwareTxt: Map<String, ByteArray?> = mapOf(
        "shortname" to "ABCD".encodeToByteArray(),
        "id" to "!a1b2c3d4".encodeToByteArray(),
        "pio_env" to "heltec-v3".encodeToByteArray(),
    )

    @Test
    fun serviceTypeMatchesFirmwareAndPlatformSpellings() {
        assertTrue(isMeshtasticServiceType("_meshtastic._tcp"))
        assertTrue(isMeshtasticServiceType("_meshtastic._tcp."))
        assertTrue(isMeshtasticServiceType("._meshtastic._tcp."))
        assertTrue(isMeshtasticServiceType("_meshtastic._tcp.local."))
        assertTrue(isMeshtasticServiceType("_Meshtastic._TCP"))
    }

    @Test
    fun serviceTypeRejectsOtherServices() {
        assertFalse(isMeshtasticServiceType(null))
        assertFalse(isMeshtasticServiceType(""))
        assertFalse(isMeshtasticServiceType("_http._tcp."))
        assertFalse(isMeshtasticServiceType("_meshtastic._udp."))
        assertFalse(isMeshtasticServiceType("_meshtasticx._tcp."))
    }

    @Test
    fun txtDecodesUtf8AndKeepsKeyOnlyRecords() {
        val decoded = decodeTxtAttributes(
            mapOf("shortname" to "Ünï".encodeToByteArray(), "flag" to null, "empty" to ByteArray(0)),
        )
        assertEquals(mapOf("shortname" to "Ünï", "flag" to "", "empty" to ""), decoded)
    }

    @Test
    fun addressesAreDeduplicatedWithIpv4First() {
        val result = normalizeAddresses(listOf("2001:db8::1", "192.168.1.5", "192.168.1.5", "10.0.0.2"))
        assertEquals(listOf("192.168.1.5", "10.0.0.2", "2001:db8::1"), result)
    }

    @Test
    fun addressesDropLinkLocalIpv6AndStripZonesAndBrackets() {
        val result = normalizeAddresses(listOf("fe80::1%wlan0", "[2001:db8::2%en0]", "", "not-an-address"))
        assertEquals(listOf("2001:db8::2"), result)
    }

    @Test
    fun linkLocalCoversWholeFe80Slash10Range() {
        assertTrue(isLinkLocalIpv6("fe80::1"))
        assertTrue(isLinkLocalIpv6("febf::1"))
        assertFalse(isLinkLocalIpv6("fec0::1"))
        assertFalse(isLinkLocalIpv6("::1"))
        assertFalse(isLinkLocalIpv6("192.168.1.1"))
    }

    @Test
    fun buildMapsResolvedServiceToPublicType() {
        val radio = assertNotNull(
            buildDiscoveredRadio("Meshtastic", "_meshtastic._tcp.", listOf("192.168.1.9"), 4403, firmwareTxt),
        )
        assertEquals("Meshtastic", radio.serviceName)
        assertEquals("192.168.1.9", radio.host)
        assertEquals(4403, radio.port)
        assertEquals("ABCD", radio.shortName)
        assertEquals("!a1b2c3d4", radio.nodeId)
        assertEquals("heltec-v3", radio.pioEnv)
    }

    @Test
    fun buildReturnsNullForWrongTypeNoAddressOrBadPort() {
        assertNull(buildDiscoveredRadio("x", "_http._tcp.", listOf("10.0.0.1"), 80, emptyMap()))
        assertNull(buildDiscoveredRadio("x", "_meshtastic._tcp.", emptyList(), 4403, emptyMap()))
        assertNull(buildDiscoveredRadio("x", "_meshtastic._tcp.", listOf("fe80::1"), 4403, emptyMap()))
        assertNull(buildDiscoveredRadio("x", "_meshtastic._tcp.", listOf("10.0.0.1"), 0, emptyMap()))
        assertNull(buildDiscoveredRadio("x", "_meshtastic._tcp.", listOf("10.0.0.1"), 70_000, emptyMap()))
        assertNull(buildDiscoveredRadio("", "_meshtastic._tcp.", listOf("10.0.0.1"), 4403, emptyMap()))
    }

    @Test
    fun radioConvertsToTcpTransportSpecAndTransport() {
        val radio = DiscoveredTcpRadio("Meshtastic", listOf("10.1.2.3", "2001:db8::1"), 4404, emptyMap())
        assertEquals(TransportSpec.Tcp("10.1.2.3", 4404), radio.toTransportSpec())
        assertEquals(TransportIdentity.of(TransportSpec.Tcp("10.1.2.3", 4404)), radio.toTransport().identity)
    }

    @Test
    fun radioRequiresAnAddress() {
        assertFailsWith<IllegalArgumentException> { DiscoveredTcpRadio("x", emptyList(), 4403, emptyMap()) }
    }

    @Test
    fun missingTxtKeysYieldNullAccessors() {
        val radio = DiscoveredTcpRadio("x", listOf("10.0.0.1"), 4403, emptyMap())
        assertNull(radio.shortName)
        assertNull(radio.nodeId)
        assertNull(radio.pioEnv)
    }

    @Test
    fun defaultsMatchFirmwareAdvertisement() {
        assertEquals("_meshtastic._tcp", TcpDiscovery.SERVICE_TYPE)
        assertEquals(4403, TcpDiscovery.DEFAULT_PORT)
    }

    @Test
    fun sockaddrInParsesIpv4() {
        // len=16 family=AF_INET port=4403 addr=192.168.1.9 then 8 zero bytes
        val bytes = byteArrayOf(16, 2, 0x11, 0x33, -64, -88, 1, 9, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals("192.168.1.9", parseDarwinSockaddr(bytes))
    }

    @Test
    fun sockaddrIn6ParsesGlobalAddress() {
        val bytes = ByteArray(28)
        bytes[0] = 28
        bytes[1] = 30
        bytes[8] = 0x20
        bytes[9] = 0x01
        bytes[10] = 0x0d
        bytes[11] = 0xb8.toByte()
        bytes[23] = 1
        assertEquals("2001:db8:0:0:0:0:0:1", parseDarwinSockaddr(bytes))
        assertEquals(listOf("2001:db8:0:0:0:0:0:1"), normalizeAddresses(listOf(parseDarwinSockaddr(bytes)!!)))
    }

    @Test
    fun sockaddrRejectsUnknownFamilyAndTruncatedInput() {
        assertNull(parseDarwinSockaddr(byteArrayOf(16, 18, 0, 0, 0, 0, 0, 0)))
        assertNull(parseDarwinSockaddr(byteArrayOf(16, 2, 0, 0, 1)))
        assertNull(parseDarwinSockaddr(byteArrayOf(28, 30, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(parseDarwinSockaddr(ByteArray(1)))
    }

    @Test
    fun filterDropsRepeatedFoundAndPassesChanges() {
        val filter = DiscoveryEventFilter()
        val radio = DiscoveredTcpRadio("Meshtastic", listOf("192.168.1.10"), 4403, emptyMap())
        val moved = radio.copy(addresses = listOf("192.168.1.11"))

        assertNotNull(filter.accept(TcpDiscoveryEvent.Found(radio)))
        assertNull(filter.accept(TcpDiscoveryEvent.Found(radio)))
        assertNotNull(filter.accept(TcpDiscoveryEvent.Found(moved)))
        assertNull(filter.accept(TcpDiscoveryEvent.Found(moved)))
    }

    @Test
    fun filterReportsLostOnceAndFoundAgainAfterIt() {
        val filter = DiscoveryEventFilter()
        val radio = DiscoveredTcpRadio("Meshtastic", listOf("192.168.1.10"), 4403, emptyMap())

        assertNull(filter.accept(TcpDiscoveryEvent.Lost("Meshtastic")))
        assertNotNull(filter.accept(TcpDiscoveryEvent.Found(radio)))
        assertNotNull(filter.accept(TcpDiscoveryEvent.Lost("Meshtastic")))
        assertNull(filter.accept(TcpDiscoveryEvent.Lost("Meshtastic")))
        assertNotNull(filter.accept(TcpDiscoveryEvent.Found(radio)))
    }
}
