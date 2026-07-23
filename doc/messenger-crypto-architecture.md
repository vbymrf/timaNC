# Архитектура защиты сообщений мессенджера на базе Kodium

> ⛔ **NONCANONICAL LEGACY OVERVIEW — НЕ ИСПОЛЬЗОВАТЬ как wire/API/DDL/security/retention контракт.**  
> **Каноническая спецификация:** [03-security/crypto-protocol.md](./03-security/crypto-protocol.md); escrow/retention — [03-security/escrow-legal-access.md](./03-security/escrow-legal-access.md) и [04-data/retention-archival.md](./04-data/retention-archival.md). При любом расхождении этот документ проигрывает каноническим спецификациям и ADR.  
> **Решения:** [ADR-0014](./adr/0014-participant-e2e-and-recovery.md), [ADR-0015](./adr/0015-document-v2-and-media-pipeline.md), [ADR-0016](./adr/0016-escrow-key-hierarchy-and-threshold.md), [ADR-0017](./adr/0017-kodium-crypto-hardening.md)  
> **Решения:** [ADR-0004 controlled escrow](./adr/0004-controlled-escrow.md) (amended by [ADR-0016](./adr/0016-escrow-key-hierarchy-and-threshold.md): иерархия ключей epoch×shard + threshold-decap + scope-инвариант) · [ADR-0005 Kodium gate](./adr/0005-kodium-readiness-gate.md) · [ADR-0017 crypto hardening](./adr/0017-kodium-crypto-hardening.md) (key commitment) · аудит [kodium-security-audit.md](./03-security/kodium-security-audit.md)  
> **Системный контекст:** [02-architecture/system-architecture.md](./02-architecture/system-architecture.md)

Документ описывает **исторический обзор** реализации шифрования. Актуальная каноника:

- **Participant E2E** — основной контур (X3DH + Double Ratchet для 1:1; Sender Keys для групп).
- **Wrapped keys** — fallback доставки, не единственный recovery.
- **Escrow** — обязателен; полный юридический доступ включая soft-deleted.
- **Recovery** — старое устройство → peer с согласием → optional phrase backup.

См. [crypto-protocol.md](./03-security/crypto-protocol.md); исследовательский источник — `е2е личная переписка .md`.

**Не является:** формальной спецификацией протокола, SLA хранения или production gate. Оценки объёма данных (Telegram-style) — **иллюстрации**, требуют load-test validation.

### Каноническое уточнение DocumentV2/media

Все приведённые ниже legacy-примеры `encrypted_payload`, offset/length entities, placeholder, generic `/messages`/`/media` и CAS по plaintext hash **не являются wire/storage контрактом**. Канон:

- private сохраняет participant E2E из ADR-0014; `encrypted_nodes`, `markup` и `encrypted_metadata` optional (SQL `NULL`/API omit), `metadata` обязателен;
- public хранит optional plaintext `nodes`; encrypted-поля отсутствуют; DocumentV2 полностью заменяет offset/length/placeholder;
- presence bitmap, nullable `markup` и обязательный `metadata` входят в signature/AEAD AAD; revisions immutable;
- `[]`, `{}` и пустой ciphertext нормализуются в отсутствие; `has_content` требует непустой text array или реальный media/content binding;
- private media: sender + recipient client validation; public media: server AV/sanitize/transcode;
- ровно `thumbnail`, `preview`, `full`, без `original`; executable/active content запрещён;
- media auth обязателен, presigned URL ≤15 минут; retention и legal hold применяются к revisions/variants;
- используются domain endpoints из ADR-0015, а не generic routes.

---

## 1. Текущее состояние репозитория

**Kodium** — это pure Kotlin Multiplatform **криптографическая библиотека** (`eu.livotov.labs:kodium:1.0.0`), а не готовый мессенджер. В репозитории нет backend-сервисов, мобильных клиентов, схем БД и транспортного слоя.

### Что уже реализовано в Kodium

| Компонент | Файлы | Статус |
|-----------|-------|--------|
| TweetNaCl (Box, SecretBox, Sign) | `Kodium.kt`, `core/NaCl.kt` | ✅ Готово |
| X3DH (PreKeys, Signed PreKey) | `ratchet/X3DH.kt` | ✅ Готово |
| Double Ratchet (1:1 E2EE) | `ratchet/DoubleRatchet.kt` | ✅ Готово |
| PQ X3DH + PQ Double Ratchet | `ratchet/PQXDH.kt`, `PQDoubleRatchetSession.kt` | ✅ Готово |
| ML-KEM-768 (FIPS 203) | `core/MLKEM.kt`, `core/fips203/*` | ✅ Готово |
| HKDF, PBKDF2, Ed25519 | `ratchet/HKDF.kt`, `core/fips203/*` | ✅ Готово |
| Экспорт/импорт сессий и ключей | `DoubleRatchetSession.exportToEncryptedString()` | ✅ Готово |
| LRU для пропущенных ключей ratchet | `maxSkippedMessages = 2000` | ✅ Готово |
| Групповое шифрование (Sender Keys, MLS) | — | ❌ Нет |
| Escrow / wrapped keys | — | ❌ Нет |
| RSA-OAEP | — | ❌ Нет (Curve25519 + ML-KEM) |

