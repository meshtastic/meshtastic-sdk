/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceConnectionStatus
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.HamParameters
import org.meshtastic.proto.KeyVerificationAdmin
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.NodeRemoteHardwarePinsResponse
import org.meshtastic.proto.Position
import org.meshtastic.proto.SensorConfig
import org.meshtastic.proto.SharedContact
import org.meshtastic.proto.User
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Device configuration and control RPCs.
 *
 * Each method maps onto a single [AdminMessage] round-trip with the local device. Failures that
 * are part of the normal operating envelope (timeout, unauthorized, session-key expiry, node
 * unreachable for remote-admin paths) surface as typed [AdminResult] variants. Catastrophic
 * failures (transport gone, engine torn down) throw [MeshtasticException].
 *
 * Acquired via [RadioClient.admin]. Available only while the client is connected.
 *
 * @since 0.1.0
 */
public interface AdminApi {

    // ── Configs ─────────────────────────────────────────────────────────────

    /** Read a single [Config] section from the device. */
    public suspend fun getConfig(type: AdminMessage.ConfigType): AdminResult<Config>

    /** Write a [Config] section to the device. */
    public suspend fun setConfig(config: Config): AdminResult<Unit>

    /** Read a single [ModuleConfig] section from the device. */
    public suspend fun getModuleConfig(type: AdminMessage.ModuleConfigType): AdminResult<ModuleConfig>

    /** Write a [ModuleConfig] section to the device. */
    public suspend fun setModuleConfig(config: ModuleConfig): AdminResult<Unit>

    // ── Owner ───────────────────────────────────────────────────────────────

    /** Read the local node's [User] (long name / short name / hardware model / public key). */
    public suspend fun getOwner(): AdminResult<User>

    /** Update the local node's [User]. Persists across reboots. */
    public suspend fun setOwner(user: User): AdminResult<Unit>

    // ── Channels ────────────────────────────────────────────────────────────

    /** Read the [Channel] at [index] (0..7). */
    public suspend fun getChannel(index: ChannelIndex): AdminResult<Channel>

    /** Write a [Channel]. The slot is determined by [Channel.index]. */
    public suspend fun setChannel(channel: Channel): AdminResult<Unit>

    /**
     * Read every configured channel (indices `0..7`).
     *
     * Issues up to 8 sequential `get_channel_request` round-trips. Stops early if a slot returns
     * a disabled channel (firmware convention: disabled slots have `role = DISABLED`).
     */
    public suspend fun listChannels(): AdminResult<List<Channel>>

    // ── Node management ─────────────────────────────────────────────────────

    /** Mark [node] as a favorite (persisted in firmware NodeDB). */
    public suspend fun setFavorite(node: NodeId, favorite: Boolean): AdminResult<Unit>

    /** Mark [node] as ignored — packets from it are filtered before reaching apps. */
    public suspend fun setIgnored(node: NodeId, ignored: Boolean): AdminResult<Unit>

    /** Toggle mute state on [node] — muted nodes do not forward packets. */
    public suspend fun toggleMuted(node: NodeId): AdminResult<Unit>

    // ── Position ────────────────────────────────────────────────────────────

    /** Set a fixed GPS position for the device (disables GPS module). */
    public suspend fun setFixedPosition(position: Position): AdminResult<Unit>

    /** Remove the fixed position and re-enable GPS. */
    public suspend fun removeFixedPosition(): AdminResult<Unit>

    // ── Device UI Config ────────────────────────────────────────────────────

    /** Read the device's UI configuration (display preferences, language, etc.). */
    public suspend fun getUIConfig(): AdminResult<DeviceUIConfig>

    /** Write the device's UI configuration. */
    public suspend fun storeUIConfig(config: DeviceUIConfig): AdminResult<Unit>

    // ── Canned Messages ─────────────────────────────────────────────────────

    /** Read the canned message module's preset messages. */
    public suspend fun getCannedMessages(): AdminResult<String>

    /** Write the canned message module's preset messages (pipe-delimited). */
    public suspend fun setCannedMessages(messages: String): AdminResult<Unit>

    // ── Ringtone ────────────────────────────────────────────────────────────

    /** Read the device's ringtone (RTTTL format). */
    public suspend fun getRingtone(): AdminResult<String>

    /** Write the device's ringtone (RTTTL format). */
    public suspend fun setRingtone(rtttl: String): AdminResult<Unit>

    // ── Device status ───────────────────────────────────────────────────────

    /** Read the device's connection status (WiFi, BLE, Ethernet, MQTT). */
    public suspend fun getDeviceConnectionStatus(): AdminResult<DeviceConnectionStatus>

    /** Read the remote hardware pin configuration of [node]. */
    public suspend fun getRemoteHardwarePins(): AdminResult<NodeRemoteHardwarePinsResponse>

    // ── Ham radio ───────────────────────────────────────────────────────────

    /** Configure the device for amateur radio use (sets call sign, disables encryption). */
    public suspend fun setHamMode(params: HamParameters): AdminResult<Unit>

    // ── DFU / file management ───────────────────────────────────────────────

    /** Enter DFU (firmware update) mode. The device will reboot into its bootloader. */
    public suspend fun enterDfuMode(): AdminResult<Unit>

    /** Delete a file from the device's filesystem. */
    public suspend fun deleteFile(path: String): AdminResult<Unit>

    // ── Backup / Restore ────────────────────────────────────────────────────

