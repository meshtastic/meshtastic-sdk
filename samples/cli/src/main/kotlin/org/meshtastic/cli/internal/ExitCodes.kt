/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.internal

/** Stable process exit codes shared by every `cli` subcommand. Documented in samples/cli/README.md. */
internal object ExitCodes {
    /** Operation completed successfully. */
    const val OK: Int = 0

    /** Operation failed (handshake error, send rejected, probe failed, etc.). */
    const val FAILURE: Int = 1

    /** Usage error: bad flag, missing required arg, malformed transport spec. */
    const val USAGE: Int = 2

    /** A `--timeout` was exceeded before the operation completed. */
    const val TIMEOUT: Int = 3

    /** No matching device was found (BLE scan empty, serial port absent, TCP host unreachable). */
    const val NO_DEVICE: Int = 4

    /** The requested transport is not supported on this platform/build. */
    const val UNSUPPORTED: Int = 5

    /** User-initiated interrupt (SIGINT / Ctrl+C). */
    const val INTERRUPTED: Int = 130
}
