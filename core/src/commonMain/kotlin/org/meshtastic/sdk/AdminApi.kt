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
import org.meshtastic.proto.ModuleConfig
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
