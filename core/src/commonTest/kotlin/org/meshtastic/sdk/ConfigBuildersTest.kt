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
import org.meshtastic.proto.SensorConfig
import org.meshtastic.proto.SharedContact
import org.meshtastic.proto.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

class ConfigBuildersTest {

    @Test
    fun deviceConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config(
                device = Config.DeviceConfig().copy(
                    role = Config.DeviceConfig.Role.TRACKER,
                    serial_enabled = true,
                    button_gpio = 23,
                    buzzer_gpio = 12,
                ),
            ),
        ) {
            setDeviceConfig {
                copy(
                    role = Config.DeviceConfig.Role.TRACKER,
                    serial_enabled = true,
                    button_gpio = 23,
                    buzzer_gpio = 12,
                )
            }
        }
    }

    @Test
    fun loraConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config(
                lora = Config.LoRaConfig().copy(
                    use_preset = true,
                    region = Config.LoRaConfig.RegionCode.EU_868,
                    modem_preset = Config.LoRaConfig.ModemPreset.SHORT_FAST,
                    bandwidth = 250,
                    spread_factor = 9,
                ),
            ),
        ) {
            setLoraConfig {
                copy(
                    use_preset = true,
                    region = Config.LoRaConfig.RegionCode.EU_868,
                    modem_preset = Config.LoRaConfig.ModemPreset.SHORT_FAST,
                    bandwidth = 250,
                    spread_factor = 9,
                )
            }
        }
    }

    @Test
    fun bluetoothConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config(
                bluetooth = Config.BluetoothConfig().copy(
                    enabled = true,
                    fixed_pin = 123456,
                    mode = Config.BluetoothConfig.PairingMode.FIXED_PIN,
                ),
            ),
        ) {
            setBluetoothConfig {
                copy(
                    enabled = true,
                    fixed_pin = 123456,
                    mode = Config.BluetoothConfig.PairingMode.FIXED_PIN,
                )
            }
        }
    }

    @Test
    fun displayConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config(
                display = Config.DisplayConfig().copy(
                    screen_on_secs = 45,
                    gps_format = Config.DisplayConfig.DeprecatedGpsCoordinateFormat.UNUSED,
                    units = Config.DisplayConfig.DisplayUnits.IMPERIAL,
                    flip_screen = true,
                ),
            ),
        ) {
            setDisplayConfig {
                copy(
                    screen_on_secs = 45,
                    gps_format = Config.DisplayConfig.DeprecatedGpsCoordinateFormat.UNUSED,
                    units = Config.DisplayConfig.DisplayUnits.IMPERIAL,
                    flip_screen = true,
                )
            }
        }
    }

    @Test
    fun networkConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config(
                network = Config.NetworkConfig().copy(
                    wifi_enabled = true,
                    wifi_ssid = "mesh-wifi",
                    wifi_psk = "super-secret",
                    eth_enabled = true,
                ),
            ),
        ) {
            setNetworkConfig {
                copy(
                    wifi_enabled = true,
                    wifi_ssid = "mesh-wifi",
                    wifi_psk = "super-secret",
                    eth_enabled = true,
                )
            }
        }
    }

    @Test
    fun positionConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config(
                position = Config.PositionConfig().copy(
                    gps_enabled = true,
                    fixed_position = true,
                    position_broadcast_secs = 300,
                    gps_mode = Config.PositionConfig.GpsMode.ENABLED,
                ),
            ),
        ) {
            setPositionConfig {
                copy(
                    gps_enabled = true,
                    fixed_position = true,
                    position_broadcast_secs = 300,
                    gps_mode = Config.PositionConfig.GpsMode.ENABLED,
                )
            }
        }
    }

    @Test
    fun powerConfigBuilderWrapsExpectedFields() = runTest {
        assertConfigWrite(
            Config(
                power = Config.PowerConfig().copy(
                    is_power_saving = true,
                    on_battery_shutdown_after_secs = 90,
                    wait_bluetooth_secs = 15,
                ),
            ),
        ) {
            setPowerConfig {
                copy(
                    is_power_saving = true,
                    on_battery_shutdown_after_secs = 90,
                    wait_bluetooth_secs = 15,
                )
            }
        }
    }

    @Test
    fun securityConfigBuilderWrapsExpectedFields() = runTest {
        val publicKey = bytes(1, 2, 3)
        val privateKey = bytes(4, 5, 6)
        val adminKey = bytes(7, 8, 9)

        assertConfigWrite(
            Config(
                security = Config.SecurityConfig().copy(
                    public_key = publicKey,
                    private_key = privateKey,
                    admin_key = listOf(adminKey),
                    serial_enabled = true,
                ),
            ),
        ) {
            setSecurityConfig {
                copy(
                    public_key = publicKey,
                    private_key = privateKey,
                    admin_key = listOf(adminKey),
                    serial_enabled = true,
                )
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
                copy(
                    role = Config.DeviceConfig.Role.CLIENT_HIDDEN,
                    button_gpio = 5,
                )
            },
        )
        assertEquals(
            expectedResult,
            admin.setNetworkConfig {
                copy(
                    wifi_enabled = true,
                    wifi_ssid = "mesh",
                    wifi_psk = "secret",
                    eth_enabled = true,
                )
            },
        )
        assertEquals(
            expectedResult,
            admin.setLoraConfig {
                copy(
                    region = Config.LoRaConfig.RegionCode.US,
                    modem_preset = Config.LoRaConfig.ModemPreset.LONG_TURBO,
                    bandwidth = 500,
                    spread_factor = 7,
                )
            },
        )

        assertEquals(
            listOf(
                Config(
                    device = Config.DeviceConfig().copy(
                        role = Config.DeviceConfig.Role.CLIENT_HIDDEN,
                        button_gpio = 5,
                    ),
                ),
                Config(
                    network = Config.NetworkConfig().copy(
                        wifi_enabled = true,
                        wifi_ssid = "mesh",
                        wifi_psk = "secret",
                        eth_enabled = true,
                    ),
                ),
                Config(
                    lora = Config.LoRaConfig().copy(
                        region = Config.LoRaConfig.RegionCode.US,
                        modem_preset = Config.LoRaConfig.ModemPreset.LONG_TURBO,
                        bandwidth = 500,
                        spread_factor = 7,
                    ),
                ),
            ),
            admin.configs,
        )
    }

    @Test
    fun configBuildersAllowOutOfRangeScalarValuesWithoutCrashing() = runTest {
        val admin = CapturingAdminApi()
        val expectedResult = AdminResult.Success(Unit)

        assertEquals(expectedResult, admin.setDeviceConfig { copy(button_gpio = -1) })
        assertEquals(expectedResult, admin.setLoraConfig { copy(bandwidth = -1, spread_factor = -7) })
        assertEquals(expectedResult, admin.setBluetoothConfig { copy(fixed_pin = -1) })
        assertEquals(expectedResult, admin.setDisplayConfig { copy(screen_on_secs = -1) })
        assertEquals(expectedResult, admin.setPositionConfig { copy(position_broadcast_secs = -1) })
        assertEquals(expectedResult, admin.setPowerConfig { copy(on_battery_shutdown_after_secs = -1) })

        assertEquals(
            listOf(
                Config(device = Config.DeviceConfig().copy(button_gpio = -1)),
                Config(lora = Config.LoRaConfig().copy(bandwidth = -1, spread_factor = -7)),
                Config(bluetooth = Config.BluetoothConfig().copy(fixed_pin = -1)),
                Config(display = Config.DisplayConfig().copy(screen_on_secs = -1)),
                Config(position = Config.PositionConfig().copy(position_broadcast_secs = -1)),
                Config(power = Config.PowerConfig().copy(on_battery_shutdown_after_secs = -1)),
            ),
            admin.configs,
        )
    }

    @Test
    fun moduleConfigBuildersWrapExpectedSections() = runTest {
        val admin = CapturingAdminApi()
        val expectedResult = AdminResult.Success(Unit)

        assertEquals(expectedResult, admin.setMqttConfig { copy(enabled = true) })
        assertEquals(expectedResult, admin.setSerialConfig { copy(enabled = true) })
        assertEquals(expectedResult, admin.setExternalNotificationConfig { copy(enabled = true) })
        assertEquals(expectedResult, admin.setStoreForwardConfig { copy(enabled = true) })
        assertEquals(expectedResult, admin.setRangeTestConfig { copy(sender = 7) })
        assertEquals(expectedResult, admin.setTelemetryConfig { copy(device_update_interval = 60) })
        assertEquals(expectedResult, admin.setCannedMessageConfig { copy(rotary1_enabled = true) })
        assertEquals(expectedResult, admin.setAudioConfig { copy(codec2_enabled = true) })
        assertEquals(expectedResult, admin.setRemoteHardwareConfig { copy(enabled = true) })
        assertEquals(expectedResult, admin.setNeighborInfoConfig { copy(enabled = true) })
        assertEquals(expectedResult, admin.setAmbientLightingConfig { copy(led_state = true) })
        assertEquals(expectedResult, admin.setDetectionSensorConfig { copy(enabled = true) })
        assertEquals(expectedResult, admin.setPaxcounterConfig { copy(enabled = true) })
        assertEquals(expectedResult, admin.setStatusMessageConfig { copy(node_status = "ready") })
        assertEquals(expectedResult, admin.setTrafficManagementConfig { copy(enabled = true) })

        assertEquals(
            listOf(
                ModuleConfig(mqtt = ModuleConfig.MQTTConfig().copy(enabled = true)),
                ModuleConfig(serial = ModuleConfig.SerialConfig().copy(enabled = true)),
                ModuleConfig(external_notification = ModuleConfig.ExternalNotificationConfig().copy(enabled = true)),
                ModuleConfig(store_forward = ModuleConfig.StoreForwardConfig().copy(enabled = true)),
                ModuleConfig(range_test = ModuleConfig.RangeTestConfig().copy(sender = 7)),
                ModuleConfig(telemetry = ModuleConfig.TelemetryConfig().copy(device_update_interval = 60)),
                ModuleConfig(canned_message = ModuleConfig.CannedMessageConfig().copy(rotary1_enabled = true)),
                ModuleConfig(audio = ModuleConfig.AudioConfig().copy(codec2_enabled = true)),
                ModuleConfig(remote_hardware = ModuleConfig.RemoteHardwareConfig().copy(enabled = true)),
                ModuleConfig(neighbor_info = ModuleConfig.NeighborInfoConfig().copy(enabled = true)),
                ModuleConfig(ambient_lighting = ModuleConfig.AmbientLightingConfig().copy(led_state = true)),
                ModuleConfig(detection_sensor = ModuleConfig.DetectionSensorConfig().copy(enabled = true)),
                ModuleConfig(paxcounter = ModuleConfig.PaxcounterConfig().copy(enabled = true)),
                ModuleConfig(statusmessage = ModuleConfig.StatusMessageConfig().copy(node_status = "ready")),
                ModuleConfig(traffic_management = ModuleConfig.TrafficManagementConfig().copy(enabled = true)),
            ),
            admin.moduleConfigs,
        )
    }

    private suspend fun assertConfigWrite(expected: Config, call: suspend CapturingAdminApi.() -> AdminResult<Unit>) {
        val admin = CapturingAdminApi()
        assertEquals(AdminResult.Success(Unit), admin.call())
        assertEquals(listOf(expected), admin.configs)
        assertTrue(admin.moduleConfigs.isEmpty())
    }
}

