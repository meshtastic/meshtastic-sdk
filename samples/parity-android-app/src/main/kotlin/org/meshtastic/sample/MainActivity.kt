/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import org.meshtastic.sdk.storage.sqldelight.AndroidContextHolder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SqlDelightStorageProvider's Android driver looks up the application context here.
        AndroidContextHolder.context = applicationContext

        val controller = MeshSampleController(
            storagePath = applicationContext.filesDir.absolutePath,
            scope = lifecycleScope,
        )
        setContent { App(controller) }
    }
}
