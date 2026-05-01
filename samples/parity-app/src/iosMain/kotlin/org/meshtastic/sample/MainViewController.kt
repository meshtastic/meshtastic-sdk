/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.meshtastic.sample

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIViewController

/**
 * Entry point consumed by the SwiftUI host (`ContentView.swift`) via
 * `MainViewControllerKt.MainViewController()`.
 *
 * Function name is intentionally PascalCase: SKIE/Kotlin-Native bridges this directly to the
 * Swift static method `MainViewControllerKt.MainViewController()`, and Swift convention expects
 * type-like factory names to start with an uppercase letter.
 */
@Suppress("ktlint:standard:function-naming")
fun MainViewController(): UIViewController {
    val docs = NSFileManager.defaultManager
        .URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        .firstOrNull() as? NSURL
    val storagePath = docs?.path.orEmpty()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val controller = MeshSampleController(storagePath = storagePath, scope = scope)

    return ComposeUIViewController { App(controller) }
}
