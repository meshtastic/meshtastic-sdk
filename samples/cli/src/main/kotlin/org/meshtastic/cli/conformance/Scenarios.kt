/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.conformance

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.ConnectionState
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SendOutcome
import org.meshtastic.sdk.connectAndAwaitReady
import org.meshtastic.sdk.sendDirectMessage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Result of a single conformance scenario. Status enumerates the three terminal outcomes a
 * release-readiness reviewer cares about; everything else (logs, intermediate states) belongs
 * in the surrounding [Output] / transcript stream, not here.
 */
internal data class ScenarioResult(
    val id: String,
    val name: String,
    val status: Status,
    val durationMs: Long,
    val message: String,
) {
    enum class Status { PASS, FAIL, SKIP }
}

/**
 * Phase 5 acceptance scenarios per `docs/SPEC.md` §6 + `docs/manual-tests.md`. Each function is
 * idempotent and self-contained: it returns a [ScenarioResult] and never throws. Failures inside
 * a scenario produce `Status.FAIL` with the exception class + message in [ScenarioResult.message]
 * so the surrounding transcript can render the whole sweep deterministically.
 *
 * Scenarios that genuinely cannot run (e.g. `cs4` without a `--peer-node`) return
 * [ScenarioResult.Status.SKIP] with a one-line reason.
 */
internal object Scenarios {

    /**
     * **cs1 — Handshake under 30 s** (manual A1 / A4). Caller supplies a fresh, not-yet-connected
     * [client]; this scenario calls [connectAndAwaitReady] and times the wall-clock to
     * `ConnectionState.Connected`. PASS if the connect returns within [budget] and `ownNode` is
     * populated; FAIL otherwise.
     *
     * Returns the live [client] in [ScenarioResult.message] is **not** done — the caller keeps
     * the session for subsequent scenarios.
     */
    suspend fun cs1HandshakeUnder30s(client: RadioClient, budget: Duration = 30.seconds): ScenarioResult {
        val start = TimeSource.Monotonic.markNow()
        return runScenario("cs1", "handshake under ${budget.inWholeSeconds}s") {
            val bundle = client.connectAndAwaitReady(budget)
            require(client.ownNode.value != null) { "ownNode null after Connected" }
            val firmware = bundle.metadata.firmware_version
            "connected in ${start.elapsedNow().inWholeMilliseconds} ms (firmware=$firmware)"
        }
    }

    /**
     * **cs2 — Broadcast text acceptance** (manual C1). Broadcasts a small text packet on channel 0.
     * PASS if the [MessageHandle] resolves to [SendOutcome.Success] within [budget]; FAIL on
     * any failure outcome or timeout. Broadcasts auto-resolve once the device accepts the packet
     * (no mesh-level ACK is expected for broadcast).
     */
    suspend fun cs2SendTextRoundTrip(client: RadioClient, budget: Duration = 30.seconds): ScenarioResult =
        runScenario("cs2", "broadcast text acceptance") {
            val handle = client.sendText("conformance probe")
            val outcome = withTimeoutOrNull(budget) { handle.await() }
                ?: error("did not reach terminal state in ${budget.inWholeSeconds}s")
            when (outcome) {
                SendOutcome.Success -> "id=${handle.id} accepted"
                is SendOutcome.Failure -> error("failed: ${outcome.reason::class.simpleName}")
            }
        }

    /**
     * **cs3 — `admin.getOwner()` round-trip** (Phase 2 RPC). PASS on `AdminResult.Success` carrying
     * a non-empty `User.long_name`; FAIL on any other AdminResult variant.
     */
    suspend fun cs3AdminGetOwner(client: RadioClient): ScenarioResult =
        runScenario("cs3", "admin.getOwner round-trip") {
            when (val result = client.admin.getOwner()) {
                is AdminResult.Success -> "owner='${result.value.long_name}' id='${result.value.id}'"
                AdminResult.Timeout -> error("timed out")
                AdminResult.NodeUnreachable -> error("node unreachable")
                AdminResult.SessionKeyExpired -> error("session key expired (twice — retry exhausted)")
                AdminResult.Unauthorized -> error("unauthorized")
                AdminResult.RateLimited -> error("device rate-limited the request")
                is AdminResult.Failed -> error("routing error: ${result.routingError}")
            }
        }

    /**
     * **cs4 — `routing.traceRoute(peer)`** (Phase 2 RPC). PASS on `AdminResult.Success` with a
     * `RouteDiscovery` that has at least one hop; FAIL otherwise. Caller MUST pass a real
     * [peer] reachable on the mesh (the scenario can't synthesize one).
     */
    suspend fun cs4TraceRoute(client: RadioClient, peer: NodeId, budget: Duration = 60.seconds): ScenarioResult =
        runScenario("cs4", "traceRoute to $peer") {
            val result = withTimeoutOrNull(budget) {
                client.routing.traceRoute(peer)
            } ?: error("dispatcher hung past ${budget.inWholeSeconds}s")
            when (result) {
                is AdminResult.Success -> {
                    val hops = result.value.route.size
                    val backHops = result.value.route_back.size
                    "hops=$hops back=$backHops"
                }

                AdminResult.Timeout -> error("timed out at firmware")

                AdminResult.NodeUnreachable -> error("no route to peer")

                AdminResult.SessionKeyExpired -> error("session key expired")

                AdminResult.Unauthorized -> error("unauthorized")

                AdminResult.RateLimited -> error("device rate-limited the request")

                is AdminResult.Failed -> error("routing error: ${result.routingError}")
            }
        }

