package com.tima.client.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tima.client.database.TimaDatabase
import com.tima.client.network.PrivateDocumentEnvelopeDto
import com.tima.client.network.PrivateMessageHistoryDto
import com.tima.client.network.PrivateMessageWriteCodec
import com.tima.client.network.PrivateMessageWriteDto
import com.tima.client.network.PrivateMetadataDto
import com.tima.client.network.ReservedMessageIds
import com.tima.client.network.SecureStorage
import com.tima.client.network.TimaTransportException
import com.tima.client.network.WrappedKeyDto
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class DurableMessagingOutboxTest {
    @Test
    fun retryAndExactRequestSurviveCloseReopen() = runBlocking {
        val path = Files.createTempFile("tima-durable-send", ".sqlite")
        val storage = TestStorage()
        val expected = PrivateMessageWriteCodec.encode(write())
        open(path, create = true).use { driver ->
            val store = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            store.enqueueSend(send(expected), bubble())
            check(store.claimOutboxSend(LOCAL_ID))
            store.scheduleOutboxRetry(LOCAL_ID, 5)
        }

        val remote = TestRemote()
        open(path).use { driver ->
            val store = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            coordinator(store, remote, now = 5).retrySend(LOCAL_ID)
            assertNull(store.outboxSend(LOCAL_ID))
            assertEquals(MessageDeliveryState.SENT, store.messages(CHAT_ID).single().delivery)
        }

        assertEquals(listOf(SEND_KEY), remote.keys)
        assertContentEquals(expected, remote.envelopes.single())
        Files.deleteIfExists(path)
        Unit
    }

    @Test
    fun restorationRecoversStaleSendingAndDrainsIt() = runBlocking {
        val path = Files.createTempFile("tima-stale-send", ".sqlite")
        val storage = TestStorage()
        open(path, create = true).use { driver ->
            val store = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            store.enqueueSend(send(PrivateMessageWriteCodec.encode(write())), bubble())
            check(store.claimOutboxSend(LOCAL_ID))
        }

        val remote = TestRemote()
        open(path).use { driver ->
            val store = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            coordinator(store, remote, now = 10).loadSession()
            assertNull(store.outboxSend(LOCAL_ID))
            assertEquals(MessageDeliveryState.SENT, store.messages(CHAT_ID).single().delivery)
        }
        assertEquals(1, remote.envelopes.size)
        Files.deleteIfExists(path)
        Unit
    }

    @Test
    fun nonRetryableFailureIsTerminalButManualRetryRemainsPossible() = runBlocking {
        val path = Files.createTempFile("tima-terminal-send", ".sqlite")
        val storage = TestStorage()
        open(path, create = true).use { driver ->
            val store = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            store.enqueueSend(send(PrivateMessageWriteCodec.encode(write())), bubble())
            val remote = TestRemote(
                failure = TimaTransportException(400, "INVALID_ENVELOPE", retryable = false),
            )
            coordinator(store, remote, now = 0).loadSession()
            assertEquals("terminal", store.outboxSend(LOCAL_ID)?.state)
            assertEquals(MessageDeliveryState.ERROR, store.messages(CHAT_ID).single().delivery)
            assertEquals(1, remote.envelopes.size)
        }

        open(path).use { driver ->
            val store = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            val remote = TestRemote()
            coordinator(store, remote, now = 20).retrySend(LOCAL_ID)
            assertNull(store.outboxSend(LOCAL_ID))
            assertEquals(1, remote.envelopes.size)
        }
        Files.deleteIfExists(path)
        Unit
    }

    @Test
    fun malformedPathAndPayloadFailClosedWithoutNetworkSend() = runBlocking {
        val path = Files.createTempFile("tima-malformed-send", ".sqlite")
        val storage = TestStorage()
        open(path, create = true).use { driver ->
            val store = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            store.enqueueSend(
                send(PrivateMessageWriteCodec.encode(write())).copy(requestPath = "https://evil.test/messages"),
                bubble(),
            )
            store.enqueueSend(
                send(byteArrayOf(0, 1, 2)).copy(
                    localId = "local-malformed-payload",
                    idempotencyKey = "00000000-0000-4000-8000-000000000092",
                ),
                bubble().copy(localId = "local-malformed-payload"),
            )
            val remote = TestRemote()
            coordinator(store, remote, now = 0).loadSession()

            assertEquals("terminal", store.outboxSend(LOCAL_ID)?.state)
            assertEquals("terminal", store.outboxSend("local-malformed-payload")?.state)
            assertEquals(0, remote.envelopes.size)
        }
        Files.deleteIfExists(path)
        Unit
    }

    @Test
    fun cancellationReturnsSendingRowToRetryWithoutLosingIt() = runBlocking {
        val path = Files.createTempFile("tima-cancelled-send", ".sqlite")
        val storage = TestStorage()
        open(path, create = true).use { driver ->
            val store = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            store.enqueueSend(send(PrivateMessageWriteCodec.encode(write())), bubble())
            val remote = TestRemote(failure = CancellationException("scope stopped"))

            assertFailsWith<CancellationException> {
                coordinator(store, remote, now = 12).loadSession()
            }

            val retained = requireNotNull(store.outboxSend(LOCAL_ID))
            assertEquals("retry", retained.state)
            assertEquals(0, retained.retryCount)
            assertEquals(12, retained.nextAttemptEpochMillis)
        }
        Files.deleteIfExists(path)
        Unit
    }

    @Test
    fun signedOutRestorationDoesNotDrainOutbox() = runBlocking {
        val path = Files.createTempFile("tima-signed-out-send", ".sqlite")
        val storage = TestStorage()
        open(path, create = true).use { driver ->
            val store = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            store.enqueueSend(send(PrivateMessageWriteCodec.encode(write())), bubble())
            val remote = TestRemote()
            val signedOut = object : SessionRepository {
                override suspend fun current(): ClientSession? = null
                override suspend fun save(session: ClientSession) = Unit
                override suspend fun clear() = Unit
            }

            coordinator(store, remote, now = 0, sessions = signedOut).loadSession()

            assertEquals("pending", store.outboxSend(LOCAL_ID)?.state)
            assertEquals(0, remote.envelopes.size)
        }
        Files.deleteIfExists(path)
        Unit
    }

    @Test
    fun offlineSessionRestorationCanSkipOutboxReplay() = runBlocking {
        val path = Files.createTempFile("tima-offline-restore-send", ".sqlite")
        val storage = TestStorage()
        open(path, create = true).use { driver ->
            val store = EncryptedSqlDelightMessagingCache(TimaDatabase(driver), storage)
            store.enqueueSend(send(PrivateMessageWriteCodec.encode(write())), bubble())
            val remote = TestRemote()

            coordinator(store, remote, now = 0).loadSession(drainOutbox = false)

            assertEquals("pending", store.outboxSend(LOCAL_ID)?.state)
            assertEquals(0, remote.envelopes.size)
        }
        Files.deleteIfExists(path)
        Unit
    }

    private fun coordinator(
        store: MessagingCache,
        remote: TestRemote,
        now: Long,
        sessions: SessionRepository = TestSessions,
    ) = Phase1MessagingCoordinator(
        sessions = sessions,
        remote = remote,
        crypto = object : PrivateMessageCrypto {
            override suspend fun encrypt(
                chatId: String,
                text: String,
                reservation: ReservedMessageIds,
                parentRevisionId: String?,
                revisionNumber: ULong,
            ): PrivateMessageWriteDto = error("encryption must not run while draining")

            override suspend fun decrypt(value: PrivateMessageHistoryDto): DecryptedMessage =
                error("decryption not expected")
        },
        cache = store,
        ids = IdGenerator { error("ID generation not expected") },
        nowEpochMillis = { now },
    )

    private fun send(envelope: ByteArray) = DurableSend(
        localId = LOCAL_ID,
        idempotencyKey = SEND_KEY,
        chatId = CHAT_ID,
        requestPath = "/v1/chats/$CHAT_ID/messages",
        envelope = envelope,
        nextAttemptEpochMillis = 0,
        createdAtEpochMillis = 0,
    )

    private fun bubble() = MessageBubble(
        localId = LOCAL_ID,
        chatId = CHAT_ID,
        messageId = 7uL,
        revisionId = REVISION_ID,
        revisionNumber = 1uL,
        senderUserId = USER_ID,
        text = "known private plaintext",
        createdAt = null,
        delivery = MessageDeliveryState.PENDING,
    )

    private fun open(path: Path, create: Boolean = false): JdbcSqliteDriver =
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").also {
            if (create) TimaDatabase.Schema.create(it)
        }

    private class TestRemote(
        var failure: Exception? = null,
    ) : MessagingRemoteDataSource {
        val envelopes = mutableListOf<ByteArray>()
        val keys = mutableListOf<String>()

        override suspend fun sendSerialized(
            chatId: String,
            envelope: ByteArray,
            idempotencyKey: String,
        ) {
            envelopes += envelope.copyOf()
            keys += idempotencyKey
            failure?.let { throw it }
        }

        override suspend fun listChats(limit: Int) = emptyList<RemoteChat>()
        override suspend fun createChat(peerUserId: String, idempotencyKey: String) =
            error("not expected")

        override suspend fun history(chatId: String, cursor: String?, limit: Int) =
            RemoteHistoryPage(emptyList(), null)

        override suspend fun reserveMessage(chatId: String, idempotencyKey: String) =
            error("not expected")

        override suspend fun send(
            chatId: String,
            value: PrivateMessageWriteDto,
            idempotencyKey: String,
        ) = error("serialized send expected")

        override suspend fun edit(
            chatId: String,
            messageId: ULong,
            value: PrivateMessageWriteDto,
            idempotencyKey: String,
        ) = error("not expected")

        override suspend fun markRead(chatId: String, messageId: ULong) = error("not expected")
        override suspend fun delete(chatId: String, messageId: ULong) = error("not expected")
    }
}

