package com.tima.client.windows

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tima.client.data.ChatPreview
import com.tima.client.data.MessageBubble
import com.tima.client.data.MessageDeliveryState
import com.tima.client.media.MEDIA_INPUT_LIMIT_BYTES
import com.tima.client.media.MediaVariantName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.filechooser.FileNameExtensionFilter
import javax.imageio.ImageIO

fun main() {
    SwingUtilities.invokeLater {
        val baseUrl = System.getenv("TIMA_API_BASE_URL").orEmpty()
        val developmentMode = WindowsDevelopmentModeGate.enabled(
            buildAllowsDevelopmentEscrow = windowsDevelopmentEscrowBuildAllowed(),
            explicitEnvironmentOptIn =
                System.getenv("TIMA_WINDOWS_ENABLE_DEVELOPMENT_ESCROW")
                    ?.toBooleanStrictOrNull() == true,
        )
        val runtime = runCatching { WindowsPhase1Runtime(baseUrl, developmentMode) }.getOrElse {
            showBlockedShell(it.message ?: "platform services unavailable")
            return@invokeLater
        }
        WindowsShell(runtime).show()
    }
}

internal fun windowsDevelopmentEscrowBuildAllowed(
    property: String? = System.getProperty("tima.windows.developmentEscrowBuild"),
): Boolean = property?.toBooleanStrictOrNull() == true

