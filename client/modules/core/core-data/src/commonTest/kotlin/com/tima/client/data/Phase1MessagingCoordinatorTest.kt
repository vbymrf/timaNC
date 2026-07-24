package com.tima.client.data

import com.tima.client.crypto.HybridKodiumEscrowBlobBuilder
import com.tima.client.crypto.MessengerCrypto
import com.tima.client.network.AuthContext
import com.tima.client.network.PrivateDocumentEnvelopeDto
import com.tima.client.network.PrivateMessageHistoryDto
import com.tima.client.network.PrivateMessageWriteDto
import com.tima.client.network.PrivateMetadataDto
import com.tima.client.network.ReservedMessageIds
import com.tima.client.network.SecureStorage
import com.tima.client.network.TimaHttpTransport
import com.tima.client.network.WrappedKeyDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Phase1MessagingCoordinatorTest {
    @Test
    fun chatListRepresentsEmptyOfflineAndErrorStates() = runBlocking {
        val fixture = fixture(online = false)
        fixture.coordinator.refreshChats()
        assertEquals(UiLoadState.Empty(offline = true), fixture.coordinator.state.value.chats)

        fixture.online = true
        fixture.remote.chats = listOf(RemoteChat(TEST_CHAT, TEST_PEER, "Peer", null, 0))
        fixture.coordinator.refreshChats()
        assertIs<UiLoadState.Content<List<ChatPreview>>>(fixture.coordinator.state.value.chats)

        fixture.remote.failure = IllegalStateException("network")
        fixture.coordinator.refreshChats()
        val error = assertIs<UiLoadState.Error<List<ChatPreview>>>(fixture.coordinator.state.value.chats)
        assertEquals("CLIENT_OPERATION_FAILED", error.code)
        assertEquals(1, error.cached?.size)
    }

    @Test
    fun sendTransitionsPendingToSent() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.openThread(TEST_CHAT)
        val localId = fixture.coordinator.sendText(TEST_CHAT, "private hello")

        val sent = assertIs<SendUiState.Sent>(fixture.coordinator.state.value.send)
        assertEquals(localId, sent.localId)
        assertEquals(7uL, sent.messageId)
        val bubble = fixture.thread().single()
        assertEquals(MessageDeliveryState.SENT, bubble.delivery)
        assertEquals("private hello", bubble.text)
        assertEquals(1, fixture.remote.sendCalls)
    }

    @Test
    fun failedSendRetriesSameEncryptedRequestAndIdempotency() = runBlocking {
        val fixture = fixture()
        fixture.remote.failNextSend = true
        fixture.coordinator.openThread(TEST_CHAT)
        val localId = fixture.coordinator.sendText(TEST_CHAT, "retry secret")

        assertIs<SendUiState.Error>(fixture.coordinator.state.value.send)
        assertEquals(MessageDeliveryState.ERROR, fixture.thread().single().delivery)
        val firstEnvelope = fixture.remote.lastWrite
        val firstKey = fixture.remote.lastSendKey

        fixture.coordinator.retrySend(localId)

        assertIs<SendUiState.Sent>(fixture.coordinator.state.value.send)
        assertTrue(firstEnvelope === fixture.remote.lastWrite)
        assertEquals(firstKey, fixture.remote.lastSendKey)
        assertEquals(1, fixture.crypto.encryptCalls)
        assertEquals(2, fixture.remote.sendCalls)
        assertFalse(fixture.remote.lastWrite.toString().contains("retry secret"))
    }

    @Test
    fun clearingSessionRemovesDecryptedStateAndPendingRetries() = runBlocking {
        val fixture = fixture()
        fixture.remote.failNextSend = true
        fixture.remote.chats = listOf(RemoteChat(TEST_CHAT, TEST_PEER, "Peer", null, 0))
        fixture.coordinator.refreshChats()
        fixture.coordinator.openThread(TEST_CHAT)
        val localId = fixture.coordinator.sendText(TEST_CHAT, "clear this plaintext")

        fixture.coordinator.clearSessionState()

        assertEquals(SessionUiState.SignedOut, fixture.coordinator.state.value.session)
        assertEquals(UiLoadState.Loading, fixture.coordinator.state.value.chats)
        assertEquals(UiLoadState.Empty(), fixture.coordinator.state.value.thread)
        assertEquals(SendUiState.Idle, fixture.coordinator.state.value.send)
        assertTrue(runCatching { fixture.coordinator.retrySend(localId) }.isFailure)
    }

    @Test
    fun restGapFillRefreshesOnlyRequestedHistory() = runBlocking {
        val fixture = fixture()
        fixture.remote.historyByChat[TEST_CHAT] = listOf(history(9uL, TEST_PEER, "cipher"))
        fixture.coordinator.openThread(TEST_CHAT)
        fixture.remote.historyCalls.clear()

        fixture.coordinator.catchUp(setOf(TEST_CHAT))

        assertEquals(listOf(TEST_CHAT), fixture.remote.historyCalls)
        assertEquals("decrypted:cipher", fixture.thread().single().text)
    }

    @Test
    fun editAndDeleteUpdateThreadState() = runBlocking {
        val fixture = fixture()
        fixture.remote.historyByChat[TEST_CHAT] = listOf(history(5uL, TEST_USER, "before"))
        fixture.coordinator.openThread(TEST_CHAT)

        fixture.coordinator.editText(TEST_CHAT, 5uL, "after")
        assertEquals("after", fixture.thread().single().text)
        assertTrue(fixture.thread().single().edited)
        assertEquals(1, fixture.remote.editCalls)
        assertEquals(TEST_REVISION, fixture.crypto.lastParentRevisionId)
        assertEquals(2uL, fixture.crypto.lastRevisionNumber)

        fixture.coordinator.deleteMessage(TEST_CHAT, 5uL)
        assertTrue(fixture.thread().isEmpty())
        assertEquals(5uL, fixture.remote.lastDeleted)
    }

    @Test
    fun markReadMarksIncomingMessagesButNotOwnOutgoingMessages() = runBlocking {
        val fixture = fixture()
        fixture.remote.historyByChat[TEST_CHAT] = listOf(
            history(5uL, TEST_USER, "outgoing"),
            history(6uL, TEST_PEER, "incoming"),
        )
        fixture.coordinator.openThread(TEST_CHAT)

        fixture.coordinator.markRead(TEST_CHAT, 6uL)

        assertEquals(
            MessageDeliveryState.SENT,
            fixture.thread().single { it.messageId == 5uL }.delivery,
        )
        assertEquals(
            MessageDeliveryState.READ,
            fixture.thread().single { it.messageId == 6uL }.delivery,
        )
        assertEquals(6uL, fixture.remote.lastRead)
    }

    @Test
    fun secureSessionUsesOnlySecureStorageAndClearsBytes() = runBlocking {
        val storage = MemorySecureStorage()
        val repository = SecureStorageSessionRepository(storage)
        val session = ClientSession("token-secret", TEST_USER, TEST_DEVICE)
        repository.save(session)

        assertEquals(session, repository.current())
        assertFalse(storage.value!!.all { it == 0.toByte() })
        repository.clear()
        assertNull(repository.current())
    }

    @Test
    fun productionCryptoFailsClosedWhenTrustMaterialIsMissing() = runBlocking {
        val crypto = ProductionPrivateMessageCrypto(
            messengerCrypto = MessengerCrypto(HybridKodiumEscrowBlobBuilder()),
            sessions = object : SessionRepository {
                override suspend fun current() = null
                override suspend fun save(session: ClientSession) = Unit
                override suspend fun clear() = Unit
            },
            identities = DeviceIdentityProvider { null },
            recipientDirectory = RecipientDeviceDirectory { null },
            senderDirectory = SenderKeyDirectory { _, _ -> null },
            escrowConfigs = VerifiedEscrowConfigProvider { null },
        )

        val error = kotlin.runCatching {
            crypto.encrypt(TEST_CHAT, "must not escape", ReservedMessageIds(1uL, TEST_REVISION))
        }.exceptionOrNull()
        val missing = assertIs<MissingEncryptionConfigurationException>(error)
        assertEquals(
            setOf("authenticated session", "device identity", "recipient device directory", "verified escrow config"),
            missing.missing,
        )
    }

    private fun fixture(online: Boolean = true): Fixture {
        val sessions = object : SessionRepository {
            override suspend fun current() = ClientSession("token", TEST_USER, TEST_DEVICE)
            override suspend fun save(session: ClientSession) = Unit
            override suspend fun clear() = Unit
        }
        val remote = FakeRemote()
        val crypto = FakeCrypto()
        val values = ArrayDeque(
            listOf(
                "local-1", "reserve-key", "send-key", TEST_REVISION,
                "edit-key", "more-1", "more-2", "more-3",
            ),
        )
        var currentOnline = online
        val coordinator = Phase1MessagingCoordinator(
            sessions = sessions,
            remote = remote,
            crypto = crypto,
            cache = NonDurableInMemoryMessagingCache(),
            ids = IdGenerator { values.removeFirst() },
            connectivity = Connectivity { currentOnline },
        )
        return Fixture(coordinator, remote, crypto, currentOnline) {
            currentOnline = it
        }
    }

    private class Fixture(
        val coordinator: Phase1MessagingCoordinator,
        val remote: FakeRemote,
        val crypto: FakeCrypto,
        onlineValue: Boolean,
        private val setOnline: (Boolean) -> Unit,
    ) {
        var online = onlineValue
            set(value) {
                field = value
                setOnline(value)
            }

        fun thread(): List<MessageBubble> = when (val thread = coordinator.state.value.thread) {
            is UiLoadState.Content -> thread.value
            is UiLoadState.Empty -> emptyList()
            is UiLoadState.Error -> thread.cached.orEmpty()
            UiLoadState.Loading -> error("thread is loading")
        }
    }

    private class FakeCrypto : PrivateMessageCrypto {
        var encryptCalls = 0
        var lastParentRevisionId: String? = null
        var lastRevisionNumber: ULong? = null

        override suspend fun encrypt(
            chatId: String,
            text: String,
            reservation: ReservedMessageIds,
            parentRevisionId: String?,
            revisionNumber: ULong,
        ): PrivateMessageWriteDto {
            encryptCalls++
            lastParentRevisionId = parentRevisionId
            lastRevisionNumber = revisionNumber
            return encryptedWrite(reservation.messageId, reservation.revisionId)
        }

        override suspend fun decrypt(value: PrivateMessageHistoryDto) =
            DecryptedMessage("decrypted:${value.document.encrypted_nodes.single()}", value.document.metadata.revision_number)
    }

    private class FakeRemote : MessagingRemoteDataSource {
        var chats = emptyList<RemoteChat>()
        var failure: Throwable? = null
        val historyByChat = mutableMapOf<String, List<PrivateMessageHistoryDto>>()
        val historyCalls = mutableListOf<String>()
        var failNextSend = false
        var sendCalls = 0
        var editCalls = 0
        var lastWrite: PrivateMessageWriteDto? = null
        var lastSendKey: String? = null
        var lastRead: ULong? = null
        var lastDeleted: ULong? = null

        override suspend fun listChats(limit: Int): List<RemoteChat> {
            failure?.let { throw it }
            return chats
        }

        override suspend fun createChat(peerUserId: String, idempotencyKey: String) =
            RemoteChat(TEST_CHAT, peerUserId, "Peer", null, 0)

        override suspend fun history(chatId: String, cursor: String?, limit: Int): RemoteHistoryPage {
            historyCalls += chatId
            return RemoteHistoryPage(historyByChat[chatId].orEmpty(), null)
        }

        override suspend fun reserveMessage(chatId: String, idempotencyKey: String) =
            ReservedMessageIds(7uL, TEST_REVISION)

        override suspend fun send(
            chatId: String,
            value: PrivateMessageWriteDto,
            idempotencyKey: String,
        ) {
            sendCalls++
            lastWrite = value
            lastSendKey = idempotencyKey
            if (failNextSend) {
                failNextSend = false
                throw IllegalStateException("temporary")
            }
        }

        override suspend fun edit(
            chatId: String,
            messageId: ULong,
            value: PrivateMessageWriteDto,
            idempotencyKey: String,
        ) {
            editCalls++
        }

        override suspend fun markRead(chatId: String, messageId: ULong) {
            lastRead = messageId
        }

        override suspend fun delete(chatId: String, messageId: ULong) {
            lastDeleted = messageId
        }
    }

    private class MemorySecureStorage : SecureStorage {
        var value: ByteArray? = null
        override suspend fun read(key: String) = value?.copyOf()
        override suspend fun write(key: String, value: ByteArray) {
            this.value = value.copyOf()
        }
        override suspend fun delete(key: String) {
            value?.fill(0)
            value = null
        }
    }
}

