/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.LogRecord
import org.meshtastic.sdk.MeshtasticException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BleDeviceLogsTest {

    private fun record(n: Int): ByteArray = LogRecord.Builder()
        .also {
            it.message = "line $n"
            it.source = "t"
            it.level = LogRecord.Level.INFO
        }
        .build()
        .encode()

    private suspend fun connected(peripheral: FakeBlePeripheral): BleTransport {
        val transport = BleTransport(peripheral, address = ADDRESS)
        transport.connect()
        return transport
    }

    @Test
    fun recordsAreDecodedInOrder() = runTest {
        val transport = connected(FakeBlePeripheral(logNotifications = flow { repeat(3) { emit(record(it)) } }))

        val messages = transport.deviceLogs().toList().map { it.message }

        assertEquals(listOf("line 0", "line 1", "line 2"), messages)
        transport.shutdown()
    }

    @Test
    fun malformedPayloadIsSkipped() = runTest {
        val garbage = byteArrayOf(0x0A, 0x7F)
        val transport = connected(
            FakeBlePeripheral(
                logNotifications = flow {
                    emit(record(0))
                    emit(garbage)
                    emit(record(1))
                },
            ),
        )

        val messages = transport.deviceLogs().toList().map { it.message }

        assertEquals(listOf("line 0", "line 1"), messages)
        transport.shutdown()
    }

    @Test
    fun nothingSubscribesUntilCollected() = runTest {
        val peripheral = FakeBlePeripheral(logNotifications = flow { emit(record(0)) })
        val transport = connected(peripheral)

        val flow = transport.deviceLogs()
        assertEquals(0, peripheral.logSubscriptions)
        flow.toList()
        assertEquals(1, peripheral.logSubscriptions)
        transport.shutdown()
    }

    @Test
    fun slowCollectorDropsOldestAndNeverBlocksTheProducer() = runTest {
        val total = 500
        var produced = 0
        val upstream: Flow<ByteArray> = flow {
            repeat(total) {
                emit(record(it))
                produced++
            }
        }
        val transport = connected(FakeBlePeripheral(logNotifications = upstream))

        val received = transport.deviceLogs().onEach { delay(1_000) }.toList().map { it.message }

        assertEquals(total, produced)
        assertTrue(received.size < total, "expected drops, got ${received.size}")
        assertEquals("line ${total - 1}", received.last())
        val indices = received.map { it.removePrefix("line ").toInt() }
        assertEquals(indices.sorted(), indices, "order must be preserved")
        transport.shutdown()
    }

    @Test
    fun missingCharacteristicCompletesEmpty() = runTest {
        val peripheral = FakeBlePeripheral(
            servicesAfterConnect = {
                fakeServices(
                    BleConstants.MESH_SERVICE_UUID,
                    BleConstants.FROMRADIO_UUID,
                    BleConstants.TORADIO_UUID,
                    BleConstants.FROMNUM_UUID,
                )
            },
            logNotifications = flow { emit(record(0)) },
        )
        val transport = connected(peripheral)

        assertEquals(emptyList(), transport.deviceLogs().toList())
        assertEquals(0, peripheral.logSubscriptions)
        transport.shutdown()
    }

    @Test
    fun subscriptionFailureSurfacesAsTransportError() = runTest {
        val transport = connected(FakeBlePeripheral(logNotifications = flow { error("link lost") }))

        assertFailsWith<MeshtasticException.Transport> { transport.deviceLogs().toList() }
        transport.shutdown()
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
