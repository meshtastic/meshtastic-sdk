/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import org.meshtastic.sdk.internal.CommandDispatcher
import org.meshtastic.sdk.internal.ResponseKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommandDispatcherTest {

    @Test
    fun matchingTelemetryResponseCompletesPendingRequest() = runTest {
        val dispatcher = CommandDispatcher()
        val deferred = register(dispatcher, requestId = 101)
        val timeoutJob = Job()
        dispatcher.attachTimeoutJob(101, timeoutJob)

        val expected = Telemetry(device_metrics = DeviceMetrics(battery_level = 87, voltage = 4.1f))
        assertTrue(dispatcher.tryComplete(telemetryPacket(requestId = 101, telemetry = expected)))

        val result = deferred.await()
        val success = result as AdminResult.Success<*>
        assertEquals(expected, success.value)
        assertEquals(0, dispatcher.size())
        assertTrue(timeoutJob.isCancelled)
    }

    @Test
    fun timeoutResolvesPendingRequest() = runTest {
        val dispatcher = CommandDispatcher()
        val deferred = register(dispatcher, requestId = 202)

        dispatcher.timeout(202)

        assertEquals(AdminResult.Timeout, deferred.await())
        assertEquals(0, dispatcher.size())
    }

    @Test
    fun concurrentRequestsResolveIndependentlyOutOfOrder() = runTest {
        val dispatcher = CommandDispatcher()
        val telemetryDeferred = register(dispatcher, requestId = 301)
        val ownerDeferred = register(dispatcher, requestId = 302, kind = ResponseKind.AdminOwner)

        val expectedOwner = User(long_name = "Remote Node", short_name = "RN")
        assertTrue(
            dispatcher.tryComplete(
                adminPacket(
                    requestId = 302,
                    response = AdminMessage(get_owner_response = expectedOwner),
                ),
            ),
        )

        val expectedTelemetry = Telemetry(device_metrics = DeviceMetrics(battery_level = 45, uptime_seconds = 99))
        assertTrue(dispatcher.tryComplete(telemetryPacket(requestId = 301, telemetry = expectedTelemetry)))

        val ownerResult = ownerDeferred.await() as AdminResult.Success<*>
        val telemetryResult = telemetryDeferred.await() as AdminResult.Success<*>
        assertEquals(expectedOwner, ownerResult.value)
        assertEquals(expectedTelemetry, telemetryResult.value)
        assertEquals(0, dispatcher.size())
    }

    @Test
    fun routingErrorResolvesOnlyMatchingPendingRequest() = runTest {
        val dispatcher = CommandDispatcher()
        val failedDeferred = register(dispatcher, requestId = 401)
        val successDeferred = register(dispatcher, requestId = 402)
        val timeoutJob = Job()
        dispatcher.attachTimeoutJob(401, timeoutJob)

        assertTrue(dispatcher.tryFailFromRouting(401, Routing.Error.NOT_AUTHORIZED))
        assertEquals(AdminResult.Unauthorized, failedDeferred.await())
        assertFalse(successDeferred.isCompleted)
        assertTrue(timeoutJob.isCancelled)

        val expected = Telemetry(device_metrics = DeviceMetrics(battery_level = 64))
        assertTrue(dispatcher.tryComplete(telemetryPacket(requestId = 402, telemetry = expected)))
        val success = successDeferred.await() as AdminResult.Success<*>
        assertEquals(expected, success.value)
    }

    @Test
    fun duplicateResponseIsIgnoredAfterCompletion() = runTest {
        val dispatcher = CommandDispatcher()
        val deferred = register(dispatcher, requestId = 501)
        val first = Telemetry(device_metrics = DeviceMetrics(battery_level = 12))

        assertTrue(dispatcher.tryComplete(telemetryPacket(requestId = 501, telemetry = first)))
        assertFalse(
            dispatcher.tryComplete(
                telemetryPacket(
                    requestId = 501,
                    telemetry = Telemetry(device_metrics = DeviceMetrics(battery_level = 99)),
                ),
            ),
        )

        val success = deferred.await() as AdminResult.Success<*>
        assertEquals(first, success.value)
        assertEquals(0, dispatcher.size())
    }

    @Test
    fun unmatchedRequestIdIsIgnored() = runTest {
        val dispatcher = CommandDispatcher()
        val deferred = register(dispatcher, requestId = 601)

        assertFalse(
            dispatcher.tryComplete(
                telemetryPacket(
                    requestId = 999,
                    telemetry = Telemetry(device_metrics = DeviceMetrics(battery_level = 1)),
                ),
            ),
        )

        assertFalse(deferred.isCompleted)
        assertEquals(1, dispatcher.size())
    }

    @Test
    fun wrongPortDoesNotConsumePendingTelemetryRequest() = runTest {
        val dispatcher = CommandDispatcher()
        val deferred = register(dispatcher, requestId = 701)

        assertFalse(
            dispatcher.tryComplete(
                adminPacket(
                    requestId = 701,
                    response = AdminMessage(get_owner_response = User(long_name = "Wrong Port")),
                ),
            ),
        )
        assertFalse(deferred.isCompleted)
        assertEquals(1, dispatcher.size())

        val expected = Telemetry(device_metrics = DeviceMetrics(battery_level = 22))
        assertTrue(dispatcher.tryComplete(telemetryPacket(requestId = 701, telemetry = expected)))
        val success = deferred.await() as AdminResult.Success<*>
        assertEquals(expected, success.value)
    }

    @Test
    fun invalidPayloadDoesNotConsumePendingRequest() = runTest {
        val dispatcher = CommandDispatcher()
        val deferred = register(dispatcher, requestId = 801)

        assertFalse(dispatcher.tryComplete(invalidPacket(requestId = 801)))
        assertFalse(deferred.isCompleted)
        assertEquals(1, dispatcher.size())
    }

    @Test
    fun routingAckNoneLeavesPendingRequestWaiting() = runTest {
        val dispatcher = CommandDispatcher()
        val deferred = register(dispatcher, requestId = 901)

        assertFalse(dispatcher.tryFailFromRouting(901, Routing.Error.NONE))

        assertFalse(deferred.isCompleted)
        assertEquals(1, dispatcher.size())
    }

    @Test
    fun reRegisteringSameIdTimesOutPriorCaller() = runTest {
        val dispatcher = CommandDispatcher()
        val oldDeferred = register(dispatcher, requestId = 1001)
        val oldTimeoutJob = Job()
        dispatcher.attachTimeoutJob(1001, oldTimeoutJob)

        val replacementDeferred = CompletableDeferred<AdminResult<Any?>>()
        dispatcher.register(1001, ResponseKind.Telemetry, replacementDeferred)

        assertEquals(AdminResult.Timeout, oldDeferred.await())
        assertTrue(oldTimeoutJob.isCancelled)
        assertEquals(1, dispatcher.size())

        val expected = Telemetry(device_metrics = DeviceMetrics(battery_level = 73))
        assertTrue(dispatcher.tryComplete(telemetryPacket(requestId = 1001, telemetry = expected)))
        val success = replacementDeferred.await() as AdminResult.Success<*>
        assertEquals(expected, success.value)
    }

    @Test
    fun cancelAllFailsEveryPendingRequest() = runTest {
        val dispatcher = CommandDispatcher()
        val first = register(dispatcher, requestId = 1101)
        val second = register(dispatcher, requestId = 1102)
        val firstJob = Job()
        val secondJob = Job()
        dispatcher.attachTimeoutJob(1101, firstJob)
        dispatcher.attachTimeoutJob(1102, secondJob)

        dispatcher.cancelAll(AdminResult.NodeUnreachable)

        assertEquals(AdminResult.NodeUnreachable, first.await())
        assertEquals(AdminResult.NodeUnreachable, second.await())
        assertTrue(firstJob.isCancelled)
        assertTrue(secondJob.isCancelled)
        assertEquals(0, dispatcher.size())
    }

    @Test
    fun zeroRequestIdResponseIsIgnored() = runTest {
        val dispatcher = CommandDispatcher()
        val deferred = register(dispatcher, requestId = 1201)

        assertFalse(
            dispatcher.tryComplete(
                telemetryPacket(
                    requestId = 0,
                    telemetry = Telemetry(device_metrics = DeviceMetrics(battery_level = 99)),
                ),
            ),
        )

        assertFalse(deferred.isCompleted)
        assertEquals(1, dispatcher.size())
    }

    private fun register(
        dispatcher: CommandDispatcher,
        requestId: Int,
        kind: ResponseKind<*> = ResponseKind.Telemetry,
    ): CompletableDeferred<AdminResult<Any?>> {
        val deferred = CompletableDeferred<AdminResult<Any?>>()
        dispatcher.register(requestId, kind, deferred)
        return deferred
    }

    private fun telemetryPacket(
        requestId: Int,
        telemetry: Telemetry,
        portnum: PortNum = PortNum.TELEMETRY_APP,
    ): MeshPacket = MeshPacket(
        decoded = Data(
            portnum = portnum,
            payload = Telemetry.ADAPTER.encode(telemetry).toByteString(),
            request_id = requestId,
        ),
    )

    private fun adminPacket(requestId: Int, response: AdminMessage): MeshPacket = MeshPacket(
        decoded = Data(
            portnum = PortNum.ADMIN_APP,
            payload = AdminMessage.ADAPTER.encode(response).toByteString(),
            request_id = requestId,
        ),
    )

    private fun invalidPacket(requestId: Int): MeshPacket = MeshPacket(
        decoded = Data(
            portnum = PortNum.TELEMETRY_APP,
            payload = byteArrayOf(0x80.toByte()).toByteString(),
            request_id = requestId,
        ),
    )
}
