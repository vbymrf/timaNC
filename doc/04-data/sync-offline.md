# Sync и Offline

> **Decision:** [ADR-0014](../adr/0014-participant-e2e-and-recovery.md)

## 1. Principles

- **Local-first:** SQLite is UI source of truth.
- **Server authoritative** for ordering and membership.
- **Decrypt local** for search index.
- **Participant E2E** on device; server relays ciphertext + wrapped keys.

## 2. Sync cursor

```text
GET /v1/chats/{chat_id}/messages?after_message_id={id}&limit=100
```

Response содержит private `DocumentV2` и wrapped keys для **этого `device_id`**. `metadata` всегда присутствует (`format_version=2`, положительный `revision_number`, `content_mode=private`); optional `encrypted_nodes`, `markup`, `encrypted_metadata` присутствуют только при наличии значения. Public `nodes` с private-полями не смешиваются. Для public group sync используется public-проекция (`nodes` / `markup`), а encrypted-поля отсутствуют (`NULL` в хранилище); сервер сверяет `content_mode` с режимом группы.

При decode JSON `null`, `[]` и `{}` optional-полей нормализуются в отсутствие. Пустой private metadata object не шифруется; media-only документ не содержит текстового поля. `text_link.secret_ref` разрешается через `encrypted_metadata`, причём обе стороны ссылки должны присутствовать. Полностью пустой документ и markup только с пустым layout не проходят `has_content` и не попадают в sync.

## 3. Outbox (client)

| Field | Purpose |
|-------|---------|
| `local_id` | UUID |
| `idempotency_key` | Dedup server-side |
| `document_envelope` | Pre-encrypted `DocumentV2` envelope |
| `state` | pending / sending / retry / terminal |
| `retry_count` | Exponential backoff |

Outbox хранит exact bytes уже канонизованного encrypted write DTO: optional-поля
представлены presence/absence, а не `null` или пустыми контейнерами. Каждая
попытка повторно использует те же bytes и `idempotency_key`; plaintext остаётся
только в отдельно зашифрованном UI cache. `sending` восстанавливается в `retry`
после restart. Retryable transport errors получают ограниченный exponential
backoff; non-retryable и malformed rows переходят в `terminal` и не запускаются
автоматически, но допускают явный user retry. Logout атомарно по intent очищает
UI cache/outbox и удаляет platform-protected cache key.

Durable boundary начинается после успешных reservation и encryption, но до
первого network send. Поэтому post-encryption retry переживает restart, а
reservation failure и полностью offline composition до reservation пока не
переживают завершение процесса.

## 4. Multi-device

| Scenario | Behavior |
|----------|----------|
| New device approved | Trusted device signs; peer recovery **or** wrapped-key backfill within the 6-month protected-content retention window |
| Ratchet on phone A | Phone B: own ratchet session or wrapped-key fallback |
| Device revoked | Stop wraps; invalidate sessions |
| All devices lost | Peer recovery (consent) or phrase backup if enabled |

**Ratchet session export** to trusted device: encrypted blob via pairwise wrap (optional).

## 5. Peer recovery sync

```text
POST /recovery/requests          — initiate (chat_id, device_id, scope)
GET  /recovery/requests/{id}     — status
POST /recovery/requests/{id}/respond — peer uploads rewrapped chunks
POST /recovery/requests/{id}/confirm — requester acknowledges
```

- Server stores transfer metadata + encrypted chunks (already E2E-wrapped).
- Rate limits: max N chats/hour; bulk triggers confirmation.
- Push to owner: «Запрошено восстановление — это вы?»

## 6. Conflict resolution

- Message revisions: append-only; edit creates the next immutable revision.
- Concurrent revisions: server accepts one successor of `current_revision_id`; stale parent → `409 REVISION_CONFLICT`.
- Emotions, recommendations: LWW with server timestamp.
- Profile: server wins.

## 7. Gap recovery (ratchet desync)

1. Auto fall back to wrapped key.
2. Background re-X3DH.
3. If persistent failure → peer recovery for gap range.

## 8. Offline media

- После native normalization каждый из трёх JPEG variants шифруется до durable
  boundary. SQLite хранит encrypted queue record, app-private files — только
  ciphertext; source/normalized plaintext в SQLite и durable files не попадает.
- Restart повторяет незавершённые PUT по сохранённым idempotency keys и manifest.
  При 401/403 или локально истёкшем presigned slot запись атомарно сбрасывает
  старый `media_id`/slots и сохраняет новые init/complete idempotency keys до
  повторного authenticated init. Старый незавершённый объект остаётся server-side
  orphan до TTL/retention cleanup; клиент не заявляет его немедленное удаление.
  Текущий alpha не заявляет OS background transfer.
- После complete media attachment канонически шифруется в media-only DocumentV2
  и передаётся существующему exact-ciphertext message outbox. Это две durable
  стадии; queue удаляется только после достижения message-outbox boundary.
  Стабильные message local ID, reservation key и send key записываются в media
  queue до upload, поэтому crash после message transport success не создаёт
  вторую reservation/encryption/message.
- Receiver запрашивает только thumbnail/preview, проверяет ciphertext
  checksum/size, AEAD/version, JPEG magic и dimensions после decrypt и не
  экспортирует plaintext во внешние приложения.
- Logout удаляет media queue, protected queue key, ciphertext temp files и
  decrypted in-memory previews. Перезапись перед delete является best effort:
  flash/SSD copy-on-write и wear levelling не гарантируют физическое стирание.

## 9. Windows linked device

Same sync API after QR trust. Full participant E2E + recovery paths.

## 10. Virtual users

| Scenario | Behavior |
|----------|----------|
| Owner opens VP chat | Normal sync; decrypt via VP key wrap on device |
| New operator | Owner grants → new wraps |
| Operator revoked | Rotate VP keys |

## 11. Push/WS wake-up и catch-up

FCM, APNs, UnifiedPush, foreground WebSocket и app resume вызывают один общий
sync coordinator. Wake-up payload сообщает только тип события и scope для
синхронизации; он не заменяет server-authoritative данные.

Coordinator coalesces повторы, выполняет delta sync с сохранённого cursor,
идемпотентно обновляет SQLite и восстанавливает WebSocket subscription. Поэтому
повторная доставка через vendor и собственный канал не создаёт дубликатов, а
полная недоступность push компенсируется при следующем WS connect/app resume.

На iOS без APNs catch-up запускается только при активном приложении или его
возврате в foreground. Подробнее:
[hybrid-notification-delivery.md](../02-architecture/hybrid-notification-delivery.md).

## 12. Ссылки

- [key-lifecycle.md](../03-security/key-lifecycle.md) §12
- [crypto-protocol.md](../03-security/crypto-protocol.md) §4
- [realtime-events.md](../05-api/realtime-events.md)
- [hybrid-notification-delivery.md](../02-architecture/hybrid-notification-delivery.md)