### Ключевые API для интеграции

```kotlin
// Асимметричное шифрование (обёртка ключей)
Kodium.encryptToEncodedString(mySecretKey, theirPublicKey, data)

// Симметричное шифрование (контент сообщений)
Kodium.encryptSymmetricToEncodedString(passwordOrKey, data)

// 1:1 сессия с PFS
DoubleRatchetSession.initializeAsInitiator(sharedSecret, responderRatchetKey)
session.encryptToEncodedString(plaintext)

// Post-Quantum гибрид
Kodium.pqc.encryptToEncodedString(mySecretKey, theirPublicKey, data)
MLKEM.encapsulate(publicKey) // → (ciphertext, sharedSecret) для escrow

// Подпись сообщений
Kodium.signDetachedToEncodedString(privateKey, data)
```

---

## 2. Проблема чистого Signal Protocol

Классический Signal Stack (X3DH → Double Ratchet → PreKey Server) даёт сильную криптографию, но создаёт **операционный ад** на уровне приложения:

```
Устройство A (онлайн)                    Устройство B (офлайн → смена IP)
      │                                           │
      │  X3DH + первое сообщение                  │  не получило PreKey bundle
      │──────────────────────────────────────────►│  (или OTK уже consumed)
      │                                           │
      │  ratchet step N+3                           │  сессия на N, не на N+3
      │──────────────────────────────────────────►│  → сообщения в бездну
      │                                           │
      │  MAX_SKIP (2000) исчерпан                  │  невосстановимый desync
      └───────────────────────────────────────────┘
```

**Корневые причины**, которые ломают «ИИ-сгенерированные» реализации:

1. **Состояние сессии — единственный источник истины.** Потерял SQLite с ratchet state — потерял всё.
2. **PreKey lifecycle.** One-Time PreKeys расходуются; без серверной дисциплины ротации handshake падает.
3. **Нет fallback.** Если ratchet не может расшифровать — нет плана Б.
4. **Мультиустройство.** Каждое устройство — отдельная сессия; синхронизация усложняется экспоненциально.

Kodium реализует ratchet **корректно на уровне математики** (forward secrecy, break-in recovery, out-of-order до 2000 сообщений), но **не решает** транспортные и серверные проблемы — это задача слоя приложения.

---

## 3. Рекомендуемая архитектура: «Конверт + Обёртки + Escrow»

Вместо замены Double Ratchet на слабую схему — **добавить два независимых слоя**, которые делают ratchet опциональным для доставки, а не обязательным:

```
┌─────────────────────────────────────────────────────────────────┐
│                         КЛИЕНТ                                   │
│                                                                  │
│  ┌─────────────┐   ┌──────────────┐   ┌─────────────────────┐  │
│  │ Identity    │   │ Envelope     │   │ Escrow Module       │  │
│  │ KeyPair     │   │ (per-msg     │   │ ML-KEM encapsulate  │  │
│  │ (статичный) │   │  SymKey)     │   │ → Escrow_Public     │  │
│  └─────────────┘   └──────────────┘   └─────────────────────┘  │
│         │                  │                      │              │
│         │    ┌─────────────┴──────────────┐       │              │
│         │    │ Double Ratchet (опционально)│       │              │
│         │    │ PFS + break-in recovery    │       │              │
│         │    └──────────────────────────┘       │              │
└─────────┼──────────────────┼──────────────────────┼──────────────┘
          │                  │                      │
          ▼                  ▼                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                    СЕРВЕР (ретранслятор)                         │
│                                                                  │
│  messages:     ciphertext + header + escrow_blob                 │
│  wrapped_keys: Box(identityKey, message_key)  ← ПЛАН Б          │
│                                                                  │
└──────────────────────────────┬──────────────────────────────────┘
                               ▼
                    ┌─────────────────────┐
                    │ HSM / Nitro Enclave │
                    │ Escrow Private Key  │
                    │ M-of-N + audit log  │
                    └─────────────────────┘
```

### Принцип

