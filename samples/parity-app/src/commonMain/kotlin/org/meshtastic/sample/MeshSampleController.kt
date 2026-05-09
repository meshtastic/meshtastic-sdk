/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sample

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.meshtastic.sdk.NodeChange
import org.meshtastic.sdk.RadioClient
import org.meshtastic.sdk.SendOutcome
import org.meshtastic.sdk.storage.sqldelight.SqlDelightStorageProvider
import org.meshtastic.sdk.transport.tcp.TcpTransport

/** UI snapshot — kept narrow so the same shape works on every platform. */
data class MeshUiState(
    val host: String = "meshtastic.local",
    val port: Int = 4403,
    val status: String = "Idle",
    val isWorking: Boolean = false,
    val log: List<String> = emptyList(),
)

/**
 * Cross-platform controller. The host (Activity / ViewController / Window) supplies a
 * writable directory for SQLDelight and a coroutine scope; the controller owns connection
 * lifecycle.
 *
 * TCP-only by design — see [`docs/samples.md`](https://github.com/meshtastic/meshtastic-sdk/blob/main/docs/samples.md)
 * for why the parity sample doesn't bundle BLE.
 */
class MeshSampleController(private val storagePath: String, private val scope: CoroutineScope) {

    private val _ui = MutableStateFlow(MeshUiState())
    val ui: StateFlow<MeshUiState> = _ui

    private var workJob: Job? = null
    private var client: RadioClient? = null

    fun setHost(host: String) {
        _ui.update { it.copy(host = host) }
    }

    fun setPort(port: Int) {
        _ui.update { it.copy(port = port) }
    }

    fun connect() {
        if (workJob?.isActive == true) return
        val current = _ui.value
        _ui.update { it.copy(status = "Connecting to ${current.host}:${current.port}…", isWorking = true) }
        workJob = scope.launch {
            try {
                val transport = TcpTransport(host = current.host, port = current.port)
                val storage = SqlDelightStorageProvider(baseDir = storagePath)
                val rc = RadioClient.Builder()
                    .transport(transport)
                    .storage(storage)
                    .build()
                client = rc

                rc.connection
                    .onEach { state -> _ui.update { it.copy(status = "State: $state") } }
                    .launchIn(this)

                rc.nodes
                    .onEach { ev ->
                        when (ev) {
                            is NodeChange.Snapshot -> append("[snapshot] ${ev.nodes.size} nodes")
                            is NodeChange.Added -> append("[+] node 0x${ev.node.num.toString(16)}")
                            is NodeChange.Updated -> append("[~] node 0x${ev.node.num.toString(16)}")
                            is NodeChange.Removed -> append("[-] node 0x${ev.nodeId.raw.toString(16)}")
                            is NodeChange.WentOffline -> append("[offline] node 0x${ev.nodeId.raw.toString(16)}")
                            is NodeChange.CameOnline -> append("[online] node 0x${ev.nodeId.raw.toString(16)}")
                        }
                    }
                    .launchIn(this)

                rc.packets
                    .onEach { pkt ->
                        append("[pkt] from=0x${pkt.from.toString(16)} port=${pkt.decoded?.portnum}")
                    }
                    .launchIn(this)

                rc.connect()
                append("Connected.")
            } catch (e: Exception) {
                append("Failed: ${e.message}")
                _ui.update { it.copy(isWorking = false) }
            }
        }
    }

    fun disconnect() {
        workJob?.cancel()
        workJob = null
        scope.launch {
            runCatching { client?.disconnect() }
            client = null
            _ui.update { it.copy(status = "Disconnected", isWorking = false) }
        }
    }

    fun sendBroadcast(text: String) {
        val rc = client ?: run {
            append("Cannot send — not connected")
            return
        }
        if (text.isBlank()) return
        scope.launch {
            try {
                val handle = rc.sendText(text)
                append("[send] queued id=${handle.id}")
                when (val outcome = handle.await()) {
                    SendOutcome.Success -> append("[send] acked id=${handle.id}")
                    is SendOutcome.Failure -> append("[send] failed id=${handle.id}: ${outcome.reason}")
                }
            } catch (e: Exception) {
                append("[send] error: ${e.message}")
            }
        }
    }

    private fun append(line: String) {
        _ui.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    companion object {
        private const val MAX_LOG_LINES = 200
    }
}
