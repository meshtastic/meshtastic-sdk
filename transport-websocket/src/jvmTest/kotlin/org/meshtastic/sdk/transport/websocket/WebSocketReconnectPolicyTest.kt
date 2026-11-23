/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.websocket

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.sdk.AutoReconnectConfig
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.InMemoryStorageProvider
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.FakeRadioTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketReconnectPolicyTest {

    @Test
    fun close4001EndsInFailedCarryingTheCloseCode() = runTest {
        val transport = FakeRadioTransport(TransportIdentity("ws:ws://node:80/api/v1/ws"), autoHandshake = true)
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .autoReconnect(AutoReconnectConfig(initialBackoff = 1.seconds, jitter = 0.0))
            .build()
        val states = mutableListOf<ConnectionState>()
        client.connect()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.connection.collect { states += it } }

        // What WebSocketTransport publishes for a 4001 close (describeSessionEnd).
        transport.simulateError(
            WebSocketTransportException(
                "WebSocket closed: another client took the node's only WebSocket slot (4001)",
                closeCode = WebSocketTransport.CLOSE_REPLACED,
            ),
            recoverable = false,
        )
        runCurrent()
        advanceTimeBy(30_000L)
        runCurrent()

        val failed = assertIs<ConnectionState.Failed>(client.connection.value)
        assertEquals(4001, assertIs<WebSocketTransportException>(failed.cause).closeCode)
        assertTrue(states.none { it is ConnectionState.Reconnecting }, "states: $states")
    }
}