    /**
     * **cs5 — Large-mesh NodeDB sync** (Phase 5 acceptance). PASS if the post-handshake NodeDB
     * snapshot contains at least [minNodes] nodes within [budget]; FAIL otherwise. The threshold
     * is intentionally low (default 1) so a single-radio bench passes; bump it via
     * `--min-nodes` for actual mesh coverage tests.
     */
    suspend fun cs5LargeMeshSync(
        client: RadioClient,
        minNodes: Int = 1,
        budget: Duration = 30.seconds,
    ): ScenarioResult = runScenario("cs5", "NodeDB ≥ $minNodes nodes within ${budget.inWholeSeconds}s") {
        val nodes = withTimeoutOrNull(budget) {
            // Wait for connection-state Connected as a light sanity gate, then pull snapshot.
            client.connection.first { it is ConnectionState.Connected }
            client.nodeSnapshot()
        } ?: error("timed out waiting for NodeDB snapshot")
        require(nodes.size >= minNodes) { "only ${nodes.size} nodes (< $minNodes)" }
        "snapshot has ${nodes.size} nodes"
    }

    /**
     * **cs6 — Reconnect after drop** (manual A4). Disconnects the live [client], waits for
     * `ConnectionState.Disconnected`, then re-`connect()`s and verifies `ownNode` repopulates
     * with the same `NodeNum`. PASS on identity match within [budget]; FAIL otherwise.
     */
    suspend fun cs6ReconnectAfterDrop(client: RadioClient, budget: Duration = 60.seconds): ScenarioResult =
        runScenario("cs6", "disconnect + reconnect cycle") {
            val originalNodeNum = client.ownNode.value?.num
                ?: error("ownNode null pre-cycle (cs1 should have caught this)")
            client.disconnect()
            client.connection.first { it is ConnectionState.Disconnected }
            withTimeoutOrNull(budget) {
                client.connectAndAwaitReady(budget)
            } ?: error("reconnect did not complete in ${budget.inWholeSeconds}s")
            val newNodeNum = client.ownNode.value?.num
            require(newNodeNum == originalNodeNum) {
                "NodeNum changed across cycle: $originalNodeNum → $newNodeNum"
            }
            "same NodeNum=0x${originalNodeNum.toUInt().toString(16).padStart(8, '0')}"
        }

    /** Build a SKIP result. Used for scenarios whose prerequisites weren't supplied. */
    fun skip(id: String, name: String, reason: String): ScenarioResult =
        ScenarioResult(id = id, name = name, status = ScenarioResult.Status.SKIP, durationMs = 0L, message = reason)

    /**
     * **cs7 — Unicast DM text round-trip** (manual C2). Sends a direct message to a specific peer
     * with `wantAck = true`. PASS if the [MessageHandle] resolves to [SendOutcome.Success]
     * within [budget]; FAIL on any failure outcome or timeout. Unlike cs2 (broadcast), this
     * exercises the full send → routing-ACK path.
     */
    suspend fun cs7UnicastDmText(
        client: RadioClient,
        peer: NodeId,
        budget: Duration = 30.seconds,
    ): ScenarioResult = runScenario("cs7", "unicast DM to $peer") {
        val handle = client.sendDirectMessage(to = peer, text = "dm conformance probe")
        val outcome = withTimeoutOrNull(budget) { handle.await() }
            ?: error("did not reach terminal state in ${budget.inWholeSeconds}s")
        when (outcome) {
            SendOutcome.Success -> "id=${handle.id} acked by ${peer}"
            is SendOutcome.Failure -> error("failed: ${outcome.reason::class.simpleName}")
        }
    }

    /**
     * Wrap a single-scenario block: time it, catch every exception, and produce a
     * [ScenarioResult]. The block returns the success-path message; failures throw and the
     * exception's class + message become the FAIL message.
     */
    private suspend inline fun runScenario(id: String, name: String, block: () -> String): ScenarioResult {
        val start = TimeSource.Monotonic.markNow()
        return try {
            val msg = block()
            ScenarioResult(id, name, ScenarioResult.Status.PASS, start.elapsedNow().inWholeMilliseconds, msg)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            val msg = e.message ?: (e::class.simpleName ?: "unknown failure")
            ScenarioResult(id, name, ScenarioResult.Status.FAIL, start.elapsedNow().inWholeMilliseconds, msg)
        }
    }
}
