import DeviceCheck
import Foundation
import Security

enum PlatformAdapterError: LocalizedError {
    case unavailable(String)
    case invalidProof

    var errorDescription: String? {
        switch self {
        case .unavailable(let reason): return reason
        case .invalidProof: return "The platform service returned an invalid proof."
        }
    }
}

final class AppAttestAdapter {
    private let service = DCAppAttestService.shared
    private let keychain = KeychainSecretStore(service: "com.tima.messnc.app-attest")
    private let keyAlias = "key-id-v1"

    func assertion(
        requestBodySHA256: Data,
        completion: @escaping (Result<(keyID: String, proof: Data), Error>) -> Void
    ) {
        guard requestBodySHA256.count == 32, service.isSupported else {
            completion(.failure(PlatformAdapterError.unavailable("App Attest is unavailable.")))
            return
        }
        do {
            if let stored = try keychain.read(keyAlias),
               let keyID = String(data: stored, encoding: .utf8),
               !keyID.isEmpty {
                service.generateAssertion(keyID, clientDataHash: requestBodySHA256) {
                    assertion, error in
                    guard let assertion, !assertion.isEmpty else {
                        completion(.failure(error ?? PlatformAdapterError.invalidProof))
                        return
                    }
                    completion(.success((keyID, assertion)))
                }
                return
            }
        } catch {
            completion(.failure(error))
            return
        }

        service.generateKey { [service, keychain, keyAlias] keyID, error in
            guard let keyID, !keyID.isEmpty else {
                completion(.failure(error ?? PlatformAdapterError.invalidProof))
                return
            }
            service.attestKey(keyID, clientDataHash: requestBodySHA256) { object, error in
                guard let object, !object.isEmpty else {
                    completion(.failure(error ?? PlatformAdapterError.invalidProof))
                    return
                }
                do {
                    try keychain.write(Data(keyID.utf8), alias: keyAlias)
                    // The verifier registers this first App Attest object; later calls are assertions.
                    completion(.success((keyID, object)))
                } catch {
                    completion(.failure(error))
                }
            }
        }
    }
}

final class KeychainSecretStore {
    private let service: String

    init(service: String) {
        precondition(!service.isEmpty)
        self.service = service
    }

    func write(_ value: Data, alias: String) throws {
        guard !value.isEmpty, valid(alias) else { throw PlatformAdapterError.invalidProof }
        var query = base(alias)
        SecItemDelete(query as CFDictionary)
        query[kSecValueData as String] = value
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        query[kSecAttrSynchronizable as String] = kCFBooleanFalse
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw PlatformAdapterError.unavailable("Keychain write failed (\(status)).")
        }
    }

    func read(_ alias: String) throws -> Data? {
        guard valid(alias) else { throw PlatformAdapterError.invalidProof }
        var query = base(alias)
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw PlatformAdapterError.unavailable("Keychain read failed (\(status)).")
        }
        return data
    }

    func delete(_ alias: String) throws {
        guard valid(alias) else { throw PlatformAdapterError.invalidProof }
        let status = SecItemDelete(base(alias) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw PlatformAdapterError.unavailable("Keychain delete failed (\(status)).")
        }
    }

    private func base(_ alias: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: alias,
        ]
    }

    private func valid(_ alias: String) -> Bool {
        alias.range(of: #"^[a-z0-9-]{1,100}$"#, options: .regularExpression) != nil
    }
}

final class ApnsLifecycle {
    static let shared = ApnsLifecycle()
    private let lock = NSLock()
    private var token: String?
    private var failure: Error?

    func didRegister(_ data: Data) {
        lock.withLock {
            token = data.map { String(format: "%02x", $0) }.joined()
            failure = nil
        }
    }

    func didFail(_ error: Error) {
        lock.withLock {
            token = nil
            failure = error
        }
    }

    func currentToken() throws -> String {
        try lock.withLock {
            if let token, !token.isEmpty { return token }
            throw failure ?? PlatformAdapterError.unavailable("APNs has not registered a token.")
        }
    }
}

final class GenericPushBoundary {
    static let shared = GenericPushBoundary()
    var onWakeForChat: ((String) -> Void)?

    func receive(_ payload: [AnyHashable: Any]) {
        let allowed = Set(["type", "chat_id", "preview", "encrypted", "collapse_key", "aps"])
        guard Set(payload.keys.compactMap { $0 as? String }).isSubset(of: allowed),
              payload["type"] as? String == "message",
              payload["preview"] as? String == "Новое сообщение",
              payload["encrypted"] as? Bool == true,
              let chatID = payload["chat_id"] as? String,
              payload["collapse_key"] as? String == "chat:\(chatID)"
        else { return }
        onWakeForChat?(chatID)
    }
}
