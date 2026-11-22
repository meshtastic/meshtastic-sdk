/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.cmd

import com.github.ajalt.clikt.core.Context
import kotlinx.coroutines.runBlocking
import org.meshtastic.cli.BaseCommand
import org.meshtastic.cli.mcp.McpServer
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

/** `cli mcp` — serve MCP over stdio, keeping one radio connection open across tool calls. */
internal class McpCmd : BaseCommand(name = "mcp") {
    override fun help(context: Context) =
        "Serve MCP over stdio (tools: connect, connection_state, nodes, send_text); logs go to stderr."

    override fun run() {
        // stdout carries only JSON-RPC frames, so every println in the process is pointed at stderr.
        val frames = FileOutputStream(FileDescriptor.out)
        System.setOut(System.err)
        runBlocking { McpServer(System.`in`, frames).serve() }
        // Transport and BLE threads are not all daemons; exit once the session is torn down.
        exitProcess(0)
    }
}
