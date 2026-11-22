/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:Suppress("DEPRECATION")

/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.meshtastic.sdk

import kotlinx.coroutines.test.runTest
import okio.ByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceConnectionStatus
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.HamParameters
import org.meshtastic.proto.KeyVerificationAdmin
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.NodeRemoteHardwarePinsResponse
import org.meshtastic.proto.Position
import org.meshtastic.proto.Routing
import org.meshtastic.proto.SensorConfig
import org.meshtastic.proto.SharedContact
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

class ConfigBuildersTest {

    @Test
    fun deviceConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config.Builder().also { wb ->
                wb.device = Config.DeviceConfig.Builder().also { wb ->
                    wb.role = Config.DeviceConfig.Role.TRACKER
                    wb.serial_enabled = true
                    wb.button_gpio = 23
                    wb.buzzer_gpio = 12
                }.build()
            }.build(),
        ) {
            setDeviceConfig {
                this.newBuilder().also { wb ->
                    wb.role = Config.DeviceConfig.Role.TRACKER
                    wb.serial_enabled = true
                    wb.button_gpio = 23
                    wb.buzzer_gpio = 12
                }.build()
            }
        }
    }

    @Test
    fun loraConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config.Builder().also { wb ->
                wb.lora = Config.LoRaConfig.Builder().also { wb ->
                    wb.use_preset = true
                    wb.region = Config.LoRaConfig.RegionCode.EU_868
                    wb.modem_preset = Config.LoRaConfig.ModemPreset.SHORT_FAST
                    wb.bandwidth = 250
                    wb.spread_factor = 9
                }.build()
            }.build(),
        ) {
            setLoraConfig {
                this.newBuilder().also { wb ->
                    wb.use_preset = true
                    wb.region = Config.LoRaConfig.RegionCode.EU_868
                    wb.modem_preset = Config.LoRaConfig.ModemPreset.SHORT_FAST
                    wb.bandwidth = 250
                    wb.spread_factor = 9
                }.build()
            }
        }
    }

    @Test
    fun bluetoothConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config.Builder().also { wb ->
                wb.bluetooth = Config.BluetoothConfig.Builder().also { wb ->
                    wb.enabled = true
                    wb.fixed_pin = 123456
                    wb.mode = Config.BluetoothConfig.PairingMode.FIXED_PIN
                }.build()
            }.build(),
        ) {
            setBluetoothConfig {
                this.newBuilder().also { wb ->
                    wb.enabled = true
                    wb.fixed_pin = 123456
                    wb.mode = Config.BluetoothConfig.PairingMode.FIXED_PIN
                }.build()
            }
        }
    }

    @Test
    fun displayConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config.Builder().also { wb ->
                wb.display = Config.DisplayConfig.Builder().also { wb ->
                    wb.screen_on_secs = 45
                    wb.gps_format = Config.DisplayConfig.DeprecatedGpsCoordinateFormat.UNUSED
                    wb.units = Config.DisplayConfig.DisplayUnits.IMPERIAL
                    wb.flip_screen = true
                }.build()
            }.build(),
        ) {
            setDisplayConfig {
                this.newBuilder().also { wb ->
                    wb.screen_on_secs = 45
                    wb.gps_format = Config.DisplayConfig.DeprecatedGpsCoordinateFormat.UNUSED
                    wb.units = Config.DisplayConfig.DisplayUnits.IMPERIAL
                    wb.flip_screen = true
                }.build()
            }
        }
    }

    @Test
    fun networkConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config.Builder().also { wb ->
                wb.network = Config.NetworkConfig.Builder().also { wb ->
                    wb.wifi_enabled = true
                    wb.wifi_ssid = "mesh-wifi"
                    wb.wifi_psk = "super-secret"
                    wb.eth_enabled = true
                }.build()
            }.build(),
        ) {
            setNetworkConfig {
                this.newBuilder().also { wb ->
                    wb.wifi_enabled = true
                    wb.wifi_ssid = "mesh-wifi"
                    wb.wifi_psk = "super-secret"
                    wb.eth_enabled = true
                }.build()
            }
        }
    }

    @Test
    fun positionConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config.Builder().also { wb ->
                wb.position = Config.PositionConfig.Builder().also { wb ->
                    wb.gps_enabled = true
                    wb.fixed_position = true
                    wb.position_broadcast_secs = 300
                    wb.gps_mode = Config.PositionConfig.GpsMode.ENABLED
                }.build()
            }.build(),
        ) {
            setPositionConfig {
                this.newBuilder().also { wb ->
                    wb.gps_enabled = true
                    wb.fixed_position = true
                    wb.position_broadcast_secs = 300
                    wb.gps_mode = Config.PositionConfig.GpsMode.ENABLED
                }.build()
            }
        }
    }

    @Test
    fun powerConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config.Builder().also { wb ->
                wb.power = Config.PowerConfig.Builder().also { wb ->
                    wb.is_power_saving = true
                    wb.on_battery_shutdown_after_secs = 90
                    wb.wait_bluetooth_secs = 15
                }.build()
            }.build(),
        ) {
            setPowerConfig {
                this.newBuilder().also { wb ->
                    wb.is_power_saving = true
                    wb.on_battery_shutdown_after_secs = 90
                    wb.wait_bluetooth_secs = 15
                }.build()
            }
        }
    }

    @Test
    fun securityConfigBuilderWrapsExpectedFields() = runTest {
        val publicKey = bytes(1, 2, 3)
        val privateKey = bytes(4, 5, 6)
        val adminKey = bytes(7, 8, 9)

        assertConfigWrite(
            Config.Builder().also { wb ->
                wb.security = Config.SecurityConfig.Builder().also { wb ->
                    wb.public_key = publicKey
                    wb.private_key = privateKey
                    wb.admin_key = listOf(adminKey)
                    wb.serial_enabled = true
                }.build()
            }.build(),
        ) {
            setSecurityConfig {
                this.newBuilder().also { wb ->
                    wb.public_key = publicKey
                    wb.private_key = privateKey
                    wb.admin_key = listOf(adminKey)
                    wb.serial_enabled = true
                }.build()
            }
        }
    }

    @Test
    fun multipleConfigBuilderCallsComposeExpectedConfigs() = runTest {
        val admin = CapturingAdminApi()
        val expectedResult = AdminResult.Success(Unit)

        assertEquals(
            expectedResult,
            admin.setDeviceConfig {
                this.newBuilder().also { wb ->
                    wb.role = Config.DeviceConfig.Role.CLIENT_HIDDEN
                    wb.button_gpio = 5
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setNetworkConfig {
                this.newBuilder().also { wb ->
                    wb.wifi_enabled = true
                    wb.wifi_ssid = "mesh"
                    wb.wifi_psk = "secret"
                    wb.eth_enabled = true
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setLoraConfig {
                this.newBuilder().also { wb ->
                    wb.region = Config.LoRaConfig.RegionCode.US
                    wb.modem_preset = Config.LoRaConfig.ModemPreset.LONG_TURBO
                    wb.bandwidth = 500
                    wb.spread_factor = 7
                }.build()
            },
        )

        assertEquals(
            listOf(
                Config.Builder().also { wb ->
                    wb.device = Config.DeviceConfig.Builder().also { wb ->
                        wb.role = Config.DeviceConfig.Role.CLIENT_HIDDEN
                        wb.button_gpio = 5
                    }.build()
                }.build(),
                Config.Builder().also { wb ->
                    wb.network = Config.NetworkConfig.Builder().also { wb ->
                        wb.wifi_enabled = true
                        wb.wifi_ssid = "mesh"
                        wb.wifi_psk = "secret"
                        wb.eth_enabled = true
                    }.build()
                }.build(),
                Config.Builder().also { wb ->
                    wb.lora = Config.LoRaConfig.Builder().also { wb ->
                        wb.region = Config.LoRaConfig.RegionCode.US
                        wb.modem_preset = Config.LoRaConfig.ModemPreset.LONG_TURBO
                        wb.bandwidth = 500
                        wb.spread_factor = 7
                    }.build()
                }.build(),
            ),
            admin.configs,
        )
    }

    @Test
    fun configBuildersAllowOutOfRangeScalarValuesWithoutCrashing() = runTest {
        val admin = CapturingAdminApi()
        val expectedResult = AdminResult.Success(Unit)

        assertEquals(
            expectedResult,
            admin.setDeviceConfig {
                this.newBuilder().also { wb ->
                    wb.button_gpio = -1
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setLoraConfig {
                this.newBuilder().also { wb ->
                    wb.bandwidth = -1
                    wb.spread_factor = -7
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setBluetoothConfig {
                this.newBuilder().also { wb ->
                    wb.fixed_pin = -1
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setDisplayConfig {
                this.newBuilder().also { wb ->
                    wb.screen_on_secs = -1
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setPositionConfig {
                this.newBuilder().also { wb ->
                    wb.position_broadcast_secs = -1
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setPowerConfig {
                this.newBuilder().also { wb ->
                    wb.on_battery_shutdown_after_secs = -1
                }.build()
            },
        )

        assertEquals(
            listOf(
                Config.Builder().also { wb ->
                    wb.device = Config.DeviceConfig.Builder().also { wb ->
                        wb.button_gpio = -1
                    }.build()
                }.build(),
                Config.Builder().also { wb ->
                    wb.lora = Config.LoRaConfig.Builder().also { wb ->
                        wb.bandwidth = -1
                        wb.spread_factor = -7
                    }.build()
                }.build(),
                Config.Builder().also { wb ->
                    wb.bluetooth = Config.BluetoothConfig.Builder().also { wb ->
                        wb.fixed_pin = -1
                    }.build()
                }.build(),
                Config.Builder().also { wb ->
                    wb.display = Config.DisplayConfig.Builder().also { wb ->
                        wb.screen_on_secs = -1
                    }.build()
                }.build(),
                Config.Builder().also { wb ->
                    wb.position = Config.PositionConfig.Builder().also { wb ->
                        wb.position_broadcast_secs = -1
                    }.build()
                }.build(),
                Config.Builder().also { wb ->
                    wb.power = Config.PowerConfig.Builder().also { wb ->
                        wb.on_battery_shutdown_after_secs = -1
                    }.build()
                }.build(),
            ),
            admin.configs,
        )
    }

    @Test
    fun moduleConfigBuildersWrapExpectedSections() = runTest {
        val admin = CapturingAdminApi()
        val expectedResult = AdminResult.Success(Unit)

        assertEquals(
            expectedResult,
            admin.setMqttConfig {
                this.newBuilder().also { wb ->
                    wb.enabled = true
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setSerialConfig {
                this.newBuilder().also { wb ->
                    wb.enabled = true
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setExternalNotificationConfig {
                this.newBuilder().also { wb ->
                    wb.enabled = true
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setStoreForwardConfig {
                this.newBuilder().also { wb ->
                    wb.enabled = true
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setRangeTestConfig {
                this.newBuilder().also { wb ->
                    wb.sender = 7
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setTelemetryConfig {
                this.newBuilder().also { wb ->
                    wb.device_update_interval = 60
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setCannedMessageConfig {
                this.newBuilder().also { wb ->
                    wb.rotary1_enabled = true
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setAudioConfig {
                this.newBuilder().also { wb ->
                    wb.codec2_enabled = true
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setRemoteHardwareConfig {
                this.newBuilder().also { wb ->
                    wb.enabled = true
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setNeighborInfoConfig {
                this.newBuilder().also { wb ->
                    wb.enabled = true
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setAmbientLightingConfig {
                this.newBuilder().also { wb ->
                    wb.led_state = true
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setDetectionSensorConfig {
                this.newBuilder().also { wb ->
                    wb.enabled = true
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setPaxcounterConfig {
                this.newBuilder().also { wb ->
                    wb.enabled = true
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setStatusMessageConfig {
                this.newBuilder().also { wb ->
                    wb.node_status = "ready"
                }.build()
            },
        )
        assertEquals(
            expectedResult,
            admin.setTrafficManagementConfig {
                this.newBuilder().also { wb ->
                    wb.position_min_interval_secs = 60
                }.build()
            },
        )

        assertEquals(
            listOf(
                ModuleConfig.Builder().also { wb ->
                    wb.mqtt = ModuleConfig.MQTTConfig.Builder().also { wb ->
                        wb.enabled = true
                    }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.serial = ModuleConfig.SerialConfig.Builder().also { wb ->
                        wb.enabled = true
                    }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.external_notification =
                        ModuleConfig.ExternalNotificationConfig.Builder().also { wb ->
                            wb.enabled = true
                        }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.store_forward =
                        ModuleConfig.StoreForwardConfig.Builder().also { wb ->
                            wb.enabled = true
                        }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.range_test = ModuleConfig.RangeTestConfig.Builder().also { wb ->
                        wb.sender = 7
                    }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.telemetry = ModuleConfig.TelemetryConfig.Builder().also { wb ->
                        wb.device_update_interval = 60
                    }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.canned_message =
                        ModuleConfig.CannedMessageConfig.Builder().also { wb ->
                            wb.rotary1_enabled = true
                        }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.audio = ModuleConfig.AudioConfig.Builder().also { wb ->
                        wb.codec2_enabled = true
                    }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.remote_hardware =
                        ModuleConfig.RemoteHardwareConfig.Builder().also { wb ->
                            wb.enabled = true
                        }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.neighbor_info =
                        ModuleConfig.NeighborInfoConfig.Builder().also { wb ->
                            wb.enabled = true
                        }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.ambient_lighting =
                        ModuleConfig.AmbientLightingConfig.Builder().also { wb ->
                            wb.led_state = true
                        }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.detection_sensor =
                        ModuleConfig.DetectionSensorConfig.Builder().also { wb ->
                            wb.enabled = true
                        }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.paxcounter = ModuleConfig.PaxcounterConfig.Builder().also { wb ->
                        wb.enabled = true
                    }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.statusmessage =
                        ModuleConfig.StatusMessageConfig.Builder().also { wb ->
                            wb.node_status = "ready"
                        }.build()
                }.build(),
                ModuleConfig.Builder().also { wb ->
                    wb.traffic_management = ModuleConfig.TrafficManagementConfig.Builder().also { wb ->
                        wb.position_min_interval_secs = 60
                    }.build()
                }.build(),
            ),
            admin.moduleConfigs,
        )
    }

    @Test
    fun everyConfigBuilderStartsFromTheCurrentSection() = runTest {
        for (case in configCases) {
            val admin = CapturingAdminApi(readConfig = { AdminResult.Success(case.current) })
            assertEquals(AdminResult.Success(Unit), case.sendUnchanged(admin), case.name)
            assertEquals(listOf(case.type), admin.configReads, case.name)
            assertEquals(listOf(case.current), admin.configs, case.name)
            assertTrue(admin.moduleConfigs.isEmpty(), case.name)
        }
    }

    @Test
    fun everyModuleConfigBuilderStartsFromTheCurrentSection() = runTest {
        for (case in moduleConfigCases) {
            val admin = CapturingAdminApi(readModuleConfig = { AdminResult.Success(case.current) })
            assertEquals(AdminResult.Success(Unit), case.sendUnchanged(admin), case.name)
            assertEquals(listOf(case.type), admin.moduleConfigReads, case.name)
            assertEquals(listOf(case.current), admin.moduleConfigs, case.name)
            assertTrue(admin.configs.isEmpty(), case.name)
        }
    }

    @Test
    fun loraBuilderKeepsRegionAndTxEnabledWhenOnlyHopLimitChanges() = runTest {
        val current = Config.LoRaConfig.Builder().also { wb ->
            wb.use_preset = true
            wb.region = Config.LoRaConfig.RegionCode.US
            wb.modem_preset = Config.LoRaConfig.ModemPreset.LONG_FAST
            wb.hop_limit = 3
            wb.tx_enabled = true
        }.build()
        val admin = CapturingAdminApi(
            readConfig = { AdminResult.Success(Config.Builder().also { wb -> wb.lora = current }.build()) },
        )

        val result = admin.setLoraConfig {
            assertEquals(current, this)
            newBuilder().also { wb -> wb.hop_limit = 5 }.build()
        }

        assertEquals(AdminResult.Success(Unit), result)
        val sent = admin.configs.single().lora
        assertEquals(current.newBuilder().also { wb -> wb.hop_limit = 5 }.build(), sent)
        assertEquals(Config.LoRaConfig.RegionCode.US, sent?.region)
        assertEquals(true, sent?.tx_enabled)
    }

    @Test
    fun mqttBuilderKeepsAddressWhenOnlyEnabledChanges() = runTest {
        val current = ModuleConfig.MQTTConfig.Builder().also { wb ->
            wb.address = "mqtt.example.org"
            wb.username = "mesh"
            wb.encryption_enabled = true
        }.build()
        val admin = CapturingAdminApi(
            readModuleConfig = { AdminResult.Success(ModuleConfig.Builder().also { wb -> wb.mqtt = current }.build()) },
        )

        val result = admin.setMqttConfig { newBuilder().also { wb -> wb.enabled = true }.build() }

        assertEquals(AdminResult.Success(Unit), result)
        assertEquals(current.newBuilder().also { wb -> wb.enabled = true }.build(), admin.moduleConfigs.single().mqtt)
    }

    @Test
    fun everyBuilderSendsNothingWhenTheReadFails() = runTest {
        for (case in configCases) {
            val admin = CapturingAdminApi(readConfig = { AdminResult.Timeout })
            assertEquals(AdminResult.Timeout, case.sendUnchanged(admin), case.name)
            assertTrue(admin.configs.isEmpty(), case.name)
        }
        for (case in moduleConfigCases) {
            val admin = CapturingAdminApi(readModuleConfig = { AdminResult.Unauthorized })
            assertEquals(AdminResult.Unauthorized, case.sendUnchanged(admin), case.name)
            assertTrue(admin.moduleConfigs.isEmpty(), case.name)
        }
    }

    @Test
    fun everyBuilderSendsNothingWhenTheResponseLacksItsSection() = runTest {
        val noResponse = AdminResult.Failed(Routing.Error.NO_RESPONSE)
        for (case in configCases) {
            val admin = CapturingAdminApi(readConfig = { AdminResult.Success(Config.Builder().build()) })
            var blockRan = false
            assertEquals(noResponse, case.send(admin) { blockRan = true }, case.name)
            assertTrue(admin.configs.isEmpty(), case.name)
            assertFalse(blockRan, case.name)
        }
        for (case in moduleConfigCases) {
            val admin = CapturingAdminApi(readModuleConfig = { AdminResult.Success(ModuleConfig.Builder().build()) })
            var blockRan = false
            assertEquals(noResponse, case.send(admin) { blockRan = true }, case.name)
            assertTrue(admin.moduleConfigs.isEmpty(), case.name)
            assertFalse(blockRan, case.name)
        }
    }

    private suspend fun assertConfigWrite(expected: Config, call: suspend CapturingAdminApi.() -> AdminResult<Unit>) {
        val admin = CapturingAdminApi()
        assertEquals(AdminResult.Success(Unit), admin.call())
        assertEquals(listOf(expected), admin.configs)
        assertTrue(admin.moduleConfigs.isEmpty())
    }
}

private class CapturingAdminApi(
    private val readConfig: (
        AdminMessage.ConfigType,
    ) -> AdminResult<Config> = { AdminResult.Success(emptySection(it)) },
    private val readModuleConfig: (AdminMessage.ModuleConfigType) -> AdminResult<ModuleConfig> = {
        AdminResult.Success(emptyModuleSection(it))
    },
) : AdminApi {
    val configs = mutableListOf<Config>()
    val moduleConfigs = mutableListOf<ModuleConfig>()
    val configReads = mutableListOf<AdminMessage.ConfigType>()
    val moduleConfigReads = mutableListOf<AdminMessage.ModuleConfigType>()

    override fun forNode(dest: NodeId): AdminApi = this

    override fun withRpcTimeout(timeout: Duration): AdminApi = this

    override suspend fun getDeviceMetadata(): AdminResult<DeviceMetadata> = unused()

    override suspend fun getConfig(type: AdminMessage.ConfigType): AdminResult<Config> {
        configReads += type
        return readConfig(type)
    }

    override suspend fun setConfig(config: Config): AdminResult<Unit> {
        configs += config
        return AdminResult.Success(Unit)
    }

    override suspend fun getModuleConfig(type: AdminMessage.ModuleConfigType): AdminResult<ModuleConfig> {
        moduleConfigReads += type
        return readModuleConfig(type)
    }

    override suspend fun setModuleConfig(config: ModuleConfig): AdminResult<Unit> {
        moduleConfigs += config
        return AdminResult.Success(Unit)
    }

    override suspend fun getOwner(): AdminResult<User> = unused()

    override suspend fun setOwner(user: User): AdminResult<Unit> = unused()

    override suspend fun getChannel(index: ChannelIndex): AdminResult<Channel> = unused()

    override suspend fun setChannel(channel: Channel): AdminResult<Unit> = unused()

    override suspend fun listChannels(): AdminResult<List<Channel>> = unused()

    override suspend fun setFavorite(node: NodeId, favorite: Boolean): AdminResult<Unit> = unused()

    override suspend fun setIgnored(node: NodeId, ignored: Boolean): AdminResult<Unit> = unused()

    override suspend fun toggleMuted(node: NodeId): AdminResult<Unit> = unused()

    override suspend fun setFixedPosition(position: Position): AdminResult<Unit> = unused()

    override suspend fun removeFixedPosition(): AdminResult<Unit> = unused()

    override suspend fun getUIConfig(): AdminResult<DeviceUIConfig> = unused()

    override suspend fun storeUIConfig(config: DeviceUIConfig): AdminResult<Unit> = unused()

    override suspend fun getCannedMessages(): AdminResult<String> = unused()

    override suspend fun setCannedMessages(messages: String): AdminResult<Unit> = unused()

    override suspend fun getRingtone(): AdminResult<String> = unused()

    override suspend fun setRingtone(rtttl: String): AdminResult<Unit> = unused()

    override suspend fun getDeviceConnectionStatus(): AdminResult<DeviceConnectionStatus> = unused()

    override suspend fun getRemoteHardwarePins(): AdminResult<NodeRemoteHardwarePinsResponse> = unused()

    override suspend fun setHamMode(params: HamParameters): AdminResult<Unit> = unused()

    override suspend fun enterDfuMode(): AdminResult<Unit> = unused()

    override suspend fun deleteFile(path: String): AdminResult<Unit> = unused()

    override suspend fun backupPreferences(location: AdminMessage.BackupLocation): AdminResult<Unit> = unused()

    override suspend fun restorePreferences(location: AdminMessage.BackupLocation): AdminResult<Unit> = unused()

    override suspend fun removeBackupPreferences(location: AdminMessage.BackupLocation): AdminResult<Unit> = unused()

    override suspend fun removeNode(node: NodeId): AdminResult<Unit> = unused()

    override suspend fun setScale(scale: Int): AdminResult<Unit> = unused()

    override suspend fun sendInputEvent(event: AdminMessage.InputEvent): AdminResult<Unit> = unused()

    override suspend fun setDisplayMirror(enabled: Boolean): AdminResult<Unit> = unused()

    override suspend fun requestDisplayFrame(): AdminResult<Unit> = unused()

    override suspend fun addContact(contact: SharedContact): AdminResult<Unit> = unused()

    override suspend fun keyVerification(verification: KeyVerificationAdmin): AdminResult<Unit> = unused()

    override suspend fun lockdown(auth: org.meshtastic.proto.LockdownAuth): AdminResult<Unit> = unused()

    override suspend fun rebootOta(after: Duration): AdminResult<Unit> = unused()

    override suspend fun otaRequest(event: AdminMessage.OTAEvent): AdminResult<Unit> = unused()

    override suspend fun setSensorConfig(config: SensorConfig): AdminResult<Unit> = unused()

    override suspend fun exitSimulator(): AdminResult<Unit> = unused()

    override suspend fun reboot(after: Duration): AdminResult<Unit> = unused()

    override suspend fun shutdown(after: Duration): AdminResult<Unit> = unused()

    override suspend fun factoryReset(preserveBleBonds: Boolean): AdminResult<Unit> = unused()

    override suspend fun nodeDbReset(preserveFavorites: Boolean): AdminResult<Unit> = unused()

    override suspend fun setTimeOnly(unixTime: Int): AdminResult<Unit> = unused()

    override suspend fun setTime(at: Instant?): AdminResult<Unit> = unused()

    override suspend fun <T> editSettings(block: suspend AdminEdit.() -> T): AdminResult<T> = unused()

    override suspend fun <T> batch(block: suspend AdminBatchScope.() -> T): T = unused()
}

private fun unused(): Nothing = error("unused in ConfigBuildersTest")

private fun bytes(vararg values: Int): ByteString =
    ByteString.of(*ByteArray(values.size) { index -> values[index].toByte() })

private class BuilderCase<K, C>(
    val name: String,
    val type: K,
    val current: C,
    private val call: suspend AdminApi.(onBlock: () -> Unit) -> AdminResult<Unit>,
) {
    suspend fun send(admin: AdminApi, onBlock: () -> Unit): AdminResult<Unit> = admin.call(onBlock)

    suspend fun sendUnchanged(admin: AdminApi): AdminResult<Unit> = send(admin) {}
}

private fun config(fill: (Config.Builder) -> Unit): Config = Config.Builder().also(fill).build()

private fun moduleConfig(fill: (ModuleConfig.Builder) -> Unit): ModuleConfig = ModuleConfig.Builder().also(fill).build()

private val configCases: List<BuilderCase<AdminMessage.ConfigType, Config>> = listOf(
    BuilderCase(
        "device",
        AdminMessage.ConfigType.DEVICE_CONFIG,
        config { wb ->
            wb.device = Config.DeviceConfig.Builder().also { wb ->
                wb.role = Config.DeviceConfig.Role.ROUTER
                wb.button_gpio = 7
            }.build()
        },
    ) { onBlock ->
        setDeviceConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "position",
        AdminMessage.ConfigType.POSITION_CONFIG,
        config { wb ->
            wb.position = Config.PositionConfig.Builder().also { wb ->
                wb.position_broadcast_secs = 900
                wb.gps_mode = Config.PositionConfig.GpsMode.ENABLED
            }.build()
        },
    ) { onBlock ->
        setPositionConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "power",
        AdminMessage.ConfigType.POWER_CONFIG,
        config { wb ->
            wb.power = Config.PowerConfig.Builder().also { wb ->
                wb.is_power_saving = true
                wb.wait_bluetooth_secs = 30
            }.build()
        },
    ) { onBlock ->
        setPowerConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "network",
        AdminMessage.ConfigType.NETWORK_CONFIG,
        config { wb ->
            wb.network = Config.NetworkConfig.Builder().also { wb ->
                wb.wifi_enabled = true
                wb.wifi_ssid = "mesh"
                wb.wifi_psk = "sekrit"
            }.build()
        },
    ) { onBlock ->
        setNetworkConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "display",
        AdminMessage.ConfigType.DISPLAY_CONFIG,
        config { wb ->
            wb.display = Config.DisplayConfig.Builder().also { wb ->
                wb.screen_on_secs = 45
                wb.flip_screen = true
            }.build()
        },
    ) { onBlock ->
        setDisplayConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "lora",
        AdminMessage.ConfigType.LORA_CONFIG,
        config { wb ->
            wb.lora = Config.LoRaConfig.Builder().also { wb ->
                wb.region = Config.LoRaConfig.RegionCode.EU_868
                wb.hop_limit = 4
                wb.tx_enabled = true
            }.build()
        },
    ) { onBlock ->
        setLoraConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "bluetooth",
        AdminMessage.ConfigType.BLUETOOTH_CONFIG,
        config { wb ->
            wb.bluetooth = Config.BluetoothConfig.Builder().also { wb ->
                wb.enabled = true
                wb.fixed_pin = 123456
            }.build()
        },
    ) { onBlock ->
        setBluetoothConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "security",
        AdminMessage.ConfigType.SECURITY_CONFIG,
        config { wb ->
            wb.security = Config.SecurityConfig.Builder().also { wb ->
                wb.is_managed = true
                wb.admin_key = listOf(bytes(7, 8, 9))
            }.build()
        },
    ) { onBlock ->
        setSecurityConfig {
            onBlock()
            this
        }
    },
)

private val moduleConfigCases: List<BuilderCase<AdminMessage.ModuleConfigType, ModuleConfig>> = listOf(
    BuilderCase(
        "mqtt",
        AdminMessage.ModuleConfigType.MQTT_CONFIG,
        moduleConfig { wb ->
            wb.mqtt = ModuleConfig.MQTTConfig.Builder().also { wb ->
                wb.enabled = true
                wb.address = "mqtt.example.org"
            }.build()
        },
    ) { onBlock ->
        setMqttConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "serial",
        AdminMessage.ModuleConfigType.SERIAL_CONFIG,
        moduleConfig { wb -> wb.serial = ModuleConfig.SerialConfig.Builder().also { wb -> wb.enabled = true }.build() },
    ) { onBlock ->
        setSerialConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "external_notification",
        AdminMessage.ModuleConfigType.EXTNOTIF_CONFIG,
        moduleConfig { wb ->
            wb.external_notification =
                ModuleConfig.ExternalNotificationConfig.Builder().also { wb -> wb.enabled = true }.build()
        },
    ) { onBlock ->
        setExternalNotificationConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "store_forward",
        AdminMessage.ModuleConfigType.STOREFORWARD_CONFIG,
        moduleConfig { wb ->
            wb.store_forward = ModuleConfig.StoreForwardConfig.Builder().also { wb -> wb.enabled = true }.build()
        },
    ) { onBlock ->
        setStoreForwardConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "range_test",
        AdminMessage.ModuleConfigType.RANGETEST_CONFIG,
        moduleConfig { wb ->
            wb.range_test = ModuleConfig.RangeTestConfig.Builder().also { wb -> wb.sender = 7 }.build()
        },
    ) { onBlock ->
        setRangeTestConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "telemetry",
        AdminMessage.ModuleConfigType.TELEMETRY_CONFIG,
        moduleConfig { wb ->
            wb.telemetry = ModuleConfig.TelemetryConfig.Builder().also { wb -> wb.device_update_interval = 60 }.build()
        },
    ) { onBlock ->
        setTelemetryConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "canned_message",
        AdminMessage.ModuleConfigType.CANNEDMSG_CONFIG,
        moduleConfig { wb ->
            wb.canned_message =
                ModuleConfig.CannedMessageConfig.Builder().also { wb -> wb.rotary1_enabled = true }.build()
        },
    ) { onBlock ->
        setCannedMessageConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "audio",
        AdminMessage.ModuleConfigType.AUDIO_CONFIG,
        moduleConfig { wb ->
            wb.audio =
                ModuleConfig.AudioConfig.Builder().also { wb -> wb.codec2_enabled = true }.build()
        },
    ) { onBlock ->
        setAudioConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "remote_hardware",
        AdminMessage.ModuleConfigType.REMOTEHARDWARE_CONFIG,
        moduleConfig { wb ->
            wb.remote_hardware = ModuleConfig.RemoteHardwareConfig.Builder().also { wb -> wb.enabled = true }.build()
        },
    ) { onBlock ->
        setRemoteHardwareConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "neighbor_info",
        AdminMessage.ModuleConfigType.NEIGHBORINFO_CONFIG,
        moduleConfig { wb ->
            wb.neighbor_info = ModuleConfig.NeighborInfoConfig.Builder().also { wb -> wb.enabled = true }.build()
        },
    ) { onBlock ->
        setNeighborInfoConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "ambient_lighting",
        AdminMessage.ModuleConfigType.AMBIENTLIGHTING_CONFIG,
        moduleConfig { wb ->
            wb.ambient_lighting =
                ModuleConfig.AmbientLightingConfig.Builder().also { wb -> wb.led_state = true }.build()
        },
    ) { onBlock ->
        setAmbientLightingConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "detection_sensor",
        AdminMessage.ModuleConfigType.DETECTIONSENSOR_CONFIG,
        moduleConfig { wb ->
            wb.detection_sensor = ModuleConfig.DetectionSensorConfig.Builder().also { wb -> wb.enabled = true }.build()
        },
    ) { onBlock ->
        setDetectionSensorConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "paxcounter",
        AdminMessage.ModuleConfigType.PAXCOUNTER_CONFIG,
        moduleConfig { wb ->
            wb.paxcounter = ModuleConfig.PaxcounterConfig.Builder().also { wb -> wb.enabled = true }.build()
        },
    ) { onBlock ->
        setPaxcounterConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "statusmessage",
        AdminMessage.ModuleConfigType.STATUSMESSAGE_CONFIG,
        moduleConfig { wb ->
            wb.statusmessage =
                ModuleConfig.StatusMessageConfig.Builder().also { wb -> wb.node_status = "ready" }.build()
        },
    ) { onBlock ->
        setStatusMessageConfig {
            onBlock()
            this
        }
    },
    BuilderCase(
        "traffic_management",
        AdminMessage.ModuleConfigType.TRAFFICMANAGEMENT_CONFIG,
        moduleConfig { wb ->
            wb.traffic_management = ModuleConfig.TrafficManagementConfig.Builder().also { wb ->
                wb.position_min_interval_secs = 60
            }.build()
        },
    ) { onBlock ->
        setTrafficManagementConfig {
            onBlock()
            this
        }
    },
)

/** An empty section of [type], which the other tests' expectations are written against. */
private fun emptySection(type: AdminMessage.ConfigType): Config = config { wb ->
    when (type) {
        AdminMessage.ConfigType.DEVICE_CONFIG -> wb.device = Config.DeviceConfig.Builder().build()
        AdminMessage.ConfigType.POSITION_CONFIG -> wb.position = Config.PositionConfig.Builder().build()
        AdminMessage.ConfigType.POWER_CONFIG -> wb.power = Config.PowerConfig.Builder().build()
        AdminMessage.ConfigType.NETWORK_CONFIG -> wb.network = Config.NetworkConfig.Builder().build()
        AdminMessage.ConfigType.DISPLAY_CONFIG -> wb.display = Config.DisplayConfig.Builder().build()
        AdminMessage.ConfigType.LORA_CONFIG -> wb.lora = Config.LoRaConfig.Builder().build()
        AdminMessage.ConfigType.BLUETOOTH_CONFIG -> wb.bluetooth = Config.BluetoothConfig.Builder().build()
        AdminMessage.ConfigType.SECURITY_CONFIG -> wb.security = Config.SecurityConfig.Builder().build()
        else -> error("no builder reads $type")
    }
}

private fun emptyModuleSection(type: AdminMessage.ModuleConfigType): ModuleConfig = moduleConfig { wb ->
    when (type) {
        AdminMessage.ModuleConfigType.MQTT_CONFIG -> wb.mqtt = ModuleConfig.MQTTConfig.Builder().build()

        AdminMessage.ModuleConfigType.SERIAL_CONFIG -> wb.serial = ModuleConfig.SerialConfig.Builder().build()

        AdminMessage.ModuleConfigType.EXTNOTIF_CONFIG ->
            wb.external_notification = ModuleConfig.ExternalNotificationConfig.Builder().build()

        AdminMessage.ModuleConfigType.STOREFORWARD_CONFIG ->
            wb.store_forward = ModuleConfig.StoreForwardConfig.Builder().build()

        AdminMessage.ModuleConfigType.RANGETEST_CONFIG -> wb.range_test = ModuleConfig.RangeTestConfig.Builder().build()

        AdminMessage.ModuleConfigType.TELEMETRY_CONFIG -> wb.telemetry = ModuleConfig.TelemetryConfig.Builder().build()

        AdminMessage.ModuleConfigType.CANNEDMSG_CONFIG ->
            wb.canned_message = ModuleConfig.CannedMessageConfig.Builder().build()

        AdminMessage.ModuleConfigType.AUDIO_CONFIG -> wb.audio = ModuleConfig.AudioConfig.Builder().build()

        AdminMessage.ModuleConfigType.REMOTEHARDWARE_CONFIG ->
            wb.remote_hardware = ModuleConfig.RemoteHardwareConfig.Builder().build()

        AdminMessage.ModuleConfigType.NEIGHBORINFO_CONFIG ->
            wb.neighbor_info = ModuleConfig.NeighborInfoConfig.Builder().build()

        AdminMessage.ModuleConfigType.AMBIENTLIGHTING_CONFIG ->
            wb.ambient_lighting = ModuleConfig.AmbientLightingConfig.Builder().build()

        AdminMessage.ModuleConfigType.DETECTIONSENSOR_CONFIG ->
            wb.detection_sensor = ModuleConfig.DetectionSensorConfig.Builder().build()

        AdminMessage.ModuleConfigType.PAXCOUNTER_CONFIG ->
            wb.paxcounter =
                ModuleConfig.PaxcounterConfig.Builder().build()

        AdminMessage.ModuleConfigType.STATUSMESSAGE_CONFIG ->
            wb.statusmessage = ModuleConfig.StatusMessageConfig.Builder().build()

        AdminMessage.ModuleConfigType.TRAFFICMANAGEMENT_CONFIG ->
            wb.traffic_management = ModuleConfig.TrafficManagementConfig.Builder().build()

        else -> error("no builder reads $type")
    }
}
