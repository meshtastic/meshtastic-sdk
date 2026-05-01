/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Shared Compose UI for the parity sample. Identical on Android, iOS, and JVM desktop —
 * the only platform-specific code lives in the host (Activity / ViewController / Window)
 * which constructs the [MeshSampleController] with a writable storage path.
 */
@Composable
fun App(controller: MeshSampleController) {
    val state by controller.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var hostInput by remember { mutableStateOf(state.host) }
    var portInput by remember { mutableStateOf(state.port.toString()) }
    var sendInput by remember { mutableStateOf("hello mesh") }

    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) listState.animateScrollToItem(state.log.lastIndex)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold { padding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = state.status, style = MaterialTheme.typography.titleMedium)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            modifier = Modifier.weight(2f),
                            value = hostInput,
                            onValueChange = {
                                hostInput = it
                                controller.setHost(it)
                            },
                            label = { Text("Host") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = portInput,
                            onValueChange = {
                                portInput = it
                                it.toIntOrNull()?.let(controller::setPort)
                            },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = !state.isWorking,
                            onClick = { controller.connect() },
                        ) { Text("Connect") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = state.isWorking,
                            onClick = { controller.disconnect() },
                        ) { Text("Disconnect") }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            modifier = Modifier.weight(2f),
                            value = sendInput,
                            onValueChange = { sendInput = it },
                            label = { Text("Broadcast text") },
                            singleLine = true,
                        )
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = state.isWorking,
                            onClick = { controller.sendBroadcast(sendInput) },
                        ) { Text("Send") }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(text = "Log", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(state.log) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
