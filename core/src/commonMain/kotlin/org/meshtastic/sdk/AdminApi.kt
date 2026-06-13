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
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.HamParameters
import org.meshtastic.proto.KeyVerificationAdmin
import org.meshtastic.proto.LockdownAuth
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

    // ── Remote targeting ────────────────────────────────────────────────────

    /**
     * Return an [AdminApi] instance that targets [dest] instead of the local device.
     *
     * All calls on the returned instance route admin messages to the specified remote node
     * over the mesh. Note: `editSettings`, `batch`, `getDeviceConnectionStatus`, and lifecycle
     * commands (`reboot`, `shutdown`, `factoryReset`, `nodeDbReset`) work identically — the
     * firmware handles admin-over-mesh transparently. The sole exception is [lockdown], which is
     * local-only (the firmware consumes its passphrase inline on the phone link); calling it on a
     * remote-targeting instance returns [AdminResult.Unauthorized].
     *
     * ```kotlin
     * val remoteAdmin = client.admin.forNode(NodeId(0x12345678.toInt()))
     * remoteAdmin.setConfig(config) // → sent to remote node
     * ```
     *
     * @param dest the target node's [NodeId]
     * @return a remote-targeting [AdminApi] instance
     * @since 0.2.0
     */
    public fun forNode(dest: NodeId): AdminApi

    // ── Device info ─────────────────────────────────────────────────────────

    /**
     * Request [DeviceMetadata] from the device (firmware version, hardware model, etc.).
     *
     * For the local node, this is cached during handshake and available via
     * [RadioClient.deviceConfig]. For remote nodes, use [forNode] to target the desired node:
     *
     * ```kotlin
     * val metadata = client.admin.forNode(remoteNodeId).getDeviceMetadata()
     * ```
     *
     * @return the device's metadata
     * @since 0.2.0
     */
    public suspend fun getDeviceMetadata(): AdminResult<DeviceMetadata>

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

    /**
     * Toggle mute state on [node] — muted nodes do not forward packets.
     *
     * Note: The firmware uses a toggle primitive (`toggle_muted_node`), so calling this
     * always flips the current state. Track local mute state if you need idempotent behavior.
     */
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

    /**
     * Enter DFU (firmware update) mode. The device will reboot into its bootloader.
     *
     * This is a fire-and-forget admin write; [AdminResult.Success] means the request was queued
     * locally, not that the device stayed connected long enough to acknowledge the reboot.
     */
    public suspend fun enterDfuMode(): AdminResult<Unit>

    /** Delete a file from the device's filesystem. */
    public suspend fun deleteFile(path: String): AdminResult<Unit>

    // ── Backup / Restore ────────────────────────────────────────────────────

    /** Back up device preferences to the specified [location]. */
    public suspend fun backupPreferences(
        location: AdminMessage.BackupLocation = AdminMessage.BackupLocation.FLASH,
    ): AdminResult<Unit>

    /** Restore device preferences from the specified [location]. */
    public suspend fun restorePreferences(
        location: AdminMessage.BackupLocation = AdminMessage.BackupLocation.FLASH,
    ): AdminResult<Unit>

    /** Remove a stored preference backup from [location]. */
    public suspend fun removeBackupPreferences(
        location: AdminMessage.BackupLocation = AdminMessage.BackupLocation.FLASH,
    ): AdminResult<Unit>

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

    // ── Lockdown (hardened builds) ──────────────────────────────────────────

    /**
     * Provision, unlock, or lock the device's storage lockdown
     * (`MESHTASTIC_LOCKDOWN` hardened firmware builds only).
     *
     * The device reports its current state via [MeshEvent.LockdownStatusChanged] — observe that to
     * decide which [LockdownAuth] to send and to learn the outcome:
     * - **Provision / unlock:** set [LockdownAuth.passphrase] (1–32 bytes). On success the device
     *   issues a session token bounded by [LockdownAuth.boots_remaining] (boot-count TTL, `0` =
     *   firmware default) and [LockdownAuth.valid_until_epoch] (absolute wall-clock expiry, `0` =
     *   no time limit).
     * - **Lock now:** set [LockdownAuth.lock_now] = `true` (ignores the passphrase) to immediately
     *   revoke admin authorization and reboot into the locked state.
     *
     * **Local device only.** The firmware consumes `lockdown_auth` inline on the direct phone link
     * (BLE/serial/TCP) and wipes the passphrase from memory before it can reach the mesh/admin
     * routing layer. Calling this on a [forNode]-scoped instance therefore returns
     * [AdminResult.Unauthorized] rather than leaking a passphrase onto the mesh.
     *
     * **Fire-and-forget.** [AdminResult.Success] means the request was queued onto the local link,
     * not that the device accepted the passphrase — the device answers with a fresh
     * [MeshEvent.LockdownStatusChanged] rather than a routing ACK. Returns
     * [AdminResult.NodeUnreachable] if no session has been established yet.
     *
     * @param auth the lockdown command parameters
     * @since 0.3.0
     */
    public suspend fun lockdown(auth: LockdownAuth): AdminResult<Unit>

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
     * Set the device's wall clock from raw Unix time seconds without sending position data.
     *
     * This uses `AdminMessage.set_time_only` directly and is fire-and-forget; [AdminResult.Success]
     * means the packet was queued locally.
     */
    public suspend fun setTimeOnly(unixTime: Int): AdminResult<Unit>

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

    /**
     * Exception-based counterpart to [editSettings] that also exposes batched getter helpers.
     *
     * Getter failures throw [AdminResultException] via [getOrThrow]. If [block] throws, the SDK
     * does not send `commit_edit_settings`; firmware eventually discards the buffered edits.
     */
    public suspend fun <T> batch(block: suspend AdminBatchScope.() -> T): T
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

/**
 * Receiver type for [AdminApi.batch] — combines [AdminEdit] setters with getter helpers.
 *
 * Getter failures throw [AdminResultException] via [getOrThrow]. Setters share the same deferred
 * commit semantics as [AdminApi.editSettings].
 */
public interface AdminBatchScope : AdminEdit {
    public suspend fun getConfig(type: AdminMessage.ConfigType): Config
    public suspend fun getModuleConfig(type: AdminMessage.ModuleConfigType): ModuleConfig
    public suspend fun listChannels(): List<Channel>
}
