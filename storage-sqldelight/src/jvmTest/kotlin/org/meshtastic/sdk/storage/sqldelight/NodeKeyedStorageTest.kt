/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.User
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.TransportIdentity
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeKeyedStorageTest {

    private lateinit var baseDir: String

    @BeforeTest
    fun setUp() {
        baseDir = Files.createTempDirectory("mesh-store-test").absolutePathString()
    }

    @AfterTest
    fun tearDown() {
        val p = baseDir.toPath()
        if (FileSystem.SYSTEM.exists(p)) FileSystem.SYSTEM.deleteRecursively(p)
    }

    private fun sampleBundle(nodeNum: Int, longName: String = "Radio"): ConfigBundle = ConfigBundle(
        myInfo = MyNodeInfo(my_node_num = nodeNum),
        metadata = DeviceMetadata(firmware_version = "2.5.0"),
        configs = listOf(Config()),
        moduleConfigs = emptyList(),
    ).also { _ ->
        // `longName` is only used to generate a NodeInfo below.
        require(longName.isNotEmpty())
    }

    private fun sampleNode(nodeNum: Int, longName: String): NodeInfo = NodeInfo(
        num = nodeNum,
        user = User(id = "!${nodeNum.toString(16)}", long_name = longName, short_name = longName.take(4)),
    )

    @Test
    fun `first bind creates opaque db keyed by nodeNum`() = runTest {
        val provider = SqlDelightStorageProvider(baseDir)
        val bleIdentity = TransportIdentity("ble:AA:BB:CC:DD:EE:FF")

        val storage = provider.activate(bleIdentity)
        // Pre-bind reads should return empty/null defaults.
        assertTrue(storage.loadNodes().isEmpty())
        assertNull(storage.loadConfig())
        assertNull(storage.loadSessionPasskey())

        storage.recordOwnNode(NodeId(42), "2.5.0")
        storage.saveConfig(sampleBundle(42))
        storage.saveNode(sampleNode(42, "RadioA"))
        storage.close()

        val index = readIndex("$baseDir/${SqlDelightStorageProvider.INDEX_FILENAME}".toPath())
        assertEquals(1, index.identityToDbId.size)
        assertEquals(1, index.nodeNumToDbId.size)
        val dbId = index.identityToDbId[bleIdentity.raw]
        assertNotNull(dbId)
        assertEquals(dbId, index.nodeNumToDbId[42])
        assertTrue(FileSystem.SYSTEM.exists("$baseDir/$dbId.db".toPath()))
    }

    @Test
    fun `second transport to same radio shares the same db`() = runTest {
        val provider = SqlDelightStorageProvider(baseDir)
        val ble = TransportIdentity("ble:AA:BB:CC:DD:EE:FF")
        val tcp = TransportIdentity("tcp:192.168.1.180:4403")

        // First connect: BLE — populate DB.
        provider.activate(ble).apply {
            recordOwnNode(NodeId(42), "2.5.0")
            saveConfig(sampleBundle(42))
            saveNode(sampleNode(42, "RadioA"))
            close()
        }

        // Second connect: TCP to same radio — different identity, same nodeNum.
        val tcpStorage = provider.activate(tcp)
        // No identity alias yet → deferred reads return empty until recordOwnNode.
        assertTrue(tcpStorage.loadNodes().isEmpty())
        assertNull(tcpStorage.loadConfig())

        tcpStorage.recordOwnNode(NodeId(42), "2.5.0")
        // Now should see the previously-saved data.
        assertEquals("RadioA", tcpStorage.loadNodes()[NodeId(42)]?.user?.long_name)
        val cfg = tcpStorage.loadConfig()
        assertNotNull(cfg)
        assertEquals(42, cfg.myInfo.my_node_num)
        tcpStorage.close()

        // Both aliases now resolve to the same dbId.
        val index = readIndex("$baseDir/${SqlDelightStorageProvider.INDEX_FILENAME}".toPath())
        assertEquals(2, index.identityToDbId.size)
        assertEquals(index.identityToDbId[ble.raw], index.identityToDbId[tcp.raw])
    }

    @Test
    fun `reconnect via known alias takes fast path and loads prior state`() = runTest {
        val provider = SqlDelightStorageProvider(baseDir)
        val ble = TransportIdentity("ble:AA:BB:CC:DD:EE:FF")

        provider.activate(ble).apply {
            recordOwnNode(NodeId(42), "2.5.0")
            saveNode(sampleNode(42, "RadioA"))
            close()
        }

        // Reconnect via same identity → alias hit → reads immediately return stored data.
        val storage = provider.activate(ble)
        assertEquals("RadioA", storage.loadNodes()[NodeId(42)]?.user?.long_name)
        storage.close()
    }

    @Test
    fun `factory reset clears inner db and remaps nodeNum binding`() = runTest {
        val provider = SqlDelightStorageProvider(baseDir)
        val ble = TransportIdentity("ble:AA:BB:CC:DD:EE:FF")

        // First connect: nodeNum = 42.
        provider.activate(ble).apply {
            recordOwnNode(NodeId(42), "2.5.0")
            saveNode(sampleNode(42, "RadioA"))
            close()
        }

        // Reconnect via same BLE identity, radio now reports nodeNum = 99 (factory reset / swap).
        val storage = provider.activate(ble)
        storage.recordOwnNode(NodeId(99), "2.5.0")
        // Inner factory-reset logic wiped stale node 42.
        assertTrue(storage.loadNodes().isEmpty())
        storage.close()

        val index = readIndex("$baseDir/${SqlDelightStorageProvider.INDEX_FILENAME}".toPath())
        // Old nodeNum→dbId binding must be gone; new one must be present.
        assertFalse(index.nodeNumToDbId.containsKey(42))
        assertEquals(index.identityToDbId[ble.raw], index.nodeNumToDbId[99])
    }

    @Test
    fun `in-memory mode skips index and returns fresh storage per activation`() = runTest {
        val provider = SqlDelightStorageProvider(baseDir = "")
        val s1 = provider.activate(TransportIdentity("ble:AA"))
        val s2 = provider.activate(TransportIdentity("ble:AA"))
        s1.saveNode(sampleNode(1, "A"))
        // Second activation is a completely separate in-memory DB.
        assertTrue(s2.loadNodes().isEmpty())
        s1.close()
        s2.close()
    }
}