private class WindowsShell(
    private val runtime: WindowsPhase1Runtime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val status = JLabel("Tima Phase 1 private messaging")
        .identified("phase1.status", "Current Windows messaging operation status")
    private val session = JLabel("Restoring DPAPI-protected linked session…")
        .identified("phase1.session", "Linked-user session and escrow trust status")
    private val delivery = JLabel()
        .identified("phase1.delivery", "Foreground message catch-up capability")
    private val qr = JLabel().identified("link.qr", "QR code for linking this Windows device")
    private val startLink = JButton("Start Windows link")
        .identified("link.start", "Start linking this Windows device")
    private val claim = JButton("Claim confirmed link")
        .identified("link.claim", "Claim a link confirmed by a trusted mobile device")
        .apply { isEnabled = false }
    private val copyLinkPayload = JButton("Copy link payload")
        .identified("link.copy-payload", "Copy the pending QR payload for a trusted mobile device")
        .apply { isEnabled = false }
    private val linkingPanel = JPanel(BorderLayout(8, 8))
        .identified("link.panel", "Windows device linking controls")
    private val peer = JTextField()
        .identified("chat.peer-user-id", "Peer user identifier for a private chat")
    private val createChat = JButton("Create / open chat")
        .identified("chat.create", "Create or open a private one-to-one chat")
    private val refreshChats = JButton("Refresh chats")
        .identified("chat.refresh", "Refresh private chat list")
    private val chatModel = DefaultListModel<ChatPreview>()
    private val chats = JList(chatModel)
        .identified("chat.list", "Private chat list")
    private val refreshThread = JButton("Refresh thread")
        .identified("thread.refresh", "Catch up the selected private thread")
    private val messageModel = DefaultListModel<MessageBubble>()
    private val messages = JList(messageModel)
        .identified("thread.list", "Encrypted private message thread")
    private val compose = JTextArea(3, 30)
        .identified("message.compose", "Encrypted private message text")
    private val send = JButton("Send encrypted text")
        .identified("message.send", "Encrypt and send private message")
    private val attachImage = JButton("Attach encrypted image")
        .identified("media.attach-image", "Select one private image for encrypted upload")
    private val openPreview = JButton("Open image preview")
        .identified("media.open-preview", "Open selected encrypted image inside Tima")
    private val mediaState = JLabel("No image upload")
        .identified("media.upload-state", "Encrypted image upload progress")
    private val retryMedia = JButton("Retry image upload")
        .identified("media.retry", "Retry the encrypted image upload")
        .apply { isEnabled = false }
    private val retry = JButton("Retry")
        .identified("message.retry", "Retry selected failed message")
    private val edit = JButton("Edit")
        .identified("message.edit", "Edit selected authored message")
    private val delete = JButton("Delete")
        .identified("message.delete", "Delete selected authored message")
    private val markRead = JButton("Mark read")
        .identified("message.mark-read", "Mark through selected incoming message as read")
    private val logout = JButton("Logout")
        .identified("session.logout", "Logout and wipe the encrypted offline messaging cache")
    private val frame = JFrame("Tima")
    private var rendered: WindowsMessagingViewState? = null
    private val thumbnailCache = mutableMapOf<String, ImageIcon>()
    private val thumbnailLoading = mutableSetOf<String>()

    fun show() {
        configureActions()
        configureLists()
        linkingPanel.border = BorderFactory.createTitledBorder("Link this Windows device")
        linkingPanel.add(qr, BorderLayout.CENTER)
        linkingPanel.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(startLink)
            add(copyLinkPayload)
            add(claim)
        }, BorderLayout.SOUTH)
        val chatPanel = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createTitledBorder("Private chats")
            add(JPanel(GridLayout(0, 1, 4, 4)).apply {
                add(peer)
                add(createChat)
                add(refreshChats)
            }, BorderLayout.NORTH)
            add(JScrollPane(chats), BorderLayout.CENTER)
        }
        val threadPanel = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createTitledBorder("Encrypted thread")
            add(refreshThread, BorderLayout.NORTH)
            add(JScrollPane(messages), BorderLayout.CENTER)
            add(JPanel(BorderLayout(4, 4)).apply {
                add(JScrollPane(compose), BorderLayout.CENTER)
                add(JPanel(GridLayout(0, 3, 4, 4)).apply {
                    add(send)
                    add(attachImage)
                    add(openPreview)
                    add(retryMedia)
                    add(retry)
                    add(edit)
                    add(delete)
                    add(markRead)
                    add(logout)
                }, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
        val product = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chatPanel, threadPanel).apply {
            resizeWeight = 0.32
        }
        frame.contentPane = JPanel(BorderLayout(12, 12)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(JPanel(GridLayout(0, 1)).apply {
                add(status)
                add(session)
                add(delivery)
                add(mediaState)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(linkingPanel, BorderLayout.NORTH)
                add(product, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) {
                clearCopiedLinkPayload()
                scope.cancel()
                runtime.close()
            }
        })
        frame.minimumSize = Dimension(900, 640)
        frame.setSize(1100, 760)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true

        scope.launch {
            runtime.messaging.state.collect { state ->
                val view = WindowsMessagingPresenter.present(
                    state,
                    runtime.privateSendingEnabled,
                    runtime.trustSummary,
                    runtime.deliverySummary,
                )
                SwingUtilities.invokeLater { render(view) }
            }
        }
        scope.launch {
            runtime.media.state.collect { state ->
                SwingUtilities.invokeLater {
                    mediaState.text = state.state?.let {
                        "$it: ${state.completedVariants}/${state.totalVariants}" +
                            (state.errorCode?.let { code -> " · $code" } ?: "")
                    } ?: "No image upload"
                    retryMedia.isEnabled = state.retryable && state.localId != null
                }
            }
        }
        runtime.startPeriodicCatchUp(scope)
        runOperation("Secure linked session restored") { runtime.restoreSession() }
    }

    private fun configureActions() {
        startLink.addActionListener {
            startLink.isEnabled = false
            runOperation(
                successMessage = "Link session started",
                operation = { runtime.linking.start(System.getProperty("user.name") + " Windows") },
                success = { pending ->
                    qr.icon = ImageIcon(qrImage(pending.qrPayload))
                    qr.toolTipText = pending.qrPayload
                    status.text = "Scan before ${pending.expiresAt}; no QR secret is logged."
                    copyLinkPayload.isEnabled = true
                    claim.isEnabled = true
                },
                failure = { startLink.isEnabled = true },
            )
        }
        copyLinkPayload.addActionListener {
            qr.toolTipText?.let { payload ->
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(payload), null)
                status.text = "Link payload copied; clear the clipboard after confirmation."
            }
        }
        claim.addActionListener {
            claim.isEnabled = false
            runOperation(
                successMessage = "Linked session established",
                operation = runtime::claimLink,
                success = { session ->
                    qr.icon = null
                    copyLinkPayload.isEnabled = false
                    clearCopiedLinkPayload()
                    qr.toolTipText = null
                    status.text =
                        "Linked user ${session.userId}; device ${session.deviceId}; credentials protected with DPAPI."
                },
                failure = { claim.isEnabled = true },
            )
        }
        createChat.addActionListener {
            runOperation("Private chat opened") { runtime.createAndOpenChat(peer.text.trim()) }
        }
        refreshChats.addActionListener {
            runOperation("Chats refreshed") { runtime.messaging.refreshChats() }
        }
        refreshThread.addActionListener {
            runOperation("Thread caught up") { runtime.refreshThread() }
        }
        send.addActionListener {
            val text = compose.text
            runOperation(
                successMessage = "Encrypted send completed",
                success = { compose.text = "" },
                operation = { runtime.send(text) },
            )
        }
        attachImage.addActionListener {
            val chatId = rendered?.activeChatId ?: return@addActionListener
            val chooser = JFileChooser().apply {
                dialogTitle = "Select one private image"
                fileFilter = FileNameExtensionFilter("Images", "jpg", "jpeg", "png", "gif", "bmp")
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                runOperation("Encrypted image queued") {
                    val bytes = Files.newInputStream(chooser.selectedFile.toPath()).use {
                        readBounded(it, MEDIA_INPUT_LIMIT_BYTES)
                    }
                    runtime.media.selectAndSend(chatId, bytes)
                }
            }
        }
        openPreview.addActionListener {
            val attachment = messages.selectedValue?.attachment ?: return@addActionListener
            runOperation(
                "Encrypted image preview opened",
                operation = { runtime.mediaDownloader.download(attachment, MediaVariantName.PREVIEW) },
                success = { jpeg ->
                    try {
                        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(jpeg)))
                        JOptionPane.showMessageDialog(
                            frame,
                            JLabel(ImageIcon(image)),
                            "Encrypted image",
                            JOptionPane.PLAIN_MESSAGE,
                        )
                    } finally {
                        jpeg.fill(0)
                    }
                },
                failure = {},
            )
        }
        retryMedia.addActionListener {
            runtime.media.state.value.localId?.let { localId ->
                runOperation("Encrypted image retry completed") { runtime.media.retry(localId) }
            }
        }
        retry.addActionListener {
            messages.selectedValue?.let { value ->
                runOperation("Message retried") { runtime.retry(value) }
            }
        }
        edit.addActionListener { messages.selectedValue?.let(::showEdit) }
        delete.addActionListener {
            messages.selectedValue?.let { value ->
                if (JOptionPane.showConfirmDialog(
                        frame,
                        "Delete this message?",
                        "Delete encrypted message",
                        JOptionPane.OK_CANCEL_OPTION,
                    ) == JOptionPane.OK_OPTION
                ) {
                    runOperation("Message deleted") { runtime.delete(value) }
                }
            }
        }
        markRead.addActionListener {
            messages.selectedValue?.let { value ->
                runOperation("Read state updated") { runtime.markRead(value) }
            }
        }
        logout.addActionListener {
            runOperation("Signed out; encrypted offline cache and protected key wiped") {
                runtime.logout()
                thumbnailCache.clear()
                thumbnailLoading.clear()
            }
        }
    }

    private fun clearCopiedLinkPayload() {
        val payload = qr.toolTipText ?: return
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val current = runCatching {
            clipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
        if (current == payload) {
            clipboard.setContents(StringSelection(""), null)
        }
    }

    private fun configureLists() {
        chats.selectionMode = ListSelectionModel.SINGLE_SELECTION
        chats.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component = super.getListCellRendererComponent(
                list,
                (value as? ChatPreview)?.let {
                    "${it.peerDisplayName} · ${it.unreadCount} unread"
                } ?: value,
                index,
                isSelected,
                cellHasFocus,
            )
        }
        chats.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                chats.selectedValue?.let { chat ->
                    if (chat.chatId != rendered?.activeChatId) {
                        runOperation("Thread opened") { runtime.messaging.openThread(chat.chatId) }
                    }
                }
            }
        }
        messages.selectionMode = ListSelectionModel.SINGLE_SELECTION
        messages.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val message = value as? MessageBubble
                val component = super.getListCellRendererComponent(
                    list,
                    message?.let {
                        "${it.text.ifEmpty { if (it.attachment != null) "Encrypted image" else "" }}" +
                            "${if (it.edited) " (edited)" else ""} · ${it.delivery}"
                    } ?: value,
                    index,
                    isSelected,
                    cellHasFocus,
                ) as JLabel
                component.icon = message?.let { thumbnailCache[it.localId] }
                val attachment = message?.attachment
                if (attachment != null &&
                    message.localId !in thumbnailCache &&
                    thumbnailLoading.add(message.localId)
                ) {
                    scope.launch {
                        runCatching {
                            runtime.mediaDownloader.download(
                                attachment,
                                MediaVariantName.THUMBNAIL,
                            )
                        }.onSuccess { jpeg ->
                            try {
                                val decoded = ImageIO.read(ByteArrayInputStream(jpeg))
                                val scaled = decoded.getScaledInstance(40, 40, java.awt.Image.SCALE_SMOOTH)
                                SwingUtilities.invokeLater {
                                    thumbnailCache[message.localId] = ImageIcon(scaled)
                                    messages.repaint()
                                }
                            } finally {
                                jpeg.fill(0)
                            }
                        }
                        thumbnailLoading.remove(message.localId)
                    }
                }
                return component
            }
        }
        messages.addListSelectionListener { updateMessageActions() }
    }

    private fun render(view: WindowsMessagingViewState) {
        rendered = view
        session.text = "<html>${view.sessionLabel}<br>${view.trustLabel}</html>"
        delivery.text = view.deliveryBanner
        linkingPanel.isVisible = !view.signedIn
        listOf(peer, createChat, refreshChats, refreshThread, logout).forEach {
            it.isEnabled = view.signedIn
        }
        send.isEnabled = view.sendEnabled
        compose.isEnabled = view.sendEnabled
        attachImage.isEnabled = view.sendEnabled && view.activeChatId != null

        val selectedChat = view.activeChatId
        chatModel.clear()
        view.chats.forEach(chatModel::addElement)
        if (view.chats.isEmpty() && view.chatsStatus != null) {
            status.text = view.chatsStatus
        }
        (0 until chatModel.size()).firstOrNull {
            chatModel.getElementAt(it).chatId == selectedChat
        }?.let { chats.selectedIndex = it }

        val selectedMessage = messages.selectedValue?.localId
        messageModel.clear()
        view.messages.forEach(messageModel::addElement)
        (0 until messageModel.size()).firstOrNull {
            messageModel.getElementAt(it).localId == selectedMessage
        }?.let { messages.selectedIndex = it }
        if (view.messages.isEmpty() && view.threadStatus != null && view.activeChatId != null) {
            status.text = view.threadStatus
        }
        updateMessageActions()
    }

    private fun updateMessageActions() {
        val value = messages.selectedValue
        val currentUser = rendered?.currentUserId
        retry.isEnabled = value?.delivery == MessageDeliveryState.ERROR && runtime.privateSendingEnabled
        edit.isEnabled =
            value?.messageId != null && value.senderUserId == currentUser && runtime.privateSendingEnabled
        delete.isEnabled = value?.messageId != null && value.senderUserId == currentUser
        markRead.isEnabled = value?.messageId != null && value.senderUserId != currentUser
        openPreview.isEnabled = value?.attachment != null
    }

    private fun showEdit(message: MessageBubble) {
        val editor = JTextArea(message.text, 5, 36)
            .identified("message.edit-text", "Replacement encrypted private message text")
        val optionPane = JOptionPane(
            JScrollPane(editor),
            JOptionPane.PLAIN_MESSAGE,
            JOptionPane.OK_CANCEL_OPTION,
        )
        val dialog = optionPane.createDialog(frame, "Edit encrypted message")
        dialog.name = "message.edit-dialog"
        dialog.accessibleContext.accessibleDescription = "Edit authored encrypted message"
        dialog.isVisible = true
        if (optionPane.value == JOptionPane.OK_OPTION) {
            runOperation("Message edited") { runtime.edit(message, editor.text) }
        }
    }

    private fun runOperation(
        successMessage: String,
        success: () -> Unit = {},
        operation: suspend () -> Unit,
    ) = runOperation(successMessage, operation, { success() }, {})

    private fun <T> runOperation(
        successMessage: String,
        operation: suspend () -> T,
        success: (T) -> Unit,
        failure: () -> Unit,
    ) {
        status.text = "Working…"
        scope.launch {
            runCatching { operation() }.fold(
                onSuccess = { value ->
                    SwingUtilities.invokeLater {
                        status.text = successMessage
                        success(value)
                    }
                },
                onFailure = { error ->
                    SwingUtilities.invokeLater {
                        status.text = "Failed: ${error.message}"
                        failure()
                    }
                },
            )
        }
    }
}

private fun <T : JComponent> T.identified(stableName: String, description: String): T = apply {
    name = stableName
    accessibleContext.accessibleName = stableName
    accessibleContext.accessibleDescription = description
}

private fun qrImage(payload: String): BufferedImage {
    require(payload.isNotBlank())
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 360, 360)
    return BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB).apply {
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
    }
}

private fun showBlockedShell(message: String) {
    JFrame("Tima").apply {
        contentPane.add(
            JLabel("<html><body style='padding:16px'>Blocked: $message</body></html>"),
        )
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        setSize(480, 160)
        setLocationRelativeTo(null)
        isVisible = true
    }
}