> **Ratchet — для PFS. Серверные wrapped keys — для доставки. Escrow — для legal intercept.**

Три механизма **независимы**. Потеря ratchet-сессии не означает потерю сообщений.

---

## 4. Личные чаты (1:1)

### 4.1. Формат сообщения

```text
PersonalMessage {
    message_id:       uint64
    chat_id:          UUID
    sender_id:        UUID

    // Слой 1: Конверт (всегда)
    encrypted_payload: SecretBox(plaintext, message_key, nonce)
    message_key_id:    uint32          // версия ключа для ротации

    // Слой 2: Escrow (всегда)
    escrow_blob:       ML-KEM-encapsulate(Escrow_Public, message_key)
                       // ~1088 байт; альтернатива: RSA-OAEP в HSM

    // Слой 3: Ratchet (если сессия жива — опционально дублирует payload)
    ratchet_envelope:  RatchetMessage?   // null если сессия не установлена

    // Слой 4: Обёртка для получателя (ПЛАН Б — всегда на сервере)
    // Хранится отдельно в таблице personal_message_keys:
    wrapped_key:       Box(sender_ephemeral, recipient_identity, message_key)

    signature:         Ed25519(sender_signing_key, canonical_bytes)
}
```

### 4.2. Алгоритм отправки

```kotlin
// 1. Генерация ключа сообщения
val messageKey = Kodium.generateHighEntropyKey()
val plaintext = compressThenSerialize(text)  // ZSTD до шифрования → 1.3-1.5x

// 2. Шифрование контента (конверт)
val encryptedPayload = Kodium.encryptSymmetric(messageKey, plaintext)

// 3. Escrow blob
val (escrowCiphertext, _) = MLKEM.encapsulate(escrowPublicKey)
val escrowBlob = escrowCiphertext + Kodium.encryptSymmetric(derivedFromKEM, messageKey)

// 4. Обёртка для получателя (ПЛАН Б)
val senderEphemeral = Kodium.generateKeyPair()
val wrappedKey = Kodium.encrypt(senderEphemeral, recipientIdentityKey, messageKey)

// 5. Ratchet (опционально, для PFS)
val ratchetEnvelope = ratchetSession?.encrypt(encryptedPayload) // если сессия есть

// 6. Отправка на сервер: payload + escrow + wrapped_key + metadata
```

### 4.3. Алгоритм получения

```kotlin
fun decryptMessage(msg: PersonalMessage, session: DoubleRatchetSession?): ByteArray {
    // Путь A: ratchet (быстрый, PFS)
    msg.ratchetEnvelope?.let { envelope ->
        return session?.decrypt(envelope) ?: fallthrough
    }

    // Путь B: wrapped key с сервера (надёжный, async-safe)
    val messageKey = Kodium.decrypt(myIdentityKey, msg.wrappedKey)
    return Kodium.decryptSymmetric(messageKey, msg.encryptedPayload)
}
```

### 4.4. Почему это решает «асинхронный ад»

| Сценарий | Чистый Signal | Конверт + Обёртки |
|----------|--------------|-------------------|
| Получатель офлайн 3 дня | Ratchet ушёл вперёд; при MAX_SKIP — потеря | Wrapped key на сервере; расшифровка при подключении |
| Смена IP / переустановка | Сессия потеряна → re-handshake | Identity key + wrapped keys → история доступна |
| Оба онлайн | Ratchet, минимальный overhead | Ratchet (путь A) + wrapped key (фоново) |
| Потеря устройства без бэкапа | Всё потеряно | Восстановление через анклав + escrow + proof of ownership |

### 4.5. Реализация на Kodium

| Операция | Kodium API |
|----------|-----------|
| Identity key pair | `Kodium.generateKeyPair()` |
| Message key | `Kodium.generateHighEntropyKey()` |
| Шифрование контента | `Kodium.encryptSymmetric(key, data)` |
| Обёртка для получателя | `Kodium.encrypt(ephemeral, recipientPubKey, messageKey)` |
| Escrow blob | `MLKEM.encapsulate(escrowPubKey)` + symmetric wrap |
| Ratchet (PFS) | `DoubleRatchetSession.encryptToEncodedString()` |
| Подпись | `Kodium.signDetachedToEncodedString()` |
| PQ-вариант | `PQDoubleRatchetSession` + `Kodium.pqc.encrypt` для обёрток |

> **Замена RSA-OAEP на ML-KEM:** Kodium не содержит RSA. Для escrow рекомендуется ML-KEM-768 (`MLKEM.encapsulate`), что даёт эквивалентную семантику «encrypt-to-public» и совместимо с post-quantum требованиями. Если HSM требует RSA-OAEP — добавить тонкий JNI/wrapper к HSM API, Kodium используется только на клиенте.

