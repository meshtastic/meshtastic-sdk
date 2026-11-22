/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class) // Kable 0.45 Service/Characteristic UUIDs are kotlin.uuid.Uuid

package org.meshtastic.sdk.transport.ble

import com.juul.kable.DiscoveredService
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.meshtastic.sdk.MeshtasticException

internal enum class BondProgress { None, Bonding, Bonded }

/** The platform's bond state machine, behind a seam so the wait logic runs without a radio. */
internal interface BondControl {
    fun progress(): BondProgress

    /** Asks the OS to start bonding; false when it refused or a bond is already in flight. */
    fun create(): Boolean
}

/** Clears the platform's cached GATT table; false when the platform refused. */
internal fun interface GattCacheRefresher {
    fun refresh(): Boolean
}

/** Read by the platform connect path on Kable's thread, flipped by [BleTransport.connect]. */
internal class AutoConnectSwitch {
    // ADR-012: lifecycle idempotency - cross-thread flag, no compound state.
    private val on = atomic(false)

    val isOn: Boolean get() = on.value

    fun turnOn() {
        on.value = true
    }

    fun reset() {
        on.value = false
    }
}

/**
 * Starts a bond if the device has none and waits for it. [onBonding] fires once a bond is in
 * flight. Fails with [MeshtasticException.Transport] when the OS refuses, rejects, or takes
 * longer than [timeoutMs].
 */
internal suspend fun BondControl.ensureBonded(
    onBonding: () -> Unit,
    timeoutMs: Long = BOND_TIMEOUT_MS,
    pollMs: Long = BOND_POLL_MS,
) {
    if (progress() == BondProgress.Bonded) return
    if (!create()) {
        when (progress()) {
            BondProgress.Bonded -> return
            BondProgress.Bonding -> Unit
            BondProgress.None -> throw MeshtasticException.Transport("The OS did not start bonding")
        }
    }
    onBonding()
    try {
        withTimeout(timeoutMs) { awaitBond(pollMs) }
    } catch (_: TimeoutCancellationException) {
        throw MeshtasticException.Transport("Timed out after ${timeoutMs}ms waiting for the bond")
    }
}

private suspend fun BondControl.awaitBond(pollMs: Long) {
    // create() can return before the stack reports BOND_BONDING, so a few None reads are not a failure.
    var noneReads = 0
    while (true) {
        when (progress()) {
            BondProgress.Bonded -> return

            BondProgress.Bonding -> noneReads = 0

            BondProgress.None -> if (++noneReads > BOND_NONE_GRACE_READS) {
                throw MeshtasticException.Transport("Bonding failed or was rejected")
            }
        }
        delay(pollMs)
    }
}

/** True when discovery ran and the Meshtastic service or one of its three characteristics is absent. */
internal fun List<DiscoveredService>?.isMissingMeshtasticGatt(): Boolean {
    if (this == null) return false
    val service = firstOrNull { it.serviceUuid == BleConstants.MESH_SERVICE_UUID } ?: return true
    val present = service.characteristics.map { it.characteristicUuid }.toSet()
    return !present.containsAll(
        listOf(BleConstants.FROMRADIO_UUID, BleConstants.TORADIO_UUID, BleConstants.FROMNUM_UUID),
    )
}

internal const val BOND_TIMEOUT_MS: Long = 30_000L
internal const val BOND_POLL_MS: Long = 500L
private const val BOND_NONE_GRACE_READS: Int = 2
