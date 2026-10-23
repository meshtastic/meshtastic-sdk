/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.User
import org.meshtastic.sdk.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeartbeatRowsTest {

    @Test
    fun `heartbeat for an unknown node is kept but not returned as a node`() = runTest {
        val driver = createDriver(":memory:")
        try {
            val storage = SqlDelightStorage(driver)
            storage.saveHeartbeat(NodeId(0x55), 1_000L)

            assertTrue(storage.loadNodes().isEmpty())
            assertEquals(mapOf(NodeId(0x55) to 1_000L), storage.loadHeartbeats())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `node saved after its heartbeat is returned and keeps the heartbeat`() = runTest {
        val driver = createDriver(":memory:")
        try {
            val storage = SqlDelightStorage(driver)
            storage.saveHeartbeat(NodeId(0x55), 1_000L)
            storage.saveNode(node(0x55, "Late"))

            assertEquals("Late", storage.loadNodes()[NodeId(0x55)]?.user?.long_name)
            assertEquals(1_000L, storage.loadHeartbeats()[NodeId(0x55)])
        } finally {
            driver.close()
        }
    }

    private fun node(num: Int, longName: String): NodeInfo = NodeInfo.Builder().also { wb ->
        wb.num = num
        wb.user = User.Builder().also { it.long_name = longName }.build()
    }.build()
}
