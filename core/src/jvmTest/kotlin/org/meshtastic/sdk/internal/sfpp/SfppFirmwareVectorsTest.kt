/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal.sfpp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.meshtastic.sdk.SfppHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Runs `sfpp-vectors.json`, the conformance file generated from firmware `test/test_sfpp_vectors`.
 * Every expected value is a literal from that file; none comes from this SDK.
 */
class SfppFirmwareVectorsTest {
    private val vectors = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/sfpp-vectors.json")) {
            "sfpp-vectors.json is not on the classpath"
        }
            .use { it.readBytes().decodeToString() },
    ).jsonObject

    @Test fun theFileIsSchema1() {
        assertEquals(1L, vectors.long("schema"))
    }

    @Test fun messageHashesMatchTheFirmware() = eachCase("hash", count = 4) { case ->
        val hash = SfppHash.compute(case.bytes("ciphertext"), case.u32("to"), case.u32("from"), case.u32("id"))

        assertEquals(case.str("expected"), hash.hex())
    }

    @Test fun ctrDecryptsTheFirmwareCiphertext() = eachCase("ctr", count = 2) { case ->
        val key = ChannelCipher.expandKey(case.bytes("psk"))
        if ("expanded_key" in case) assertEquals(case.str("expanded_key"), key.hex(), "expanded key")

        val plaintext = ChannelCipher.ctr(key, case.u32("from"), case.u32("id"), case.bytes("ciphertext"))

        assertEquals(case.str("expected_plaintext"), plaintext.hex())
    }

    @Test fun ccmOpensTheFirmwareCiphertextAndRejectsATamperedTag() = eachCase("ccm", count = 2) { case ->
        val plaintext = ChannelCipher.ccm(
            key = ChannelCipher.expandKey(case.bytes("psk")),
            from = case.u32("from"),
            to = case.u32("to"),
            id = case.u32("id"),
            ciphertextAndTag = case.bytes("ciphertext_and_tag"),
        )

        if ("expect" in case && case.str("expect") == "fail") {
            assertNull(plaintext, "a tampered tag must not open")
        } else {
            assertEquals(case.str("expected_plaintext"), plaintext?.hex())
        }
    }

    @Test fun firmwareFramesDecodeAndReassemble() = eachCase("frames", count = 2) { case ->
        val frames = case.getValue("frames").jsonArray.map { hex ->
            assertNotNull(SfppWire.decode(hex.jsonPrimitive.content.decodeHex().toByteArray()), "frame did not decode")
        }
        val envelope = frames.first()

        frames.forEach { assertEquals(SfppWire.ITEM_FRAME, it.type, "type") }
        assertEquals(case.str("ciphertext"), frames.joinToString("") { it.message.hex() }, "frame payloads")
        assertEquals(case.u32("from"), envelope.encapsulatedFrom, "from")
        assertEquals(case.u32("id"), envelope.encapsulatedId, "id")
        assertEquals(case.long("heard_ago_s"), envelope.heardAgoS, "heard_ago_s")
        assertEquals(case.bool("origin_verified"), envelope.originVerified, "origin_verified")

        val assembler = SfppFrameAssembler()
        frames.dropLast(1).forEach { assertNull(assembler.offer(it, ARCHIVE, attested = false, nowMs = 0)) }
        val replay = assertNotNull(
            assembler.offer(frames.last(), ARCHIVE, attested = false, nowMs = 0),
            "the last frame did not complete a verified set",
        )

        assertEquals(case.str("ciphertext"), replay.ciphertext.hex(), "reassembled ciphertext")
        assertEquals(case.u32("to"), replay.to, "to")
        assertEquals(case.u32("from"), replay.from, "from")
        assertEquals(case.u32("id"), replay.id, "id")
        assertEquals(case.long("heard_ago_s"), replay.heardAgoS, "heard_ago_s")
        assertEquals(case.bool("origin_verified"), replay.originVerified, "origin_verified")
    }

    // Runs every case of a group and reports all that fail, so one bad case does not hide the others.
    private fun eachCase(group: String, count: Int, check: (JsonObject) -> Unit) {
        val cases = vectors.getValue("cases").jsonObject.getValue(group).jsonArray.map { it.jsonObject }
        assertEquals(count, cases.size, "$group cases in the file")
        val failures = cases.mapNotNull { case ->
            runCatching { check(case) }.exceptionOrNull()?.let { "$group/${case.str("name")}: ${it.message}" }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private companion object {
        const val ARCHIVE = 0x0A0B0C0D
    }
}

private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.also {
    check(it.isString) { "$key is not a string" }
}.content

private fun JsonObject.bytes(key: String): ByteArray = str(key).decodeHex().toByteArray()

private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

// Node numbers and packet ids are uint32 in the file and Int bit patterns in the SDK.
private fun JsonObject.u32(key: String): Int = long(key).toInt()

private fun ByteArray.hex(): String = toByteString().hex()
