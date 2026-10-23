/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.TransportIdentity
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The engine launches storage calls as separate coroutines, so storage must tolerate overlap. */
class ConcurrentStorageTest {

    private lateinit var baseDir: String

    @BeforeTest
    fun setUp() {
        baseDir = Files.createTempDirectory("mesh-concurrent-test").absolutePathString()
    }

    @AfterTest
    fun tearDown() {
        val p = baseDir.toPath()
        if (FileSystem.SYSTEM.exists(p)) FileSystem.SYSTEM.deleteRecursively(p)
    }

    @Test
    fun `writes racing the first bind all reach the database`() = runTest {
        val provider = SqlDelightStorageProvider(baseDir)
        repeat(ROUNDS) { round ->
            val identity = TransportIdentity("tcp:10.0.0.$round:4403")
            val ownNode = NodeId(1000 + round)
            val storage = provider.activate(identity)

            withContext(Dispatchers.Default) {
                coroutineScope {
                    val writers = (1..WRITES).map { n ->
                        async {
                            storage.saveNode(node(n))
                            storage.saveHeartbeat(NodeId(n), n.toLong())
                        }
                    }
                    launch { storage.recordOwnNode(ownNode, "2.7.0") }
                    launch { storage.saveConfig(bundle(ownNode.raw)) }
                    writers.awaitAll()
                }
            }

            val nodes = storage.loadNodes()
            val heartbeats = storage.loadHeartbeats()
            for (n in 1..WRITES) {
                assertNotNull(nodes[NodeId(n)], "round $round lost node $n")
                assertEquals(n.toLong(), heartbeats[NodeId(n)], "round $round lost heartbeat $n")
            }
            assertEquals(ownNode.raw, storage.loadConfig()?.myInfo?.my_node_num)
            storage.close()

            // Same data after reopening through the bound alias.
            val reopened = provider.activate(identity)
            assertEquals(WRITES, reopened.loadNodes().size)
            reopened.close()
        }
    }

    @Test
    fun `concurrent transactions on an in-memory database do not interfere`() = runTest {
        val storage = SqlDelightStorageProvider(baseDir = "").activate(TransportIdentity("ble:AA"))
        withContext(Dispatchers.Default) {
            (1..WRITES).map { n ->
                async {
                    storage.saveChannels(listOf(channel("ch$n"), channel("x$n")))
                    storage.saveConfig(bundle(n))
                    storage.saveNode(node(n))
                }
            }.awaitAll()
        }
        assertEquals(2, storage.loadChannels().size)
        assertEquals(WRITES, storage.loadNodes().size)
        assertNotNull(storage.loadConfig())
        storage.close()
    }

    private fun node(num: Int): NodeInfo = NodeInfo.Builder().also { it.num = num }.build()

    private fun channel(name: String): Channel = Channel.Builder().also { wb ->
        wb.settings = ChannelSettings.Builder().also { it.name = name }.build()
    }.build()

    private fun bundle(nodeNum: Int): ConfigBundle = ConfigBundle(
        myInfo = MyNodeInfo.Builder().also { it.my_node_num = nodeNum }.build(),
        metadata = DeviceMetadata.Builder().build(),
        configs = listOf(Config.Builder().build()),
        moduleConfigs = emptyList(),
    )

    private companion object {
        const val ROUNDS = 10
        const val WRITES = 50
    }
}
