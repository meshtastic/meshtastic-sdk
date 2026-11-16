/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import okio.ByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.Position
import org.meshtastic.proto.Routing
import org.meshtastic.proto.User

private const val CHANNEL_SLOTS = 8

/**
 * Builds a [DeviceProfile] from the connected device's state.
 *
 * The profile carries the owner names, the channel set as a share URL, every device and module
 * config section the profile format has a field for, the fixed position when the device uses one,
 * and, unless [includeDeviceText] is false, the ringtone and canned messages read through
 * [RadioClient.admin]. A ringtone or canned-message read that does not succeed is left out of the
 * profile rather than failing the export. Encode the result with [DeviceProfile.ADAPTER].
 *
 * `Config.sessionkey` and `Config.device_ui` have no field in the profile and are not exported.
 *
 * The security section is exported without `private_key` unless [includePrivateKey] is true. The
 * private key is the node's identity; a profile that carries it can impersonate the node.
 *
 * @return [AdminResult.Success] with the profile, or [AdminResult.Failed] with
 *   [Routing.Error.BAD_REQUEST] when the config bundle or own node has not loaded yet.
 * @since 0.3.0
 */
public suspend fun RadioClient.exportDeviceProfile(
    includePrivateKey: Boolean = false,
    includeDeviceText: Boolean = true,
): AdminResult<DeviceProfile> {
    val bundle = configBundle.value ?: return AdminResult.Failed(Routing.Error.BAD_REQUEST)
    val own = ownNode.value ?: return AdminResult.Failed(Routing.Error.BAD_REQUEST)
    val ringtone = if (includeDeviceText) admin.getRingtone().getOrNull() else null
    val canned = if (includeDeviceText) admin.getCannedMessages().getOrNull() else null
    val fixedPosition = own.position?.takeIf { p ->
        bundle.configs.any { it.position?.fixed_position == true } &&
            p.latitude_i != null && p.longitude_i != null
    }?.let { p ->
        Position.Builder().also { wb ->
            wb.latitude_i = p.latitude_i
            wb.longitude_i = p.longitude_i
            wb.altitude = p.altitude
        }.build()
    }
    return AdminResult.Success(
        buildDeviceProfile(
            owner = own.user,
            configs = bundle.configs,
            moduleConfigs = bundle.moduleConfigs,
            channels = channels.value,
            fixedPosition = fixedPosition,
            ringtone = ringtone,
            cannedMessages = canned,
            includePrivateKey = includePrivateKey,
        ),
    )
}

/**
 * Applies [profile] to the connected local device inside one [AdminApi.editSettings] transaction.
 *
 * Only fields the profile carries are written; a section that is absent from the profile leaves
 * the device's value alone. Writes run in this order: owner names, device, position, power,
 * network, display, bluetooth and (when [installSecurity]) security config, module configs, the
 * channel set, then LoRa config when it differs from the device's. LoRa config comes from the
 * channel URL's embedded config, else from `config.lora`.
 *
 * - The channel set replaces all eight slots: slot 0 is primary, the rest of the set secondary,
 *   remaining slots disabled.
 * - The fixed position is written after the transaction commits with
 *   [AdminApi.setFixedPosition], because [AdminEdit] has no fixed-position write.
 * - `is_licensed` is never installed; use [enableHamMode] for licensed operation.
 * - `module_config.traffic_management` is not installed.
 * - Ringtone and canned messages are not installed.
 *
 * **Security.** The profile's security section is skipped unless [installSecurity] is true. When
 * it is true, the section is written as the profile has it, including `private_key` and
 * `admin_key` entries, which replaces the device's identity and remote-admin keys. Profiles from
 * [exportDeviceProfile] carry an empty `private_key` unless the exporter opted in.
 *
 * @return [AdminResult.Failed] with [Routing.Error.BAD_REQUEST], before any write, when the config
 *   bundle has not loaded or the profile's channel URL is malformed or holds no primary channel or
 *   more than eight channels. Otherwise the result of the transaction, or of the fixed-position
 *   write when the transaction succeeded.
 * @since 0.3.0
 */
public suspend fun RadioClient.installDeviceProfile(
    profile: DeviceProfile,
    installSecurity: Boolean = false,
): AdminResult<Unit> {
    val bundle = configBundle.value ?: return AdminResult.Failed(Routing.Error.BAD_REQUEST)
    val plan = planProfileInstall(
        profile = profile,
        currentUser = ownNode.value?.user,
        currentLora = bundle.configs.firstNotNullOfOrNull { it.lora },
        installSecurity = installSecurity,
    ) ?: return AdminResult.Failed(Routing.Error.BAD_REQUEST)
    return admin.applyProfilePlan(plan)
}

