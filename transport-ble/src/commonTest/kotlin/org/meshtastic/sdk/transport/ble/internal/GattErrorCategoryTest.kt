/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble.internal

import com.juul.kable.GattStatusException
import com.juul.kable.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the GATT-status → category map used by the BLE transport's retry
 * and disconnect-classification paths. Cross-ref: TX-14 in `audit-report.md`.
 */
class GattErrorCategoryTest {

    @Test
    fun classifyKnownStatuses() {
        assertEquals(GattErrorCategory.CONN_TIMEOUT, classifyGattStatus(AndroidGattStatus.CONN_TIMEOUT))
        assertEquals(
            GattErrorCategory.PEER_TERMINATED,
            classifyGattStatus(AndroidGattStatus.CONN_TERMINATE_PEER_USER),
        )
        assertEquals(
            GattErrorCategory.LOCAL_TERMINATED,
            classifyGattStatus(AndroidGattStatus.CONN_TERMINATE_LOCAL_HOST),
        )
        assertEquals(GattErrorCategory.BOND_OR_MTU, classifyGattStatus(AndroidGattStatus.ERROR_133))
    }

    @Test
    fun classifyUnknownStatusFallsBackToGeneric() {
        assertEquals(GattErrorCategory.GENERIC, classifyGattStatus(99))
    }

    @Test
    fun classifyThrowableWalksCauseChain() {
        val gatt = GattStatusException(message = "boom", cause = null, status = 133)
        val wrapped = RuntimeException("wrapped", gatt)
        val doubleWrapped = RuntimeException("outer", wrapped)
        assertEquals(GattErrorCategory.BOND_OR_MTU, classifyGattError(doubleWrapped))
    }

    @Test
    fun classifyThrowableWithoutGattStatusIsGeneric() {
        assertEquals(GattErrorCategory.GENERIC, classifyGattError(RuntimeException("nope")))
    }

    @Test
    fun backoffForClampsIndex() {
        // 5+ failed attempts must not throw; just reuse the last slot.
        val backoff = GattErrorCategory.BOND_OR_MTU.backoffFor(99)
        assertEquals(GattErrorCategory.BOND_OR_MTU.backoffMs.last(), backoff)
    }

    @Test
    fun bondOrMtuExponentialBackoffOrdering() {
        // Sanity: subsequent attempts wait at least as long as previous ones
        // for categories that escalate.
        val cat = GattErrorCategory.CONN_TIMEOUT
        var prev = -1L
        cat.backoffMs.forEach {
            assertTrue(it >= prev, "backoff schedule must be non-decreasing for $cat")
            prev = it
        }
    }

    @Test
    fun disconnectStatusToCodeExtractsUnknown() {
        val status: State.Disconnected.Status = State.Disconnected.Status.Unknown(133)
        assertEquals(133, status.gattStatusCodeOrNull())
    }

    @Test
    fun disconnectStatusToCodeReturnsNullForTypedReasons() {
        val status: State.Disconnected.Status = State.Disconnected.Status.Cancelled
        assertEquals(null, status.gattStatusCodeOrNull())
    }
}
