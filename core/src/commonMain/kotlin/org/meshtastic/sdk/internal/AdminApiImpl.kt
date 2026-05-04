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
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.User
import org.meshtastic.sdk.AdminApi
import org.meshtastic.sdk.AdminEdit
import org.meshtastic.sdk.AdminResult
import org.meshtastic.sdk.ChannelIndex
import org.meshtastic.sdk.NodeId
import org.meshtastic.sdk.SendFailure
import org.meshtastic.sdk.SendState
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
) : AdminApi {

    override suspend fun getConfig(type: AdminMessage.ConfigType): AdminResult<Config> = retryOnSessionExpiry {
        submitAdminRpc(
            adminMsg = AdminMessage(get_config_request = type),
            kind = ResponseKind.AdminConfig,
        )
    }

    override suspend fun setConfig(config: Config): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage(set_config = config))
    }

    override suspend fun getModuleConfig(type: AdminMessage.ModuleConfigType): AdminResult<ModuleConfig> =
        retryOnSessionExpiry {
            submitAdminRpc(
                adminMsg = AdminMessage(get_module_config_request = type),
                kind = ResponseKind.AdminModuleConfig,
            )
        }

    override suspend fun setModuleConfig(config: ModuleConfig): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage(set_module_config = config))
    }

    override suspend fun getOwner(): AdminResult<User> = retryOnSessionExpiry {
        submitAdminRpc(
            adminMsg = AdminMessage(get_owner_request = true),
            kind = ResponseKind.AdminOwner,
        )
    }

    override suspend fun setOwner(user: User): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage(set_owner = user))
    }

    override suspend fun getChannel(index: ChannelIndex): AdminResult<Channel> = retryOnSessionExpiry {
        submitAdminRpc(
            adminMsg = AdminMessage(get_channel_request = index.raw),
            kind = ResponseKind.AdminChannel,
        )
    }

    override suspend fun setChannel(channel: Channel): AdminResult<Unit> {
        val result = retryOnSessionExpiry { submitAdminAck(AdminMessage(set_channel = channel)) }
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
            AdminMessage(set_favorite_node = node.raw)
        } else {
            AdminMessage(remove_favorite_node = node.raw)
        }
        submitAdminAck(msg)
    }

    override suspend fun setIgnored(node: NodeId, ignored: Boolean): AdminResult<Unit> = retryOnSessionExpiry {
        val msg = if (ignored) {
            AdminMessage(set_ignored_node = node.raw)
        } else {
            AdminMessage(remove_ignored_node = node.raw)
        }
        submitAdminAck(msg)
    }

    override suspend fun reboot(after: Duration): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage(reboot_seconds = after.inWholeSeconds.toInt().coerceAtLeast(0)))
    }

    override suspend fun shutdown(after: Duration): AdminResult<Unit> = retryOnSessionExpiry {
        submitAdminAck(AdminMessage(shutdown_seconds = after.inWholeSeconds.toInt().coerceAtLeast(0)))
    }

    override suspend fun factoryReset(preserveBleBonds: Boolean): AdminResult<Unit> = retryOnSessionExpiry {
        // factory_reset_config wipes settings only (preserves identity + BLE bonds).
        // factory_reset_device wipes everything including BLE bonds.
        val msg = if (preserveBleBonds) {
            AdminMessage(factory_reset_config = 1)
        } else {
            AdminMessage(factory_reset_device = 1)
        }
        submitAdminAck(msg)
    }

    override suspend fun nodeDbReset(preserveFavorites: Boolean): AdminResult<Unit> = retryOnSessionExpiry {
        // Firmware exposes only `nodedb_reset = true`; preserveFavorites is honoured by the
        // device's own NodeDB module which keeps favorite-marked entries across the wipe.
        submitAdminAck(AdminMessage(nodedb_reset = true))
    }

    override suspend fun setTime(at: Instant?): AdminResult<Unit> = retryOnSessionExpiry {
        val instant = at ?: nowProvider()
        val seconds = instant.epochSeconds.toInt()
        submitAdminAck(AdminMessage(set_time_only = seconds))
    }

    override suspend fun <T> editSettings(block: suspend AdminEdit.() -> T): AdminResult<T> {
        val begin = retryOnSessionExpiry { submitAdminAck(AdminMessage(begin_edit_settings = true)) }
        if (begin !is AdminResult.Success) return begin.cast()
        val edit = AdminEditImpl()
        val payload = try {
            edit.block()
        } catch (e: AdminEditFailure) {
            return e.result.cast()
        }
        val commit = retryOnSessionExpiry { submitAdminAck(AdminMessage(commit_edit_settings = true)) }
        if (commit !is AdminResult.Success) return commit.cast()

        // Gap G: optimistically update configBundle with written values after successful commit.
        engine.applyConfigEdits(edit.writtenConfigs, edit.writtenModuleConfigs)

        return AdminResult.Success(payload)
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
        val packet = MeshPacket(
            id = requestId,
            from = engine.myNodeNumOrNull() ?: 0,
            to = to.raw,
            decoded = Data(
                portnum = PortNum.ADMIN_APP,
                payload = payload,
                want_response = true,
            ),
        )
        return engine.submitRpc(packet, requestId, kind, rpcTimeout)
    }

    /**
     * Send an admin packet whose only success signal is a wire-level ACK. Goes through the
     * existing send path so the engine's per-send ACK timer + Routing.Error mapping apply.
     */
    private suspend fun submitAdminAck(adminMsg: AdminMessage, to: NodeId = localNode()): AdminResult<Unit> {
        val payload = AdminMessage.ADAPTER.encode(adminMsg).toByteString()
        val id = engine.nextMessageId()
        val packet = MeshPacket(
            id = id.raw,
            from = engine.myNodeNumOrNull() ?: 0,
            to = to.raw,
            want_ack = true,
            decoded = Data(
                portnum = PortNum.ADMIN_APP,
                payload = payload,
                want_response = false,
            ),
        )
        val stateFlow = MutableStateFlow<SendState>(SendState.Queued)
        engine.trySend(packet, id, stateFlow)
        val terminal = stateFlow.first {
            it is SendState.Failed || it == SendState.Acked || it == SendState.Delivered
        }
        return when (terminal) {
            SendState.Acked, SendState.Delivered -> AdminResult.Success(Unit)
            is SendState.Failed -> mapSendFailureToAdminResult(terminal.reason)
            else -> AdminResult.Timeout
        }
    }

    /**
     * Single-shot retry on `SessionKeyExpired`: re-issue `get_owner_request` to refresh the
     * session passkey, then replay the original [block] once. The retry result is returned as-is
     * so a second `SessionKeyExpired` surfaces to the caller (the device is rejecting our key).
     */
    private suspend fun <T> retryOnSessionExpiry(block: suspend () -> AdminResult<T>): AdminResult<T> {
        val first = block()
        if (first !is AdminResult.SessionKeyExpired) return first
        // Re-seed: a fresh getOwner round-trip latches a new session_passkey. We don't propagate
        // its success / failure — the original call's retry is the user-visible signal.
        getOwner()
        return block()
    }

    private fun localNode(): NodeId = NodeId(engine.myNodeNumOrNull() ?: 0)

    private inner class AdminEditImpl : AdminEdit {
        val writtenConfigs = mutableListOf<Config>()
        val writtenModuleConfigs = mutableListOf<ModuleConfig>()

        override suspend fun setConfig(config: Config) {
            enqueueOrThrow(AdminMessage(set_config = config))
            writtenConfigs += config
        }
        override suspend fun setModuleConfig(config: ModuleConfig) {
            enqueueOrThrow(AdminMessage(set_module_config = config))
            writtenModuleConfigs += config
        }
        override suspend fun setOwner(user: User) = enqueueOrThrow(AdminMessage(set_owner = user))
        override suspend fun setChannel(channel: Channel) = enqueueOrThrow(AdminMessage(set_channel = channel))
        override suspend fun setFavorite(node: NodeId, favorite: Boolean) {
            val msg = if (favorite) {
                AdminMessage(set_favorite_node = node.raw)
            } else {
                AdminMessage(remove_favorite_node = node.raw)
            }
            enqueueOrThrow(msg)
        }
        override suspend fun setIgnored(node: NodeId, ignored: Boolean) {
            val msg = if (ignored) {
                AdminMessage(set_ignored_node = node.raw)
            } else {
                AdminMessage(remove_ignored_node = node.raw)
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
            val packet = MeshPacket(
                id = id.raw,
                from = engine.myNodeNumOrNull() ?: 0,
                to = engine.myNodeNumOrNull() ?: 0,
                decoded = Data(
                    portnum = PortNum.ADMIN_APP,
                    payload = payload,
                    want_response = false,
                ),
            )
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

    is SendFailure.Other -> AdminResult.Failed(reason.routingError)

    is SendFailure.Unknown -> AdminResult.Timeout
}

@Suppress("UNCHECKED_CAST")
private fun <T> AdminResult<*>.cast(): AdminResult<T> = this as AdminResult<T>