---

## 5. Приватные группы

### 5.1. Двухуровневая модель (Sender Keys)

```
Уровень 1: Group SymKey (GK)
    Encrypted_Payload = SecretBox(plaintext, GK, nonce)

Уровень 2: Доставка GK участникам
    For each active_member:
        Wrapped_GK[member] = Box(member_identity_key, GK)

Уровень 3: Escrow (один blob на период GK)
    escrow_blob = ML-KEM-encapsulate(Escrow_Public, GK)
```

### 5.2. Ротация Group Key

| Событие | Действие |
|---------|----------|
| Каждые N сообщений (100) | Новый GK, новый escrow_blob, новые wrapped_GK |
| Вход участника | Немедленная ротация (post-compromise security) |
| Выход/блокировка | Ротация; wrapped_GK не создаётся для исключённых |
| Suspend (апелляция) | Старые wrapped_GK → archived, TTL 30 дней |

### 5.3. Формат группового сообщения

```text
GroupMessage {
    group_id:          UUID
    message_id:        uint64
    sender_id:         UUID
    gk_version:        uint32           // какой GK использовался

    encrypted_payload: SecretBox(plaintext, GK, nonce)
    escrow_blob:       ML-KEM(Escrow_Public, GK)  // ОДИН на период GK
    signature:         Ed25519(sender, payload)
}
```

Wrapped_GK для каждого участника хранится в `user_wrapped_keys_history` на сервере, не в каждом сообщении.

### 5.4. Реализация на Kodium

Групповой модуль — **новый код поверх Kodium** (в приложении, не в библиотеке):

```kotlin
class GroupKeyManager {
    fun rotateGroupKey(groupId: UUID, members: List<IdentityKey>): GroupKeyRotation {
        val gk = Kodium.generateHighEntropyKey()
        val wrapped = members.associate { member ->
            member.id to Kodium.encrypt(
                Kodium.generateKeyPair(),  // ephemeral
                member.publicKey,
                gk
            )
        }
        val escrowBlob = escrowModule.wrap(gk)
        return GroupKeyRotation(gk, wrapped, escrowBlob)
    }
}
```

---

## 6. Публичные чаты и каналы

Для публичных чатов E2E **не применяется**. Используется Telegram-подобная модель:

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  Клиент A    │     │   Сервер     │     │  Клиент B    │
│              │     │  (открытый   │     │              │
│  отправляет  │────►│   текст)     │────►│  кэш SQLite  │
│  plaintext   │     │  hot→warm→  │     │  локально    │
│              │     │  cold tiers  │     │              │
└──────────────┘     └──────────────┘     └──────────────┘
```

### Применимые оптимизации

| Оптимизация | Реализация |
|-------------|-----------|
| Бинарный формат | Protobuf + zstd |
| Шардирование | PostgreSQL/TimescaleDB по `chat_id` |
| Hot/warm/cold | S3 Lifecycle / MinIO |
| Дедупликация медиа | Content-addressable storage (SHA-256) |
| Forward-ссылки | `forward_from_msg_id` (экономия 1000x) |
| Append-only | Kafka → материализованные view |
| Клиентский кэш | SQLite на устройстве |

### Оценка объёма (активная группа 1000 чел.)

```text
10 000 msg/день × 20 байт (binary) = 200 KB/день
× 365 = ~70 MB/год на группу
× 10M групп = 700 TB/год → ~150 TB с zstd + dedup + 3x replication
```

---

## 7. Серверная инфраструктура

### 7.1. Микросервисы

```
API Gateway (Envoy/Kong)
    ├── Message Service     — хранение ciphertext + escrow_blob
    ├── Media Service       — S3/MinIO, ciphertext-only, CAS dedup
    ├── Group Service       — GK rotation, wrapped_GK distribution
    ├── User Service        — identity keys, device registry
    ├── Key Service         — wrapped_keys CRUD, НЕ хранит приватные ключи
    └── Escrow Service      — Nitro Enclave / HSM, M-of-N, audit
```

### 7.2. Схема БД (PostgreSQL)

```sql
-- Личные сообщения
CREATE TABLE personal_messages (
    message_id      BIGINT PRIMARY KEY,
    chat_id         UUID NOT NULL,
    sender_id       UUID NOT NULL,
    encrypted_payload BYTEA NOT NULL,
    escrow_blob     BYTEA NOT NULL,
    ratchet_envelope BYTEA,          -- nullable
    gk_version      INT DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    deleted         BOOLEAN DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    deleted_by      UUID
);

