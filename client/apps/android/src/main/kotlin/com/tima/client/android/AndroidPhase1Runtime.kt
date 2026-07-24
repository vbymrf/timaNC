package com.tima.client.android

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.tima.client.crypto.HybridKodiumEscrowBlobBuilder
import com.tima.client.crypto.MessengerCrypto
import com.tima.client.data.ClientSession
import com.tima.client.data.EncryptedSqlDelightMessagingCache
import com.tima.client.data.EncryptedSqlDelightMediaQueueStore
import com.tima.client.data.IdGenerator
import com.tima.client.data.Phase1MessagingCoordinator
import com.tima.client.data.ProductionPrivateMessageCrypto
import com.tima.client.data.SecureStorageSessionRepository
import com.tima.client.data.TimaMessagingRemoteDataSource
import com.tima.client.database.TimaDatabase
import com.tima.client.network.AttestationCoordinator
import com.tima.client.network.AttestationProvider
import com.tima.client.network.AuthContext
import com.tima.client.network.ForegroundRealtimeSync
import com.tima.client.network.NotificationWakeSink
import com.tima.client.network.PlatformServiceUnavailableException
import com.tima.client.network.Phase1PlatformClient
import com.tima.client.network.RealtimeReconnect
import com.tima.client.network.RestGapFill
import com.tima.client.network.TimaHttpTransport
import com.tima.client.network.TimaRealtimeTransport
import com.tima.client.media.MediaIdGenerator
import com.tima.client.media.MediaMessageSender
import com.tima.client.media.PrivateImageUploadCoordinator
import com.tima.client.media.PrivateImageDownloader
import com.tima.client.media.PrivateMediaTransport
import com.tima.client.sync.WakeToSyncCoordinator
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.URI
import java.util.UUID

class AndroidPhase1Runtime(
    context: Context,
    baseUrl: String,
    cloudProjectNumber: Long,
    val developmentMode: Boolean,
) : AutoCloseable {
    val secureStorage = AndroidKeystoreSecureStorage(context)
    val integrity = cloudProjectNumber.takeIf { it > 0 }?.let {
        PlayIntegrityAttestationProvider(context, it)
    }
    val pushTokens = FcmPushTokenProvider(context)
    val unifiedPush = UnifiedPushEndpointProvider(context)

    private var authContext: AuthContext? = null
    private val databaseDriver =
        AndroidSqliteDriver(TimaDatabase.Schema, context.applicationContext, "messaging-cache-v1.db")
    private val database = TimaDatabase(databaseDriver)
    private val httpClient = HttpClient(OkHttp)
    private val mediaHttpClient = HttpClient(OkHttp) { followRedirects = false }
    private val validatedBaseUrl = validBaseUrl(baseUrl, developmentMode)
    private val transport = TimaHttpTransport(httpClient, validatedBaseUrl, { authContext })
    private val realtime = TimaRealtimeTransport(httpClient, validatedBaseUrl, { authContext })
    private var wakeSink: NotificationWakeSink? = null
    private val sessions = SecureStorageSessionRepository(secureStorage)
    private val messagingStore = EncryptedSqlDelightMessagingCache(database, secureStorage)
    private val mediaQueue = EncryptedSqlDelightMediaQueueStore(database, secureStorage)
    private val identities = AndroidDeviceIdentityRepository(secureStorage)
    private val trust = AndroidTrustMaterialProvider(transport, sessions, identities, developmentMode)
    private val attestationProvider: AttestationProvider = when {
        developmentMode -> DevelopmentAndroidAttestationProvider.create(
            debugBuild = BuildConfig.DEBUG,
            explicitDevelopmentAuth = BuildConfig.ENABLE_DEVELOPMENT_AUTH,
        )
        integrity != null -> integrity
        else -> object : AttestationProvider {
            override suspend fun attest(action: String, requestBodySha256: ByteArray) =
                throw PlatformServiceUnavailableException("Play Integrity configuration")
        }
    }
    private val attestation = AttestationCoordinator(transport, attestationProvider)
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
        cache = messagingStore,
        ids = IdGenerator { UUID.randomUUID().toString() },
        nowEpochMillis = System::currentTimeMillis,
    )
    private val mediaTransport = PrivateMediaTransport(
        transport,
        mediaHttpClient,
        developmentMode,
        System::currentTimeMillis,
    )
    val media = PrivateImageUploadCoordinator(
        normalizer = AndroidImageNormalizer(),
        queue = mediaQueue,
        blobs = AndroidCiphertextBlobStore(context.applicationContext),
        transport = mediaTransport,
        sender = MediaMessageSender { chatId, attachment, binding ->
            messaging.ensureMediaMessage(chatId, attachment, binding)
        },
        ids = MediaIdGenerator { UUID.randomUUID().toString() },
        nowEpochMillis = System::currentTimeMillis,
    )
    val mediaDownloader = PrivateImageDownloader(mediaTransport)
    val authentication = AndroidAuthenticationClient(
        transport = transport,
        attestation = attestation,
        sessions = sessions,
        identities = identities,
        onSession = ::installSession,
    )
    val privateSendingEnabled: Boolean
        get() = developmentMode
    val trustSummary: String
        get() = if (developmentMode) {
            "Development escrow trust is explicit; encrypted Path-B sends are enabled."
        } else {
            "Sending blocked: production escrow signing roots are not provisioned in this slice."
        }
    val phase1 = Phase1PlatformClient(
        transport,
        attestation,
        pushTokens,
    )
    val unifiedPushPhase1 = Phase1PlatformClient(
        transport,
        attestation,
        unifiedPush,
    )

    init {
        installWakeCoordinator(messaging, RealtimeReconnect { })
    }

    fun installWakeCoordinator(coordinator: NotificationWakeSink) {
        wakeSink?.let(AndroidNotificationWakeBridge::uninstall)
        wakeSink = coordinator
        AndroidNotificationWakeBridge.install(coordinator)
    }

    fun installWakeCoordinator(
        gapFill: RestGapFill,
        reconnect: RealtimeReconnect,
    ): WakeToSyncCoordinator = WakeToSyncCoordinator(
        gapFill = gapFill,
        realtime = reconnect,
        nowMillis = System::currentTimeMillis,
    ).also(::installWakeCoordinator)

    suspend fun runForegroundRealtime(
        subscriptionFrame: ByteArray,
        consumeFrame: suspend (ByteArray) -> Unit,
    ) {
        val coordinator = checkNotNull(wakeSink) { "wake coordinator is not installed" }
        ForegroundRealtimeSync(realtime, coordinator).run(subscriptionFrame, consumeFrame)
    }

    suspend fun restoreSession() {
        sessions.current()?.let(::installSession)
        messaging.loadSession()
        if (authContext != null) media.resumePending()
        if (authContext != null) messaging.refreshChats()
    }

    suspend fun logout() {
        runCatching { transport.post("/v1/auth/logout") }
        sessions.clear()
        authContext = null
        media.wipeSession()
        messaging.clearSessionState()
    }

    override fun close() {
        wakeSink?.let(AndroidNotificationWakeBridge::uninstall)
        mediaHttpClient.close()
        httpClient.close()
        databaseDriver.close()
    }

    private fun validBaseUrl(value: String, developmentMode: Boolean): String {
        val uri = URI(value)
        val developmentLoopback = developmentMode &&
            uri.scheme == "http" &&
            uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
        require(uri.scheme == "https" || developmentLoopback) {
            "Tima API base URL must use HTTPS; explicit development builds may use a loopback host"
        }
        return value
    }

    private fun installSession(session: ClientSession) {
        authContext = AuthContext(session.accessToken, session.deviceId)
    }
}
