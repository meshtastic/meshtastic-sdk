/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.storage.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.User
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.TransportIdentity
import org.meshtastic.sdk.storage.sqldelight.internal.MeshDatabase
import org.w3c.dom.Worker
import org.w3c.dom.WorkerOptions
import org.w3c.dom.WorkerType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserRoundTripTest {

    private fun testWorker(dbFile: String) = Worker(
        "/sqlite-opfs.test-worker.mjs?db=${js("encodeURIComponent")(dbFile)}",
        WorkerOptions(type = "module".unsafeCast<WorkerType>()),
    )

    private fun provider(baseDir: String) = SqlDelightStorageProvider(baseDir, ::testWorker)

    private fun driver(dbFile: String) = WebWorkerDriver(testWorker(dbFile))

    private fun channel(name: String) = Channel.Builder().apply {
        settings = ChannelSettings.Builder().apply { this.name = name }.build()
    }.build()

    private fun node(num: Int, name: String) = NodeInfo.Builder().apply {
        this.num = num
        user = User.Builder().apply {
            id = "!$num"
            long_name = name
            short_name = "N$num"
        }.build()
    }.build()

    @Test
    fun providerWritesThenReadsBackAfterWorkerRestart() = runTest {
        val baseDir = "spike-${Random.nextInt(0, Int.MAX_VALUE)}"
        val identity = TransportIdentity("ble:AA:BB:CC:DD:EE:FF")

        val first = provider(baseDir).activate(identity)
        first.recordOwnNode(NodeId(42), "2.7.0")
        first.saveNode(node(42, "RadioA"))
        first.saveHeartbeat(NodeId(42), 1_700_000_000_000L)
        first.saveChannels(listOf(channel("LongFast")))
        first.close()

        val second = provider(baseDir).activate(identity)
        val nodes = second.loadNodes()
        assertEquals("RadioA", nodes[NodeId(42)]?.user?.long_name)
        assertEquals(mapOf(NodeId(42) to 1_700_000_000_000L), second.loadHeartbeats())
        assertEquals("LongFast", second.loadChannels().single().settings?.name)
        second.close()
    }

    @Test
    fun v1DatabaseIsMigratedInTheWorker() = runTest {
        val name = "v1-${Random.nextInt(0, Int.MAX_VALUE)}.db"
        val raw = driver(name)
        V1_DDL.forEach { raw.execute(null, it, 0).await() }
        raw.execute(null, "PRAGMA user_version = 1", 0).await()
        raw.execute(null, "INSERT INTO nodes(node_num, long_name) VALUES (77, 'old')", 0).await()
        raw.close()

        val driver = driver(name)
        val storage = SqlDelightStorage(driver)
        assertEquals(setOf(NodeId(77)), storage.loadNodes().keys)
        storage.saveHeartbeat(NodeId(77), 5L)
        assertEquals(mapOf(NodeId(77) to 5L), storage.loadHeartbeats())
        val version = driver.executeQuery(null, "PRAGMA user_version", { c ->
            QueryResult.AsyncValue {
                c.next().await()
                c.getLong(0)
            }
        }, 0).await()
        assertEquals(3L, version)
        storage.close()
    }

    @Test
    fun aSecondWorkerOnTheSameDatabaseWaitsForTheFirstAndKeepsItsData() = runTest {
        val name = "shared-${Random.nextInt(0, Int.MAX_VALUE)}.db"
        val first = driver(name)
        first.execute(null, "CREATE TABLE t (x INTEGER)", 0).await()
        first.execute(null, "INSERT INTO t VALUES (7)", 0).await()

        // The first worker still holds the pool's files, so the second must wait for them, not fail.
        val second = driver(name)
        val read = async {
            second.executeQuery(null, "SELECT x FROM t", { c ->
                QueryResult.AsyncValue {
                    c.next().await()
                    c.getLong(0)
                }
            }, 0).await()
        }
        // Real time, so the second worker reaches its pool install while the first still holds the files.
        withContext(Dispatchers.Default) { delay(2_000) }
        first.close()

        assertEquals(7L, read.await())
        second.close()
    }

    @Test
    fun rollbackDiscardsWrites() = runTest {
        val driver = driver("rb-${Random.nextInt(0, Int.MAX_VALUE)}.db")
        val storage = SqlDelightStorage(driver)
        storage.saveNode(node(1, "keep"))
        val db = MeshDatabase(driver)
        runCatching {
            db.transaction {
                db.meshQueries.deleteNode(1L)
                error("boom")
            }
        }
        assertEquals(setOf(NodeId(1)), storage.loadNodes().keys)
        storage.close()
    }

    @Test
    fun overlappingTransactionsDoNotMix() = runTest {
        val storage = SqlDelightStorage(driver("mix-${Random.nextInt(0, Int.MAX_VALUE)}.db"))
        storage.loadChannels()
        val three = listOf(channel("A0"), channel("A1"), channel("A2"))
        val one = listOf(channel("B0"))
        coroutineScope {
            launch { storage.saveChannels(three) }
            launch { storage.saveChannels(one) }
        }
        assertEquals(listOf("B0"), storage.loadChannels().map { it.settings?.name })
        storage.close()
    }

    private companion object {
        val V1_DDL = listOf(
            "CREATE TABLE nodes (node_num INTEGER PRIMARY KEY NOT NULL, user_id TEXT, long_name TEXT, " +
                "short_name TEXT, hw_model INTEGER, is_licensed INTEGER NOT NULL DEFAULT 0, role INTEGER, " +
                "public_key BLOB, last_heard_epoch INTEGER, snr REAL, rssi INTEGER, hops_away INTEGER, " +
                "via_mqtt INTEGER NOT NULL DEFAULT 0, is_self INTEGER NOT NULL DEFAULT 0, raw_node_info BLOB)",
            "CREATE TABLE channels (idx INTEGER PRIMARY KEY NOT NULL, role INTEGER NOT NULL, name TEXT, psk BLOB, " +
                "psk_index INTEGER, uplink INTEGER NOT NULL DEFAULT 0, downlink INTEGER NOT NULL DEFAULT 0, settings_raw BLOB)",
            "CREATE TABLE configs (section TEXT PRIMARY KEY NOT NULL, payload BLOB NOT NULL)",
            "CREATE TABLE messages (packet_id INTEGER PRIMARY KEY NOT NULL, from_num INTEGER NOT NULL, " +
                "to_num INTEGER NOT NULL, channel_idx INTEGER NOT NULL, text TEXT NOT NULL, " +
                "sent_epoch INTEGER NOT NULL, " +
                "rx_epoch INTEGER, send_state TEXT NOT NULL, failure_reason TEXT)",
            "CREATE TABLE session (key TEXT PRIMARY KEY NOT NULL, value BLOB)",
        )
    }
}
