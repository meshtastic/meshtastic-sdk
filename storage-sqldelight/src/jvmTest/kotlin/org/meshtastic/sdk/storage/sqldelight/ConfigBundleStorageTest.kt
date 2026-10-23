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
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.sdk.ConfigBundle
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.TransportIdentity
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigBundleStorageTest {

    private val deviceUI = DeviceUIConfig.Builder().version(3).screen_brightness(42).screen_lock(true).build()

    private fun bundle(deviceUIConfig: DeviceUIConfig?) = ConfigBundle(
        myInfo = MyNodeInfo.Builder().also { it.my_node_num = 42 }.build(),
        metadata = DeviceMetadata.Builder().also { it.firmware_version = "2.7.0" }.build(),
        configs = listOf(Config.Builder().build()),
        moduleConfigs = listOf(ModuleConfig.Builder().build()),
        deviceUIConfig = deviceUIConfig,
    )

    @Test
    fun `saveConfig and loadConfig round-trip the whole bundle`() = runTest {
        val driver = createDriver(":memory:")
        try {
            val storage = SqlDelightStorage(driver)
            val saved = bundle(deviceUI)
            storage.saveConfig(saved)
            assertEquals(saved, storage.loadConfig())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `saving a bundle without device UI config removes the stored one`() = runTest {
        val driver = createDriver(":memory:")
        try {
            val storage = SqlDelightStorage(driver)
            storage.saveConfig(bundle(deviceUI))
            storage.saveConfig(bundle(null))
            val loaded = assertNotNull(storage.loadConfig())
            assertNull(loaded.deviceUIConfig)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `device UI config saved before the first bind survives reopening`() = runTest {
        val baseDir = Files.createTempDirectory("mesh-config-test").absolutePathString()
        try {
            val provider = SqlDelightStorageProvider(baseDir)
            val identity = TransportIdentity("ble:AA:BB:CC:DD:EE:FF")
            provider.activate(identity).apply {
                saveConfig(bundle(deviceUI)) // deferred: buffered until recordOwnNode
                recordOwnNode(NodeId(42), "2.7.0")
                close()
            }
            val reopened = provider.activate(identity)
            assertEquals(deviceUI, reopened.loadConfig()?.deviceUIConfig)
            reopened.close()
        } finally {
            FileSystem.SYSTEM.deleteRecursively(baseDir.toPath())
        }
    }
}
