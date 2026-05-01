/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class) // Kable 0.42 characteristicOf(Uuid, Uuid) still requires opt-in

package org.meshtastic.sdk.transport.ble

import com.juul.kable.Characteristic
import com.juul.kable.characteristicOf
import kotlin.uuid.Uuid

/**
 * Meshtastic BLE GATT layout per `docs/protocol.md §3`.
 *
 * The Meshtastic mesh service exposes four characteristics on the same primary service.
 * The service UUID is also used by the host as a scan filter to discover Meshtastic radios.
 */
public object BleConstants {

    /** Primary GATT service exposed by every Meshtastic radio. */
    public val MESH_SERVICE_UUID: Uuid =
        Uuid.parse("6ba1b218-15a8-461f-9fa8-5dcae273eafd")

    /**
     * `fromradio` — READ characteristic. Each read returns one full `FromRadio`
     * protobuf payload, or an empty buffer once the device's outbound queue is drained.
     */
    public val FROMRADIO_UUID: Uuid =
        Uuid.parse("2c55e69e-4993-11ed-b878-0242ac120002")

    /**
     * `toradio` — WRITE-WITHOUT-RESPONSE characteristic. Each write carries one full
     * `ToRadio` protobuf payload (no `0x94 0xC3` framing — BLE uses GATT message
     * boundaries directly).
     */
    public val TORADIO_UUID: Uuid =
        Uuid.parse("f75c76d2-129e-4dad-a1dd-7866124401e7")

    /**
     * `fromnum` — NOTIFY+READ characteristic. A 4-byte little-endian counter that
     * the device increments whenever it pushes data into the `fromradio` queue.
     *
     * Treat its value purely as a **wake signal** — never as a "messages waiting"
     * count. Always drain `fromradio` to empty.
     */
    public val FROMNUM_UUID: Uuid =
        Uuid.parse("ed9da18c-a800-4f66-a670-aa7547e34453")

    /** Lazily resolved `fromradio` characteristic (used for reads). */
    public val FROMRADIO: Characteristic =
        characteristicOf(MESH_SERVICE_UUID, FROMRADIO_UUID)

    /** Lazily resolved `toradio` characteristic (used for writes). */
    public val TORADIO: Characteristic =
        characteristicOf(MESH_SERVICE_UUID, TORADIO_UUID)

    /** Lazily resolved `fromnum` characteristic (used for notifications). */
    public val FROMNUM: Characteristic =
        characteristicOf(MESH_SERVICE_UUID, FROMNUM_UUID)
}
