/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sample

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() = application {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val storagePath = System.getProperty("user.home") + "/.meshtastic-parity-sample"
    val controller = MeshSampleController(storagePath = storagePath, scope = scope)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Meshtastic Parity Sample",
        state = rememberWindowState(width = 480.dp, height = 720.dp),
    ) {
        App(controller)
    }
}