-- Обёртки ключей (ПЛАН Б)
CREATE TABLE personal_message_keys (
    message_id      BIGINT REFERENCES personal_messages,
    recipient_id    UUID NOT NULL,
    wrapped_key     BYTEA NOT NULL,   -- Box(identity, message_key)
  PRIMARY KEY (message_id, recipient_id)
);

-- Групповые ключи
CREATE TABLE group_key_history (
    group_id        UUID NOT NULL,
    gk_version      INT NOT NULL,
    escrow_blob     BYTEA NOT NULL,
    rotated_at      TIMESTAMPTZ NOT NULL,
    reason          TEXT,             -- 'periodic' | 'member_join' | 'member_leave'
  PRIMARY KEY (group_id, gk_version)
);

CREATE TABLE user_wrapped_keys (
    group_id        UUID NOT NULL,
    gk_version      INT NOT NULL,
    user_id         UUID NOT NULL,
    wrapped_gk      BYTEA NOT NULL,
    status          TEXT DEFAULT 'active',  -- 'active' | 'archived' | 'deleted'
  PRIMARY KEY (group_id, gk_version, user_id)
);

-- Индексы
CREATE INDEX idx_messages_chat ON personal_messages(chat_id, message_id);
CREATE INDEX idx_wrapped_keys_recipient ON personal_message_keys(recipient_id);
```

### 7.3. Redis

- Pub/Sub для push-уведомлений о новых сообщениях
- Кэш последних N wrapped_keys per chat (TTL 24h)
- Rate limiting на Key Service

---

## 8. Escrow и юридический доступ

### 8.1. Поток

```
1. Юридически обязывающий запрос → Security Admin
2. M-of-N (Shamir) → разблокировка Escrow Private Key в HSM
3. HSM: для каждого escrow_blob → ML-KEM decapsulate → message_key / GK
4. Audit log (write-only): «орган X получил 150 msg, 12 deleted»
5. Следственное ПО расшифровывает encrypted_payload полученными ключами
```

### 8.2. Soft delete

- `deleted = TRUE` — клиентам не отдаётся
- Физически остаётся; анклав видит всё
- Audit: «из 150 сообщений 12 были удалены пользователем»

### 8.3. Политика хранения ключей

| Период | Клиентский доступ | Юридический доступ |
|--------|-------------------|--------------------|
| 0–90 дней | Wrapped keys на сервере | Escrow blob |
| 90 дней – 7 лет | Только через анклав | Escrow blob |
| > 7 лет | Удалено | Только архив |

---

## 9. Оптимизации хранения для E2E-чатов

| Оптимизация | Работает? | Как |
|-------------|-----------|-----|
| ZSTD до шифрования | ✅ | На клиенте, `compress(plaintext)` → encrypt |
| Protobuf вместо JSON | ✅ | Бинарная сериализация до encrypt |
| Шардирование по chat_id | ✅ | Метаданные не зашифрованы |
| Hot/warm/cold | ✅ | По `created_at`, не по содержимому |
| Клиентский кэш (SQLite) | ✅ | Основная копия истории |
| Immutable revisions | ✅ | Редактирование создаёт новую подписанную ревизию |
| Дедупликация текста | ❌ | Разный nonce → разный ciphertext |
| Forward-ссылки | ❌ | Нужна перешифровка → полная копия |
| Дедупликация медиа | ⚠️ | SHA-256 открытого файла до шифрования |

### Forward (пересылка)

```kotlin
// Пересылка = расшифровать + перешифровать для целевого чата
val plaintext = decryptFromSourceChat(msg)
val forwarded = encryptForTargetChat(plaintext, targetChat)
// + metadata: forward_from_chat_id, forward_from_msg_id
```

Цена E2E: полная копия вместо ссылки. Экономия 0%, но E2E сохранён.

---

## 10. Медиа + LiveKit vs голосовые сообщения

Kodium шифрует произвольные `ByteArray` — фото, голосовые, документы. **LiveKit** и **Kodium** работают на разных слоях: первый — real-time WebRTC, второй — E2E шифрование контента приложения. Их нельзя подменять друг другом.

### 10.1. Три разных сценария

| Сценарий | Технология | Где шифруется | Сервер видит plaintext? |
|----------|------------|---------------|-------------------------|
| **Голосовое сообщение** (async, как в Telegram) | Kodium на клиенте | До upload в S3/MinIO | ❌ Нет |
| **Живой звонок** (audio/video room) | LiveKit + WebRTC (SRTP) | В RTP-потоке | ⚠️ SFU видит медиа* |
| **Запись звонка** (Track Egress, audio-only) | LiveKit Egress | На сервере при экспорте | ✅ Да (по умолчанию) |

\* При включённом LiveKit E2EE медиа зашифровано end-to-end в комнате; SFU не расшифровывает. Это **отдельная** функция LiveKit, не Kodium SecretBox.

```
Голосовое сообщение (рекомендуемый путь для E2E-мессенджера):

  Mic → Opus encode → SecretBox(media_key) → upload S3
         │                                      │
         └── media_key → wrapped_key + period_id ──► Message Service

