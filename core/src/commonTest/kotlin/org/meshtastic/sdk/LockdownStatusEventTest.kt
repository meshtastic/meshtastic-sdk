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
import org.meshtastic.proto.LockdownStatus
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Inbound `FromRadio.lockdown_status` (hardened `MESHTASTIC_LOCKDOWN` firmware builds) must surface
 * as a typed [MeshEvent.LockdownStatusChanged] rather than a generic `ProtocolWarning`. The same
 * dispatch helper handles the variant during the handshake and after it, so this also covers the
 * mid-handshake case the firmware actually uses (sent right after `config_complete_id`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockdownStatusEventTest {

    @Test
    fun lockdownStatusSurfacesAsTypedEvent() = runTest {
        val (transport, client) = connectedClient()
        val events = mutableListOf<MeshEvent>()
        val job = backgroundScope.launch { client.events.collect { events.add(it) } }
        client.connect()
        runCurrent()

        val status = LockdownStatus(
            state = LockdownStatus.State.LOCKED,
            lock_reason = "needs_auth",
            backoff_seconds = 0,
        )
        transport.injectFrame(FromRadio(lockdown_status = status).toFrame())
        runCurrent()

        val event = events.filterIsInstance<MeshEvent.LockdownStatusChanged>().singleOrNull()
        assertTrue(event != null, "expected a typed LockdownStatusChanged event, got: $events")
        assertEquals(status, event.status)
        // No generic protocol warning should be emitted for a recognized variant.
        assertTrue(events.none { it is MeshEvent.ProtocolWarning })

        job.cancel()
        runCatching { client.disconnect() }
    }

    @Test
    fun lockdownDisabledStateSurfaces() = runTest {
        // DISABLED is a develop-SNAPSHOT-only enum value; assert the SDK round-trips it.
        val (transport, client) = connectedClient()
        val events = mutableListOf<MeshEvent>()
        val job = backgroundScope.launch { client.events.collect { events.add(it) } }
        client.connect()
        runCurrent()

        val status = LockdownStatus(state = LockdownStatus.State.DISABLED)
        transport.injectFrame(FromRadio(lockdown_status = status).toFrame())
        runCurrent()

        val event = events.filterIsInstance<MeshEvent.LockdownStatusChanged>().singleOrNull()
        assertTrue(event != null, "expected a typed LockdownStatusChanged event, got: $events")
        assertEquals(LockdownStatus.State.DISABLED, event.status.state)

        job.cancel()
        runCatching { client.disconnect() }
    }

    private fun kotlinx.coroutines.test.TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:lockdown"),
            autoHandshake = true,
            nodeNum = 0x11111111,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .rpcTimeout(60.seconds)
            .sendTimeout(60.seconds)
            .build()
        return transport to client
    }
}
