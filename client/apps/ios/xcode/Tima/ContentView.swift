import CryptoKit
import ImageIO
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers
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
    @State private var showImagePicker = false
    @State private var mediaStatus = "No image upload"
    @State private var mediaRetryLocalId: String?

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
                            run("Signed out; encrypted offline cache wiped") {
                                try await requireRuntime().logout()
                                password = ""
                                otp = ""
                                compose = ""
                                editedText = ""
                                editingMessage = nil
                                mediaStatus = "No image upload"
                                mediaRetryLocalId = nil
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
                    Button("Attach encrypted image") {
                        showImagePicker = true
                    }
                    .disabled(viewState?.sendEnabled != true || viewState?.activeChatId == nil)
                    .accessibilityIdentifier("media.attach")
                    Text(mediaStatus)
                        .accessibilityIdentifier("media.upload.state")
                    if let localId = mediaRetryLocalId {
                        Button("Retry image upload") {
                            run("Encrypted image retry completed") {
                                try await requireRuntime().retryMedia(localId: localId)
                                await MainActor.run { refreshMediaState() }
                            }
                        }
                        .accessibilityIdentifier("media.retry")
                    }
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
            .sheet(isPresented: $showImagePicker) {
                PrivateImagePicker { result in
                    showImagePicker = false
                    switch result {
                    case .success(let source):
                        sendPickedImage(source)
                    case .failure(let error):
                        status = "Failed: \(error.localizedDescription)"
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func messageRow(_ message: MessageBubble) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text((message.text.isEmpty && message.attachment != nil ? "Encrypted image" : message.text) +
                 (message.edited ? " (edited)" : ""))
            if let attachment = message.attachment {
                PrivateMediaImageView(runtime: runtime, attachment: attachment)
                    .accessibilityIdentifier("media.thumbnail.\(message.localId)")
            }
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

    private func sendPickedImage(_ picked: Data) {
        Task {
            status = "Normalizing image…"
            do {
                var source = picked
                defer { source.resetBytes(in: 0..<source.count) }
                let normalized = try await Task.detached {
                    try normalizePrivateImage(source)
                }.value
                var thumbnail = normalized.thumbnail.data
                var preview = normalized.preview.data
                var full = normalized.full.data
                defer {
                    thumbnail.resetBytes(in: 0..<thumbnail.count)
                    preview.resetBytes(in: 0..<preview.count)
                    full.resetBytes(in: 0..<full.count)
                }
                _ = try await requireRuntime().sendNormalizedImage(
                    thumbnail: thumbnail.kotlinBytes(),
                    thumbnailWidth: Int32(normalized.thumbnail.width),
                    thumbnailHeight: Int32(normalized.thumbnail.height),
                    preview: preview.kotlinBytes(),
                    previewWidth: Int32(normalized.preview.width),
                    previewHeight: Int32(normalized.preview.height),
                    full: full.kotlinBytes(),
                    fullWidth: Int32(normalized.full.width),
                    fullHeight: Int32(normalized.full.height)
                )
                status = "Encrypted image queued"
                refreshMediaState()
                render()
            } catch {
                status = "Failed: \(error.localizedDescription)"
                refreshMediaState()
            }
        }
    }

    @MainActor
    private func refreshMediaState() {
        guard let state = runtime?.mediaState() else {
            mediaStatus = "No image upload"
            mediaRetryLocalId = nil
            return
        }
        if let queueState = state.state {
            mediaStatus = "\(queueState): \(state.completedVariants)/\(state.totalVariants)" +
                (state.errorCode.map { " · \($0)" } ?? "")
        } else {
            mediaStatus = "No image upload"
        }
        mediaRetryLocalId = state.retryable ? state.localId : nil
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
        refreshMediaState()
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

private struct NormalizedVariant {
    var data: Data
    let width: Int
    let height: Int
}

private struct NormalizedPrivateImage {
    var thumbnail: NormalizedVariant
    var preview: NormalizedVariant
    var full: NormalizedVariant
}

private enum PrivateImageError: LocalizedError {
    case invalid, executable, oversized, dimensions, encode
    var errorDescription: String? {
        switch self {
        case .invalid: return "Invalid or unsupported image"
        case .executable: return "Executable input is blocked"
        case .oversized: return "Image exceeds 25 MiB"
        case .dimensions: return "Unsupported image dimensions"
        case .encode: return "JPEG normalization failed"
        }
    }
}

private func normalizePrivateImage(_ sourceData: Data) throws -> NormalizedPrivateImage {
    guard !sourceData.isEmpty, sourceData.count <= 25 * 1024 * 1024 else {
        throw PrivateImageError.oversized
    }
    let prefix = [UInt8](sourceData.prefix(4))
    if (prefix.count >= 2 && prefix[0] == 0x4d && prefix[1] == 0x5a) ||
        (prefix.count >= 4 && prefix == [0x7f, 0x45, 0x4c, 0x46]) ||
        (prefix.count >= 2 && prefix[0] == 0x23 && prefix[1] == 0x21) {
        throw PrivateImageError.executable
    }
    guard let source = CGImageSourceCreateWithData(sourceData as CFData, nil),
          let type = CGImageSourceGetType(source),
          UTType(type as String)?.conforms(to: .image) == true,
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
          let width = properties[kCGImagePropertyPixelWidth] as? Int,
          let height = properties[kCGImagePropertyPixelHeight] as? Int else {
        throw PrivateImageError.invalid
    }
    guard width > 0, height > 0, width <= 20_000, height <= 20_000,
          Int64(width) * Int64(height) <= 80_000_000 else {
        throw PrivateImageError.dimensions
    }
    return NormalizedPrivateImage(
        thumbnail: try normalizeVariant(source, sourceWidth: width, sourceHeight: height, maxEdge: 40),
        preview: try normalizeVariant(source, sourceWidth: width, sourceHeight: height, maxEdge: 320),
        full: try normalizeVariant(source, sourceWidth: width, sourceHeight: height, maxEdge: 1280)
    )
}

private func normalizeVariant(
    _ source: CGImageSource,
    sourceWidth: Int,
    sourceHeight: Int,
    maxEdge: Int
) throws -> NormalizedVariant {
    let target = min(maxEdge, max(sourceWidth, sourceHeight))
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: target,
        kCGImageSourceShouldCacheImmediately: true
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
        throw PrivateImageError.invalid
    }
    guard image.width > 0, image.height > 0,
          image.width <= maxEdge, image.height <= maxEdge else {
        throw PrivateImageError.dimensions
    }
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let rect = CGRect(x: 0, y: 0, width: image.width, height: image.height)
    let renderer = UIGraphicsImageRenderer(
        size: CGSize(width: image.width, height: image.height),
        format: format
    )
    let normalized = renderer.image { context in
        UIColor.black.setFill()
        context.fill(rect)
        UIImage(cgImage: image).draw(in: rect)
    }
    guard let jpeg = normalized.jpegData(compressionQuality: 0.88) else {
        throw PrivateImageError.encode
    }
    return NormalizedVariant(data: jpeg, width: image.width, height: image.height)
}

private struct PrivateImagePicker: UIViewControllerRepresentable {
    let completion: (Result<Data, Error>) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(completion: completion) }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .images
        configuration.selectionLimit = 1
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let completion: (Result<Data, Error>) -> Void
        init(completion: @escaping (Result<Data, Error>) -> Void) {
            self.completion = completion
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            guard let provider = results.first?.itemProvider else {
                completion(.failure(PrivateImageError.invalid))
                return
            }
            provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, error in
                let result: Result<Data, Error>
                do {
                    if let error = error { throw error }
                    guard let safeURL = url else { throw PrivateImageError.invalid }
                    result = .success(try boundedRead(safeURL))
                } catch {
                    result = .failure(error)
                }
                DispatchQueue.main.async { self.completion(result) }
            }
        }
    }
}

private func boundedRead(_ url: URL) throws -> Data {
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    var result = Data()
    do {
        while let chunk = try handle.read(upToCount: 64 * 1024), !chunk.isEmpty {
            guard result.count + chunk.count <= 25 * 1024 * 1024 else {
                throw PrivateImageError.oversized
            }
            result.append(chunk)
        }
        return result
    } catch {
        result.resetBytes(in: 0..<result.count)
        throw error
    }
}

private struct PrivateMediaImageView: View {
    let runtime: IosPhase1Runtime?
    let attachment: MediaAttachmentUi
    @State private var thumbnail: UIImage?
    @State private var preview: UIImage?
    @State private var showPreview = false
    @State private var failed = false

    var body: some View {
        Button {
            Task { await loadPreview() }
        } label: {
            if let thumbnail {
                Image(uiImage: thumbnail)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 80, maxHeight: 80)
            } else if failed {
                Text("Image unavailable")
            } else {
                ProgressView()
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Open encrypted image preview")
        .task(id: attachment.mediaId) { await loadThumbnail() }
        .sheet(isPresented: $showPreview, onDismiss: { preview = nil }) {
            if let preview {
                Image(uiImage: preview)
                    .resizable()
                    .scaledToFit()
                    .accessibilityIdentifier("media.preview")
            }
        }
    }

    @MainActor
    private func loadThumbnail() async {
        guard thumbnail == nil, let runtime else { return }
        do {
            let bytes = try await runtime.downloadMedia(attachment: attachment, variant: "thumbnail")
            var data = bytes.swiftData()
            defer { data.resetBytes(in: 0..<data.count) }
            thumbnail = try checkedImage(
                data,
                expectedWidth: Int(attachment.thumbnail.width),
                expectedHeight: Int(attachment.thumbnail.height),
                maxEdge: 40
            )
        } catch {
            failed = true
        }
    }

    @MainActor
    private func loadPreview() async {
        guard let runtime else { return }
        do {
            let bytes = try await runtime.downloadMedia(attachment: attachment, variant: "preview")
            var data = bytes.swiftData()
            defer { data.resetBytes(in: 0..<data.count) }
            preview = try checkedImage(
                data,
                expectedWidth: Int(attachment.preview.width),
                expectedHeight: Int(attachment.preview.height),
                maxEdge: 320
            )
            showPreview = true
        } catch {
            failed = true
        }
    }
}

private func checkedImage(
    _ data: Data,
    expectedWidth: Int,
    expectedHeight: Int,
    maxEdge: Int
) throws -> UIImage {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
          let type = CGImageSourceGetType(source),
          type as String == UTType.jpeg.identifier,
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
          let width = properties[kCGImagePropertyPixelWidth] as? Int,
          let height = properties[kCGImagePropertyPixelHeight] as? Int,
          width == expectedWidth, height == expectedHeight,
          width > 0, height > 0, width <= maxEdge, height <= maxEdge,
          width * height <= 4_000_000,
          let cgImage = CGImageSourceCreateImageAtIndex(source, 0, [
              kCGImageSourceShouldCacheImmediately: true
          ] as CFDictionary) else {
        throw PrivateImageError.invalid
    }
    return UIImage(cgImage: cgImage)
}

private extension Data {
    func kotlinBytes() -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            result.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return result
    }
}

private extension KotlinByteArray {
    func swiftData() -> Data {
        var result = Data(count: Int(size))
        result.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            for index in 0..<Int(size) {
                base[index] = UInt8(bitPattern: get(index: Int32(index)))
            }
        }
        return result
    }
}

private enum RuntimeConfigurationError: LocalizedError {
    case missingBaseURL

    var errorDescription: String? {
        "Set TimaBaseURL in Info.plist"
    }
}
