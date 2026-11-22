/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import org.meshtastic.proto.Channel

private const val DEST_DOC = "Remote node (!aabbccdd, 0xHEX or decimal); omit for the connected radio."
private const val TIMEOUT_DOC = "Per-request reply timeout in ms (default 30000)."

private fun SchemaBuilder.remote() {
    prop("dest", "string", DEST_DOC)
    prop("timeout_ms", "integer", TIMEOUT_DOC)
}

private fun SchemaBuilder.sendTarget() {
    prop("dest", "string", "BROADCAST (default), !aabbccdd, 0xHEX or decimal node number.")
    prop("channel", "integer", "Channel index (default 0).")
    prop("want_ack", "boolean", "Ask for a wire-level ACK.")
    prop("await_ms", "integer", "How long to wait for a terminal outcome (default 30000).")
}

/** Every tool that needs a live radio; `connect` and `connection_state` live in [McpServer]. */
internal val deviceTools: List<ToolDef> = listOf(
    ToolDef(
        "nodes",
        "The connected radio's node DB, most recently heard first. Each row is " +
            "`{id, long_name, short_name, hw_model, last_heard, snr, hops_away, via_mqtt}`, absent fields omitted.",
        schema { prop("limit", "integer", "Return at most this many rows (default all).") },
        READ_ONLY,
        ::nodesList,
    ),
    ToolDef(
        "send_text",
        "Send a text message and wait for Acked/Delivered/Failed. A broadcast succeeds on the implicit ACK of a " +
            "neighbour's rebroadcast. Returns `{message_id, to, channel, outcome, reason, states, elapsed_ms}`.",
        schema(required = listOf("text")) {
            prop("text", "string", "Message body (UTF-8, at most 233 bytes).")
            prop("to", "string", "Alias of dest, kept for older callers.")
            sendTarget()
        },
        RADIO_WRITE,
        ::sendText,
    ),
    ToolDef(
        "send_position",
        "Broadcast or send a position packet. Returns the send_text shape.",
        schema(required = listOf("latitude", "longitude")) {
            prop("latitude", "number", "Degrees.")
            prop("longitude", "number", "Degrees.")
            prop("altitude", "integer", "Meters.")
            sendTarget()
        },
        RADIO_WRITE,
        ::sendPositionTool,
    ),
    ToolDef(
        "send_waypoint",
        "Send or delete (delete=true, needs id) a waypoint. Returns the send_text shape plus " +
            "`{waypoint_id, deleted}`.",
        schema {
            prop("id", "integer", "Waypoint id; generated when omitted on create.")
            prop("latitude", "number", "Degrees; required unless delete.")
            prop("longitude", "number", "Degrees; required unless delete.")
            prop("name", "string", "Short label.")
            prop("description", "string", "Longer text.")
            prop("icon", "string", "An emoji or its code point.")
            prop("expire", "integer", "Unix seconds after which it lapses; 0 never.")
            prop("locked_to", "integer", "Node number allowed to edit it.")
            prop("delete", "boolean", "Delete the waypoint with this id.")
            sendTarget()
        },
        RADIO_WRITE,
        ::sendWaypointTool,
    ),
    ToolDef(
        "request",
        "Ask a node for data and wait for the answer. kind is ${REQUEST_KINDS.joinToString(" | ")}. " +
            "Returns `{kind, dest, result, elapsed_ms}`; traceroute adds `route`, `route_back`, `snr_towards`, " +
            "`snr_back` (dB), `hops_away`; position and node_info return `{outcome: reply|timeout, from, result}`. " +
            "A request that gets no answer is a tool error (traceroute, telemetry, neighbor_info) or " +
            "`outcome: timeout` (position, node_info).",
        schema(required = listOf("kind")) {
            prop("kind", "string", "What to request.", enum = REQUEST_KINDS)
            prop("dest", "string", "Target node; required for traceroute, position and node_info, default local.")
            prop("variant", "string", "Telemetry variant.", enum = TELEMETRY_VARIANTS)
            prop("channel", "integer", "Channel index; default the channel the node was heard on.")
            prop("hop_limit", "integer", "Traceroute hop limit (default 7).")
            prop("timeout_ms", "integer", "Reply timeout in ms (default 60000); raise it for multi-hop.")
        },
        RADIO_WRITE,
        ::request,
    ),
    ToolDef(
        "config_get",
        "Read radio settings. Returns `{config: {section: {field: value}}, channels, owner, metadata, errors}`; " +
            "only what was asked for is present. Local reads come from the handshake copy unless live is true.",
        schema {
            prop(
                "sections",
                "array",
                "Section names or \"all\" (default all when nothing else is asked).",
                items = "string",
            )
            prop("channels", "boolean", "Include all channels.")
            prop("owner", "boolean", "Include the owner (user).")
            prop("metadata", "boolean", "Include device metadata.")
            prop("live", "boolean", "Ask the radio instead of using the handshake copy.")
            prop("include_private_key", "boolean", "Keep security.private_key (default redacted).")
            remote()
        },
        RADIO_READ,
        ::configGet,
    ),
    ToolDef(
        "config_set",
        "Apply dotted-path edits as one edit transaction. values maps `section.field` (e.g. `lora.hop_limit`, " +
            "`mqtt.map_report_settings.publish_interval_secs`) to a JSON value; sections are " +
            "${Sections.all.joinToString { it.name }}. " +
            "Unknown paths fail the call. Returns `{applied, verified, config}` where config is read back from the " +
            "radio; " +
            "verified false means the read-back failed (the radio may be rebooting) and config shows what was sent.",
        schema(required = listOf("values")) {
            prop("values", "object", "Map of dotted path to value.")
            remote()
        },
        RADIO_DESTRUCTIVE,
        ::configSet,
    ),
    ToolDef(
        "channel_set",
        "Create or edit a channel; unspecified fields keep their current value. Returns `{verified, channel}`.",
        schema(required = listOf("index")) {
            prop("index", "integer", "0..7; 0 is the primary channel.")
            prop("name", "string", "Channel name.")
            prop("psk", "string", "Base64 key (0, 1, 16 or 32 bytes), or none, default, random.")
            prop("role", "string", "Channel role.", enum = Channel.Role.entries.map { it.name })
            prop("uplink_enabled", "boolean", "MQTT uplink.")
            prop("downlink_enabled", "boolean", "MQTT downlink.")
            prop("position_precision", "integer", "Position bits shared on this channel; 0 off.")
            prop("muted", "boolean", "Mute the channel.")
            remote()
        },
        RADIO_DESTRUCTIVE,
        ::channelSet,
    ),
    ToolDef(
        "channel_delete",
        "Disable a secondary channel. Returns `{verified, channel}`.",
        schema(required = listOf("index")) {
            prop("index", "integer", "1..7.")
            remote()
        },
        RADIO_DESTRUCTIVE,
        ::channelDelete,
    ),
    ToolDef(
        "owner_set",
        "Edit the owner user, or enable ham mode with ham_call_sign (connected radio only). Returns " +
            "`{verified, owner}`.",
        schema {
            prop("long_name", "string", "Long name.")
            prop("short_name", "string", "Short name (4 characters).")
            prop("is_licensed", "boolean", "Licensed amateur operator.")
            prop("is_unmessagable", "boolean", "Do not receive direct messages.")
            prop("ham_call_sign", "string", "Enable ham mode with this call sign.")
            prop("ham_tx_power", "integer", "Ham mode TX power in dBm.")
            prop("ham_frequency", "number", "Ham mode frequency in MHz.")
            remote()
        },
        RADIO_DESTRUCTIVE,
        ::ownerSet,
    ),
    ToolDef(
        "fixed_position_set",
        "Pin the radio's position and turn on fixed-position mode. Returns the position set.",
        schema(required = listOf("latitude", "longitude")) {
            prop("latitude", "number", "Degrees.")
            prop("longitude", "number", "Degrees.")
            prop("altitude", "integer", "Meters.")
            remote()
        },
        RADIO_WRITE,
        ::fixedPositionSet,
    ),
    ToolDef(
        "fixed_position_clear",
        "Clear the fixed position and return to GPS. Returns `{cleared: true}`.",
        schema { remote() },
        RADIO_WRITE,
        ::fixedPositionClear,
    ),
    ToolDef(
        "set_time",
        "Set the radio's clock; unix_time defaults to now. Returns `{set: true}`.",
        schema {
            prop("unix_time", "integer", "Unix seconds.")
            remote()
        },
        RADIO_WRITE,
        ::setTime,
    ),
    ToolDef(
        "admin_action",
        "Run one admin action. action is ${ADMIN_ACTIONS.joinToString(" | ")}. Returns `{action, dest}`; on the " +
            "connected radio a restarting action adds `reconnect_required: true` and the session must be reopened " +
            "with connect.",
        schema(required = listOf("action")) {
            prop("action", "string", "What to do.", enum = ADMIN_ACTIONS)
            prop("node", "string", "Node for favorite, unfavorite, ignore, unignore, remove_node.")
            prop(
                "scope",
                "string",
                "factory_reset: config (settings only) or full (identity and BLE bonds too).",
                enum = listOf("config", "full"),
            )
            prop("preserve_favorites", "boolean", "nodedb_reset: keep favorites (default true).")
            prop("after_s", "integer", "reboot, shutdown: delay in seconds.")
            remote()
        },
        RADIO_DESTRUCTIVE,
        ::adminAction,
    ),
    ToolDef(
        "profile_export",
        "Export the radio's settings as a DeviceProfile (connected radio only). Returns `{profile}`.",
        schema {
            prop("include_private_key", "boolean", "Include the security private key (default false).")
            prop("include_device_text", "boolean", "Read ringtone and canned messages too (default true).")
        },
        RADIO_READ,
        ::profileExport,
    ),
)
