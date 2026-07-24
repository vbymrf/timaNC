# Клиентская архитектура (KMP)

## 1. Модули Gradle

```
tima-client/
├── shared/
│   ├── domain/          # Use cases, entities
│   ├── data/            # Repositories, API clients
│   ├── crypto/          # messenger-crypto (Kodium wrapper)
│   ├── document/        # DocumentV2, canonical JSON, immutable revisions
│   ├── media/           # validation, 3 variants, E2E/public upload clients
│   ├── sync/            # Outbox, delta sync, conflict rules
│   ├── database/        # SQLDelight schemas
│   └── network/         # Ktor client, WS
├── feature-group/       # Переписка, GK
├── feature-channel/     # Каналы (метаданные)
├── feature-voiceroom/   # Аудио-чаты
├── feature-community/   # Сообщества
├── feature-editor/      # Посты: канал / статья / медиа / история
├── feature-inbox/       # Окно 4: threads, events, preferences
├── feature-reactions/   # Эмоции 1–9, [+]/[−]
├── core-publications/   # posts, drafts
├── core-comments/
├── core-attributes/     # хэштеги, жанры, чипсы
├── core-feeds/          # ленты, полки
├── composeApp/          # Compose Multiplatform UI
├── androidApp/
├── iosApp/
└── desktopApp/          # Windows (JVM/Native)
```

Границы feature-модулей — [module-boundaries.md](./module-boundaries.md); Konsist в CI запрещает cross-feature imports.

## 2. Слои

```mermaid
flowchart TB
  UI[Compose_UI]
  VM[ViewModels]
  UC[UseCases]
  REPO[Repositories]
  LOCAL[SQLDelight]
  REMOTE[Ktor_WS]
  CRYPTO[messenger_crypto]
  PLATFORM[expect_actual]

  UI --> VM --> UC --> REPO
  REPO --> LOCAL
  REPO --> REMOTE
  REPO --> CRYPTO
  CRYPTO --> PLATFORM
```

## 3. Offline-first

1. **Write:** UI → local SQLite (pending) → background sync → server ack → update state.
2. **Read:** SQLite primary; gap fill via sync API.
3. **Crypto:** private DocumentV2 имеет `metadata.content_mode=private`; присутствующие `encrypted_nodes`/`encrypted_metadata` шифруются до enqueue, открытые `markup`/`metadata` входят в signature/AAD; ключи в Secure Enclave / Android Keystore / Windows DPAPI+phone trust.
4. **Revision:** edit создаёт новую immutable revision; pending revision не переписывает уже подтверждённую.

### 3.1. Текущий Phase 1 UI data foundation

`client/modules/core/core-data` содержит общие immutable UI-модели, контракты
session/chat/thread/send, encrypted REST adapter и `Phase1MessagingCoordinator`,
который реализует `RestGapFill`. Production crypto adapter использует
`MessengerCrypto`/`RestCryptoTransportAdapter` и fail-closed блокирует send, если
нет device identity, directory keys или проверенного escrow config.

Android Views, iOS SwiftUI и Windows Swing runtime используют
`EncryptedSqlDelightMessagingCache`: app-private SQLite хранит только
идентификаторы/порядок для lookup, а полный `ChatPreview` и `MessageBubble`
записывается как Kodium authenticated ciphertext. Случайный 32-byte row key
`messaging-cache-row-key-v1` защищён Android Keystore-backed secure storage,
iOS Keychain или Windows DPAPI. Формат ciphertext versioned; неизвестная версия,
повреждённый key/row и расхождение открытых индексов с decrypted payload
fail-closed. Logout транзакционно удаляет cache rows и затем protected key.
`NonDurableInMemoryMessagingCache` остаётся для детерминированных unit tests.

iOS development OTP/HMAC и development escrow root разрешены только сочетанием
Xcode `DEBUG` и явного `TimaEnableDevelopmentAuth`; production profile использует
App Attest boundary и fail-closed блокирует private writes без provisioned
verified escrow roots. Без APNs UI честно сообщает только foreground/resume
catch-up, не background wake.