class TimaMessagingRemotePrivacyTest {
    @Test
    fun textSendTransportBodyContainsCiphertextButNoPlaintext() = runBlocking {
        var body = ""
        val engine = MockEngine { request ->
            body = (request.body as TextContent).text
            respond("{}", HttpStatusCode.Created)
        }
        val transport = TimaHttpTransport(
            HttpClient(engine),
            "https://api.example.test",
            { AuthContext("token", TEST_DEVICE) },
        )
        val remote = TimaMessagingRemoteDataSource(transport)

        remote.send(
            TEST_CHAT,
            encryptedWrite(1uL, TEST_REVISION),
            "00000000-0000-4000-8000-000000000006",
        )

        assertFalse(body.contains("plaintext must stay local"))
        assertTrue(body.contains("ciphertext-only"))
        assertTrue(body.contains("escrow-ciphertext"))
    }
}

private fun encryptedWrite(messageId: ULong, revisionId: String) = PrivateMessageWriteDto(
    sender_id = "00000000-0000-4000-8000-000000000002",
    message_id = messageId.toString(),
    revision_id = revisionId,
    message_key_id = 0,
    document = PrivateDocumentEnvelopeDto(
        encrypted_nodes = listOf("ciphertext-only"),
        metadata = PrivateMetadataDto("private", 2u, 1uL),
        protocol_version = 2,
        presence_bitmap = 1u,
        key_commitment = "commitment",
        escrow_blob = "escrow-ciphertext",
        ratchet_envelope = null,
        signature = "signature",
    ),
    wrapped_keys = listOf(
        WrappedKeyDto(
            "00000000-0000-4000-8000-000000000004",
            "wrapped-ciphertext",
            2,
            "commitment",
        ),
    ),
)

