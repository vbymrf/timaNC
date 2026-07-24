package com.tima.client.ios

import com.tima.client.crypto.HybridKodiumEscrowBlobBuilder
import com.tima.client.crypto.MessengerCrypto
import com.tima.client.data.ClientSession
import com.tima.client.data.IdGenerator
import com.tima.client.data.MessageBubble
import com.tima.client.data.NonDurableInMemoryMessagingCache
import com.tima.client.data.Phase1MessagingCoordinator
import com.tima.client.data.ProductionPrivateMessageCrypto
import com.tima.client.data.SecureStorageSessionRepository
import com.tima.client.data.TimaMessagingRemoteDataSource
import com.tima.client.network.AttestationCoordinator
import com.tima.client.network.AttestationProvider
import com.tima.client.network.AuthContext
import com.tima.client.network.ForegroundRealtimeSync
import com.tima.client.network.NotificationWakeSignal
import com.tima.client.network.NotificationWakeSink
import com.tima.client.network.Phase1PlatformClient
import com.tima.client.network.RealtimeReconnect
import com.tima.client.network.RestGapFill
import com.tima.client.network.TimaHttpTransport
import com.tima.client.network.TimaRealtimeTransport
import com.tima.client.network.WakeSource
import com.tima.client.sync.WakeToSyncCoordinator
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlin.time.TimeSource
import platform.Foundation.NSURL

class IosPhase1Runtime(
    baseUrl: String,
    debugBuild: Boolean,
    explicitDevelopmentAuth: Boolean,
) {
    val developmentMode = IosDevelopmentModeGate.enabled(debugBuild, explicitDevelopmentAuth)
    val secureStorage = KeychainSecureStorage()
    val appAttest = AppAttestProvider(secureStorage)
    val apns = ApnsPushTokenProvider(secureStorage)

    private var auth: AuthContext? = null
    private val httpClient = HttpClient(Darwin)
    private val validatedBaseUrl = validBaseUrl(baseUrl, developmentMode)
    private val transport = TimaHttpTransport(httpClient, validatedBaseUrl, { auth })
    private val realtime = TimaRealtimeTransport(httpClient, validatedBaseUrl, { auth })
    private var wakeSink: NotificationWakeSink? = null
    private val clockStart = TimeSource.Monotonic.markNow()
    private val sessions = SecureStorageSessionRepository(secureStorage)
    private val identities = IosDeviceIdentityRepository(secureStorage)
    private val trust = IosTrustMaterialProvider(transport, sessions, identities, developmentMode)
    private var apnsAvailable = false
    private val attestationProvider: AttestationProvider = if (developmentMode) {
        DevelopmentIosAttestationProvider.create(
            debugBuild = debugBuild,
            explicitDevelopmentAuth = explicitDevelopmentAuth,
        )
    } else {
        appAttest
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
        cache = NonDurableInMemoryMessagingCache(),
        ids = IdGenerator(::newUuid),
    )
    val authentication = IosAuthenticationClient(
        transport,
        attestation,
        sessions,
        identities,
        ::installSession,
    )
    val privateSendingEnabled: Boolean
        get() = developmentMode
    val trustSummary: String
        get() = if (developmentMode) {
            "Explicit development escrow trust is active; encrypted Path-B writes are enabled."
        } else {
            "Writes blocked: production escrow signing roots are not provisioned in this slice."
        }
    val phase1 = Phase1PlatformClient(
        transport,
        attestation,
        apns,
    )

    init {
        installWakeCoordinator(messaging, RealtimeReconnect { })
    }

    fun installWakeCoordinator(
        gapFill: RestGapFill,
        reconnect: RealtimeReconnect,
    ): WakeToSyncCoordinator = WakeToSyncCoordinator(
        gapFill = gapFill,
        realtime = reconnect,
        nowMillis = { clockStart.elapsedNow().inWholeMilliseconds },
    ).also { wakeSink = it }

    suspend fun applicationDidBecomeActive() {
        checkNotNull(wakeSink) { "wake coordinator is not installed" }
            .wake(NotificationWakeSignal(WakeSource.APP_RESUME))
    }

    suspend fun didReceiveApnsWake(payload: Map<String, String>) {
        checkNotNull(wakeSink) { "wake coordinator is not installed" }
            .wake(NotificationWakeSignal(WakeSource.APNS, payload))
    }

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
        if (auth != null) messaging.refreshChats()
    }

    suspend fun register(phone: String, password: String, displayName: String, otp: String) {
        val effectiveOtp = if (otp.isBlank() && developmentMode) "000000" else otp.trim()
        authentication.register(phone, password, displayName, effectiveOtp)
        messaging.loadSession()
        messaging.refreshChats()
    }

    suspend fun login(phone: String, password: String) {
        authentication.login(phone, password)
        messaging.loadSession()
        messaging.refreshChats()
    }

    suspend fun logout() {
        runCatching { transport.post("/v1/auth/logout") }
        sessions.clear()
        auth = null
        messaging.clearSessionState()
    }

    suspend fun apnsDidRegister(deviceToken: platform.Foundation.NSData) {
        apns.didRegisterForRemoteNotifications(deviceToken)
        apnsAvailable = true
    }

    suspend fun apnsDidFail() {
        apns.didFailToRegisterForRemoteNotifications()
        apnsAvailable = false
    }

    fun viewState(): IosMessagingViewState = IosMessagingPresenter.present(
        messaging.state.value,
        privateSendingEnabled,
        trustSummary,
        apnsAvailable,
    )

    suspend fun createAndOpenChat(peerUserId: String): String {
        val chat = messaging.createChat(peerUserId.trim())
        messaging.openThread(chat.chatId)
        return chat.chatId
    }

    suspend fun refreshChats() = messaging.refreshChats()

    suspend fun openChat(chatId: String) = messaging.openThread(chatId)

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

    fun close() {
        httpClient.close()
    }

    private fun validBaseUrl(value: String, developmentMode: Boolean): String {
        val parsed = NSURL(string = value)
        val developmentLoopback = developmentMode &&
            parsed.scheme == "http" &&
            parsed.host in setOf("localhost", "127.0.0.1")
        require(parsed.scheme == "https" || developmentLoopback) {
            "Tima API base URL must use HTTPS; explicit development builds may use loopback HTTP"
        }
        return value
    }

    private fun installSession(session: ClientSession) {
        auth = AuthContext(session.accessToken, session.deviceId)
    }
}
