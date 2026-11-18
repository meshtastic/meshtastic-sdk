/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.LogRecord
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `FromRadio.log_record` surfaces as [MeshEvent.DeviceLog] instead of being dropped. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceLogEventTest {

    @Test
    fun logRecordSurfacesAsDeviceLogEvent() = runTest {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:device-log"),
            autoHandshake = true,
            nodeNum = 0x11111111,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        val events = mutableListOf<MeshEvent>()
        val job = backgroundScope.launch { client.events.collect { events.add(it) } }
        client.connect()
        runCurrent()

        val record = LogRecord.Builder().also { wb ->
            wb.message = "Lost connection to radio"
            wb.source = "RadioLibInterface"
        }.build()
        transport.injectFrame(FromRadio.Builder().also { wb -> wb.log_record = record }.build().toFrame())
        runCurrent()

        val logs = events.filterIsInstance<MeshEvent.DeviceLog>()
        assertEquals(1, logs.size, "expected one DeviceLog, got: $events")
        assertEquals(record, logs.single().record)
        assertTrue(events.none { it is MeshEvent.ProtocolWarning })

        job.cancel()
        runCatching { client.disconnect() }
    }
}
