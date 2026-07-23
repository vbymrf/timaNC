# Client Hardening

> Platform attestation details: [client-attestation.md](./client-attestation.md).

## 1. Platform matrix

| Control | Android | iOS | Windows |
|---------|---------|-----|---------|
| Root/jailbreak detect | Play Integrity + local checks | App Attest + jailbreak API | Best-effort |
| App integrity | Play Integrity | App Attest | N/A |
| Device trust | Play Integrity token | DCAppAttest | QR via mobile |
| Key storage | Android Keystore (TEE) | Secure Enclave / Keychain | DPAPI + linked session |
| Biometric lock | BiometricPrompt | LocalAuthentication | Windows Hello |
| Cert pinning | Network security config | URLSession pinning | Custom Ktor engine |
| Screenshot block | FLAG_SECURE (optional per chat) | UITextField secure (limited) | Policy flag only |
| Clipboard | Clear on background (E2E) | iOS pasteboard TTL | Same |

## 2. Attestation flow

```kotlin
// commonMain
interface AttestationProvider {
  suspend fun getAttestationToken(action: String): String
}
```

| Action | Required attestation |
|--------|---------------------|
| register | Yes |
| login | Yes |
| send_message (private) | Yes |
| send_message (public) | Recommended |
| link_windows | Mobile only |

**Server:** `/v1/verify/attestation/ios`, `/v1/verify/integrity/android` — cache result 24h per device.

**Fail-closed:** invalid attestation → 403 `ATTESTATION_FAILED`.

## 3. Local database protection

| Layer | Option |
|-------|--------|
| SQLDelight file | App sandbox |
| Additional | SQLCipher with key from Keystore |
| Ratchet state | Encrypted blob inside DB |

User password (optional) derives SQLCipher key via PBKDF2.

## 4. Memory

- Zeroize `ByteArray` message keys after use (best effort on JVM/Native).
- Avoid logging ciphertext or keys; redact in crash reports.

### 4.1. Crypto target policy (side-channel, R-4 · [ADR-0017](../adr/0017-kodium-crypto-hardening.md))

- Операции с секретными ключами (identity, `deriveKeyFromPassword`, ratchet, ML-KEM decapsulate, key commitment) выполняются **только** на native/JVM-таргетах.
- Браузерный JS/Wasm для операций с секретами **запрещён** — pure-Kotlin/JS не гарантирует constant-time (JIT/GC); Web client вне scope ([threat-model.md](./threat-model.md) §1).
- Долговременные ключи — Keystore/Secure Enclave; время жизни секрета в heap минимизируется, буферы зануляются.
- Constant-time на целевых платформах — обязательный пункт независимого аудита ([ADR-0005](../adr/0005-kodium-readiness-gate.md)).

## 5. Backup

| Platform | Policy |
|----------|--------|
| Android | `allowBackup=false` for crypto prefs |
| iOS | Exclude Keychain items from iCloud backup |
| Windows | No roaming keys |

## 5.1. Document и media safety

- Private media валидируется дважды: sender до шифрования и recipient после расшифрования, до decode/open.
- Проверяются MIME declaration против magic bytes, размер, dimensions/frame limits, успешный безопасный decode и допустимый codec.
- Decode выполняется sandboxed/least-privilege; auto-open запрещён.
- Executable, macro, active HTML/SVG, event handlers, polyglot executable и `javascript:` URL отклоняются. DocumentV2 `code` всегда inert text.
- Принимаются только `thumbnail`, `preview`, `full`; `original` не запрашивается и не сохраняется media pipeline.
- Открытые `markup`/`metadata` используются только после проверки Ed25519 signature и AEAD AAD binding.

## 6. Obfuscation

- R8/ProGuard on Android release.
- Symbol stripping iOS release.
- **Not** relied upon for secret storage — assume client extractable.

## 7. API secrets

Golden rule from `Тима.docx`:

- No OpenAI/Maps keys in client — backend proxy.
- HMAC client secret: rotate via force update every 14d (Phase 2).

## 8. Windows trust anchor

See [doc_UI/24-device-linking.md](../doc_UI/24-device-linking.md):

1. Windows generates keypair.
2. Mobile (attested) confirms.
3. Server marks session `trusted_via_phone`.

Without mobile: read-only or blocked — **no** Windows-only registration for private send.

## 9. Ссылки

- [client-attestation.md](./client-attestation.md)
- [threat-model.md](./threat-model.md)
- [key-lifecycle.md](./key-lifecycle.md)
- [ADR-0017](../adr/0017-kodium-crypto-hardening.md)
- [kodium-security-audit.md](./kodium-security-audit.md)
