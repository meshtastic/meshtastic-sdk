/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.InMemoryStorageProvider
import kotlin.test.Test
import kotlin.test.assertNotNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HandshakeAwaitTest {
    private fun TestScope.buildClient(): RadioClient = RadioClient.Builder()
        .transport(FakeRadioTransport(identity = TransportIdentity("fake:test"), autoHandshake = true))
        .storage(InMemoryStorageProvider())
        .coroutineContext(backgroundScope.coroutineContext)
        .build()

    @Test fun returnsSnapshotMap() = runTest {
        val client = buildClient()
        client.connect()
        assertNotNull(client.awaitInitialNodeDb())
    }
}