Живой звонок:

  Client A ◄──── WebRTC / SRTP ────► LiveKit SFU ◄────► Client B
           (signaling через TLS 1.3)

Запись через Track Egress (audio-only):

  LiveKit Room → Egress worker → OGG/MP3 файл (plaintext на сервере)
                      │
                      └── ⚠️ конфликт со строгим E2E, см. 10.4
```

### 10.2. Шифрование медиа через Kodium (фото, голосовые, файлы)

Kodium не имеет потокового API — `SecretBox` принимает весь `ByteArray` в память. Для типичных медиа это приемлемо; для больших видео нужно **чанкование на уровне приложения**.

#### Алгоритм отправки

```kotlin
// 1. Подготовка файла
val plaintext = opusEncoder.finish()           // или JPEG/WebP bytes
val contentHash = sha256(plaintext)          // для CAS-дедупликации (до шифрования)

// 2. Ключ медиа (отдельный от message_key текста, но тот же period_id)
val mediaKey = Kodium.generateHighEntropyKey()
val ciphertext = Kodium.encryptSymmetric(mediaKey, plaintext)

// 3. Upload в object storage (сервер хранит только ciphertext)
val mediaRef = mediaService.upload(ciphertext, contentHash)

// 4. Сообщение-указатель (маленькое, через обычный конверт)
PersonalMessage {
    type:              VOICE | IMAGE | FILE
    media_ref:         UUID / S3 key
    media_key_hint:    wrapped в personal_message_keys  // Box, ~72 B
    period_id:         uint32   // escrow для периода, не на файл
    encrypted_payload: SecretBox(metadata: duration, width, mime, ...)
}
```

#### Размеры и лимиты

| Тип | Типичный размер | Kodium | Примечание |
|-----|-----------------|--------|------------|
| Голосовое 30 с (Opus) | 30–80 KB | ✅ `encryptSymmetric` | Запись на клиенте |
| Фото | 100 KB – 3 MB | ✅ | WebP-превью отдельным `media_key` |
| Видео > 10 MB | Большой | ⚠️ Chunked | См. 10.3 |
| Стикер / документ | до 20 MB | ⚠️ / ✅ | По политике лимитов |

#### Дедупликация медиа (CAS)

```text
Клиент вычисляет SHA-256(plaintext) ДО шифрования
  → если hash есть в CAS — upload пропускается, сохраняется только media_ref + новый media_key
  → утечка метаданных: «этот файл уже был в системе» (осознанный trade-off)
```

### 10.3. Chunked-шифрование для больших файлов

```text
media_key = random(32)
for each chunk[i] with plaintext bytes:
    chunk_key[i] = HKDF(media_key, info="chunk:" + i)
    chunk_ct[i]  = SecretBox(chunk[i], chunk_key[i])

MediaBlob {
    media_ref,
    chunk_count,
    chunk_size,
    chunks[],          // или один concatenated blob с индексом смещений
    media_key          // → wrapped_key на сервере
}
```

Сервер отдаёт ciphertext чанками; клиент расшифровывает потоково, не держа весь файл в RAM.

### 10.4. LiveKit Egress (Track Egress, audio-only)

**Track Egress** экспортирует аудио-трек участника в файл (OGG, MP3 и др.) **на стороне сервера**. LiveKit декодирует WebRTC-поток → plaintext → файл.

| Аспект | Поведение |
|--------|-----------|
| Назначение | Архив звонка, compliance-запись, аналитика |
| E2E мессенджера | **Не подходит** как замена голосовому сообщению |
| Plaintext на сервере | **Да**, в момент записи |
| Интеграция с Kodium | Нет нативной; пост-обработка возможна, но сервер уже видел аудио |

**Варианты, если нужна запись звонка при E2E-политике:**

| Подход | E2E | Сложность |
|--------|-----|-----------|
| Не записывать; только async голосовые через Kodium | ✅ | Низкая |
| Запись на **клиенте** → Kodium → upload | ✅ | Средняя |
| LiveKit E2EE в комнате + Egress (ключи у клиентов) | ✅ | Высокая |
| Egress → немедленно `SecretBox` на сервере | ❌ сервер видел поток | Средняя |
| Egress только с явного согласия всех участников | ⚠️ Политика | Низкая |

**Рекомендация:** для приватных чатов и групп — **голосовые сообщения только через клиентский Kodium**. LiveKit Egress — опционально для **публичных** комнат или звонков с баннером «ведётся запись» и отдельной юридической политикой.

### 10.5. Живые звонки (LiveKit room)

```text
┌──────────┐   TLS 1.3 + WSS    ┌──────────────┐   SRTP/WebRTC   ┌──────────┐
│ Client A │◄──────────────────►│ LiveKit SFU  │◄───────────────►│ Client B │
└──────────┘                    └──────────────┘                 └──────────┘
     │                                │
     │  Kodium: только метаданные     │  медиа-поток: SRTP (не SecretBox)
     │  (room_token, identity)        │
     └────────────────────────────────┘
