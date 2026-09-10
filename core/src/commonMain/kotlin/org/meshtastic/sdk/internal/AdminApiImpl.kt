/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceConnectionStatus
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceUIConfig
import org.meshtastic.proto.HamParameters
import org.meshtastic.proto.KeyVerificationAdmin
import org.meshtastic.proto.LockdownAuth
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.NodeRemoteHardwarePinsResponse
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Routing
import org.meshtastic.proto.SensorConfig
import org.meshtastic.proto.SharedContact
import org.meshtastic.proto.User
import org.meshtastic.sdk.AdminApi
import org.meshtastic.sdk.AdminBatchScope
import org.meshtastic.sdk.AdminEdit
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.SendFailure
import org.meshtastic.sdk.SendState
import org.meshtastic.sdk.getOrThrow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Engine-backed [AdminApi] implementation.
 *
 * Setters that require an ACK go through the existing [MeshEngine.trySend] path (they share the
 * `pendingSends` + `MessageHandle` machinery, including the per-send ACK timer). Getters and
 * other typed-response RPCs use [MeshEngine.submitRpc], which routes through
 * [CommandDispatcher].
 *
 * `SessionKeyExpired` triggers a single-shot retry: a fresh `get_owner_request` re-seeds the
 * session passkey, then the original call is replayed once.
 */
