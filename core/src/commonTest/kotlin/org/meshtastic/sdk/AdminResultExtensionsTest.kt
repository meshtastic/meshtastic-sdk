/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import org.meshtastic.proto.Routing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AdminResultExtensionsTest {

    @Test
    fun getOrThrow_returns_value_on_success() {
        val result: AdminResult<String> = AdminResult.Success("hello")
        assertEquals("hello", result.getOrThrow())
    }

    @Test
    fun getOrThrow_throws_SessionKeyExpired() {
        val result: AdminResult<Unit> = AdminResult.SessionKeyExpired
        assertFailsWith<AdminResultException.SessionKeyExpired> { result.getOrThrow() }
    }

    @Test
    fun getOrThrow_throws_Unauthorized() {
        val result: AdminResult<Unit> = AdminResult.Unauthorized
        assertFailsWith<AdminResultException.Unauthorized> { result.getOrThrow() }
    }

    @Test
    fun getOrThrow_throws_Timeout() {
        val result: AdminResult<Unit> = AdminResult.Timeout
        assertFailsWith<AdminResultException.Timeout> { result.getOrThrow() }
    }

    @Test
    fun getOrThrow_throws_RateLimited() {
        val result: AdminResult<Unit> = AdminResult.RateLimited
        assertFailsWith<AdminResultException.RateLimited> { result.getOrThrow() }
    }

    @Test
    fun getOrThrow_throws_NodeUnreachable() {
        val result: AdminResult<Unit> = AdminResult.NodeUnreachable
        assertFailsWith<AdminResultException.NodeUnreachable> { result.getOrThrow() }
    }

    @Test
    fun getOrThrow_throws_RoutingFailed_with_error() {
        val result: AdminResult<Unit> = AdminResult.Failed(Routing.Error.TOO_LARGE)
        val ex = assertFailsWith<AdminResultException.RoutingFailed> { result.getOrThrow() }
        assertEquals(Routing.Error.TOO_LARGE, ex.error)
    }

    @Test
    fun fold_invokes_onSuccess() {
        val result: AdminResult<Int> = AdminResult.Success(42)
        val folded = result.fold(onSuccess = { it * 2 }, onFailure = { -1 })
        assertEquals(84, folded)
    }

    @Test
    fun fold_invokes_onFailure() {
        val result: AdminResult<Int> = AdminResult.Timeout
        val folded = result.fold(onSuccess = { it * 2 }, onFailure = { -1 })
        assertEquals(-1, folded)
    }

    @Test
    fun map_transforms_success() {
        val result: AdminResult<Int> = AdminResult.Success(5)
        val mapped = result.map { it.toString() }
        assertIs<AdminResult.Success<String>>(mapped)
        assertEquals("5", mapped.value)
    }

    @Test
    fun map_propagates_failure() {
        val result: AdminResult<Int> = AdminResult.RateLimited
        val mapped = result.map { it.toString() }
        assertIs<AdminResult.RateLimited>(mapped)
    }
}
