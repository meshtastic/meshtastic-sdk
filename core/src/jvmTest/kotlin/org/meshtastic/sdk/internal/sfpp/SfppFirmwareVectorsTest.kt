/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal.sfpp

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
    private val vectors = JsonReader(
        checkNotNull(javaClass.getResourceAsStream("/sfpp-vectors.json")) {
            "sfpp-vectors.json is not on the classpath"
        }
            .use { it.readBytes().decodeToString() },
    ).readDocument().obj()

    @Test fun theFileIsSchema1() {
        assertEquals(1L, vectors["schema"])
    }

    @Test fun messageHashesMatchTheFirmware() = eachCase("hash", count = 4) { case ->
        val hash = SfppHash.compute(case.bytes("ciphertext"), case.u32("to"), case.u32("from"), case.u32("id"))

        assertEquals(case.str("expected"), hash.hex())
    }

    @Test fun ctrDecryptsTheFirmwareCiphertext() = eachCase("ctr", count = 2) { case ->
        val key = ChannelCipher.expandKey(case.bytes("psk"))
        case["expanded_key"]?.let { assertEquals(it, key.hex(), "expanded key") }

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

        if (case["expect"] == "fail") {
            assertNull(plaintext, "a tampered tag must not open")
        } else {
            assertEquals(case.str("expected_plaintext"), plaintext?.hex())
        }
    }

    @Test fun firmwareFramesDecodeAndReassemble() = eachCase("frames", count = 2) { case ->
        val frames = case.list("frames").map { hex ->
            assertNotNull(SfppWire.decode((hex as String).decodeHex().toByteArray()), "frame did not decode")
        }
        val envelope = frames.first()

        frames.forEach { assertEquals(SfppWire.ITEM_FRAME, it.type, "type") }
        assertEquals(case.str("ciphertext"), frames.joinToString("") { it.message.hex() }, "frame payloads")
        assertEquals(case.u32("from"), envelope.encapsulatedFrom, "from")
        assertEquals(case.u32("id"), envelope.encapsulatedId, "id")
        assertEquals(case["heard_ago_s"], envelope.heardAgoS, "heard_ago_s")
        assertEquals(case["origin_verified"], envelope.originVerified, "origin_verified")

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
        assertEquals(case["heard_ago_s"], replay.heardAgoS, "heard_ago_s")
        assertEquals(case["origin_verified"], replay.originVerified, "origin_verified")
    }

    // Runs every case of a group and reports all that fail, so one bad case does not hide the others.
    private fun eachCase(group: String, count: Int, check: (Map<String, Any?>) -> Unit) {
        val cases = vectors.getValue("cases").obj().list(group).map { it.obj() }
        assertEquals(count, cases.size, "$group cases in the file")
        val failures = cases.mapNotNull { case ->
            runCatching { check(case) }.exceptionOrNull()?.let { "$group/${case["name"]}: ${it.message}" }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private companion object {
        const val ARCHIVE = 0x0A0B0C0D
    }
}

private fun Any?.obj(): Map<String, Any?> = (this as Map<*, *>).entries.associate { it.key as String to it.value }

private fun Map<String, Any?>.list(key: String): List<Any?> = getValue(key) as List<Any?>

private fun Map<String, Any?>.str(key: String): String = getValue(key) as String

private fun Map<String, Any?>.bytes(key: String): ByteArray = str(key).decodeHex().toByteArray()

// Node numbers and packet ids are uint32 in the file and Int bit patterns in the SDK.
private fun Map<String, Any?>.u32(key: String): Int = (getValue(key) as Long).toInt()

private fun ByteArray.hex(): String = toByteString().hex()

/** The JSON subset the vector file uses: objects, arrays, unescaped strings, integers and booleans. */
private class JsonReader(private val text: String) {
    private var pos = 0

    fun readDocument(): Any? = readValue().also {
        skipWhitespace()
        check(pos == text.length) { "trailing content at $pos" }
    }

    private fun readValue(): Any? {
        skipWhitespace()
        return when (val c = text[pos]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> readLiteral("true", true)
            'f' -> readLiteral("false", false)
            else -> if (c == '-' || c.isDigit()) readInteger() else error("unexpected '$c' at $pos")
        }
    }

    private fun readObject(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        readSequence('}') {
            val key = readString()
            expect(':')
            check(out.put(key, readValue()) == null) { "duplicate key $key" }
        }
        return out
    }

    private fun readArray(): List<Any?> {
        val out = ArrayList<Any?>()
        readSequence(']') { out += readValue() }
        return out
    }

    private fun readSequence(close: Char, readItem: () -> Unit) {
        pos++
        skipWhitespace()
        if (text[pos] == close) {
            pos++
            return
        }
        while (true) {
            skipWhitespace()
            readItem()
            skipWhitespace()
            if (text[pos] == close) break
            expect(',')
        }
        pos++
    }

    private fun readString(): String {
        check(text[pos] == '"') { "expected a string at $pos" }
        val end = text.indexOf('"', pos + 1)
        check(end > pos) { "unterminated string at $pos" }
        val value = text.substring(pos + 1, end)
        check('\\' !in value) { "escapes are not supported, at $pos" }
        pos = end + 1
        return value
    }

    private fun readInteger(): Long {
        val start = pos
        if (text[pos] == '-') pos++
        while (pos < text.length && text[pos].isDigit()) pos++
        return text.substring(start, pos).toLong()
    }

    private fun readLiteral(word: String, value: Boolean): Boolean {
        check(text.startsWith(word, pos)) { "expected $word at $pos" }
        pos += word.length
        return value
    }

    private fun expect(c: Char) {
        skipWhitespace()
        check(text[pos] == c) { "expected '$c' at $pos" }
        pos++
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }
}