Windows сохраняет существующий QR start/claim flow и использует созданные им
device seed и linked-user session: seed и refresh/session credentials защищены
DPAPI, параллельная identity не создаётся. После restore refresh token
ротируется, а Swing shell предоставляет chat list/create/open, history,
encrypted send/retry/edit/read/delete и logout с полной очисткой encrypted
offline cache и DPAPI-protected row key. Development escrow fixture требует одновременно Gradle build flag
`tima.windows.enableDevelopmentEscrow=true` и runtime environment opt-in
`TIMA_WINDOWS_ENABLE_DEVELOPMENT_ESCROW=true`; только в этом режиме разрешён
loopback HTTP. Обычная MSIX/app-image сборка оставляет fixture выключенным и
fail-closed блокирует private writes без verified production escrow roots.
WNS не заявлен: UI честно показывает foreground periodic authenticated REST
catch-up с интервалом 60 секунд.

Durable decrypted UI history и post-encryption send/retry outbox реализованы на
всех трёх клиентах. После reservation + encryption канонические ciphertext
bytes и idempotency key записываются до первой попытки, а restart/resume
восстанавливает stale `sending` и due retry без повторного шифрования.
Reservation failure остаётся до durable boundary; полностью offline
composition/send не заявлен.

`client/modules/core/core-media` теперь реализует private 1:1 single-image
alpha: строгую модель трёх JPEG variants, отдельные random media keys, versioned
Kodium AEAD, ciphertext SHA-256/size manifest, строгие init/PUT/complete/access
codec, restart-safe queue и post-decrypt validation. Полная retry-запись
(включая keys и presigned URL) зашифрована session-scoped row key; app-private
variant files содержат только ciphertext. Presigned absolute requests не
получают TIMA Authorization, разрешают только HTTPS (или explicit loopback dev)
и fail-closed при redirect/неожиданном variant. Android и Windows имеют picker,
normalizer, progress/retry state, thumbnail и in-app preview. iOS source теперь
содержит PHPicker без broad permission, bounded read, ImageIO/UIKit normalization,
SQLite ciphertext blob store и SwiftUI thumbnail/in-app preview. KMP iOS targets
компилируются, но Xcode/Swift archive в текущей Windows-среде не проверен; поэтому
iOS completion и hosted native acceptance остаются блокерами Phase 1.

## 4. Platform adapters (`expect`/`actual`)

| Capability | Android | iOS | Windows |
|------------|---------|-----|---------|
| Secure key storage | Keystore | Keychain / Secure Enclave | DPAPI + linked phone key |
| Attestation | Play Integrity | App Attest | QR trust (no attestation) |
| Push | FCM + UnifiedPush fallback | APNs + foreground/resume catch-up | Foreground WS / periodic REST; optional future WNS |
| Camera/Mic | CameraX | AVFoundation | MediaFoundation |
| LiveKit | client-sdk-android | client-sdk-swift | official LiveKit C++ SDK 1.0 через узкий JNI/JNA adapter |
| Biometric lock | BiometricPrompt | LocalAuthentication | Windows Hello |

Все push/WS/app-resume сигналы входят в единый sync coordinator; transport не
является источником сообщения. Полная схема и ограничение iOS без APNs:
[hybrid-notification-delivery.md](./hybrid-notification-delivery.md).

## 4.1. LiveKit на Windows

Звонки обязательны уже в Communication MVP. Windows-клиент использует **официальный LiveKit C++ SDK 1.0**; нативные типы SDK не выходят за узкий JNI/JNA adapter в `CallRepository`. `shared` и UI видят только доменные модели звонка, поэтому обновление SDK не меняет публичную KMP-границу. Основной формат поставки — подписанный **MSIX**; portable-сборка не является release gate.

## 4.2. Attestation и деградация vendor

- iOS отправляет proof только в `POST /v1/verify/attestation/ios`, Android — только в `POST /v1/verify/integrity/android`.
- При недоступности Apple/Google ранее доверенное устройство может получить конфигурируемый grace (не более 30 дней) и продолжить private send.
- Grace не разрешает новую регистрацию или linking устройства. Failed/forged proof никогда не получает grace и блокируется.
- Клиент явно различает `vendor_unavailable`, `proof_failed` и `proof_forged`; общий retry не должен превращать криптографический отказ в vendor outage.

