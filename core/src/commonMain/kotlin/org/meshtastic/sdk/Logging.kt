/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

/**
 * Severity level for log messages.
 *
 * Ordinal values are ascending in severity: [NONE] < [VERBOSE] < [DEBUG] < [INFO] < [WARN] < [ERROR].
 * Implementations can compare ordinals to filter by minimum level.
 *
 * @since 0.1.0
 */
public enum class LogLevel {
    /**
     * Disabled; no messages are logged.
     */
    NONE,

    /**
     * Verbose — detailed trace of internal operations.
     */
    VERBOSE,

    /**
     * Debug — diagnostic information for development.
     */
    DEBUG,

    /**
     * Info — significant events (connect, disconnect, errors).
     */
    INFO,

    /**
     * Warning — potentially problematic conditions.
     */
    WARN,

    /**
     * Error — failures that do not prevent further operation.
     */
    ERROR,
}

/**
 * Functional interface for consuming log messages.
 *
 * The SDK never depends on a specific logging library (no Kermit, Timber, SLF4J, etc.).
 * Apps provide their own [LogSink] implementation and wire it to [RadioClient.Builder.logger].
 * The SDK produces logs without assuming any backend.
 *
 * Implementations should be lightweight and must not block the engine coroutine for long.
 *
 * @since 0.1.0
 */
public fun interface LogSink {
    /**
     * Log a message at the given level.
     *
     * @param level the severity level
     * @param tag a category label (e.g., "WireCodec", "Engine", "Handshake")
     * @param message the log message
     * @param cause an optional exception stack trace to include
     */
    public fun log(level: LogLevel, tag: String, message: String, cause: Throwable?)

    public companion object {
        /**
         * A no-op logger that discards all messages.
         */
        public val Silent: LogSink = LogSink { _, _, _, _ -> }
    }
}

// ── Internal inline extensions ──────────────────────────────────────────────
//
// These avoid message-string construction when the sink is Silent (the default).
// Using `this !== LogSink.Silent` as the fast-path check means zero overhead for
// the common case. The lambda parameter is never invoked when silent, avoiding
// allocation of the message string and any interpolated toString() calls.

/**
 * Log at [LogLevel.VERBOSE] only if this sink is not [LogSink.Silent].
 *
 * The [message] lambda is only invoked when the sink is active, avoiding string
 * construction overhead when logging is disabled.
 */
@PublishedApi
internal inline fun LogSink.verbose(tag: String, cause: Throwable? = null, message: () -> String) {
    if (this !== LogSink.Silent) log(LogLevel.VERBOSE, tag, message(), cause)
}

/**
 * Log at [LogLevel.DEBUG] only if this sink is not [LogSink.Silent].
 *
 * The [message] lambda is only invoked when the sink is active, avoiding string
 * construction overhead when logging is disabled.
 */
@PublishedApi
internal inline fun LogSink.debug(tag: String, cause: Throwable? = null, message: () -> String) {
    if (this !== LogSink.Silent) log(LogLevel.DEBUG, tag, message(), cause)
}

/**
 * Log at [LogLevel.INFO] only if this sink is not [LogSink.Silent].
 *
 * The [message] lambda is only invoked when the sink is active, avoiding string
 * construction overhead when logging is disabled.
 */
@PublishedApi
internal inline fun LogSink.info(tag: String, cause: Throwable? = null, message: () -> String) {
    if (this !== LogSink.Silent) log(LogLevel.INFO, tag, message(), cause)
}

/**
 * Log at [LogLevel.WARN] only if this sink is not [LogSink.Silent].
 *
 * The [message] lambda is only invoked when the sink is active, avoiding string
 * construction overhead when logging is disabled.
 */
@PublishedApi
internal inline fun LogSink.warn(tag: String, cause: Throwable? = null, message: () -> String) {
    if (this !== LogSink.Silent) log(LogLevel.WARN, tag, message(), cause)
}

/**
 * Log at [LogLevel.ERROR] only if this sink is not [LogSink.Silent].
 *
 * The [message] lambda is only invoked when the sink is active, avoiding string
 * construction overhead when logging is disabled.
 */
@PublishedApi
internal inline fun LogSink.error(tag: String, cause: Throwable? = null, message: () -> String) {
    if (this !== LogSink.Silent) log(LogLevel.ERROR, tag, message(), cause)
}
