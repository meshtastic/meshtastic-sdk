/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
import org.meshtastic.sdk.PacedMessageHandle
import org.meshtastic.sdk.SendFailure
import org.meshtastic.sdk.SendState
import org.meshtastic.sdk.getOrThrow
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
    private val nowProvider: () -> Instant,
    private val editLocks: AdminEditLocks,
    private val targetNode: NodeId? = null,
) : AdminApi {

    override fun forNode(dest: NodeId): AdminApi = AdminApiImpl(
        engine = engine,
        rpcTimeout = rpcTimeout,
        nowProvider = nowProvider,
        editLocks = editLocks,
        targetNode = dest,
    )

    override fun withRpcTimeout(timeout: Duration): AdminApi = AdminApiImpl(
        engine = engine,
        rpcTimeout = timeout,
        nowProvider = nowProvider,
        editLocks = editLocks,
        targetNode = targetNode,
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
        if (!isLocalTarget()) return false
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

    private fun isLocalTarget(): Boolean = targetNode == null || targetNode.raw == engine.myNodeNumOrNull()

    override suspend fun setConfig(config: Config): AdminResult<Unit> = submitLocalWrite(
        AdminMessage.Builder().also { wb -> wb.set_config = config }.build(),
        LocalWrite.Config(config),
    )

    override suspend fun getModuleConfig(type: AdminMessage.ModuleConfigType): AdminResult<ModuleConfig> =
        retryOnSessionExpiry {
            submitAdminRpc(
                adminMsg = AdminMessage.Builder().also { wb ->
                    wb.get_module_config_request = type
                }.build(),
                kind = ResponseKind.AdminModuleConfig,
            )
        }

    override suspend fun setModuleConfig(config: ModuleConfig): AdminResult<Unit> = submitLocalWrite(
        AdminMessage.Builder().also { wb -> wb.set_module_config = config }.build(),
        LocalWrite.ModuleConfig(config),
    )

    override suspend fun getOwner(): AdminResult<User> = retryOnSessionExpiry {
        submitAdminRpc(
            adminMsg = AdminMessage.Builder().also { wb -> wb.get_owner_request = true }.build(),
            kind = ResponseKind.AdminOwner,
        )
    }

    /**
     * Send an acked write and, once the connected node acks it, mirror it on the SDK state without a
     * round trip. The write carries the generation of the link it went out on, so a link replaced
     * before the caller resumes does not take it.
     */
    private suspend fun submitLocalWrite(adminMsg: AdminMessage, write: LocalWrite): AdminResult<Unit> {
        var generation = engine.connectionGeneration()
        val result = retryOnSessionExpiry {
            generation = engine.connectionGeneration()
            submitAdminAck(adminMsg)
        }
        if (result is AdminResult.Success && isLocalTarget()) engine.applyLocalWrites(listOf(write), generation)
        return result
    }

    override suspend fun setOwner(user: User): AdminResult<Unit> = submitLocalWrite(
        AdminMessage.Builder().also { wb -> wb.set_owner = user }.build(),
        LocalWrite.Node(NodeWrite.Owner(user)),
    )

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

    override suspend fun setChannel(channel: Channel): AdminResult<Unit> = submitLocalWrite(
        AdminMessage.Builder().also { wb -> wb.set_channel = channel }.build(),
        LocalWrite.Channel(channel),
    )

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

    override suspend fun setFavorite(node: NodeId, favorite: Boolean): AdminResult<Unit> {
        val msg = if (favorite) {
            AdminMessage.Builder().also { wb -> wb.set_favorite_node = node.raw }.build()
        } else {
            AdminMessage.Builder().also { wb -> wb.remove_favorite_node = node.raw }.build()
        }
        return submitLocalWrite(msg, LocalWrite.Node(NodeWrite.Flags(node.raw, favorite = favorite)))
    }

    override suspend fun setIgnored(node: NodeId, ignored: Boolean): AdminResult<Unit> {
        val msg = if (ignored) {
            AdminMessage.Builder().also { wb -> wb.set_ignored_node = node.raw }.build()
        } else {
            AdminMessage.Builder().also { wb -> wb.remove_ignored_node = node.raw }.build()
        }
        return submitLocalWrite(msg, LocalWrite.Node(NodeWrite.Flags(node.raw, ignored = ignored)))
    }

    override suspend fun toggleMuted(node: NodeId): AdminResult<Unit> = submitLocalWrite(
        AdminMessage.Builder().also { wb -> wb.toggle_muted_node = node.raw }.build(),
        LocalWrite.Node(NodeWrite.ToggleMuted(node.raw)),
    )

    // ── Position ────────────────────────────────────────────────────────────

    override suspend fun setFixedPosition(position: Position): AdminResult<Unit> = submitLocalWrite(
        AdminMessage.Builder().also { wb -> wb.set_fixed_position = position }.build(),
        LocalWrite.Node(NodeWrite.FixedPosition(position)),
    )

    override suspend fun removeFixedPosition(): AdminResult<Unit> = submitLocalWrite(
        AdminMessage.Builder().also { wb -> wb.remove_fixed_position = true }.build(),
        LocalWrite.Node(NodeWrite.FixedPosition(null)),
    )

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

    override suspend fun playRingtone(): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.play_ringtone_request = true }.build())
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

    override suspend fun removeNode(node: NodeId): AdminResult<Unit> = submitLocalWrite(
        AdminMessage.Builder().also { wb -> wb.remove_by_nodenum = node.raw }.build(),
        LocalWrite.Node(NodeWrite.Remove(node.raw)),
    )

    // ── Input / Display ─────────────────────────────────────────────────────

    override suspend fun setScale(scale: Int): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.set_scale = scale }.build())
    }

    override suspend fun sendInputEvent(event: AdminMessage.InputEvent): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.send_input_event = event }.build())
    }

    // Display mirror: firmware consumes these in PhoneAPI on the local link, before session-key and
    // managed-mode checks, and refuses them over the mesh, so no retryOnSessionExpiry wrapper.
    override suspend fun setDisplayMirror(enabled: Boolean): AdminResult<Unit> {
        if (!isLocalTarget()) return AdminResult.Unauthorized
        return submitAdminAck(AdminMessage.Builder().also { wb -> wb.set_display_mirror = enabled }.build())
    }

    override suspend fun requestDisplayFrame(): AdminResult<Unit> {
        if (!isLocalTarget()) return AdminResult.Unauthorized
        return submitAdminAck(AdminMessage.Builder().also { wb -> wb.get_display_frame_request = true }.build())
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

    override suspend fun lockdown(auth: LockdownAuth): AdminResult<Unit> = engine.enqueueLockdown(auth, targetNode?.raw)

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

    override suspend fun nodeDbReset(preserveFavorites: Boolean): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.nodedb_reset = preserveFavorites }.build())
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

    override suspend fun <T> editSettings(block: suspend AdminEdit.() -> T): AdminResult<T> = editLock().withEditLock {
        val generation = engine.connectionGeneration()
        val begin = beginEditSettings()
        if (begin !is AdminResult.Success) return@withEditLock begin.cast()
        val edit = AdminEditImpl(generation)
        val payload = try {
            edit.block()
        } catch (failure: Throwable) {
            val cleanup = closeEditSettings(generation)
            if (failure !is AdminEditFailure) {
                cleanup.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            cleanup.exceptionOrNull()?.let(failure::addSuppressed)
            return@withEditLock failure.result.cast()
        }
        val commit = closeEditSettings(generation).getOrElse { throw it }
        if (commit !is AdminResult.Success) return@withEditLock commit.cast()

        edit.applyLocallyAfterCommit()

        AdminResult.Success(payload)
    }

    override suspend fun <T> batch(block: suspend AdminBatchScope.() -> T): T = editLock().withEditLock {
        val generation = engine.connectionGeneration()
        beginEditSettings().getOrThrow()
        val edit = AdminEditImpl(generation)
        val payload = try {
            AdminBatchScopeImpl(edit).block()
        } catch (failure: Throwable) {
            val cleanup = closeEditSettings(generation)
            cleanup.exceptionOrNull()?.let(failure::addSuppressed)
            if (failure is AdminEditFailure) failure.result.getOrThrow()
            throw failure
        }
        closeEditSettings(generation).getOrElse { throw it }.getOrThrow()

        edit.applyLocallyAfterCommit()
        payload
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * The connected node shares one lock under any number or instance, including the number a live
     * renumber is moving it to before the session restarts; a remote node has its own.
     */
    private fun editLock() = when {
        targetNode == null || engine.isConnectedNodeNum(targetNode.raw) -> editLocks.local()
        else -> editLocks.remote(targetNode.raw)
    }

    /**
     * Send an admin packet that carries a typed response on ADMIN_APP. Routes via
     * [MeshEngine.submitRpc] so the [CommandDispatcher] can correlate the reply.
     */
    private suspend fun <T> submitAdminRpc(
        adminMsg: AdminMessage,
        kind: ResponseKind<T>,
        to: NodeId = localNode(),
    ): AdminResult<T> {
        val payload = AdminMessage.ADAPTER.encode(adminMsg).toByteString()
        val packet = MeshPacket.Builder().also { wb ->
            wb.from = engine.myNodeNumOrNull() ?: 0
            wb.to = to.raw
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.ADMIN_APP
                wb.payload = payload
                wb.want_response = true
            }.build()
        }.build()
        return engine.submitRpc(packet, kind, rpcTimeout)
    }

    /**
     * Send an acked admin write through the paced queue and wait for its routing outcome.
     *
     * Firmware takes BLE down inside the handler of a restart-class command, before any ACK or
     * QueueStatus. On such a link, a command to the connected node that the transport wrote and
     * whose link then went away was applied.
     */
    private suspend fun submitAdminAck(adminMsg: AdminMessage): AdminResult<Unit> {
        val generation = engine.connectionGeneration()
        val appliedOnLinkLoss =
            isLocalTarget() && engine.linkDropsOnRestartCommands && adminMsg.dropsLinkBeforeAnswering()
        val written = if (appliedOnLinkLoss) CompletableDeferred<Unit>() else null
        val handle = enqueuePacedAdmin(adminMsg, wantAck = true, written = written)
        val terminal = try {
            withTimeoutOrNull(rpcTimeout) {
                handle.message.state.first {
                    it is SendState.Failed || it == SendState.Acked || it == SendState.Delivered
                }
            }
        } catch (e: CancellationException) {
            // A write still queued must not run after its caller is gone.
            handle.message.cancel()
            throw e
        }
        val appliedBeforeLinkLoss = written?.isCompleted == true && when (terminal) {
            null -> !engine.isConnectedAtGeneration(generation)
            is SendState.Failed -> terminal.reason == SendFailure.Disconnected
            else -> false
        }
        // A write still queued behind another must not run after its caller saw Timeout.
        if (terminal == null) handle.message.cancel()
        return when {
            appliedBeforeLinkLoss -> AdminResult.Success(Unit)
            terminal == null -> AdminResult.Timeout
            terminal == SendState.Acked || terminal == SendState.Delivered -> AdminResult.Success(Unit)
            terminal is SendState.Failed -> mapSendFailureToAdminResult(terminal.reason)
            else -> AdminResult.Timeout
        }
    }

    /**
     * Queue an admin packet behind the engine's paced sends, which release one at a time on firmware
     * queue evidence, so writes keep their order and never overrun the device's small inbound buffer.
     */
    private fun enqueuePacedAdmin(
        adminMsg: AdminMessage,
        wantAck: Boolean,
        written: CompletableDeferred<Unit>? = null,
    ): PacedMessageHandle {
        val packet = MeshPacket.Builder().also { wb ->
            wb.from = engine.myNodeNumOrNull() ?: 0
            wb.to = localNode().raw
            wb.want_ack = wantAck
            wb.decoded = Data.Builder().also { wb ->
                wb.portnum = PortNum.ADMIN_APP
                wb.payload = AdminMessage.ADAPTER.encode(adminMsg).toByteString()
                wb.want_response = false
            }.build()
        }.build()
        return engine.enqueuePaced(packet, resend = null, written = written)
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
        submitAdminAck(AdminMessage.Builder().also { wb -> wb.commit_edit_settings = true }.build())
    }

    private suspend fun closeEditSettings(generation: Long): Result<AdminResult<Unit>> = withContext(NonCancellable) {
        if (!engine.isConnectedAtGeneration(generation)) {
            Result.success(AdminResult.NodeUnreachable)
        } else {
            runCatching { commitEditSettings() }
        }
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

    private inner class AdminEditImpl(private val generation: Long) : AdminEdit {
        // In call order: the device applies the block's writes in the order they arrived.
        private val writes = mutableListOf<LocalWrite>()

        suspend fun applyLocallyAfterCommit() {
            if (isLocalTarget()) engine.applyLocalWrites(writes, generation)
        }

        override suspend fun setConfig(config: Config) {
            enqueueOrThrow(AdminMessage.Builder().also { wb -> wb.set_config = config }.build())
            writes += LocalWrite.Config(config)
        }
        override suspend fun setModuleConfig(config: ModuleConfig) {
            enqueueOrThrow(
                AdminMessage.Builder().also { wb ->
                    wb.set_module_config = config
                }.build(),
            )
            writes += LocalWrite.ModuleConfig(config)
        }
        override suspend fun setOwner(user: User) {
            enqueueOrThrow(AdminMessage.Builder().also { wb -> wb.set_owner = user }.build())
            writes += LocalWrite.Node(NodeWrite.Owner(user))
        }
        override suspend fun setChannel(channel: Channel) {
            enqueueOrThrow(AdminMessage.Builder().also { wb -> wb.set_channel = channel }.build())
            writes += LocalWrite.Channel(channel)
        }
        override suspend fun setFavorite(node: NodeId, favorite: Boolean) {
            val msg = if (favorite) {
                AdminMessage.Builder().also { wb -> wb.set_favorite_node = node.raw }.build()
            } else {
                AdminMessage.Builder().also { wb -> wb.remove_favorite_node = node.raw }.build()
            }
            enqueueOrThrow(msg)
            writes += LocalWrite.Node(NodeWrite.Flags(node.raw, favorite = favorite))
        }
        override suspend fun setIgnored(node: NodeId, ignored: Boolean) {
            val msg = if (ignored) {
                AdminMessage.Builder().also { wb -> wb.set_ignored_node = node.raw }.build()
            } else {
                AdminMessage.Builder().also { wb -> wb.remove_ignored_node = node.raw }.build()
            }
            enqueueOrThrow(msg)
            writes += LocalWrite.Node(NodeWrite.Flags(node.raw, ignored = ignored))
        }
        override suspend fun setFixedPosition(position: Position) {
            enqueueOrThrow(AdminMessage.Builder().also { wb -> wb.set_fixed_position = position }.build())
            writes += LocalWrite.Node(NodeWrite.FixedPosition(position))
        }
        override suspend fun removeFixedPosition() {
            enqueueOrThrow(AdminMessage.Builder().also { wb -> wb.remove_fixed_position = true }.build())
            writes += LocalWrite.Node(NodeWrite.FixedPosition(null))
        }

        /**
         * Enqueue an admin packet inside an `editSettings` block. `want_ack = false` because the
         * device buffers writes server-side and only ACKs at `commit_edit_settings`. Waiting for
         * the paced dispatch holds the block to the device's pace. If the engine isn't connected,
         * throw [AdminEditFailure] so editSettings unwinds cleanly with the underlying failure.
         */
        private suspend fun enqueueOrThrow(adminMsg: AdminMessage) {
            if (!engine.isConnectedAtGeneration(generation) || engine.myNodeNumOrNull() == null) {
                throw AdminEditFailure(AdminResult.NodeUnreachable)
            }
            if (!enqueuePacedAdmin(adminMsg, wantAck = false).awaitDispatch()) {
                throw AdminEditFailure(AdminResult.NodeUnreachable)
            }
        }
    }
}

private class AdminEditFailure(val result: AdminResult<Nothing>) : RuntimeException()

/**
 * Admin commands whose firmware handler takes BLE down before it can answer (`disableBluetooth()` in
 * `AdminModule.cpp`), or jumps to the bootloader without answering. `set_config` and
 * `set_module_config` only do so when they need a reboot; an unrelated link loss after any of these
 * is still read as applied.
 */
private fun AdminMessage.dropsLinkBeforeAnswering(): Boolean = commit_edit_settings == true ||
    set_config != null ||
    set_module_config != null ||
    factory_reset_config != null ||
    factory_reset_device != null ||
    nodedb_reset != null ||
    restore_preferences != null ||
    enter_dfu_mode_request == true

private fun mapSendFailureToAdminResult(reason: SendFailure): AdminResult<Unit> = when (reason) {
    SendFailure.NoRoute -> AdminResult.Failed(Routing.Error.NO_ROUTE)

    SendFailure.MaxRetransmit -> AdminResult.Failed(Routing.Error.MAX_RETRANSMIT)

    SendFailure.Disconnected, SendFailure.HandshakeFailed, SendFailure.DutyCycleLimit -> AdminResult.NodeUnreachable

    SendFailure.Timeout, SendFailure.AckTimeout -> AdminResult.Timeout

    SendFailure.Cancelled, SendFailure.IdCollision -> AdminResult.NodeUnreachable

    // ERRNO_NO_INTERFACES and ERRNO_DISABLED (firmware MeshTypes.h): no radio to send on, so retrying cannot help.
    // ERRNO_* ends at 35; above it the result is a Routing.Error that Router::send returned, such as
    // PKI_SEND_FAIL_PUBLIC_KEY.
    is SendFailure.QueueRejected -> when {
        reason.res == ERRNO_NO_INTERFACES || reason.res == ERRNO_DISABLED -> AdminResult.Failed(
            Routing.Error.NO_INTERFACE,
        )

        reason.res > ERRNO_SHOULD_RELEASE ->
            Routing.Error.fromValue(reason.res)?.let(::mapRoutingErrorToAdminResult) ?: AdminResult.RateLimited

        else -> AdminResult.RateLimited
    }

    // The SDK paced queue or the firmware transmit queue is full; the link is up, so back off and retry.
    SendFailure.QueueFull, SendFailure.RateLimited -> AdminResult.RateLimited

    is SendFailure.Other -> mapRoutingErrorToAdminResult(reason.routingError)

    is SendFailure.Unknown -> AdminResult.Timeout
}

private fun mapRoutingErrorToAdminResult(error: Routing.Error): AdminResult<Unit> = when (error) {
    Routing.Error.ADMIN_BAD_SESSION_KEY -> AdminResult.SessionKeyExpired

    Routing.Error.NOT_AUTHORIZED,
    Routing.Error.ADMIN_PUBLIC_KEY_UNAUTHORIZED,
    -> AdminResult.Unauthorized

    Routing.Error.RATE_LIMIT_EXCEEDED -> AdminResult.RateLimited

    else -> AdminResult.Failed(error)
}

@Suppress("UNCHECKED_CAST")
private fun <T> AdminResult<*>.cast(): AdminResult<T> = this as AdminResult<T>

private const val ERRNO_NO_INTERFACES = 33
private const val ERRNO_DISABLED = 34
private const val ERRNO_SHOULD_RELEASE = 35
