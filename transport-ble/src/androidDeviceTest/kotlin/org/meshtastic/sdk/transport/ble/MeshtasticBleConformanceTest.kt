/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.juul.kable.Scanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.MeshEvent
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SendState
import org.meshtastic.sdk.getOrNull
import org.meshtastic.sdk.isSuccess
import org.meshtastic.sdk.storage.sqldelight.AndroidContextHolder
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider

/**
 * Real-hardware BLE conformance for the Android transport path (the cs1–cs6 envelope from
 * `samples/cli`, driven on-device): scan by Meshtastic service UUID, connect through the
 * production `BleTransport(address)` factory (exercising the MTU-517 + connection-priority
 * hook), complete the two-stage handshake, run admin RPC round-trips, prove >20-byte writes
 * (MTU negotiation), persist through the real SQLDelight storage, and reconnect.
 *
 * **Read-only by design**: no config writes, and the send test targets the radio's own node
 * number (handled locally by firmware — nothing is transmitted over LoRa).
 *
 * Skips (does not fail) when no Meshtastic radio is advertising in range.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class) // BleConstants UUIDs are kotlin.uuid.Uuid
class MeshtasticBleConformanceTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    @Test
    fun conformance_scanConnectAdminSendReconnect() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidContextHolder.context = context
        val storageDir = context.cacheDir.resolve("sdk-conformance").apply { mkdirs() }

        // ── Scan: collect every advertising Meshtastic radio for a window ───
        val candidates = linkedMapOf<String, Pair<String?, Int>>() // address -> (name, rssi)
        withTimeoutOrNull(SCAN_WINDOW_MS) {
            Scanner {
                filters {
                    match { services = listOf(BleConstants.MESH_SERVICE_UUID) }
                }
            }.advertisements.collect { ad ->
                candidates[ad.identifier] = (ad.name ?: candidates[ad.identifier]?.first) to ad.rssi
            }
        }
        assumeTrue("No Meshtastic radio advertising in range — skipping", candidates.isNotEmpty())

        // Prefer radios already bonded to this phone (no pairing dialog), then strongest RSSI.
        val bondedAddresses = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
            .adapter.bondedDevices.map { it.address }.toSet()
        val ordered = candidates.entries.sortedWith(
            compareByDescending<Map.Entry<String, Pair<String?, Int>>> { it.key in bondedAddresses }
                .thenByDescending { it.value.second },
        )
        Log.i(
            TAG,
            "Candidates: " + ordered.joinToString {
                "${it.value.first ?: "?"}@${it.key} rssi=${it.value.second} bonded=${it.key in bondedAddresses}"
            },
        )

        // ── cs1: connect via the production factory (MTU + priority hook) ───
        var transport: BleTransport? = null
        var client: RadioClient? = null
        var handshakeMs = 0L
        val eventScope = CoroutineScope(SupervisorJob())
        val events = mutableListOf<MeshEvent>()
        for ((address, meta) in ordered.take(MAX_CONNECT_CANDIDATES).map { it.key to it.value }) {
            Log.i(TAG, "Connecting to ${meta.first ?: "?"} @ $address …")
            val attemptTransport = BleTransport(address = address)
            val attemptClient = RadioClient {
                transport(attemptTransport)
                storage(SqlDelightStorageProvider(baseDir = storageDir.absolutePath))
                logger(logcatSink)
                protocolLogging(org.meshtastic.sdk.LogLevel.DEBUG)
            }
            attemptClient.events.onEach { synchronized(events) { events.add(it) } }.launchIn(eventScope)
            val startMs = System.currentTimeMillis()
            try {
                withTimeout(CONNECT_TIMEOUT_MS) { attemptClient.connect() }
                handshakeMs = System.currentTimeMillis() - startMs
                transport = attemptTransport
                client = attemptClient
                break
            } catch (e: Exception) {
                Log.w(TAG, "Connect to $address failed after ${System.currentTimeMillis() - startMs}ms: $e")
                runCatching { attemptClient.disconnect() }
                attemptTransport.shutdown()
            }
        }
        val connectedEvents = synchronized(events) { events.toList() }
        assertNotNull(
            "No candidate radio completed the handshake; events=$connectedEvents",
            client,
        )
        client!!
        transport!!
        Log.i(TAG, "cs1 handshake completed in ${handshakeMs}ms")
        assertEquals(ConnectionState.Connected, client.connection.value)
        assertTrue("Handshake must complete within 30s (took ${handshakeMs}ms)", handshakeMs < 30_000)

        try {
            // ── cs5: handshake state — config, channels, node DB, own node ──
            val bundle = checkNotNull(client.configBundle.value) { "configBundle must be populated" }
            assertTrue("config sections expected", bundle.configs.isNotEmpty())
            val ownNode = checkNotNull(client.ownNode.value) { "ownNode must be populated" }
            val myNum = ownNode.num
            val nodes = client.nodeSnapshot()
            Log.i(
                TAG,
                "cs5 nodeDb=${nodes.size} nodes, fw=${bundle.metadata.firmware_version}, " +
                    "configs=${bundle.configs.size}, channels=${client.channels.value?.size}",
            )
            assertTrue("node DB must contain at least the local node", nodes.isNotEmpty())
            assertNotNull("channels must be populated", client.channels.value)

            // ── cs3: admin RPC round-trips (read-only) ──────────────────────
            val owner = client.admin.getOwner()
            assertTrue("getOwner must succeed: $owner", owner.isSuccess)
            Log.i(TAG, "cs3 owner=${owner.getOrNull()?.long_name}")

            val metadata = client.admin.getDeviceMetadata()
            assertTrue("getDeviceMetadata must succeed: $metadata", metadata.isSuccess)

            // 8 sequential get_channel RPCs — repeated large inbound reads.
            val channels = client.admin.listChannels()
            assertTrue("listChannels must succeed: $channels", channels.isSuccess)
            Log.i(TAG, "cs3 listChannels=${channels.getOrNull()?.size} entries")

            // ── MTU proof: a >20-byte write fails outright at ATT MTU 23 ────
            // Targets our own node number: firmware handles it locally, no LoRa TX.
            val bigPayload = "MTU conformance " + "x".repeat(160)
            val handle = client.sendText(bigPayload, to = NodeId(myNum))
            val sent = withTimeoutOrNull(SEND_TIMEOUT_MS) {
                handle.state.first { it != SendState.Queued }
            }
            Log.i(TAG, "MTU send state=$sent")
            assertNotNull("send must leave Queued (write reached the radio)", sent)
            assertFalse("177-byte write must not fail (MTU negotiation)", sent is SendState.Failed)

            // ── telemetry: local stats (non-fatal — firmware-version dependent)
            val stats = client.telemetry.requestLocalStats()
            Log.i(TAG, "localStats=${if (stats.isSuccess) "ok" else stats.toString()}")
        } finally {
            client.disconnect()
        }
        assertEquals(ConnectionState.Disconnected, client.connection.value)

        // ── cs6: reconnect on the SAME transport instance, fresh client ─────
        val client2 = RadioClient {
            transport(transport)
            storage(SqlDelightStorageProvider(baseDir = storageDir.absolutePath))
            logger(logcatSink)
            protocolLogging(org.meshtastic.sdk.LogLevel.DEBUG)
        }
        try {
            val reconnectStartMs = System.currentTimeMillis()
            withTimeout(CONNECT_TIMEOUT_MS) { client2.connect() }
            Log.i(TAG, "cs6 reconnect in ${System.currentTimeMillis() - reconnectStartMs}ms")
            assertEquals(ConnectionState.Connected, client2.connection.value)
            assertNotNull("ownNode after reconnect", client2.ownNode.value)
        } finally {
            client2.disconnect()
            transport.shutdown()
        }

        // ── No storage degradation; surface drop accounting ────────────────
        val snapshot = synchronized(events) { events.toList() }
        val degraded = snapshot.filterIsInstance<MeshEvent.StorageDegraded>()
        assertTrue("storage must not degrade: $degraded", degraded.isEmpty())
        val dropped = snapshot.filterIsInstance<MeshEvent.PacketsDropped>().sumOf { it.count }
        val warnings = snapshot.filterIsInstance<MeshEvent.ProtocolWarning>().map { it.message }
        Log.i(TAG, "events: dropped=$dropped warnings=$warnings")
        eventScope.cancel()
    }

    private companion object {
        const val TAG = "BleConformance"

        /** Routes SDK engine/protocol logs into logcat for failure diagnosis. */
        val logcatSink = org.meshtastic.sdk.LogSink { level, tag, message, cause ->
            val prio = when (level) {
                org.meshtastic.sdk.LogLevel.ERROR -> Log.ERROR
                org.meshtastic.sdk.LogLevel.WARN -> Log.WARN
                org.meshtastic.sdk.LogLevel.INFO -> Log.INFO
                else -> Log.DEBUG
            }
            Log.println(prio, "MeshSdk.$tag", if (cause != null) "$message ($cause)" else message)
        }
        const val SCAN_WINDOW_MS = 10_000L
        const val MAX_CONNECT_CANDIDATES = 3
        const val CONNECT_TIMEOUT_MS = 90_000L
        const val SEND_TIMEOUT_MS = 15_000L
    }
}
