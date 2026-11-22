/*
 * Meshtastic — open source mesh radio
 * Copyright © 2024-2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProtoPathsTest {
    private val lora = Config.LoRaConfig.Builder().also { it.hop_limit = 3 }.build()

    @Test
    fun jsonUsesSnakeCaseKeysAndWritesDefaults() {
        val json = ProtoPaths.toJson(lora)
        assertEquals(JsonPrimitive(3), json["hop_limit"])
        assertTrue("tx_enabled" in json || "use_preset" in json, "identity values are written: $json")
    }

    @Test
    fun mergeSetsAScalarAndKeepsTheRest() {
        val merged = ProtoPaths.merge(
            Config.LoRaConfig::class.java,
            lora,
            mapOf("tx_power" to JsonPrimitive(20), "hop_limit" to JsonPrimitive(5)),
        )
        assertEquals(5, merged.hop_limit)
        assertEquals(20, merged.tx_power)
    }

    @Test
    fun mergeReachesNestedMessages() {
        val base = ModuleConfig.MQTTConfig.Builder().build()
        val merged = ProtoPaths.merge(
            ModuleConfig.MQTTConfig::class.java,
            base,
            mapOf("map_report_settings.publish_interval_secs" to JsonPrimitive(3600)),
        )
        assertEquals(3600, merged.map_report_settings?.publish_interval_secs)
    }

    @Test
    fun flattenKeepsEmptyObjectsSoTheirPathsAreValidated() {
        val flat = ProtoPaths.flatten(
            Json.parseToJsonElement("""{"lora":{"hop_limit":5},"mqtt":{"typo":{}}}""").jsonObject,
        )
        assertEquals(setOf("lora.hop_limit", "mqtt.typo"), flat.keys)
    }

    @Test
    fun mergeKeepsUnknownFieldsOfTheBase() {
        val unknown = "08ff01".let { it.decodeHex() }
        val withUnknown = lora.newBuilder().addUnknownFields(unknown).build()
        val merged = ProtoPaths.merge(
            Config.LoRaConfig::class.java,
            withUnknown,
            mapOf("hop_limit" to JsonPrimitive(6)),
        )
        assertEquals(6, merged.hop_limit)
        assertEquals(unknown, merged.unknownFields)
    }

    @Test
    fun mergeAcceptsCamelCasePaths() {
        val merged = ProtoPaths.merge(Config.LoRaConfig::class.java, lora, mapOf("hopLimit" to JsonPrimitive(6)))
        assertEquals(6, merged.hop_limit)
    }

    @Test
    fun enumNamesAndBytesRoundTrip() {
        val device = ProtoPaths.merge(
            Config.DeviceConfig::class.java,
            Config.DeviceConfig.Builder().build(),
            mapOf("role" to JsonPrimitive("ROUTER")),
        )
        assertEquals(Config.DeviceConfig.Role.ROUTER, device.role)
        val security = Config.SecurityConfig.Builder().also {
            it.private_key = ByteArray(32) { 7 }.toByteString()
        }.build()
        assertEquals(security, ProtoPaths.fromJson(Config.SecurityConfig::class.java, ProtoPaths.toJson(security)))
    }

    @Test
    fun unknownFieldIsAToolError() {
        val ex = assertFailsWith<ToolFailure> {
            ProtoPaths.merge(Config.LoRaConfig::class.java, lora, mapOf("hop_limt" to JsonPrimitive(1)))
        }
        assertTrue("hop_limt" in ex.message.orEmpty())
    }

    @Test
    fun fromJsonRejectsAMisspelledNestedKey() {
        val json = Json.parseToJsonElement(
            """{"mqtt":{"enabled":true,"map_report_settings":{"publish_interval_sec":60}}}""",
        ).jsonObject
        val ex = assertFailsWith<ToolFailure> { ProtoPaths.fromJson(ModuleConfig::class.java, json) }
        assertTrue("publish_interval_sec" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun fromJsonAcceptsCamelAndSnakeKeys() {
        val json = Json.parseToJsonElement("""{"hopLimit":4,"tx_power":9}""").jsonObject
        val parsed = ProtoPaths.fromJson(Config.LoRaConfig::class.java, json)
        assertEquals(4, parsed.hop_limit)
        assertEquals(9, parsed.tx_power)
    }

    @Test
    fun subFieldOfAScalarIsAToolError() {
        assertFailsWith<ToolFailure> {
            ProtoPaths.merge(Config.LoRaConfig::class.java, lora, mapOf("hop_limit.x" to JsonPrimitive(1)))
        }
    }

    @Test
    fun wrongValueTypeIsAToolError() {
        assertFailsWith<ToolFailure> {
            ProtoPaths.merge(Config.LoRaConfig::class.java, lora, mapOf("hop_limit" to JsonPrimitive("many")))
        }
    }

    @Test
    fun flattenTurnsNestedObjectsIntoDottedPaths() {
        val flat = ProtoPaths.flatten(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"lora":{"hop_limit":4},"mqtt.enabled":true}""",
            ).jsonObject,
        )
        assertEquals(setOf("lora.hop_limit", "mqtt.enabled"), flat.keys)
    }
}
