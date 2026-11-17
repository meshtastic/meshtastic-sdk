/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.flow.StateFlow
import okio.ByteString

/**
 * Transfers files between the host and the device's on-board filesystem over the phone API,
 * using firmware's XModem-style exchange (`ToRadio.xmodemPacket` / `FromRadio.xmodemPacket`).
 *
 * This is file transfer only. Flashing a firmware image is out of scope.
 *
 * One transfer runs at a time per client; a second call fails fast with
 * [FileTransferFailure.Busy]. Cancelling the calling coroutine aborts the transfer and leaves
 * the device idle: once the device has acknowledged the upload request, an upload sends `CAN`,
 * which makes firmware delete the partial file; before that it re-sends the request to learn
 * the device's state and never sends `CAN`, which could delete an unrelated file. A
 * download sends a frame firmware treats as unexpected, so the source file is kept (firmware
 * deletes the file named by the last request when it receives `CAN`).
 *
 * **BLE duplicate filter:** BLE firmware drops a write identical to the previous one. The
 * transfer varies the `seq` of the control frames firmware ignores it on, and puts a heartbeat
 * between two writes that would otherwise be identical.
 *
 * **Nudges:** while a reply is overdue by a second the transfer writes a heartbeat, which makes a
 * device that holds its output until the host writes deliver it. A nudge is not a retry and is
 * never an XModem frame.
 *
 * **Inbound delivery:** the transfer reads device frames from [RadioClient.events], a bounded
 * drop-oldest buffer. Firmware sends one frame and waits for the reply, and the transfer reads
 * each frame as it arrives, so the buffer cannot overflow from the transfer alone. A frame lost
 * to a stalled event collector is recovered by the per-block timeout and bounded retries.
 *
 * **Access control:** firmware drops XModem frames from a client that has not authenticated on
 * `MESHTASTIC_LOCKDOWN` builds, so a download from such a client ends in
 * [FileTransferFailure.Timeout] and an upload ends in [FileTransferFailure.Timeout] before the
 * first block.
 *
 * Firmware reports no error when its filesystem fills during an upload: the device keeps
 * acknowledging blocks and drops the data it cannot write. Keep uploads small and read the file
 * back to verify.
 *
 * Access via [RadioClient.files].
 *
 * @since 0.3.0
 */
public interface FileTransferApi {

    /**
     * Progress of the transfer in flight, or `null` while idle. Reset to `null` when a transfer
     * ends, whatever the outcome.
     */
    public val progress: StateFlow<FileTransferProgress?>

    /**
     * Reads the file at [path] from the device.
     *
     * The device answers a request for a path it cannot open with a refusal, reported as
     * [FileTransferFailure.Refused]. An empty file returns an empty [ByteString].
     *
     * The download completes once the device's last block (shorter than 128 bytes) is accepted: if
     * the device's closing frame is a `CAN` or never arrives, the bytes are still returned.
     *
     * A device that falls out of step (a block ahead of the expected one, or an end-of-file frame
     * after a full block) makes the download start over, discarding what was received, up to twice;
     * [FileTransferFailure.RetriesExhausted] follows the last one. A refusal, a cancellation by the
     * device or a size limit is a real answer and ends the call.
     *
     * @param path device filesystem path, 1 to [MAX_PATH_BYTES] UTF-8 bytes, no NUL
     * @return the file contents, or the reason the transfer did not complete. A file larger than
     *   [MAX_FILE_BYTES] fails with [FileTransferFailure.TooLarge] as soon as the limit is crossed.
     */
    public suspend fun download(path: String): FileTransferResult<ByteString>

    /**
     * Writes [data] to [path] on the device, replacing any existing file.
     *
     * @param path device filesystem path, 1 to [MAX_PATH_BYTES] UTF-8 bytes, no NUL
     * @param data file contents, at most [MAX_FILE_BYTES]; empty creates an empty file
     */
    public suspend fun upload(path: String, data: ByteString): FileTransferResult<Unit>

    /** Limits that apply to every transfer. */
    public companion object {
        /**
         * Largest file the SDK transfers, in bytes. Firmware's sequence number is 16 bits and
         * blocks are 128 bytes; this stays far below the wrap and above any flash filesystem.
         */
        public const val MAX_FILE_BYTES: Int = 1 shl 20

        /** Longest device path, in UTF-8 bytes. Firmware copies at most 127 bytes of the name. */
        public const val MAX_PATH_BYTES: Int = 127
    }
}

/**
 * Direction of a [FileTransferProgress].
 *
 * @since 0.3.0
 */
public enum class FileTransferDirection {
    /** Device to host. */
    Download,

    /** Host to device. */
    Upload,
}

/**
 * Snapshot of a transfer in flight.
 *
 * @property path device filesystem path being transferred
 * @property direction whether the file is being read from or written to the device
 * @property bytesTransferred bytes the receiving side has acknowledged so far
 * @property totalBytes size of the file, known for uploads and `null` for downloads (firmware
 *   does not announce a size)
 * @since 0.3.0
 */
public data class FileTransferProgress(
    val path: String,
    val direction: FileTransferDirection,
    val bytesTransferred: Int,
    val totalBytes: Int?,
)

/**
 * Outcome of a [FileTransferApi] call. Expected failures are values, never exceptions.
 *
 * @since 0.3.0
 */
public sealed interface FileTransferResult<out T> {
    /**
     * The transfer completed.
     *
     * @param value the downloaded bytes, or [Unit] for an upload
     */
    public data class Success<T>(val value: T) : FileTransferResult<T>

    /**
     * The transfer did not complete.
     *
     * @param reason why
     */
    public data class Failure(val reason: FileTransferFailure) : FileTransferResult<Nothing>
}

/**
 * Why a [FileTransferApi] call did not complete.
 *
 * @since 0.3.0
 */
public sealed interface FileTransferFailure {
    /** The client is not connected, or the connection dropped mid-transfer. */
    public data object NotConnected : FileTransferFailure

    /** Another transfer is already running on this client. */
    public data object Busy : FileTransferFailure

    /** The path is empty, longer than [FileTransferApi.MAX_PATH_BYTES], or contains a NUL. */
    public data object InvalidPath : FileTransferFailure

    /** The file is larger than [FileTransferApi.MAX_FILE_BYTES]. */
    public data object TooLarge : FileTransferFailure

    /**
     * The device refused the request: the file cannot be opened, or the path is rejected
     * (firmware refuses a `..` path component).
     */
    public data object Refused : FileTransferFailure

    /** The device cancelled the transfer mid-way. */
    public data object DeviceCancelled : FileTransferFailure

    /** The same block failed (bad CRC, wrong sequence or no acknowledgment) too many times. */
    public data object RetriesExhausted : FileTransferFailure

    /** The device stopped answering. */
    public data object Timeout : FileTransferFailure
}