private object TestSessions : SessionRepository {
    override suspend fun current() = ClientSession("token", USER_ID, DEVICE_ID)
    override suspend fun save(session: ClientSession) = Unit
    override suspend fun clear() = Unit
}

private class TestStorage : SecureStorage {
    private val values = mutableMapOf<String, ByteArray>()
    override suspend fun read(key: String) = values[key]?.copyOf()
    override suspend fun write(key: String, value: ByteArray) {
        values[key] = value.copyOf()
    }

    override suspend fun delete(key: String) {
        values.remove(key)?.fill(0)
    }
}

private fun write() = PrivateMessageWriteDto(
    sender_id = USER_ID,
    message_id = "7",
    revision_id = REVISION_ID,
    message_key_id = 0,
    document = PrivateDocumentEnvelopeDto(
        encrypted_nodes = listOf("ciphertext"),
        metadata = PrivateMetadataDto("private", 2u, 1uL),
        protocol_version = 2,
        presence_bitmap = 1u,
        key_commitment = "commitment",
        escrow_blob = "escrow-ciphertext",
        ratchet_envelope = null,
        signature = "signature",
    ),
    wrapped_keys = listOf(
        WrappedKeyDto(DEVICE_ID, "wrapped-ciphertext", 2, "commitment"),
    ),
)

private const val CHAT_ID = "00000000-0000-4000-8000-000000000081"
private const val USER_ID = "00000000-0000-4000-8000-000000000082"
private const val DEVICE_ID = "00000000-0000-4000-8000-000000000083"
private const val REVISION_ID = "00000000-0000-4000-8000-000000000084"
private const val LOCAL_ID = "local-durable"
private const val SEND_KEY = "00000000-0000-4000-8000-000000000091"
