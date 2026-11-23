/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk.internal.sfpp

import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Data
import org.meshtastic.proto.PortNum
import org.meshtastic.sdk.SfppHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SfppReplayClientTest {
    private val archive = 0x0A0A0A0A

    private fun primary(psk: ByteArray, aead: Boolean = false) = listOf(
        Channel.Builder().also { wb ->
            wb.index = 0
            wb.role = Channel.Role.PRIMARY
            wb.settings = ChannelSettings.Builder().also {
                it.psk = psk.toByteString()
                it.use_aead = aead
            }.build()
        }.build(),
    )

    private fun SfppReplayClient.feed(frame: SfppFrame, channels: List<Channel>) =
        onFrame(frame, archive, attested = false, channels = channels, nowMs = 0)

    private fun ctrFrame() = itemFrame(SfppVector.ctrDefaultHash, SfppVector.ctrDefault, heardAgoS = 90)

    @Test
    fun ctrReplayOnThePrimaryChannelIsRecovered() {
        val recovered = assertNotNull(SfppReplayClient().feed(ctrFrame(), primary(byteArrayOf(1))))
        assertEquals(SfppVector.TEXT, recovered.text)
        assertEquals(0, recovered.channelIndex)
    }

    @Test
    fun ccmReplayOnAnAeadChannelIsRecovered() {
        val ct = SfppVector.ccmDefault
        val hash = SfppHash.compute(ct, -1, SfppVector.FROM, SfppVector.ID)
        val recovered = SfppReplayClient().feed(itemFrame(hash, ct), primary(byteArrayOf(1), aead = true))
        assertEquals(SfppVector.TEXT, assertNotNull(recovered).text)
    }

    @Test
    fun replayThatDoesNotDecryptToTextIsDropped() {
        assertNull(SfppReplayClient().onFrame(ctrFrame(), archive, false, primary(byteArrayOf(2)), 0))
        assertNull(SfppReplayClient().onFrame(ctrFrame(), archive, false, primary(byteArrayOf(1), aead = true), 0))
    }

    @Test
    fun messageAlreadyHeldLiveIsNotRecoveredAgain() {
        val client = SfppReplayClient()
        client.markSeen(SfppVector.FROM, SfppVector.ID)
        assertNull(client.onFrame(ctrFrame(), archive, false, primary(byteArrayOf(1)), 0))
    }

    private fun SfppReplayClient.copy(nowMs: Long, heardAgo: Int, attested: Boolean, verified: Boolean) = onFrame(
        itemFrame(SfppVector.ctrDefaultHash, SfppVector.ctrDefault, heardAgoS = heardAgo, originVerified = verified),
        0x0B0B0B0B,
        attested,
        primary(byteArrayOf(1)),
        nowMs,
    )

    // Spec §6.2: place by the earliest receipt (now - heard_ago_s). Placed 910 s, 2 s of slack.
    @Test
    fun unattestedCopiesAreRecoveredAgainOnlyWhenPlacedEarlier() {
        val client = SfppReplayClient()
        assertEquals(910_000L, assertNotNull(client.copy(1_000_000, 90, attested = false, verified = true)).placedAtMs)
        // The same copy five minutes later carries 300 s more heard_ago_s: same placement.
        assertNull(client.copy(1_300_000, 390, attested = false, verified = true))
        assertNull(client.copy(1_302_000, 394, attested = false, verified = false))
        assertEquals(
            907_999L,
            assertNotNull(client.copy(1_301_999, 394, attested = false, verified = false)).placedAtMs,
        )
    }

    @Test
    fun attestedCopiesRaiseTrustAndAreNeverMovedByUnattestedOnes() {
        val client = SfppReplayClient()
        assertNotNull(client.copy(1_000_000, 900, attested = false, verified = true))
        // Attested but without origin_verified: time attested, sender not; its later placement wins.
        val timeOnly = assertNotNull(client.copy(1_000_000, 60, attested = true, verified = false))
        assertEquals(true, timeOnly.timeAttested)
        assertEquals(false, timeOnly.senderVerified)
        assertEquals(940_000L, timeOnly.placedAtMs)
        // An unattested copy claiming 136 years changes nothing.
        assertNull(client.copy(1_000_000, -1, attested = false, verified = true))
        val verified = assertNotNull(client.copy(1_100_000, 160, attested = true, verified = true))
        assertEquals(true, verified.senderVerified)
        assertEquals(940_000L, verified.placedAtMs)
        // Merged: a later copy with lower trust and the same placement is no change.
        assertNull(client.copy(1_200_000, 260, attested = true, verified = false))
    }

    @Test
    fun highBitIdsAndHeardAgoSurviveUnsigned() {
        val frame = itemFrame(
            SfppVector.highHash,
            SfppVector.highCtrDefault,
            from = SfppVector.HIGH_FROM,
            id = SfppVector.HIGH_ID,
            heardAgoS = -1,
        )
        val recovered = assertNotNull(SfppReplayClient().feed(frame, primary(byteArrayOf(1))))
        assertEquals(SfppVector.TEXT, recovered.text)
        assertEquals(0xFFFFFFFFL, recovered.replay.heardAgoS)
    }

    @Test
    fun replayAddressedToOneNodeIsDropped() {
        val key = ChannelCipher.expandKey(byteArrayOf(1))
        val ct = ChannelCipher.ctr(key, SfppVector.FROM, SfppVector.ID, SfppVector.data)
        val hash = SfppHash.compute(ct, 0x12345678, SfppVector.FROM, SfppVector.ID)
        assertNull(SfppReplayClient().feed(itemFrame(hash, ct, to = 0x12345678), primary(byteArrayOf(1))))
    }

    @Test
    fun onlyPrintableUtf8TextMessagesPass() {
        fun data(port: PortNum, payload: ByteArray) = Data.ADAPTER.encode(
            Data.Builder().also {
                it.portnum = port
                it.payload = payload.toByteString()
            }.build(),
        )
        val text = "line one\n\tline two 🛰".encodeUtf8().toByteArray()
        assertNotNull(SfppTextValidator.decodeText(data(PortNum.TEXT_MESSAGE_APP, text)))
        assertNull(SfppTextValidator.decodeText(data(PortNum.POSITION_APP, "hello".encodeToByteArray())))
        assertNull(SfppTextValidator.decodeText(data(PortNum.TEXT_MESSAGE_APP, ByteArray(0))))
        assertNull(SfppTextValidator.decodeText(data(PortNum.TEXT_MESSAGE_APP, byteArrayOf(0x68, 0x07, 0x69))))
        assertNull(SfppTextValidator.decodeText(data(PortNum.TEXT_MESSAGE_APP, byteArrayOf(0x68, 0xC3.toByte(), 0x28))))
        assertNull(SfppTextValidator.decodeText(byteArrayOf(0x0A, 0x7F)))
    }
}
