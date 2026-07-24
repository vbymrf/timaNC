package com.tima.client.android

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tima.client.data.MessageBubble
import com.tima.client.data.MessageDeliveryState
import com.tima.client.network.PlatformServiceUnavailableException
import com.tima.client.network.WakeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtime: AndroidPhase1Runtime? = null
    private lateinit var status: TextView
    private lateinit var sessionState: TextView
    private lateinit var phone: EditText
    private lateinit var password: EditText
    private lateinit var displayName: EditText
    private lateinit var otp: EditText
    private lateinit var register: Button
    private lateinit var login: Button
    private lateinit var logout: Button
    private lateinit var peerId: EditText
    private lateinit var createChat: Button
    private lateinit var refreshChats: Button
    private lateinit var chatList: LinearLayout
    private lateinit var threadTitle: TextView
    private lateinit var refreshThread: Button
    private lateinit var threadList: LinearLayout
    private lateinit var compose: EditText
    private lateinit var send: Button
    private lateinit var diagnostics: LinearLayout
    private var activeChatId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())

        runtime = runCatching { configuredRuntime() }.getOrElse {
            status.text = "Blocked: ${it.message}"
            setProductEnabled(false)
            null
        }
        runtime?.let { current ->
            scope.launch {
                current.messaging.state.collect { state ->
                    val view = AndroidMessagingPresenter.present(
                        state,
                        current.privateSendingEnabled,
                        current.trustSummary,
                    )
                    render(view)
                }
            }
            scope.launch { runOperation("Session restored") { current.restoreSession() } }
        }
    }

    override fun onResume() {
        super.onResume()
        AndroidNotificationWakeBridge.wake(WakeSource.APP_RESUME)
    }

    private fun prepareTrust() {
        runOperation("Play Integrity is ready") {
            val current = checkNotNull(runtime)
            checkNotNull(current.integrity) { "Play Integrity project is not configured" }.prepare()
        }
    }

    private fun runOperation(success: String, block: suspend () -> Unit) {
        status.text = "Working…"
        scope.launch {
            status.text = runCatching {
                block()
                success
            }.fold(
                onSuccess = { it },
                onFailure = {
                    val prefix = if (it is PlatformServiceUnavailableException) "Blocked" else "Failed"
                    "$prefix: ${it.message}"
                },
            )
        }
    }

    private fun configuredRuntime(): AndroidPhase1Runtime {
        @Suppress("DEPRECATION")
        val info = packageManager.getApplicationInfo(
            packageName,
            PackageManager.GET_META_DATA,
        )
        val baseUrl = info.metaData.get("com.tima.BASE_URL")?.toString().orEmpty()
        val projectNumber = info.metaData.get("com.tima.INTEGRITY_PROJECT_NUMBER")
            ?.toString()?.toLongOrNull() ?: 0L
        val developmentMode = DevelopmentModeGate.enabled(
            BuildConfig.DEBUG,
            BuildConfig.ENABLE_DEVELOPMENT_AUTH,
        )
        return AndroidPhase1Runtime(this, baseUrl, projectNumber, developmentMode)
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        status = textView(R.id.phase1_status, "Tima Phase 1 private messaging")
        sessionState = textView(R.id.session_state, "Restoring secure session…")
        phone = input(R.id.auth_phone, "Phone (+…)", InputType.TYPE_CLASS_PHONE)
        password = input(
            R.id.auth_password,
            "Password (12+ characters)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        displayName = input(R.id.auth_display_name, "Display name (registration)", InputType.TYPE_CLASS_TEXT)
        otp = input(R.id.auth_otp, "OTP (blank uses dev OTP only in explicit dev build)", InputType.TYPE_CLASS_NUMBER)
        register = button(R.id.auth_register, "Register") { register() }
        login = button(R.id.auth_login, "Login") { login() }
        logout = button(R.id.auth_logout, "Logout") {
            runOperation("Signed out") { checkNotNull(runtime).logout() }
        }
        peerId = input(R.id.chat_peer_user_id, "Peer user UUID", InputType.TYPE_CLASS_TEXT)
        createChat = button(R.id.chat_create, "Create / open 1:1 chat") {
            val value = peerId.text.toString().trim()
            runOperation("Private chat opened") {
                val created = checkNotNull(runtime).messaging.createChat(value)
                openChat(created.chatId, created.peerDisplayName)
            }
        }
        refreshChats = button(R.id.chat_refresh, "Refresh chats") {
            runOperation("Chats refreshed") { checkNotNull(runtime).messaging.refreshChats() }
        }
        chatList = LinearLayout(this).apply {
            id = R.id.chat_list
            orientation = LinearLayout.VERTICAL
            contentDescription = "Private chat list"
        }
        threadTitle = textView(R.id.thread_title, "Select a private chat")
        refreshThread = button(R.id.thread_refresh, "Refresh / catch up thread") {
            activeChatId?.let { chatId ->
                runOperation("Thread caught up") { checkNotNull(runtime).messaging.catchUp(setOf(chatId)) }
            }
        }
        threadList = LinearLayout(this).apply {
            id = R.id.thread_list
            orientation = LinearLayout.VERTICAL
            contentDescription = "Private message thread"
        }
        compose = input(R.id.message_compose, "Encrypted message", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        send = button(R.id.message_send, "Send encrypted text") {
            val chatId = activeChatId ?: return@button
            val text = compose.text.toString()
            runOperation("Send completed") {
                checkNotNull(runtime).messaging.sendText(chatId, text)
                compose.text.clear()
            }
        }
        diagnostics = diagnosticsPanel()
        val diagnosticsToggle = button(R.id.diagnostics_toggle, "Trust & push diagnostics") {
            diagnostics.visibility = if (diagnostics.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        listOf(
            status, sessionState, phone, password, displayName, otp, register, login, logout,
            peerId, createChat, refreshChats, chatList, threadTitle, refreshThread, threadList,
            compose, send, diagnosticsToggle, diagnostics,
        ).forEach(root::addView)
        return ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun diagnosticsPanel() = LinearLayout(this).apply {
        id = R.id.diagnostics_panel
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(button(R.id.diagnostics_prepare_trust, "Prepare Play Integrity") { prepareTrust() })
        addView(button(R.id.diagnostics_register_fcm, "Register FCM token") {
            runOperation("FCM token registered") { checkNotNull(runtime).phase1.registerCurrentPushToken() }
        })
        addView(button(R.id.diagnostics_register_unified_push, "Register UnifiedPush endpoint") {
            runOperation("UnifiedPush endpoint registered") {
                checkNotNull(runtime).unifiedPushPhase1.registerCurrentPushToken()
            }
        })
    }

    private fun register() {
        val current = runtime ?: return
        val enteredOtp = otp.text.toString().trim()
        val effectiveOtp = if (enteredOtp.isEmpty() && current.developmentMode) "000000" else enteredOtp
        runOperation("Registered and signed in") {
            current.authentication.register(
                phone.text.toString(),
                password.text.toString(),
                displayName.text.toString(),
                effectiveOtp,
            )
            current.messaging.loadSession()
            current.messaging.refreshChats()
        }
    }

    private fun login() {
        val current = runtime ?: return
        runOperation("Signed in") {
            current.authentication.login(phone.text.toString(), password.text.toString())
            current.messaging.loadSession()
            current.messaging.refreshChats()
        }
    }

    private fun openChat(chatId: String, title: String) {
        activeChatId = chatId
        threadTitle.text = title
        runOperation("Thread opened") { checkNotNull(runtime).messaging.openThread(chatId) }
    }

    private fun render(view: AndroidMessagingViewState) {
        sessionState.text = "${view.sessionLabel}\n${view.trustLabel}"
        phone.visibility = if (view.signedIn) View.GONE else View.VISIBLE
        password.visibility = if (view.signedIn) View.GONE else View.VISIBLE
        displayName.visibility = if (view.signedIn) View.GONE else View.VISIBLE
        otp.visibility = if (view.signedIn) View.GONE else View.VISIBLE
        register.visibility = if (view.signedIn) View.GONE else View.VISIBLE
        login.visibility = if (view.signedIn) View.GONE else View.VISIBLE
        logout.visibility = if (view.signedIn) View.VISIBLE else View.GONE
        peerId.isEnabled = view.signedIn
        createChat.isEnabled = view.signedIn
        refreshChats.isEnabled = view.signedIn
        send.isEnabled = view.sendEnabled
        compose.isEnabled = view.sendEnabled
        renderChats(view)
        renderThread(view)
    }

    private fun renderChats(view: AndroidMessagingViewState) {
        chatList.removeAllViews()
        view.chatsStatus?.let { chatList.addView(textView(View.generateViewId(), it)) }
        view.chats.forEach { chat ->
            chatList.addView(Button(this).apply {
                text = "${chat.peerDisplayName}  (${chat.unreadCount} unread)"
                contentDescription = "Open private chat ${chat.chatId}"
                setOnClickListener { openChat(chat.chatId, chat.peerDisplayName) }
            })
        }
    }

    private fun renderThread(view: AndroidMessagingViewState) {
        threadList.removeAllViews()
        view.threadStatus?.let { threadList.addView(textView(View.generateViewId(), it)) }
        view.messages.forEach { message ->
            threadList.addView(messageRow(message, view.currentUserId))
        }
    }

    private fun messageRow(message: MessageBubble, currentUserId: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        contentDescription = "Private message ${message.messageId ?: message.localId}"
        addView(TextView(this@MainActivity).apply {
            text = "${message.text}${if (message.edited) " (edited)" else ""}\n${message.delivery}"
        })
        if (message.delivery == MessageDeliveryState.ERROR) {
            addView(Button(this@MainActivity).apply {
                text = "Retry"
                isEnabled = runtime?.privateSendingEnabled == true
                contentDescription = "Retry failed message ${message.localId}"
                setOnClickListener {
                    runOperation("Message retried") { checkNotNull(runtime).messaging.retrySend(message.localId) }
                }
            })
        }
        message.messageId?.let { messageId ->
            if (message.senderUserId == currentUserId) {
                addView(Button(this@MainActivity).apply {
                    text = "Edit"
                    isEnabled = runtime?.privateSendingEnabled == true
                    contentDescription = "Edit message $messageId"
                    setOnClickListener { showEdit(message) }
                })
                addView(Button(this@MainActivity).apply {
                    text = "Delete"
                    contentDescription = "Delete message $messageId"
                    setOnClickListener {
                        runOperation("Message deleted") {
                            checkNotNull(runtime).messaging.deleteMessage(message.chatId, messageId)
                        }
                    }
                })
            } else {
                addView(Button(this@MainActivity).apply {
                    text = "Mark read"
                    contentDescription = "Mark read through message $messageId"
                    setOnClickListener {
                        runOperation("Read state updated") {
                            checkNotNull(runtime).messaging.markRead(message.chatId, messageId)
                        }
                    }
                })
            }
        }
    }

    private fun showEdit(message: MessageBubble) {
        val editor = EditText(this).apply {
            setText(message.text)
            contentDescription = "Edited private message text"
        }
        AlertDialog.Builder(this)
            .setTitle("Edit encrypted message")
            .setView(editor)
            .setPositiveButton("Save") { _, _ ->
                runOperation("Message edited") {
                    checkNotNull(runtime).messaging.editText(
                        message.chatId,
                        checkNotNull(message.messageId),
                        editor.text.toString(),
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setProductEnabled(enabled: Boolean) {
        listOf(register, login, logout, createChat, refreshChats, refreshThread, send).forEach {
            it.isEnabled = enabled
        }
    }

    private fun textView(id: Int, value: String) = TextView(this).apply {
        this.id = id
        text = value
        setPadding(0, 8, 0, 8)
    }

    private fun input(id: Int, hint: String, type: Int) = EditText(this).apply {
        this.id = id
        this.hint = hint
        inputType = type
        contentDescription = hint
    }

    private fun button(id: Int, label: String, action: () -> Unit) = Button(this).apply {
        this.id = id
        text = label
        contentDescription = label
        setOnClickListener { action() }
    }

    override fun onDestroy() {
        runtime?.close()
        scope.cancel()
        super.onDestroy()
    }
}