private fun history(messageId: ULong, sender: String, encryptedText: String) =
    PrivateMessageHistoryDto(
        id = messageId.toString(),
        conversation_id = "00000000-0000-4000-8000-000000000001",
        sender_id = sender,
        sender_device_id = "00000000-0000-4000-8000-000000000004",
        current_revision_id = "00000000-0000-4000-8000-000000000005",
        message_key_id = 0,
        parent_revision_id = null,
        created_at = "2026-07-24T00:00:00Z",
        document = PrivateDocumentEnvelopeDto(
            encrypted_nodes = listOf(encryptedText),
            metadata = PrivateMetadataDto("private", 2u, 1uL),
            protocol_version = 2,
            presence_bitmap = 1u,
            key_commitment = "commitment",
            escrow_blob = "escrow",
            ratchet_envelope = null,
            signature = "signature",
        ),
        wrapped_keys = emptyList(),
        deleted_at = null,
    )

private const val TEST_CHAT = "00000000-0000-4000-8000-000000000001"
private const val TEST_USER = "00000000-0000-4000-8000-000000000002"
private const val TEST_PEER = "00000000-0000-4000-8000-000000000003"
private const val TEST_DEVICE = "00000000-0000-4000-8000-000000000004"
private const val TEST_REVISION = "00000000-0000-4000-8000-000000000005"
