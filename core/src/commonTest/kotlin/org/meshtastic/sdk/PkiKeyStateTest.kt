/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.ClientNotification
import org.meshtastic.proto.Data
import org.meshtastic.proto.DuplicatedPublicKey
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.KeyVerificationFinal
import org.meshtastic.proto.KeyVerificationNumberInform
import org.meshtastic.proto.KeyVerificationNumberRequest
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.User
import org.meshtastic.sdk.testing.FakeRadioTransport
import org.meshtastic.sdk.testing.toFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PkiKeyStateTest {

    private val peer = 0x22222222
    private val keyA = ByteArray(32) { 1 }.toByteString()
    private val keyB = ByteArray(32) { 2 }.toByteString()

    @Test
    fun pkiDmCarriesTheStoredRecipientKey() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()
        transport.injectPacket(nodeInfoPacket(peer, user("Peer", keyA)))
        runCurrent()

        val handle = client.sendDirectMessageEncrypted(NodeId(peer), "secret")
        runCurrent()

        val sent = transport.outboundPackets().single { it.decoded?.portnum == PortNum.TEXT_MESSAGE_APP }
        assertTrue(sent.pki_encrypted)
        assertEquals(0, sent.channel)
        assertEquals(keyA, sent.public_key)
        assertNotNull(handle)
        client.disconnect()
    }

    @Test
    fun pkiDmToUnknownKeyLeavesPublicKeyEmpty() = runTest {
        val (transport, client) = connectedClient()
        client.connect()
        runCurrent()

        client.sendDirectMessageEncrypted(NodeId(peer), "secret")
        runCurrent()

        val sent = transport.outboundPackets().single { it.decoded?.portnum == PortNum.TEXT_MESSAGE_APP }
        assertTrue(sent.pki_encrypted)
        assertEquals(0, sent.public_key.size)
        client.disconnect()
    }

    @Test
    fun mismatchedNodeInfoEmitsOneEventAndKeepsStoredUser() = runTest {
        val (transport, client) = connectedClient()
        val events = collectEvents(client)
        client.connect()
        runCurrent()
        transport.injectPacket(nodeInfoPacket(peer, user("Peer", keyA)))
        runCurrent()

        repeat(3) {
            transport.injectPacket(nodeInfoPacket(peer, user("Impostor", keyB)))
            runCurrent()
        }

        val warnings = events.filterIsInstance<MeshEvent.SecurityWarning.PublicKeyMismatch>()
        assertEquals(1, warnings.size)
        assertEquals(NodeId(peer), warnings.single().node)
        assertEquals(keyB, warnings.single().offeredKey)
        val stored = client.nodeSnapshot()[NodeId(peer)]?.user
        assertEquals("Peer", stored?.long_name)
        assertEquals(keyA, stored?.public_key)
        client.disconnect()
    }

    @Test
    fun anotherOfferedKeyEmitsAnotherEvent() = runTest {
        val (transport, client) = connectedClient()
        val events = collectEvents(client)
        client.connect()
        runCurrent()
        transport.injectPacket(nodeInfoPacket(peer, user("Peer", keyA)))
        runCurrent()

        transport.injectPacket(nodeInfoPacket(peer, user("X", keyB)))
        transport.injectPacket(nodeInfoPacket(peer, user("X", ByteArray(32) { 3 }.toByteString())))
        runCurrent()

        assertEquals(2, events.filterIsInstance<MeshEvent.SecurityWarning.PublicKeyMismatch>().size)
        client.disconnect()
    }

    @Test
    fun matchingKeyEmitsNoMismatch() = runTest {
        val (transport, client) = connectedClient()
        val events = collectEvents(client)
        client.connect()
        runCurrent()
        transport.injectPacket(nodeInfoPacket(peer, user("Peer", keyA)))
        transport.injectPacket(nodeInfoPacket(peer, user("Renamed", keyA)))
        runCurrent()

        assertTrue(events.none { it is MeshEvent.SecurityWarning.PublicKeyMismatch })
        client.disconnect()
    }

    @Test
    fun numberRequestMapsToEnterSecurityNumber() = runTest {
        val prompt = notifyKeyVerification { wb ->
            wb.key_verification_number_request = KeyVerificationNumberRequest.Builder().also { b ->
                b.nonce = 42L
                b.remote_longname = "Bob"
            }.build()
        }
        assertEquals(KeyVerificationPrompt.EnterSecurityNumber(nonce = 42L, remoteLongName = "Bob"), prompt)
    }

    @Test
    fun numberInformMapsToShowSecurityNumber() = runTest {
        val prompt = notifyKeyVerification { wb ->
            wb.key_verification_number_inform = KeyVerificationNumberInform.Builder().also { b ->
                b.nonce = 7L
                b.remote_longname = "Bob"
                b.security_number = 123456
            }.build()
        }
        assertEquals(
            KeyVerificationPrompt.ShowSecurityNumber(nonce = 7L, remoteLongName = "Bob", securityNumber = 123456),
            prompt,
        )
    }

    @Test
    fun finalMapsToConfirmVerification() = runTest {
        val prompt = notifyKeyVerification { wb ->
            wb.key_verification_final = KeyVerificationFinal.Builder().also { b ->
                b.nonce = 9L
                b.remote_longname = "Bob"
                b.isSender = true
                b.verification_characters = "ab12"
            }.build()
        }
        assertEquals(
            KeyVerificationPrompt.ConfirmVerification(
                nonce = 9L,
                remoteLongName = "Bob",
                isSender = true,
                verificationCharacters = "ab12",
            ),
            prompt,
        )
    }

    @Test
    fun plainNotificationEmitsNoKeyVerification() = runTest {
        val (transport, client) = connectedClient()
        val events = collectEvents(client)
        client.connect()
        runCurrent()

        transport.injectFrame(
            FromRadio.Builder().also { wb ->
                wb.clientNotification = ClientNotification.Builder().also { cn -> cn.message = "hello" }.build()
            }.build().toFrame(),
        )
        runCurrent()

        assertTrue(events.none { it is MeshEvent.KeyVerification })
        assertTrue(events.any { it is MeshEvent.Notification })
        client.disconnect()
    }

    @Test
    fun duplicatedPublicKeyEmitsNoKeyVerification() = runTest {
        val (transport, client) = connectedClient()
        val events = collectEvents(client)
        client.connect()
        runCurrent()

        transport.injectFrame(
            FromRadio.Builder().also { wb ->
                wb.clientNotification = ClientNotification.Builder().also { cn ->
                    cn.duplicated_public_key = DuplicatedPublicKey.Builder().build()
                }.build()
            }.build().toFrame(),
        )
        runCurrent()

        assertTrue(events.none { it is MeshEvent.KeyVerification })
        assertTrue(events.any { it is MeshEvent.SecurityWarning.DuplicatedPublicKey })
        client.disconnect()
    }

    private suspend fun TestScope.notifyKeyVerification(
        fill: (ClientNotification.Builder) -> Unit,
    ): KeyVerificationPrompt? {
        val (transport, client) = connectedClient()
        val events = collectEvents(client)
        client.connect()
        runCurrent()
        transport.injectFrame(
            FromRadio.Builder().also { wb ->
                wb.clientNotification = ClientNotification.Builder().also { cn -> fill(cn) }.build()
            }.build().toFrame(),
        )
        runCurrent()
        val prompts = events.filterIsInstance<MeshEvent.KeyVerification>()
        assertTrue(events.any { it is MeshEvent.Notification }, "raw notification still emitted")
        client.disconnect()
        return prompts.singleOrNull()?.prompt
    }

    private fun user(name: String, key: ByteString) = User.Builder().also { wb ->
        wb.long_name = name
        wb.short_name = name.take(4)
        wb.public_key = key
    }.build()

    private fun nodeInfoPacket(from: Int, user: User) = MeshPacket.Builder().also { wb ->
        wb.from = from
        wb.to = 0
        wb.rx_time = 1_700_000_100
        wb.decoded = Data.Builder().also { d ->
            d.portnum = PortNum.NODEINFO_APP
            d.payload = User.ADAPTER.encode(user).toByteString()
        }.build()
    }.build()

    private fun TestScope.collectEvents(client: RadioClient): MutableList<MeshEvent> {
        val out = mutableListOf<MeshEvent>()
        backgroundScope.launch { client.events.collect { out += it } }
        return out
    }

    private fun TestScope.connectedClient(): Pair<FakeRadioTransport, RadioClient> {
        val transport = FakeRadioTransport(
            identity = TransportIdentity("fake:pki-key-state"),
            autoHandshake = true,
            nodeNum = 0x11111111,
        )
        val client = RadioClient.Builder()
            .transport(transport)
            .storage(InMemoryStorageProvider())
            .coroutineContext(backgroundScope.coroutineContext)
            .autoSyncTimeOnConnect(false)
            .build()
        return transport to client
    }
}