```

- **Signaling** (подключение к комнате): TLS 1.3 + certificate pinning.
- **Медиа-поток**: SRTP внутри WebRTC — Kodium **не шифрует каждый RTP-пакет**.
- **E2E звонков**: при необходимости — LiveKit E2EE / insertable streams; ортогонально Kodium.

Kodium используется для: identity keys, токенов доступа к комнате (если заворачиваются), **не** для замены WebRTC.

### 10.6. TLS 1.3 (транспортный слой)

TLS 1.3 и Kodium **дополняют** друг друга. Kodium TLS не реализует.

| Угроза | TLS 1.3 (Envoy/Kong, OkHttp) | Kodium E2E |
|--------|------------------------------|------------|
| MITM в сети | ✅ | ✅ |
| Компрометация сервера | ❌ сервер видит TLS-plaintext | ✅ только ciphertext |
| Legal intercept | — | escrow по политике |

```text
Клиент ── TLS 1.3 + cert pinning ──► API Gateway (Envoy/Kong)
                                          │
                                          ├── Message Service (ciphertext)
                                          ├── Media Service (ciphertext blobs)
                                          └── LiveKit signaling (WSS/TLS)
```

Настройка: **не в Kodium**, а в gateway и HTTP-клиентах (pinning SPKI сертификата).

### 10.7. Escrow для медиа

Escrow **не на каждый файл** — через `period_id` / `ConversationKey` / `GK`, как для текста:

```text
Орган запрашивает период → escrow_blob → media_key из wrapped_keys / периода
                         → расшифровка файлов в S3 тем же ключом
```

Голосовое за март и текст за март могут делить один период — один escrow lookup.

### 10.8. Media Service (дополнение к §7)

```sql
CREATE TABLE media_objects (
    media_id        UUID PRIMARY KEY,
    content_hash    BYTEA,              -- SHA-256 plaintext (для CAS, опционально)
    storage_key     TEXT NOT NULL,      -- S3/MinIO path (ciphertext)
    size_bytes      BIGINT NOT NULL,
    mime_type       TEXT,
    chunk_count     INT DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL
);

-- Связь сообщение ↔ медиа через encrypted_payload metadata (media_id)
```

```text
Media Service (микросервис):
  - Presigned upload URL (TLS 1.3)
  - Принимает только ciphertext
  - CAS lookup по content_hash
  - Hot/warm/cold через S3 Lifecycle (как §6)