internal class AdminApiImpl(
    private val engine: MeshEngine,
    private val rpcTimeout: Duration,
    private val nowProvider: () -> Instant = { Clock.System.now() },
    private val targetNode: NodeId? = null,
) : AdminApi {

    override fun forNode(dest: NodeId): AdminApi = AdminApiImpl(
        engine = engine,
        rpcTimeout = rpcTimeout,
        nowProvider = nowProvider,
        targetNode = dest,
    )

    override suspend fun getDeviceMetadata(): AdminResult<DeviceMetadata> = retryOnSessionExpiry {
        submitAdminRpc(
            adminMsg = AdminMessage.Builder().also { wb ->
                wb.get_device_metadata_request = true
            }.build(),
            kind = ResponseKind.AdminDeviceMetadata,
        )
    }

    /**
     * Returns `true` when this AdminApi targets the **local** device and that device is in
     * managed mode. Firmware rewrites every phone packet to `from = 0` (MeshService
     * `handleToRadio`) and rejects local admin on a managed device via that branch
     * (AdminModule: `mp.from == 0 && is_managed`). Admin packets addressed to *remote* nodes
     * are routed into the mesh untouched — the target's own admin-key config authorizes them —
     * so remote admin must NOT be short-circuited by the local device's managed flag
     * (managed-fleet deployments administer remote managed nodes from a managed local node).
     */
    private fun isLocalTargetManaged(): Boolean {
        val isLocalTarget = targetNode == null || targetNode.raw == engine.myNodeNumOrNull()
        if (!isLocalTarget) return false
        val bundle = engine.configBundleState.value ?: return false
        return bundle.configs.any { config ->
            config.security?.is_managed == true
        }
    }

    override suspend fun getConfig(type: AdminMessage.ConfigType): AdminResult<Config> = retryOnSessionExpiry {
        submitAdminRpc(
            adminMsg = AdminMessage.Builder().also { wb -> wb.get_config_request = type }.build(),
            kind = ResponseKind.AdminConfig,
        )
    }

    override suspend fun setConfig(config: Config): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.set_config = config }.build())
    }

    override suspend fun getModuleConfig(type: AdminMessage.ModuleConfigType): AdminResult<ModuleConfig> =
        retryOnSessionExpiry {
            submitAdminRpc(
                adminMsg = AdminMessage.Builder().also { wb ->
                    wb.get_module_config_request = type
                }.build(),
                kind = ResponseKind.AdminModuleConfig,
            )
        }

    override suspend fun setModuleConfig(config: ModuleConfig): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.set_module_config = config }.build())
    }

    override suspend fun getOwner(): AdminResult<User> = retryOnSessionExpiry {
        submitAdminRpc(
            adminMsg = AdminMessage.Builder().also { wb -> wb.get_owner_request = true }.build(),
            kind = ResponseKind.AdminOwner,
        )
    }

    override suspend fun setOwner(user: User): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.set_owner = user }.build())
    }

    override suspend fun getChannel(index: ChannelIndex): AdminResult<Channel> = retryOnSessionExpiry {
        submitAdminRpc(
            // Firmware expects 1-based index (proto3 omits 0 as default value).
            // See admin.proto: "NOTE: This field is sent with the channel index + 1"
            adminMsg = AdminMessage.Builder().also { wb ->
                wb.get_channel_request = index.raw + 1
            }.build(),
            kind = ResponseKind.AdminChannel,
        )
    }

    override suspend fun setChannel(channel: Channel): AdminResult<Unit> {
        val result = retryOnSessionExpiry {
            submitAdminAck(
                AdminMessage.Builder().also { wb ->
                    wb.set_channel = channel
                }.build(),
            )
        }
        if (result is AdminResult.Success) engine.updateChannelAndPersist(channel)
        return result
    }

    override suspend fun listChannels(): AdminResult<List<Channel>> {
        val collected = mutableListOf<Channel>()
        for (i in 0..ChannelIndex.MAX_CHANNEL_INDEX) {
            val result = getChannel(ChannelIndex(i))
            when (result) {
                is AdminResult.Success -> {
                    val channel = result.value
                    // Disabled slots end the iteration — firmware allocates them in order.
                    if (channel.role == Channel.Role.DISABLED && i > 0) break
                    collected.add(channel)
                }

                AdminResult.Timeout, AdminResult.NodeUnreachable,
                AdminResult.SessionKeyExpired, AdminResult.Unauthorized,
                AdminResult.RateLimited,
                is AdminResult.Failed,
                -> return result.let {
                    @Suppress("UNCHECKED_CAST")
                    it as AdminResult<List<Channel>>
                }
            }
        }
        return AdminResult.Success(collected.toList())
    }

    override suspend fun setFavorite(node: NodeId, favorite: Boolean): AdminResult<Unit> = retryOnSessionExpiry {
        val msg = if (favorite) {
            AdminMessage.Builder().also { wb -> wb.set_favorite_node = node.raw }.build()
        } else {
            AdminMessage.Builder().also { wb -> wb.remove_favorite_node = node.raw }.build()
        }
        submitAdminAck(msg)
    }

    override suspend fun setIgnored(node: NodeId, ignored: Boolean): AdminResult<Unit> = retryOnSessionExpiry {
        val msg = if (ignored) {
            AdminMessage.Builder().also { wb -> wb.set_ignored_node = node.raw }.build()
        } else {
            AdminMessage.Builder().also { wb -> wb.remove_ignored_node = node.raw }.build()
        }
        submitAdminAck(msg)
    }

    override suspend fun toggleMuted(node: NodeId): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.toggle_muted_node = node.raw }.build())
    }

    // ── Position ────────────────────────────────────────────────────────────

    override suspend fun setFixedPosition(position: Position): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.set_fixed_position = position }.build())
    }

    override suspend fun removeFixedPosition(): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.remove_fixed_position = true }.build())
    }

    // ── Device UI Config ────────────────────────────────────────────────────

    override suspend fun getUIConfig(): AdminResult<DeviceUIConfig> = retryOnSessionExpiry {
        submitAdminRpc(
            adminMsg = AdminMessage.Builder().also { wb -> wb.get_ui_config_request = true }.build(),
            kind = ResponseKind.AdminDeviceUIConfig,
        )
    }

    override suspend fun storeUIConfig(config: DeviceUIConfig): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.store_ui_config = config }.build())
    }

    // ── Canned Messages ─────────────────────────────────────────────────────

    override suspend fun getCannedMessages(): AdminResult<String> = retryOnSessionExpiry {
        submitAdminRpc(
            adminMsg = AdminMessage.Builder().also { wb ->
                wb.get_canned_message_module_messages_request = true
            }.build(),
            kind = ResponseKind.AdminCannedMessages,
        )
    }

    override suspend fun setCannedMessages(messages: String): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(
            AdminMessage.Builder().also { wb ->
                wb.set_canned_message_module_messages = messages
            }.build(),
        )
    }

    // ── Ringtone ────────────────────────────────────────────────────────────

    override suspend fun getRingtone(): AdminResult<String> = retryOnSessionExpiry {
        submitAdminRpc(
            adminMsg = AdminMessage.Builder().also { wb -> wb.get_ringtone_request = true }.build(),
            kind = ResponseKind.AdminRingtone,
        )
    }

    override suspend fun setRingtone(rtttl: String): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.set_ringtone_message = rtttl }.build())
    }

    // ── Device status ───────────────────────────────────────────────────────

    override suspend fun getDeviceConnectionStatus(): AdminResult<DeviceConnectionStatus> = retryOnSessionExpiry {
        submitAdminRpc(
            adminMsg = AdminMessage.Builder().also { wb ->
                wb.get_device_connection_status_request = true
            }.build(),
            kind = ResponseKind.AdminDeviceConnectionStatus,
        )
    }

    override suspend fun getRemoteHardwarePins(): AdminResult<NodeRemoteHardwarePinsResponse> = retryOnSessionExpiry {
        submitAdminRpc(
            adminMsg = AdminMessage.Builder().also { wb ->
                wb.get_node_remote_hardware_pins_request = true
            }.build(),
            kind = ResponseKind.AdminRemoteHardwarePins,
        )
    }

    // ── Ham radio ───────────────────────────────────────────────────────────

    override suspend fun setHamMode(params: HamParameters): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.set_ham_mode = params }.build())
    }

    // ── DFU / file management ───────────────────────────────────────────────

    override suspend fun enterDfuMode(): AdminResult<Unit> {
        if (isLocalTargetManaged()) return AdminResult.Unauthorized
        return submitAdminFireAndForget(
            AdminMessage.Builder().also { wb ->
                wb.enter_dfu_mode_request = true
            }.build(),
        )
    }

    override suspend fun deleteFile(path: String): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.delete_file_request = path }.build())
    }

    // ── Backup / Restore ────────────────────────────────────────────────────

    override suspend fun backupPreferences(location: AdminMessage.BackupLocation): AdminResult<Unit> =
        retryOnSessionExpiry {
            submitAdminAck(
                AdminMessage.Builder().also { wb ->
                    wb.backup_preferences = location
                }.build(),
            )
        }

    override suspend fun restorePreferences(location: AdminMessage.BackupLocation): AdminResult<Unit> =
        retryOnSessionExpiry {
            submitAdminAck(
                AdminMessage.Builder().also { wb ->
                    wb.restore_preferences = location
                }.build(),
            )
        }

    override suspend fun removeBackupPreferences(location: AdminMessage.BackupLocation): AdminResult<Unit> =
        retryOnSessionExpiry {
            submitAdminAck(
                AdminMessage.Builder().also { wb ->
                    wb.remove_backup_preferences = location
                }.build(),
            )
        }

    // ── Node removal ────────────────────────────────────────────────────────

    override suspend fun removeNode(node: NodeId): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.remove_by_nodenum = node.raw }.build())
    }

    // ── Input / Display ─────────────────────────────────────────────────────

    override suspend fun setScale(scale: Int): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.set_scale = scale }.build())
    }

    override suspend fun sendInputEvent(event: AdminMessage.InputEvent): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.send_input_event = event }.build())
    }

    // ── Contacts ────────────────────────────────────────────────────────────

    override suspend fun addContact(contact: SharedContact): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.add_contact = contact }.build())
    }

    // ── Key verification ────────────────────────────────────────────────────

    override suspend fun keyVerification(verification: KeyVerificationAdmin): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(
            AdminMessage.Builder().also { wb ->
                wb.key_verification = verification
            }.build(),
        )
    }

    // ── Lockdown (hardened builds) ──────────────────────────────────────────

    override suspend fun lockdown(auth: LockdownAuth): AdminResult<Unit> {
        // Local-only: firmware (PhoneAPI::handleLockdownAuthInline) consumes lockdown_auth inline
        // on the direct phone link and wipes the passphrase before it can reach the mesh. A
        // remote-targeting instance must NOT send it — that would leak a passphrase onto the mesh
        // where no inline handler exists. Managed-mode is intentionally NOT consulted: lockdown is
        // a pre-auth security primitive, independent of admin authorization.
        if (targetNode != null && targetNode.raw != engine.myNodeNumOrNull()) {
            return AdminResult.Unauthorized
        }
        val local = engine.myNodeNumOrNull() ?: return AdminResult.NodeUnreachable
        // Fire-and-forget: the device answers with a fresh FromRadio.lockdown_status
        // (MeshEvent.LockdownStatusChanged), not a routing ACK.
        return submitAdminFireAndForget(
            AdminMessage.Builder().also { wb ->
                wb.lockdown_auth = auth
            }.build(),
            to = NodeId(local),
        )
    }

    // ── OTA updates ─────────────────────────────────────────────────────────

    override suspend fun rebootOta(after: Duration): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(
            AdminMessage.Builder().also { wb ->
                wb.reboot_ota_seconds = after.inWholeSeconds.toInt().coerceAtLeast(0)
            }.build(),
        )
    }

    override suspend fun otaRequest(event: AdminMessage.OTAEvent): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.ota_request = event }.build())
    }

    // ── Sensor ──────────────────────────────────────────────────────────────

    override suspend fun setSensorConfig(config: SensorConfig): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.sensor_config = config }.build())
    }

    // ── Simulator ───────────────────────────────────────────────────────────

    override suspend fun exitSimulator(): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.exit_simulator = true }.build())
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override suspend fun reboot(after: Duration): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(
            AdminMessage.Builder().also { wb ->
                wb.reboot_seconds = after.inWholeSeconds.toInt().coerceAtLeast(0)
            }.build(),
        )
    }

    override suspend fun shutdown(after: Duration): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(
            AdminMessage.Builder().also { wb ->
                wb.shutdown_seconds = after.inWholeSeconds.toInt().coerceAtLeast(0)
            }.build(),
        )
    }

    override suspend fun factoryReset(preserveBleBonds: Boolean): AdminResult<Unit> = retryOnSessionExpiry {
        // factory_reset_config wipes settings only (preserves identity + BLE bonds).
        // factory_reset_device wipes everything including BLE bonds.
        val msg = if (preserveBleBonds) {
            AdminMessage.Builder().also { wb -> wb.factory_reset_config = 1 }.build()
        } else {
            AdminMessage.Builder().also { wb -> wb.factory_reset_device = 1 }.build()
        }
        submitAdminAck(msg)
    }

    override suspend fun nodeDbReset(): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.nodedb_reset = true }.build())
    }

    override suspend fun setTimeOnly(unixTime: Int): AdminResult<Unit> {
        if (isLocalTargetManaged()) return AdminResult.Unauthorized
        return submitAdminFireAndForget(
            AdminMessage.Builder().also { wb ->
                wb.set_time_only = unixTime
            }.build(),
        )
    }

    override suspend fun setTime(at: Instant?): AdminResult<Unit> {
        val instant = at ?: nowProvider()
        return setTimeOnly(instant.epochSeconds.toInt())
    }

    override suspend fun <T> editSettings(block: suspend AdminEdit.() -> T): AdminResult<T> {
        val begin = beginEditSettings()
        if (begin !is AdminResult.Success) return begin.cast()
        val edit = AdminEditImpl()
        val payload = try {
            edit.block()
        } catch (e: AdminEditFailure) {
            return e.result.cast()
        }
        val commit = commitEditSettings()
        if (commit !is AdminResult.Success) return commit.cast()

        // Gap G: optimistically update configBundle with written values after successful commit.
        engine.applyConfigEdits(edit.writtenConfigs, edit.writtenModuleConfigs)

        return AdminResult.Success(payload)
    }

    override suspend fun <T> batch(block: suspend AdminBatchScope.() -> T): T {
        beginEditSettings().getOrThrow()
        val edit = AdminEditImpl()
        val payload = try {
            AdminBatchScopeImpl(edit).block()
        } catch (e: AdminEditFailure) {
            e.result.getOrThrow()
        }
        commitEditSettings().getOrThrow()

        engine.applyConfigEdits(edit.writtenConfigs, edit.writtenModuleConfigs)
        return payload
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Send an admin packet that carries a typed response on ADMIN_APP. Routes via
     * [MeshEngine.submitRpc] so the [CommandDispatcher] can correlate the reply.
     */
    private suspend fun <T> submitAdminRpc(
        adminMsg: AdminMessage,
        kind: ResponseKind<T>,
        to: NodeId = localNode(),
    ): AdminResult<T> {
        val requestId = engine.nextMessageId().raw
        val payload = AdminMessage.ADAPTER.encode(adminMsg).toByteString()
        val packet = MeshPacket.Builder().also { wb ->
            wb.id = requestId
            wb.from = engine.myNodeNumOrNull() ?: 0
            wb.to = to.raw
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.ADMIN_APP
                wb.payload = payload
                wb.want_response = true
            }.build()
        }.build()
        return engine.submitRpc(packet, requestId, kind, rpcTimeout)
    }

    /**
     * Send an admin packet whose only success signal is a wire-level ACK. Goes through the
     * existing send path so the engine's per-send ACK timer + Routing.Error mapping apply.
     */
    private suspend fun submitAdminAck(adminMsg: AdminMessage, to: NodeId = localNode()): AdminResult<Unit> {
        val payload = AdminMessage.ADAPTER.encode(adminMsg).toByteString()
        val id = engine.nextMessageId()
        val packet = MeshPacket.Builder().also { wb ->
            wb.id = id.raw
            wb.from = engine.myNodeNumOrNull() ?: 0
            wb.to = to.raw
            wb.want_ack = true
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.ADMIN_APP
                wb.payload = payload
                wb.want_response = false
            }.build()
        }.build()
        val stateFlow = MutableStateFlow<SendState>(SendState.Queued)
        engine.trySend(packet, id, stateFlow)
        val terminal = withTimeoutOrNull(rpcTimeout) {
            stateFlow.first {
                it is SendState.Failed || it == SendState.Acked || it == SendState.Delivered
            }
        } ?: return AdminResult.Timeout
        return when (terminal) {
            SendState.Acked, SendState.Delivered -> AdminResult.Success(Unit)
            is SendState.Failed -> mapSendFailureToAdminResult(terminal.reason)
            else -> AdminResult.Timeout
        }
    }

    /**
     * Send an admin packet without waiting for a firmware reply or routing ACK.
     */
    private fun submitAdminFireAndForget(adminMsg: AdminMessage, to: NodeId = localNode()): AdminResult<Unit> {
        engine.sendAdmin(adminMsg = adminMsg, to = to.raw)
        return AdminResult.Success(Unit)
    }

    private suspend fun beginEditSettings(): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(
            AdminMessage.Builder().also { wb ->
                wb.begin_edit_settings = true
            }.build(),
        )
    }

    private suspend fun commitEditSettings(): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(
            AdminMessage.Builder().also { wb ->
                wb.commit_edit_settings = true
            }.build(),
        )
    }

    /**
     * Single-shot retry on `SessionKeyExpired`: re-issue `get_owner_request` to refresh the
     * session passkey, then replay the original [block] once. The retry result is returned as-is
     * so a second `SessionKeyExpired` surfaces to the caller (the device is rejecting our key).
     */
    private suspend fun <T> retryOnSessionExpiry(block: suspend () -> AdminResult<T>): AdminResult<T> {
        if (isLocalTargetManaged()) return AdminResult.Unauthorized
        val first = block()
        if (first !is AdminResult.SessionKeyExpired) return first
        // Re-seed against the node this AdminApiImpl is scoped to. Session passkeys are
        // per-node (each node issues its own), and admin *read* requests don't require one —
        // so a get_owner_request to the target succeeds and its response carries a fresh
        // passkey, which the engine latches keyed by the responder. The replayed [block] then
        // picks it up via the engine's outbound admin choke point.
        reseedSessionPasskey()
        return block()
    }

    private suspend fun reseedSessionPasskey(): AdminResult<User> = submitAdminRpc(
        adminMsg = AdminMessage.Builder().also { wb -> wb.get_owner_request = true }.build(),
        kind = ResponseKind.AdminOwner,
        to = localNode(),
    )

    private fun localNode(): NodeId = targetNode ?: NodeId(engine.myNodeNumOrNull() ?: 0)

    private inner class AdminBatchScopeImpl(edit: AdminEditImpl) :
        AdminBatchScope,
        AdminEdit by edit {
        override suspend fun getConfig(type: AdminMessage.ConfigType): Config =
            this@AdminApiImpl.getConfig(type).getOrThrow()

        override suspend fun getModuleConfig(type: AdminMessage.ModuleConfigType): ModuleConfig =
            this@AdminApiImpl.getModuleConfig(type).getOrThrow()

        override suspend fun listChannels(): List<Channel> = this@AdminApiImpl.listChannels().getOrThrow()
    }

    private inner class AdminEditImpl : AdminEdit {
        val writtenConfigs = mutableListOf<Config>()
        val writtenModuleConfigs = mutableListOf<ModuleConfig>()

        override suspend fun setConfig(config: Config) {
            enqueueOrThrow(AdminMessage.Builder().also { wb -> wb.set_config = config }.build())
            writtenConfigs += config
        }
        override suspend fun setModuleConfig(config: ModuleConfig) {
            enqueueOrThrow(
                AdminMessage.Builder().also { wb ->
                    wb.set_module_config = config
                }.build(),
            )
            writtenModuleConfigs += config
        }
        override suspend fun setOwner(user: User) = enqueueOrThrow(
            AdminMessage.Builder().also { wb ->
                wb.set_owner = user
            }.build(),
        )
        override suspend fun setChannel(channel: Channel) = enqueueOrThrow(
            AdminMessage.Builder().also { wb ->
                wb.set_channel = channel
            }.build(),
        )
        override suspend fun setFavorite(node: NodeId, favorite: Boolean) {
            val msg = if (favorite) {
                AdminMessage.Builder().also { wb -> wb.set_favorite_node = node.raw }.build()
            } else {
                AdminMessage.Builder().also { wb -> wb.remove_favorite_node = node.raw }.build()
            }
            enqueueOrThrow(msg)
        }
        override suspend fun setIgnored(node: NodeId, ignored: Boolean) {
            val msg = if (ignored) {
                AdminMessage.Builder().also { wb -> wb.set_ignored_node = node.raw }.build()
            } else {
                AdminMessage.Builder().also { wb -> wb.remove_ignored_node = node.raw }.build()
            }
            enqueueOrThrow(msg)
        }

        /**
         * Enqueue an admin packet inside an `editSettings` block. `want_ack = false` because the
         * device buffers writes server-side and only ACKs at `commit_edit_settings`. If the
         * engine isn't connected (Disconnected after Ready), throw [AdminEditFailure] so
         * editSettings unwinds cleanly with the underlying failure.
         */
        private suspend fun enqueueOrThrow(adminMsg: AdminMessage) {
            if (engine.myNodeNumOrNull() == null) {
                throw AdminEditFailure(AdminResult.NodeUnreachable)
            }
            val payload = AdminMessage.ADAPTER.encode(adminMsg).toByteString()
            val id = engine.nextMessageId()
            val packet = MeshPacket.Builder().also { wb ->
                wb.id = id.raw
                wb.from = engine.myNodeNumOrNull() ?: 0
                wb.to = localNode().raw
                wb.decoded = Data.Builder().also { wb ->
                    wb.portnum = PortNum.ADMIN_APP
                    wb.payload = payload
                    wb.want_response = false
                }.build()
            }.build()
            val stateFlow = MutableStateFlow<SendState>(SendState.Queued)
            engine.trySend(packet, id, stateFlow)
            // Wait until the engine has at least transitioned out of Queued (i.e. dispatched to
            // the wire) — a flat `Failed(Disconnected)` here means the engine inbox was closed.
            val state = stateFlow.first { it != SendState.Queued }
            if (state is SendState.Failed && state.reason is SendFailure.Disconnected) {
                throw AdminEditFailure(AdminResult.NodeUnreachable)
            }
        }
    }
}