internal class ProfileInstallPlan(
    val owner: User?,
    val configs: List<Config>,
    val moduleConfigs: List<ModuleConfig>,
    val channels: List<Channel>,
    val lora: Config?,
    val fixedPosition: Position?,
)

internal fun planProfileInstall(
    profile: DeviceProfile,
    currentUser: User?,
    currentLora: Config.LoRaConfig?,
    installSecurity: Boolean,
): ProfileInstallPlan? {
    val channelSet = profile.channel_url?.takeIf { it.isNotBlank() }?.let {
        ChannelUrl.parse(it) ?: return null
    }
    val channels = if (channelSet == null) {
        emptyList()
    } else {
        if (channelSet.settings.isEmpty() || channelSet.settings.size > CHANNEL_SLOTS) return null
        (0 until CHANNEL_SLOTS).map { index ->
            Channel.Builder().also { wb ->
                wb.index = index
                wb.role = when {
                    index == 0 -> Channel.Role.PRIMARY
                    index < channelSet.settings.size -> Channel.Role.SECONDARY
                    else -> Channel.Role.DISABLED
                }
                wb.settings =
                    channelSet.settings.getOrNull(index) ?: org.meshtastic.proto.ChannelSettings.Builder().build()
            }.build()
        }
    }
    val desiredLora = channelSet?.lora_config ?: profile.config?.lora
    val lora = desiredLora?.takeIf { it != currentLora }?.let { l ->
        Config.Builder().also { wb -> wb.lora = l }.build()
    }
    val configs = profile.config?.toConfigSections().orEmpty().filter {
        it.lora == null && (installSecurity || it.security == null)
    }
    val modules = profile.module_config?.toModuleConfigSections().orEmpty().filter { it.traffic_management == null }
    val owner = if (
        currentUser != null &&
        (profile.long_name != null || profile.short_name != null || profile.is_unmessagable != null)
    ) {
        currentUser.newBuilder().also { wb ->
            wb.long_name = profile.long_name ?: currentUser.long_name
            wb.short_name = profile.short_name ?: currentUser.short_name
            wb.is_unmessagable = profile.is_unmessagable ?: currentUser.is_unmessagable
        }.build()
    } else {
        null
    }
    return ProfileInstallPlan(owner, configs, modules, channels, lora, profile.fixed_position)
}

internal suspend fun AdminEdit.applyProfilePlan(plan: ProfileInstallPlan) {
    plan.owner?.let { setOwner(it) }
    plan.configs.forEach { setConfig(it) }
    plan.moduleConfigs.forEach { setModuleConfig(it) }
    plan.channels.forEach { setChannel(it) }
    plan.lora?.let { setConfig(it) }
}

internal suspend fun AdminApi.applyProfilePlan(plan: ProfileInstallPlan): AdminResult<Unit> {
    val committed = editSettings { applyProfilePlan(plan) }
    if (committed !is AdminResult.Success) return committed
    return plan.fixedPosition?.let { setFixedPosition(it) } ?: committed
}

internal fun buildDeviceProfile(
    owner: User?,
    configs: List<Config>,
    moduleConfigs: List<ModuleConfig>,
    channels: List<Channel>?,
    fixedPosition: Position?,
    ringtone: String?,
    cannedMessages: String?,
    includePrivateKey: Boolean,
): DeviceProfile {
    val local = configs.toLocalConfig().let { lc ->
        val security = lc.security
        if (includePrivateKey || security == null) {
            lc
        } else {
            lc.newBuilder().also { wb ->
                wb.security = security.newBuilder().also { sb -> sb.private_key = ByteString.EMPTY }.build()
            }.build()
        }
    }
    val active = channels.orEmpty().filter { it.role != Channel.Role.DISABLED }.sortedBy { it.index }
    val channelUrl = if (active.isEmpty()) {
        null
    } else {
        ChannelSet.Builder().also { wb ->
            wb.settings = active.mapNotNull { it.settings }
            wb.lora_config = configs.firstNotNullOfOrNull { it.lora }
        }.build().toUrl()
    }
    return DeviceProfile.Builder().also { wb ->
        wb.long_name = owner?.long_name
        wb.short_name = owner?.short_name
        wb.is_unmessagable = owner?.is_unmessagable
        wb.is_licensed = owner?.is_licensed
        wb.channel_url = channelUrl
        wb.config = local.takeIf { configs.isNotEmpty() }
        wb.module_config = moduleConfigs.toLocalModuleConfig().takeIf { moduleConfigs.isNotEmpty() }
        wb.fixed_position = fixedPosition
        wb.ringtone = ringtone
        wb.canned_messages = cannedMessages
    }.build()
}

