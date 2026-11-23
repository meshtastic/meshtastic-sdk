/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.transport.ble

import com.juul.kable.Characteristic
import com.juul.kable.Descriptor
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredDescriptor
import com.juul.kable.DiscoveredService
import com.juul.kable.ExperimentalKableApi
import com.juul.kable.Identifier
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlin.uuid.Uuid

/**
 * Minimal in-memory [Peripheral]: [connect]/[disconnect] flip [state], [read] returns an
 * empty payload (queue drained, which satisfies the warmup read), [observe] never emits except `logradio`, which replays [logNotifications].
 * [connectBehavior] runs first on every connect (1-based call number) so a test can stall or
 * fail one; [servicesAfterConnect] is what discovery reports for that call. [events] records the
 * order of connect and disconnect calls. Members [BleTransport] never touches throw
 * [UnsupportedOperationException].
 */
// ExperimentalUuidApi: on iOS targets Kable's `Identifier` is kotlin.uuid.Uuid (experimental);
// the override below trips the opt-in only when compiled for those targets.
@OptIn(ExperimentalKableApi::class, kotlin.uuid.ExperimentalUuidApi::class)
internal class FakeBlePeripheral(
    private val connectBehavior: suspend (call: Int) -> Unit = {},
    private val servicesAfterConnect: (call: Int) -> List<DiscoveredService>? = { null },
    val events: MutableList<String> = mutableListOf(),
    private val logNotifications: Flow<ByteArray> = emptyFlow(),
) : Peripheral {

    private val stateFlow = MutableStateFlow<State>(State.Disconnected())

    var connectCalls: Int = 0
        private set
    var closeCalls: Int = 0
        private set

    override val scope: CoroutineScope = CoroutineScope(SupervisorJob())

    override val state: StateFlow<State> = stateFlow

    override val identifier: Identifier
        get() = throw UnsupportedOperationException("not used by BleTransport")

    override val name: String? = null

    private val servicesFlow = MutableStateFlow<List<DiscoveredService>?>(null)

    override val services: StateFlow<List<DiscoveredService>?> = servicesFlow

    override suspend fun connect(): CoroutineScope {
        connectCalls++
        events += "connect#$connectCalls"
        connectBehavior(connectCalls)
        servicesFlow.value = servicesAfterConnect(connectCalls)
        stateFlow.value = State.Connected(scope)
        return scope
    }

    override suspend fun disconnect() {
        events += "disconnect"
        stateFlow.value = State.Disconnected()
    }

    /** Moves [state] as the radio or the OS would, with no call from the transport. */
    fun emitState(state: State) {
        stateFlow.value = state
    }

    override suspend fun maximumWriteValueLengthForType(writeType: WriteType): Int = 512

    override suspend fun rssi(): Int = throw UnsupportedOperationException("not used by BleTransport")

    override suspend fun read(characteristic: Characteristic): ByteArray = ByteArray(0)

    override suspend fun write(characteristic: Characteristic, data: ByteArray, writeType: WriteType) = Unit

    override suspend fun read(descriptor: Descriptor): ByteArray =
        throw UnsupportedOperationException("not used by BleTransport")

    override suspend fun write(descriptor: Descriptor, data: ByteArray) = Unit

    var logSubscriptions: Int = 0
        private set

    override fun observe(characteristic: Characteristic, onSubscription: suspend () -> Unit): Flow<ByteArray> =
        if (characteristic.characteristicUuid == BleConstants.LOGRADIO_UUID) {
            logNotifications.onStart { logSubscriptions++ }
        } else {
            emptyFlow()
        }

    override fun close() {
        closeCalls++
        scope.cancel()
    }
}

/** A discovery result with the given characteristics under [service]. */
@OptIn(ExperimentalKableApi::class, kotlin.uuid.ExperimentalUuidApi::class)
internal fun fakeServices(
    service: Uuid = BleConstants.MESH_SERVICE_UUID,
    vararg characteristics: Uuid,
): List<DiscoveredService> = listOf(
    object : DiscoveredService {
        override val serviceUuid: Uuid = service
        override val characteristics: List<DiscoveredCharacteristic> = characteristics.map { uuid ->
            object : DiscoveredCharacteristic {
                override val serviceUuid: Uuid = service
                override val characteristicUuid: Uuid = uuid
                override val descriptors: List<DiscoveredDescriptor> = emptyList()
                override val properties: Characteristic.Properties = Characteristic.Properties(0)
            }
        }
    },
)

/** A complete Meshtastic GATT table. */
@OptIn(ExperimentalKableApi::class, kotlin.uuid.ExperimentalUuidApi::class)
internal fun meshtasticServices(): List<DiscoveredService> = fakeServices(
    BleConstants.MESH_SERVICE_UUID,
    BleConstants.FROMRADIO_UUID,
    BleConstants.TORADIO_UUID,
    BleConstants.FROMNUM_UUID,
)
