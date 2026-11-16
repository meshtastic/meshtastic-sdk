/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.meshtastic.sdk.MeshtasticException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class BleScannerTest {

    @Test
    fun mapsAdvertisementFieldsOneToOne() = runTest {
        val out = flowOf(RawAdvertisement("AA:BB:CC:DD:EE:FF", "Meshtastic_ab12", -61)).asDiscoveredRadios().toList()
        assertEquals(1, out.size)
        assertEquals("AA:BB:CC:DD:EE:FF", out[0].address)
        assertEquals("Meshtastic_ab12", out[0].name)
        assertEquals(-61, out[0].rssi)
    }

    @Test
    fun blankNameBecomesNull() {
        assertNull(RawAdvertisement("addr", "  ", -70).toDiscoveredRadio().name)
        assertNull(RawAdvertisement("addr", null, -70).toDiscoveredRadio().name)
    }

    @Test
    fun repeatedAdvertisementsAreAllEmitted() = runTest {
        val out = flowOf(
            RawAdvertisement("addr", "n", -60),
            RawAdvertisement("addr", "n", -60),
            RawAdvertisement("addr", "n", -64),
        ).asDiscoveredRadios().toList()
        assertEquals(listOf(-60, -60, -64), out.map { it.rssi })
    }

    @Test
    fun equalityUsesPublicFields() {
        assertEquals(
            RawAdvertisement("addr", "n", -60).toDiscoveredRadio(),
            RawAdvertisement("addr", "n", -60).toDiscoveredRadio(),
        )
    }

    @Test
    fun scanFailureIsWrappedAsTransportException() = runTest {
        val failure = IllegalStateException("bluetooth off")
        val source = flow<RawAdvertisement> { throw failure }
        val thrown = assertFailsWith<MeshtasticException.Transport> { source.asDiscoveredRadios().toList() }
        assertEquals(failure, thrown.cause)
    }

    @Test
    fun cancellationIsNotWrapped() = runTest {
        val source = flow<RawAdvertisement> { throw CancellationException("stop") }
        val thrown = assertFailsWith<CancellationException> { source.asDiscoveredRadios().toList() }
        assertIs<CancellationException>(thrown)
    }

    @Test
    fun transportFactoryRejectsRadioNotFromScanner() {
        val radio = RawAdvertisement("addr", "n", -60).toDiscoveredRadio()
        assertFailsWith<IllegalArgumentException> { BleTransport(radio) }
    }
}