internal fun List<Config>.toLocalConfig(): LocalConfig = LocalConfig.Builder().also { wb ->
    for (c in this) {
        c.device?.let { wb.device = it }
        c.position?.let { wb.position = it }
        c.power?.let { wb.power = it }
        c.network?.let { wb.network = it }
        c.display?.let { wb.display = it }
        c.lora?.let { wb.lora = it }
        c.bluetooth?.let { wb.bluetooth = it }
        c.security?.let { wb.security = it }
    }
}.build()

internal fun LocalConfig.toConfigSections(): List<Config> = listOfNotNull(
    device?.let { s -> Config.Builder().also { wb -> wb.device = s }.build() },
    position?.let { s -> Config.Builder().also { wb -> wb.position = s }.build() },
    power?.let { s -> Config.Builder().also { wb -> wb.power = s }.build() },
    network?.let { s -> Config.Builder().also { wb -> wb.network = s }.build() },
    display?.let { s -> Config.Builder().also { wb -> wb.display = s }.build() },
    lora?.let { s -> Config.Builder().also { wb -> wb.lora = s }.build() },
    bluetooth?.let { s -> Config.Builder().also { wb -> wb.bluetooth = s }.build() },
    security?.let { s -> Config.Builder().also { wb -> wb.security = s }.build() },
)

internal fun List<ModuleConfig>.toLocalModuleConfig(): LocalModuleConfig = LocalModuleConfig.Builder().also { wb ->
    for (m in this) {
        m.mqtt?.let { wb.mqtt = it }
        m.serial?.let { wb.serial = it }
        m.external_notification?.let { wb.external_notification = it }
        m.store_forward?.let { wb.store_forward = it }
        m.range_test?.let { wb.range_test = it }
        m.telemetry?.let { wb.telemetry = it }
        m.canned_message?.let { wb.canned_message = it }
        m.audio?.let { wb.audio = it }
        m.remote_hardware?.let { wb.remote_hardware = it }
        m.neighbor_info?.let { wb.neighbor_info = it }
        m.ambient_lighting?.let { wb.ambient_lighting = it }
        m.detection_sensor?.let { wb.detection_sensor = it }
        m.paxcounter?.let { wb.paxcounter = it }
        m.statusmessage?.let { wb.statusmessage = it }
        m.traffic_management?.let { wb.traffic_management = it }
        m.tak?.let { wb.tak = it }
        m.mesh_beacon?.let { wb.mesh_beacon = it }
    }
}.build()

internal fun LocalModuleConfig.toModuleConfigSections(): List<ModuleConfig> = listOfNotNull(
    mqtt?.let { s -> ModuleConfig.Builder().also { wb -> wb.mqtt = s }.build() },
    serial?.let { s -> ModuleConfig.Builder().also { wb -> wb.serial = s }.build() },
    external_notification?.let { s -> ModuleConfig.Builder().also { wb -> wb.external_notification = s }.build() },
    store_forward?.let { s -> ModuleConfig.Builder().also { wb -> wb.store_forward = s }.build() },
    range_test?.let { s -> ModuleConfig.Builder().also { wb -> wb.range_test = s }.build() },
    telemetry?.let { s -> ModuleConfig.Builder().also { wb -> wb.telemetry = s }.build() },
    canned_message?.let { s -> ModuleConfig.Builder().also { wb -> wb.canned_message = s }.build() },
    audio?.let { s -> ModuleConfig.Builder().also { wb -> wb.audio = s }.build() },
    remote_hardware?.let { s -> ModuleConfig.Builder().also { wb -> wb.remote_hardware = s }.build() },
    neighbor_info?.let { s -> ModuleConfig.Builder().also { wb -> wb.neighbor_info = s }.build() },
    ambient_lighting?.let { s -> ModuleConfig.Builder().also { wb -> wb.ambient_lighting = s }.build() },
    detection_sensor?.let { s -> ModuleConfig.Builder().also { wb -> wb.detection_sensor = s }.build() },
    paxcounter?.let { s -> ModuleConfig.Builder().also { wb -> wb.paxcounter = s }.build() },
    statusmessage?.let { s -> ModuleConfig.Builder().also { wb -> wb.statusmessage = s }.build() },
    traffic_management?.let { s -> ModuleConfig.Builder().also { wb -> wb.traffic_management = s }.build() },
    tak?.let { s -> ModuleConfig.Builder().also { wb -> wb.tak = s }.build() },
    mesh_beacon?.let { s -> ModuleConfig.Builder().also { wb -> wb.mesh_beacon = s }.build() },
)