```

### 10.9. Сводка: что использовать когда

| Задача | Решение |
|--------|---------|
| Фото в личном / групповом E2E чате | Kodium SecretBox на клиенте → S3 |
| Голосовое сообщение | Запись на клиенте → Opus → Kodium → S3 |
| Большое видео | Chunked SecretBox + media_key |
| Звонок 1:1 / групповой | LiveKit + SRTP (+ E2EE при необходимости) |
| Запись звонка (Egress audio) | Отдельная политика; **не** для strict E2E |
| Канал до upload / signaling | TLS 1.3 + pinning |
| Escrow медиа | Через period_id, не per-file |

---

## 11. Сравнение подходов

| Критерий | Чистый Signal | Только Box (статич.) | **Конверт + Обёртки + Ratchet** |
|----------|--------------|---------------------|--------------------------------|
| Forward Secrecy | ✅ Сильная | ❌ Нет | ✅ Через ratchet (путь A) |
| Async / offline | ❌ Хрупкий | ✅ Надёжный | ✅ Wrapped keys (путь B) |
| Desync recovery | ❌ Re-handshake | ✅ Всегда | ✅ Wrapped keys |
| Multi-device | ⚠️ Сложно | ✅ Просто | ✅ Per-device wrapped keys |
| Escrow | ❌ Нужен отдельный слой | ✅ Естественно | ✅ Встроен |
| Группы 1000+ | ⚠️ Sender Keys | ❌ N обёрток/msg | ✅ 1 GK + N wrapped при ротации |
| Overhead/msg | ~200 байт header | ~100 байт | ~300 байт + escrow blob |
| Сложность кода | Высокая | Низкая | Средняя |

**Рекомендация:** гибрид «Конверт + Обёртки + Ratchet» — оптимальный баланс безопасности, надёжности доставки и совместимости с Kodium.

---

## 12. Дорожная карта реализации

### Фаза 0: Kodium (текущий репозиторий) — готово

- [x] Double Ratchet + X3DH
- [x] PQ Double Ratchet + PQXDH
- [x] Box, SecretBox, Sign, ML-KEM
- [x] Session export/import
- [ ] Signed PreKey verification в X3DH handshake (рекомендуется добавить)
- [ ] `GroupKeyManager` как опциональный модуль библиотеки (v1.1)

### Фаза 1: Крипто-SDK для мессенджера (новый модуль `messenger-crypto`)

```
messenger-crypto/
├── EnvelopeCipher.kt        — per-message encrypt/decrypt
├── WrappedKeyService.kt     — Box wrap/unwrap
├── EscrowModule.kt          — ML-KEM encapsulate/decapsulate
├── PersonalChatProtocol.kt  — оркестрация слоёв 1-4
├── GroupChatProtocol.kt     — GK rotation, Sender Keys
├── MediaCipher.kt           — encrypt/decrypt, chunked upload
└── MessageSerializer.kt     — Protobuf + zstd
```

### Фаза 2: Backend

- [ ] User Service (identity keys, device registry)
- [ ] Key Service (wrapped_keys CRUD)
- [ ] Message Service (ciphertext relay + storage)
- [ ] Media Service (S3/MinIO, CAS, presigned upload; только ciphertext)
- [ ] Group Service (GK rotation scheduler)
- [ ] Escrow Service (Nitro Enclave stub → production HSM)
- [ ] LiveKit (звонки, signaling TLS 1.3); Egress — отдельная политика записи

### Фаза 3: Клиенты

- [ ] SQLite: messages + ratchet sessions + identity keys
- [ ] Secure Enclave / Keystore для приватных ключей
- [ ] Sync: ratchet state между устройствами (опционально)
- [ ] Fallback UI: «восстановление из серверных ключей»
- [ ] Голосовые: запись → Opus → Kodium → upload (без LiveKit Egress)
- [ ] Медиа: chunked encryption для видео; CAS dedup по SHA-256 plaintext

### Фаза 4: Публичные чаты

- [ ] Telegram-style storage (hot/warm/cold)
- [ ] Media dedup (CAS)
- [ ] Forward-ссылки

---

## 13. Риски и митигации

| Риск | Митигация |
|------|-----------|
| Escrow = backdoor perception | M-of-N, audit log, прозрачная политика, не авто-доступ |
| Wrapped keys на сервере = attack surface | Шифрованы identity key получателя; сервер не может расшифровать |
| Overhead escrow blob (~1 KB/msg) | Для групп: 1 blob на GK-период, не на сообщение |
| Kodium не аудирован | Независимый security audit перед production |
| PQ ratchet header ~1 KB | Использовать classical ratchet для wrapped keys; PQ для identity |
| MAX_SKIP desync | Путь B (wrapped keys) делает это нерелевантным для доставки |
| LiveKit Egress ломает E2E | Голосовые через Kodium на клиенте; Egress только с согласия / для публичных |
| Большие видео в RAM | Chunked SecretBox (§10.3) |
| CAS dedup утечка «тот же файл» | Политика: opt-in или только для публичных медиа |

---

## 14. Итог

Проект **реализуем** на базе Kodium. Библиотека покрывает ~70% криптографических примитивов; оставшиеся 30% — прикладной слой (группы, escrow-оркестрация, серверное хранение wrapped keys).

Ключевое архитектурное решение: **не полагаться на Signal Protocol как единственный механизм доставки**. Double Ratchet остаётся для forward secrecy в активных сессиях, но **конвертное шифрование + серверные wrapped keys** обеспечивают надёжную асинхронную доставку, а **escrow через ML-KEM** — контролируемый юридический доступ без компрометации identity keys пользователей.

```
Безопасность = E2E (конверт) + PFS (ratchet) + Recovery (wrapped) + Compliance (escrow)
Надёжность  = wrapped keys как source of truth, ratchet как оптимизация
Масштаб     = Telegram-паттерны для публичных чатов, Sender Keys для приватных групп
```