## 4.3. Runtime URLs

| Env | REST base | WebSocket | LiveKit |
|-----|-----------|-----------|---------|
| dev | `https://api.dev.tima.example/v1` | `wss://api.dev.tima.example/v1/ws` | `wss://rtc.dev.tima.example` |
| beta | `https://api.beta.tima.example/v1` | `wss://api.beta.tima.example/v1/ws` | `wss://rtc.beta.tima.example` |
| staging | `https://api.staging.tima.example/v1` | `wss://realtime.staging.tima.example/v1/ws` | `wss://rtc.staging.tima.example` |
| production | `https://api.tima.example/v1` | `wss://realtime.tima.example/v1/ws` | `wss://rtc.tima.example` |

Operational endpoints (`/healthz`, `/readyz`, `/metrics`) не входят в client API и не получают `/v1`.

## 5. Локальная БД (SQLDelight)

| Table group | Содержимое |
|-------------|------------|
| `ui_message_cache` | lookup columns + encrypted complete `MessageBubble`; не является durable send outbox |
| `ui_chat_cache` | lookup/order columns + encrypted complete `ChatPreview` |
| `crypto_sessions` | ratchet state (encrypted blob) |
| `identity` | public keys, device id |
| `search_fts` | decrypted index (private chats only) |
| `media_queue` | encrypted manifest/keys/URLs для ровно `thumbnail/preview/full`, retry state; без Original/chunks |
| `sync_outbox` | canonical ciphertext request bytes + idempotency/path/retry state; no private plaintext |
| `inbox_local` | кэш inbox_threads/events, read-state |
| `emotions_local` | pending emotion/recommendation sync |
| `attributes_cache` | жанры, подписки на атрибуты |
| `shelf_private` | encrypted private shelf blob + shelf_key wraps |

Схема: [client-sqlite details](../04-data/sync-offline.md).

## 5.1. DocumentV2 и media safety

- Legacy offset/length entities и placeholder не используются.
- `metadata` обязателен (`format_version=2`, `revision_number`, `content_mode`); для групп `content_mode` сверяется с `groups.kind`.
- Текстовый массив, `markup` и `encrypted_metadata` опциональны. Serializer опускает отсутствующее поле и локально хранит `NULL`, не `[]`/`{}`/пустой ciphertext; media-only опускает текстовый массив.
- `has_content` требует непустой текст или реальный media/content binding; пустой layout невалиден.
- Public документы не принимают encrypted-поля и используют `text_link`; private чувствительные ссылки используют `secret_ref`.
- Executable/active content блокируется; `code` рендерится как inert text.
- Private media: sender валидирует source и создаёт три variant до E2E encryption; recipient независимо валидирует MIME/magic bytes, limits, dimensions и decode после decrypt.
- Public media клиент отправляет source только в quarantine pipeline; публикация возможна после server AV/sanitize/transcode.
- Init/complete/read идут через domain endpoints с auth; presigned URL TTL ≤15 минут.

## 6. Навигация

Соответствует [doc_UI/01-app-shell.md](../doc_UI/01-app-shell.md): 5 окон + окно 0 (звонок) + 6–7 блогер.

- **Navigation:** Compose Navigation / Voyager / custom — ADR при выборе библиотеки.
- **State restoration:** save scroll per window in local prefs.

## 7. Зависимости (versions из `Тима.docx`)

| Lib | Version |
|-----|---------|
| Kotlin | 2.0.20+ |
| Compose Multiplatform | 1.10.3+ |
| Kodium | 1.0.0 |
| SQLDelight | latest stable |
| Ktor | 3.x |
| Koin | 4.x |

## 8. Ссылки

- [module-boundaries.md](./module-boundaries.md)
- [feed-ranking.md](../04-data/feed-ranking.md)
- [crypto-protocol.md](../03-security/crypto-protocol.md)
- [multi-device](../04-data/sync-offline.md#4-multi-device)
- [client-hardening.md](../03-security/client-hardening.md)
- [hybrid-notification-delivery.md](./hybrid-notification-delivery.md)