private class CapturingAdminApi : AdminApi {
    val configs = mutableListOf<Config>()
    val moduleConfigs = mutableListOf<ModuleConfig>()

    override fun forNode(dest: NodeId): AdminApi = this

    override suspend fun getDeviceMetadata(): AdminResult<DeviceMetadata> = unused()

    override suspend fun getConfig(type: AdminMessage.ConfigType): AdminResult<Config> = unused()

    override suspend fun setConfig(config: Config): AdminResult<Unit> {
        configs += config
        return AdminResult.Success(Unit)
    }

    override suspend fun getModuleConfig(type: AdminMessage.ModuleConfigType): AdminResult<ModuleConfig> = unused()

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

    override suspend fun addContact(contact: SharedContact): AdminResult<Unit> = unused()

    override suspend fun keyVerification(verification: KeyVerificationAdmin): AdminResult<Unit> = unused()

    override suspend fun rebootOta(after: Duration): AdminResult<Unit> = unused()

    override suspend fun otaRequest(event: AdminMessage.OTAEvent): AdminResult<Unit> = unused()

    override suspend fun setSensorConfig(config: SensorConfig): AdminResult<Unit> = unused()

    override suspend fun exitSimulator(): AdminResult<Unit> = unused()

    override suspend fun reboot(after: Duration): AdminResult<Unit> = unused()

    override suspend fun shutdown(after: Duration): AdminResult<Unit> = unused()

    override suspend fun factoryReset(preserveBleBonds: Boolean): AdminResult<Unit> = unused()

    override suspend fun nodeDbReset(): AdminResult<Unit> = unused()

    override suspend fun setTimeOnly(unixTime: Int): AdminResult<Unit> = unused()

    override suspend fun setTime(at: Instant?): AdminResult<Unit> = unused()

    override suspend fun <T> editSettings(block: suspend AdminEdit.() -> T): AdminResult<T> = unused()

    override suspend fun <T> batch(block: suspend AdminBatchScope.() -> T): T = unused()
}

private fun unused(): Nothing = error("unused in ConfigBuildersTest")

private fun bytes(vararg values: Int): ByteString =
    ByteString.of(*ByteArray(values.size) { index -> values[index].toByte() })