    /** Back up device preferences to the specified [location]. */
    public suspend fun backupPreferences(location: AdminMessage.BackupLocation = AdminMessage.BackupLocation.FLASH): AdminResult<Unit>

    /** Restore device preferences from the specified [location]. */
    public suspend fun restorePreferences(location: AdminMessage.BackupLocation = AdminMessage.BackupLocation.FLASH): AdminResult<Unit>

    /** Remove a stored preference backup from [location]. */
    public suspend fun removeBackupPreferences(location: AdminMessage.BackupLocation = AdminMessage.BackupLocation.FLASH): AdminResult<Unit>

    // ── Node removal ────────────────────────────────────────────────────────

    /** Remove a node from the device's NodeDB by its node number. */
    public suspend fun removeNode(node: NodeId): AdminResult<Unit>

    // ── Input / Display ─────────────────────────────────────────────────────

    /** Set the device's scale calibration value (e-ink display DPI). */
    public suspend fun setScale(scale: Int): AdminResult<Unit>

    /** Send a synthetic input event to the device (button press, touch, etc.). */
    public suspend fun sendInputEvent(event: AdminMessage.InputEvent): AdminResult<Unit>

    // ── Contacts ────────────────────────────────────────────────────────────

    /** Add a shared contact to the device's contact list. */
    public suspend fun addContact(contact: SharedContact): AdminResult<Unit>

    // ── Key verification ────────────────────────────────────────────────────

    /** Initiate or respond to a key verification exchange. */
    public suspend fun keyVerification(verification: KeyVerificationAdmin): AdminResult<Unit>

    // ── OTA updates ─────────────────────────────────────────────────────────

    /** Reboot into OTA update mode after [after] (default: immediately). */
    public suspend fun rebootOta(after: Duration = Duration.ZERO): AdminResult<Unit>

    /** Send an OTA event (firmware update control). */
    public suspend fun otaRequest(event: AdminMessage.OTAEvent): AdminResult<Unit>

    // ── Sensor ──────────────────────────────────────────────────────────────

    /** Configure a sensor attached to the device. */
    public suspend fun setSensorConfig(config: SensorConfig): AdminResult<Unit>

    // ── Simulator ───────────────────────────────────────────────────────────

    /** Exit the firmware simulator mode (development only). */
    public suspend fun exitSimulator(): AdminResult<Unit>

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Reboot the device after [after] (default: immediately).
     *
     * Returns [AdminResult.Success] when the device acknowledges the request. The
     * connection will drop shortly after the reboot starts; callers should expect a
     * [ConnectionState.Disconnected] transition followed by reconnect-as-needed.
     */
    public suspend fun reboot(after: Duration = Duration.ZERO): AdminResult<Unit>

    /** Power down the device after [after] (default: immediately). */
    public suspend fun shutdown(after: Duration = Duration.ZERO): AdminResult<Unit>

    /**
     * Erase device state and reset to factory defaults.
     *
     * @param preserveBleBonds when `true` (default), preserves OS-level BLE pairing and the
     *   device's identity (NodeNum, public key, channel set) — issues `factory_reset_config = 1`.
     *   When `false`, erases everything including BLE bonds — issues `factory_reset_device = 1`.
     */
    public suspend fun factoryReset(preserveBleBonds: Boolean = true): AdminResult<Unit>

    /**
     * Wipe the device's NodeDB, forcing a fresh discovery cycle on the mesh.
     *
     * The firmware always preserves favorite-marked entries during the wipe (this is
     * firmware-enforced behavior). The `nodedb_reset` proto field uses proto3 semantics where
     * only `true` can be encoded — a "wipe everything including favorites" mode is not
     * available through this command.
     *
     * The device will reboot after the reset completes.
     */
    public suspend fun nodeDbReset(): AdminResult<Unit>

    // ── Time ────────────────────────────────────────────────────────────────

    /**
     * Set the device's wall clock to [at] (default: `Clock.System.now()`).
     *
     * If [RadioClient.Builder.autoSyncTimeOnConnect] is enabled, the engine calls this once after
     * handshake when the device clock skew exceeds 60 s.
     */
    public suspend fun setTime(at: Instant? = null): AdminResult<Unit>

    // ── Transactional batched writes ────────────────────────────────────────

    /**
     * Run [block] inside a `begin_edit_settings` / `commit_edit_settings` envelope so the device
     * applies all writes atomically (avoids reboot-mid-edit corruption).
     *
     * Each setter inside [block] is fire-and-forget; the begin/commit pair is awaited. If begin
     * or commit fails, the result reflects that failure and the block's return value is discarded.
     */
    public suspend fun <T> editSettings(block: suspend AdminEdit.() -> T): AdminResult<T>
}

/**
 * Receiver type for [AdminApi.editSettings] — exposes the subset of admin writes that may be
 * batched inside a `begin_edit_settings` / `commit_edit_settings` envelope.
 *
 * Setters here suspend until the underlying admin packet is queued onto the wire; they do not
 * await an individual ACK because the device defers all writes until `commit_edit_settings`.
 *
 * @since 0.1.0
 */
public interface AdminEdit {
    public suspend fun setConfig(config: Config)
    public suspend fun setModuleConfig(config: ModuleConfig)
    public suspend fun setOwner(user: User)
    public suspend fun setChannel(channel: Channel)
    public suspend fun setFavorite(node: NodeId, favorite: Boolean)
    public suspend fun setIgnored(node: NodeId, ignored: Boolean)
}
