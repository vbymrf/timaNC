import CryptoKit
import SwiftUI
import TimaIosApp

struct ContentView: View {
    let runtime: IosPhase1Runtime?
    @State private var status = "Tima Phase 1 private messaging"
    @State private var viewState: IosMessagingViewState?
    @State private var phone = ""
    @State private var password = ""
    @State private var displayName = ""
    @State private var otp = ""
    @State private var peerUserId = ""
    @State private var compose = ""
    @State private var editingMessage: MessageBubble?
    @State private var editedText = ""
    @State private var showDiagnostics = false

    var body: some View {
        NavigationView {
            Form {
                Section("Status") {
                    Text(status)
                        .accessibilityIdentifier("phase1.status")
                    Text(viewState?.sessionLabel ?? "Restoring secure session…")
                        .accessibilityIdentifier("session.state")
                    Text(viewState?.trustLabel ?? "Trust configuration unavailable")
                        .accessibilityIdentifier("trust.state")
                    Text(viewState?.deliveryBanner ??
                         "APNs is unavailable: messages catch up only while open or when the app resumes.")
                        .accessibilityIdentifier("delivery.banner")
                }

                if viewState?.signedIn != true {
                    Section("Account") {
                        TextField("Phone (+…)", text: $phone)
                            .textContentType(.telephoneNumber)
                            .accessibilityIdentifier("auth.phone")
                        SecureField("Password (12+ characters)", text: $password)
                            .textContentType(.password)
                            .accessibilityIdentifier("auth.password")
                        TextField("Display name", text: $displayName)
                            .accessibilityIdentifier("auth.displayName")
                        TextField("OTP (blank uses explicit dev fixture)", text: $otp)
                            .keyboardType(.numberPad)
                            .accessibilityIdentifier("auth.otp")
                        Button("Register") {
                            run("Registered and signed in") {
                                try await requireRuntime().register(
                                    phone: phone,
                                    password: password,
                                    displayName: displayName,
                                    otp: otp
                                )
                            }
                        }
                        .accessibilityIdentifier("auth.register")
                        Button("Login") {
                            run("Signed in") {
                                try await requireRuntime().login(phone: phone, password: password)
                            }
                        }
                        .accessibilityIdentifier("auth.login")
                    }
                } else {
                    Section("Account") {
                        Button("Logout", role: .destructive) {
                            run("Signed out") {
                                try await requireRuntime().logout()
                                password = ""
                                otp = ""
                                compose = ""
                                editedText = ""
                                editingMessage = nil
                            }
                        }
                        .accessibilityIdentifier("auth.logout")
                    }
                }

                Section("Private chats") {
                    TextField("Peer user UUID", text: $peerUserId)
                        .accessibilityIdentifier("chat.peerUserId")
                    Button("Create / open 1:1 chat") {
                        run("Private chat opened") {
                            _ = try await requireRuntime().createAndOpenChat(peerUserId: peerUserId)
                        }
                    }
                    .disabled(viewState?.signedIn != true)
                    .accessibilityIdentifier("chat.create")
                    Button("Refresh chats") {
                        run("Chats refreshed") { try await requireRuntime().refreshChats() }
                    }
                    .disabled(viewState?.signedIn != true)
                    .accessibilityIdentifier("chat.refresh")
                    if let label = viewState?.chatsStatus {
                        Text(label).accessibilityIdentifier("chat.status")
                    }
                    ForEach(viewState?.chats ?? [], id: \.chatId) { chat in
                        Button("\(chat.peerDisplayName) (\(chat.unreadCount) unread)") {
                            run("Thread opened") {
                                try await requireRuntime().openChat(chatId: chat.chatId)
                            }
                        }
                        .accessibilityIdentifier("chat.open.\(chat.chatId)")
                    }
                }

                Section("Encrypted thread") {
                    Button("Refresh / catch up thread") {
                        run("Thread caught up") { try await requireRuntime().refreshThread() }
                    }
                    .disabled(viewState?.activeChatId == nil)
                    .accessibilityIdentifier("thread.refresh")
                    if let label = viewState?.threadStatus {
                        Text(label).accessibilityIdentifier("thread.status")
                    }
                    ForEach(viewState?.messages ?? [], id: \.localId) { message in
                        messageRow(message)
                    }
                    TextEditor(text: $compose)
                        .frame(minHeight: 70)
                        .accessibilityIdentifier("message.compose")
                    Button("Send encrypted text") {
                        let plaintext = compose
                        run("Send completed") {
                            try await requireRuntime().send(text: plaintext)
                            compose = ""
                        }
                    }
                    .disabled(viewState?.sendEnabled != true || compose.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("message.send")
                }

                DisclosureGroup("App Attest & APNs diagnostics", isExpanded: $showDiagnostics) {
                    Button("Prepare App Attest enrollment") { prepareAppAttest() }
                        .accessibilityIdentifier("diagnostics.appAttest")
                    Button("Register current APNs token") {
                        run("APNs token registered") {
                            try await requireRuntime().phase1.registerCurrentPushToken()
                        }
                    }
                    .accessibilityIdentifier("diagnostics.apns")
                }
            }
            .navigationTitle("Tima")
            .task {
                await perform("Session restored") { try await requireRuntime().restoreSession() }
            }
            .onReceive(NotificationCenter.default.publisher(for: .timaDidCatchUp)) { _ in
                render()
            }
            .alert("Edit encrypted message", isPresented: Binding(
                get: { editingMessage != nil },
                set: { if !$0 { editingMessage = nil; editedText = "" } }
            )) {
                TextField("Edited private message", text: $editedText)
                    .accessibilityIdentifier("message.edit.text")
                Button("Cancel", role: .cancel) {
                    editingMessage = nil
                    editedText = ""
                }
                Button("Save") {
                    guard let message = editingMessage else { return }
                    let text = editedText
                    run("Message edited") {
                        try await requireRuntime().edit(message: message, text: text)
                        editingMessage = nil
                        editedText = ""
                    }
                }
                .accessibilityIdentifier("message.edit.save")
            }
        }
    }

    @ViewBuilder
    private func messageRow(_ message: MessageBubble) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(message.text + (message.edited ? " (edited)" : ""))
            Text(String(describing: message.delivery))
                .font(.caption)
                .foregroundColor(.secondary)
            if message.delivery == .error {
                Button("Retry") {
                    run("Message retried") { try await requireRuntime().retry(message: message) }
                }
                .disabled(runtime?.privateSendingEnabled != true)
                .accessibilityIdentifier("message.retry.\(message.localId)")
            }
            if message.messageId != nil {
                if message.senderUserId == viewState?.currentUserId {
                    HStack {
                        Button("Edit") {
                            editedText = message.text
                            editingMessage = message
                        }
                        .disabled(runtime?.privateSendingEnabled != true)
                        .accessibilityIdentifier("message.edit.\(message.localId)")
                        Button("Delete", role: .destructive) {
                            run("Message deleted") {
                                try await requireRuntime().delete(message: message)
                            }
                        }
                        .accessibilityIdentifier("message.delete.\(message.localId)")
                    }
                } else {
                    Button("Mark read") {
                        run("Read state updated") {
                            try await requireRuntime().markRead(message: message)
                        }
                    }
                    .accessibilityIdentifier("message.markRead.\(message.localId)")
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("message.row.\(message.localId)")
    }

    private func requireRuntime() throws -> IosPhase1Runtime {
        guard let runtime else {
            throw RuntimeConfigurationError.missingBaseURL
        }
        return runtime
    }

    private func run(_ success: String, operation: @escaping () async throws -> Void) {
        Task { await perform(success, operation: operation) }
    }

    @MainActor
    private func perform(
        _ success: String,
        operation: @escaping () async throws -> Void
    ) async {
        status = "Working…"
        do {
            try await operation()
            status = success
        } catch {
            status = "Blocked/failed: \(error.localizedDescription)"
        }
        render()
    }

    @MainActor
    private func render() {
        viewState = runtime?.viewState()
    }

    private func prepareAppAttest() {
        run("App Attest enrollment object ready") {
            let digest = Data(SHA256.hash(data: Data("tima-app-attest-enrollment".utf8)))
            let challenge = KotlinByteArray(size: Int32(digest.count))
            for (index, byte) in digest.enumerated() {
                challenge.set(index: Int32(index), value: Int8(bitPattern: byte))
            }
            _ = try await requireRuntime().appAttest.prepareEnrollment(
                serverChallengeSha256: challenge
            )
        }
    }
}

private enum RuntimeConfigurationError: LocalizedError {
    case missingBaseURL

    var errorDescription: String? {
        "Set TimaBaseURL in Info.plist"
    }
}