private class AdminEditFailure(val result: AdminResult<Nothing>) : RuntimeException()

private fun mapSendFailureToAdminResult(reason: SendFailure): AdminResult<Unit> = when (reason) {
    SendFailure.NoRoute, SendFailure.MaxRetransmit, SendFailure.Disconnected,
    SendFailure.HandshakeFailed, SendFailure.DutyCycleLimit,
    -> AdminResult.NodeUnreachable

    SendFailure.Timeout, SendFailure.AckTimeout -> AdminResult.Timeout

    SendFailure.Cancelled, SendFailure.IdCollision -> AdminResult.NodeUnreachable

    // Firmware transmit queue rejected the packet (queue full) — back off and retry.
    is SendFailure.QueueRejected -> AdminResult.RateLimited

    is SendFailure.Other -> when (reason.routingError) {
        Routing.Error.ADMIN_BAD_SESSION_KEY -> AdminResult.SessionKeyExpired

        Routing.Error.NOT_AUTHORIZED,
        Routing.Error.ADMIN_PUBLIC_KEY_UNAUTHORIZED,
        -> AdminResult.Unauthorized

        Routing.Error.RATE_LIMIT_EXCEEDED -> AdminResult.RateLimited

        else -> AdminResult.Failed(reason.routingError)
    }

    is SendFailure.Unknown -> AdminResult.Timeout
}

@Suppress("UNCHECKED_CAST")
private fun <T> AdminResult<*>.cast(): AdminResult<T> = this as AdminResult<T>
