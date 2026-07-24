package com.tima.client.windows

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tima.client.crypto.HybridKodiumEscrowBlobBuilder
import com.tima.client.crypto.MessengerCrypto
import com.tima.client.data.ClientSession
import com.tima.client.data.EncryptedSqlDelightMessagingCache
import com.tima.client.data.IdGenerator
import com.tima.client.data.MessageBubble
import com.tima.client.data.Phase1MessagingCoordinator
import com.tima.client.data.ProductionPrivateMessageCrypto
import com.tima.client.data.SecureStorageSessionRepository
import com.tima.client.data.TimaMessagingRemoteDataSource
import com.tima.client.database.TimaDatabase
import com.tima.client.network.AuthContext
import com.tima.client.network.ForegroundRealtimeSync
import com.tima.client.network.NotificationWakeSignal
import com.tima.client.network.NotificationWakeSink
import com.tima.client.network.RealtimeReconnect
import com.tima.client.network.RestGapFill
import com.tima.client.network.TimaHttpTransport
import com.tima.client.network.TimaRealtimeTransport
import com.tima.client.network.WakeSource
import com.tima.client.sync.WakeToSyncCoordinator
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class WindowsPhase1Runtime(
    baseUrl: String,
    val developmentMode: Boolean,
    val storage: DpapiSecureStorage = DpapiSecureStorage(),
    databasePath: Path = defaultDatabasePath(),
) : AutoCloseable {
    private val sessions = SecureStorageSessionRepository(storage)
    private val identities = WindowsDeviceIdentityRepository(storage)

    private var auth: AuthContext? = null
    private val databaseDriver = openDatabase(databasePath)
    private val database = TimaDatabase(databaseDriver)
    private val httpClient = HttpClient(CIO)
    private val validatedBaseUrl = validBaseUrl(baseUrl, developmentMode)
    private val transport = TimaHttpTransport(httpClient, validatedBaseUrl, { auth })
    private val realtime = TimaRealtimeTransport(httpClient, validatedBaseUrl, { auth })
    private var wakeSink: NotificationWakeSink? = null
    private val trust = WindowsTrustMaterialProvider(transport, sessions, identities, developmentMode)
    val linking = WindowsLinkingClient(transport, storage, sessions)
    val messaging = Phase1MessagingCoordinator(
        sessions = sessions,
        remote = TimaMessagingRemoteDataSource(transport),
        crypto = ProductionPrivateMessageCrypto(
            messengerCrypto = MessengerCrypto(HybridKodiumEscrowBlobBuilder()),
            sessions = sessions,
            identities = identities,
            recipientDirectory = trust,
            senderDirectory = trust,
            escrowConfigs = trust,
        ),
        cache = EncryptedSqlDelightMessagingCache(database, storage),
        ids = IdGenerator { UUID.randomUUID().toString() },
    )
    val privateSendingEnabled: Boolean
        get() = developmentMode
    val trustSummary: String
        get() = if (developmentMode) {
            "Explicit development escrow trust is active; encrypted Path-B writes are enabled."
        } else {
            "Writes blocked: verified production escrow signing roots are not provisioned."
        }
    val deliverySummary =
        "No WNS is configured. Foreground delivery uses periodic authenticated REST catch-up every 60 seconds."

    init {
        installWakeCoordinator(messaging, RealtimeReconnect { })
    }

    fun installWakeCoordinator(
        gapFill: RestGapFill,
        reconnect: RealtimeReconnect,
    ): WakeToSyncCoordinator = WakeToSyncCoordinator(
        gapFill = gapFill,
        realtime = reconnect,
        nowMillis = System::currentTimeMillis,
    ).also { wakeSink = it }

    fun startPeriodicCatchUp(
        scope: CoroutineScope,
        intervalMillis: Long = 60_000,
    ): Job {
        require(intervalMillis >= 15_000) { "catch-up interval must be at least 15 seconds" }
        val coordinator = checkNotNull(wakeSink) { "wake coordinator is not installed" }
        return scope.launch {
            while (isActive) {
                try {
                    coordinator.wake(NotificationWakeSignal(WakeSource.PERIODIC_CATCH_UP))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A later periodic pass remains authoritative after transient failure.
                }
                delay(intervalMillis)
            }
        }
    }

    suspend fun runForegroundRealtime(
        subscriptionFrame: ByteArray,
        consumeFrame: suspend (ByteArray) -> Unit,
    ) {
        val coordinator = checkNotNull(wakeSink) { "wake coordinator is not installed" }
        ForegroundRealtimeSync(realtime, coordinator).run(subscriptionFrame, consumeFrame)
    }

    suspend fun restoreSession() {
        val restored = linking.restoredSession()
        if (restored == null) {
            messaging.loadSession()
            return
        }
        auth = AuthContext(restored.accessToken, restored.deviceId)
        messaging.loadSession()
        val refreshed = transport.post(
            "/v1/auth/refresh",
            buildJsonObject {
                put("refresh_token", restored.refreshToken)
                put("device_id", restored.deviceId)
            },
        )
        val session = ClientSession(
            accessToken = refreshed.string("access_token"),
            userId = refreshed.getValue("user").jsonObject.string("id"),
            deviceId = refreshed.getValue("device").jsonObject.string("id"),
        )
        linking.saveRefreshedSession(session, refreshed.string("refresh_token"))
        installSession(session)
        messaging.loadSession()
        messaging.refreshChats()
    }

    suspend fun claimLink(): LinkedWindowsSession = linking.claim().also {
        installSession(ClientSession(it.accessToken, it.userId, it.deviceId))
        messaging.loadSession()
        messaging.refreshChats()
    }

    suspend fun logout() {
        runCatching { transport.post("/v1/auth/logout") }
        linking.clearLinkedSession()
        auth = null
        messaging.clearSessionState()
    }

    suspend fun createAndOpenChat(peerUserId: String): String {
        val chat = messaging.createChat(peerUserId.trim())
        messaging.openThread(chat.chatId)
        return chat.chatId
    }

    suspend fun refreshThread() {
        messaging.state.value.activeChatId?.let { messaging.catchUp(setOf(it)) }
    }

    suspend fun send(text: String) {
        messaging.sendText(requireNotNull(messaging.state.value.activeChatId), text)
    }

    suspend fun retry(message: MessageBubble) {
        messaging.retrySend(message.localId)
    }

    suspend fun edit(message: MessageBubble, text: String) {
        require(message.senderUserId == sessions.current()?.userId) { "only the author may edit" }
        messaging.editText(message.chatId, requireNotNull(message.messageId), text)
    }

    suspend fun delete(message: MessageBubble) {
        require(message.senderUserId == sessions.current()?.userId) { "only the author may delete" }
        messaging.deleteMessage(message.chatId, requireNotNull(message.messageId))
    }

    suspend fun markRead(message: MessageBubble) {
        require(message.senderUserId != sessions.current()?.userId) {
            "mark-read applies only to incoming messages"
        }
        messaging.markRead(message.chatId, requireNotNull(message.messageId))
    }

    override fun close() {
        httpClient.close()
        databaseDriver.close()
    }

    private fun installSession(session: ClientSession) {
        auth = AuthContext(session.accessToken, session.deviceId)
    }

    companion object {
        private fun defaultDatabasePath(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: throw IllegalStateException("LOCALAPPDATA is required for the messaging cache")
            return Path.of(localAppData, "Tima", "data", "messaging-cache-v1.db")
        }

        private fun openDatabase(path: Path): JdbcSqliteDriver {
            Files.createDirectories(requireNotNull(path.parent))
            val create = Files.notExists(path)
            return JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").also {
                if (create) TimaDatabase.Schema.create(it)
            }
        }

        fun validBaseUrl(value: String, developmentMode: Boolean): String {
            val uri = URI(value)
            val developmentLoopback = developmentMode &&
                uri.scheme == "http" &&
                uri.host in setOf("localhost", "127.0.0.1", "::1")
            require(uri.scheme == "https" || developmentLoopback) {
                "TIMA_API_BASE_URL must use HTTPS; explicit development builds may use loopback HTTP"
            }
            return value
        }
    }
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
    getValue(name).jsonPrimitive.content
