/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.DeviceStorage
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.TransportIdentity
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceIdRenumberTest {

    private lateinit var baseDir: String

    @BeforeTest
    fun setUp() {
        baseDir = Files.createTempDirectory("mesh-deviceid-test").absolutePathString()
    }

    @AfterTest
    fun tearDown() {
        val p = baseDir.toPath()
        if (FileSystem.SYSTEM.exists(p)) FileSystem.SYSTEM.deleteRecursively(p)
    }

    private fun id(seed: Int): ByteString = ByteArray(16) { (seed + it).toByte() }.toByteString()

    private fun node(num: Int) = NodeInfo.Builder().also { wb -> wb.num = num }.build()

    private suspend fun seed(storage: DeviceStorage, nodeNum: Int, deviceId: ByteString?) {
        storage.recordOwnNode(NodeId(nodeNum), "2.7.0", deviceId)
        storage.saveNode(node(nodeNum))
        storage.saveNode(node(777))
    }

    @Test
    fun sameDeviceIdKeepsRowsAndRemovesTheOldOwnNode() = runTest {
        val storage = SqlDelightStorage(createDriver(":memory:"))
        seed(storage, 123, id(1))

        storage.recordOwnNode(NodeId(456), "2.8.0", id(1))

        val nodes = storage.loadNodes()
        assertNull(nodes[NodeId(123)])
        assertNotNull(nodes[NodeId(777)])
        assertEquals(id(1), storage.loadOwnDeviceId())
        storage.close()
    }

    @Test
    fun differentDeviceIdClears() = runTest {
        val storage = SqlDelightStorage(createDriver(":memory:"))
        seed(storage, 123, id(1))

        storage.recordOwnNode(NodeId(456), "2.8.0", id(2))

        assertTrue(storage.loadNodes().isEmpty())
        assertEquals(id(2), storage.loadOwnDeviceId())
        storage.close()
    }

    @Test
    fun nullIncomingDeviceIdClears() = runTest {
        val storage = SqlDelightStorage(createDriver(":memory:"))
        seed(storage, 123, id(1))

        storage.recordOwnNode(NodeId(456), "2.8.0", null)

        assertTrue(storage.loadNodes().isEmpty())
        assertNull(storage.loadOwnDeviceId())
        storage.close()
    }

    @Test
    fun nullStoredDeviceIdClears() = runTest {
        val storage = SqlDelightStorage(createDriver(":memory:"))
        seed(storage, 123, null)

        storage.recordOwnNode(NodeId(456), "2.8.0", id(1))

        assertTrue(storage.loadNodes().isEmpty())
        storage.close()
    }

    @Test
    fun sameNodeNumWithNullDeviceIdKeepsTheStoredId() = runTest {
        val storage = SqlDelightStorage(createDriver(":memory:"))
        seed(storage, 123, id(1))

        storage.recordOwnNode(NodeId(123), "2.8.0", null)

        assertEquals(id(1), storage.loadOwnDeviceId())
        assertEquals(2, storage.loadNodes().size)
        storage.close()
    }

    @Test
    fun databaseWrittenWithoutADeviceIdIsMissingAndClearsOnRenumber() = runTest {
        val dir = "$baseDir/legacy"
        FileSystem.SYSTEM.createDirectories(dir.toPath())
        val path = "$dir/legacy.db"

        // The two-argument overload writes exactly the rows a pre-device-id database holds.
        val legacy = SqlDelightStorage(createDriver(path))
        legacy.recordOwnNode(NodeId(123), "2.7.0")
        legacy.saveNode(node(123))
        legacy.close()

        val reopened = SqlDelightStorage(createDriver(path))
        assertNull(reopened.loadOwnDeviceId())
        reopened.recordOwnNode(NodeId(456), "2.8.0", id(1))
        assertTrue(reopened.loadNodes().isEmpty())
        assertEquals(id(1), reopened.loadOwnDeviceId())
        reopened.close()
    }

    @Test
    fun deviceIdSurvivesReopenAndProviderRebindsTheNodeNum() = runTest {
        val provider = SqlDelightStorageProvider(baseDir)
        val ble = TransportIdentity("ble:AA:BB:CC:DD:EE:FF")

        provider.activate(ble).apply {
            seed(this, 123, id(1))
            close()
        }

        val storage = provider.activate(ble)
        assertEquals(id(1), storage.loadOwnDeviceId())
        storage.recordOwnNode(NodeId(456), "2.8.0", id(1))
        assertNotNull(storage.loadNodes()[NodeId(777)])
        assertNull(storage.loadNodes()[NodeId(123)])
        storage.close()

        val index = readIndex("$baseDir/${SqlDelightStorageProvider.INDEX_FILENAME}".toPath())
        assertNull(index.nodeNumToDbId[123])
        assertEquals(index.identityToDbId[ble.raw], index.nodeNumToDbId[456])
    }
}
