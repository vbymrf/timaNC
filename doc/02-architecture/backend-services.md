# Backend-сервисы

## 1. Эволюционная карта

| Модуль / Service | MVP | Growth | 10M MAU |
|------------------|-----|--------|---------|
| Auth & Users | monolith | monolith | service |
| Chat & Messages | monolith | + message-worker scale | sharded |
| Key & Wrapped Keys | monolith | key-service | sharded |
| Groups & GK rotation | monolith | group-worker | service |
| Communities & ACL | monolith | community-service | service |
| Channel & posts | monolith | channel-service | service |
| Publications | monolith | publishing-worker | service |
| **Feeds** (scoring + fan-out) | monolith + workers | feed-workers | dedicated |
| **Attributes** | monolith | attributes-service | service |
| Comments | monolith | comments-service | service |
| **Reactions** (emotions + counters) | monolith + worker | reactions-service | service |
| VirtualUserAccess | monolith | identity-service | service |
| SocialInbox | monolith + projection worker | inbox-service | service |
| **BotPlatform** | — (Phase 3b) | monolith → bot-gateway | service |
| Media | monolith | media-service | + CDN |
| Notifications | monolith | notification-service | multi-region |
| Calls metadata | monolith | call-service | service |
| Escrow | isolated | isolated | isolated + HSM cluster |
| Realtime GW | separate | separate | pool per AZ |
| Search | PostgreSQL FTS (Beta) | PG FTS until measured trigger | OpenSearch only after trigger |

## 2. Auth & User Service

- Регистрация: SMS OTP, email verify, password hash (Argon2id).
- Device registry: device_id, platform, attestation status, push token.
- Session: JWT access (15m) + refresh (30d), bound to device_id.
- Endpoints: `/v1/auth/*`, `/v1/users/*`, `/v1/devices/*`.

## 3. Key Service

- Хранит **только public keys** и PreKey bundles.
- CRUD wrapped keys (ciphertext blobs per recipient).
- Для wrapped message/GK/shelf keys хранит явные `protocol_version=2` и `key_commitment`; commitment обязан совпадать с envelope и escrow path.
- Rate limit на upload PreKeys.
- **Не хранит** private keys, message plaintext.

## 4. Message Service

- Domain endpoints: `/v1/chats/{chat_id}/messages` и `/v1/groups/{group_id}/messages`; generic `/messages` запрещён.
- Accept DocumentV2 только с обязательным `metadata` (`format_version=2`, `revision_number`, `content_mode`); для групп режим обязан совпадать с `groups.kind`.
- Private (`content_mode=private`): опциональны `encrypted_nodes`, открытый `markup` и `encrypted_metadata`, плюс envelope + escrow_blob. Public (`content_mode=public`): опциональны plaintext `nodes` и `markup`; encrypted-поля запрещены.
- Private wire/revision требует `protocol_version=2`, `metadata.format_version=2` lockstep, явные `presence_bitmap:uint32` и 32-byte `key_commitment`; любой mismatch отклоняется до durable write.
- Опциональные поля передаются omitted и хранятся как `NULL`: `[]`, `{}` и ciphertext нулевой длины отклоняются. Media-only не содержит текстового массива.
- Канонизированные открытые JSONB входят в client signature и AEAD AAD; backend проверяет подпись/канонизацию, но не private plaintext.
- `has_content` требует непустой текст или реальный media/content binding; пустой layout не делает документ содержательным.
- Public-ссылка использует `text_link`; private-ссылка с чувствительной целью — `secret_ref`, а не открытый URL.
- Validate idempotency-Key, chat membership, attestation.
- Transactional write: `personal_messages` + `personal_message_keys` + outbox event.
- Ack после durable commit (не после Kafka).
- Message revisions immutable: edit создаёт новую revision с `parent_revision_id`, delete — tombstone; in-place UPDATE контента запрещён.

## 5. Group Service

- Schedule GK rotation (100 msg / join / leave).
- Distribute wrapped_GK to `user_wrapped_keys`.
- Public groups: plaintext path → EventBus fan-out (`Redis Streams` beta, Kafka topic production/GA).

## 5a. Community Service

- CRUD communities, memberships (subscription), child object attachments.
- Per-object ACL (`visibility`, join/read/speak/post permissions).
- **Voice room:** canonical table/API `voice_rooms` / `/v1/voice-rooms`; `community_id` обязателен. Wizard может сначала auto-create community, затем вызывает create уже с ID; слабая UI-привязка — только `attached_type/attached_id`.
- Does **not** rotate GK on community subscription changes.
- Community subscription does not create channel subscriptions.
- Канон: [social-objects/community.md](../01-product/social-objects/community.md).

## 5b. Channel Service

- Full CRUD каналов с обязательным `community_id`; подписчики, авторы, роли.
- `/subscribe` и `/unsubscribe` меняют только channel subscription; подписка на parent community не подписывает на дочерние каналы.
- **Только метаданные**; контент — в `publications` (`posts` с `author_type='channel'`).
- Не владеет комментариями. Канон: [social-objects/channel.md](../01-product/social-objects/channel.md).

## 5c. Publications Service

- Единая таблица `posts` (канал, медиа-лента, стена пользователя/ВП).
- Черновики `post_drafts`, расписание, publish orchestration.
- Формат: public DocumentV2 с обязательным `metadata.content_mode=public`, опциональными plaintext `nodes` и `markup`; encrypted-поля запрещены. `body + entities(offset/length)` и placeholder полностью выведены из write API ([ADR-0015](../adr/0015-document-v2-and-media-pipeline.md)).
- Endpoints: `/v1/posts` и `/v1/posts/{post_id}/revisions`; опубликованные revisions immutable.
- Media-only опускает `nodes`; `has_content` требует непустой текст или реальный media/content binding, а пустой layout невалиден. `[]`/`{}` вместо отсутствующего поля и executable/active content запрещены.
- Idempotent publish через outbox → EventBus `post.published`.

## 5d. Attributes Service

- Реестр `attributes` (= хэштеги), курируемые `genres`.
- `post_attributes` со статусом `declared|approved|rejected` («автор + репутация»).
- `user_attributes` — чипсы профиля и тематические срезы ленты.
- Индексация для поиска — [search-indexing.md](../04-data/search-indexing.md).

## 5e. Feeds Service

- **Общая лента:** feed-scoring-worker — кандидаты (подписки + approved-посты моих атрибутов + популярное в моих жанрах) × сигналы `[+]/[−]` и эмоции → Redis `feed:{user_id}`.
- **Лента друзей:** shelf-fanout-worker — публичные полки и репосты → Redis `feed_friends:{user_id}` (хронология, без скоринга).
- Канон: [feed-ranking.md](../04-data/feed-ranking.md).

## 5f. Comments Service (shared)

- Полиморфные комментарии: `target_type` ∈ `post`, `media`, `collection_item`.
- `reply_to` для веток; один визуальный уровень + `@username`.
- Эмоции на комментарий — через Reactions (`target_type=comment`).
- **Не** используется в E2E groups/chats; private collections — без комментариев (MVP).
- UI: [15-comments.md](../doc_UI/15-comments.md).

## 5g. Reactions Service (shared)

- **Emotions:** шкала 1–9, одна эмоция на `(user, target)`; валентность 1–8 влияет на рейтинг, 9 — нейтральная.
- **Recommendations:** `[+]/[−]` (`value ∈ {-1, 1}`) для публичного контента.
- **rating_counters:** денормализация по `subject_type` (`user|group|channel`) + `subject_id`; обновляет `reactions-counter-worker`.
- Activity aggregator (окно 4 «Реакции») читает Comments + Reactions.

## 5h. MediaFeed (client surface)

- Публичные медиа-посты ленты (окно 3) — те же `posts` (`kind=photo|video|article`).
- Публикация через [33-media-editor.md](../doc_UI/33-media-editor.md).

## 5i. VirtualUserAccess Service

- CRUD виртуальных пользователей (`users` с `account_type=virtual`).
- Operators: grant/revoke, capability check перед действием от имени ВП.
- Key wraps: distribute `virtual_user_key_wraps` на устройства owner/operators; **обязательная ротация** при revoke operator или transfer.
- Transfer: двустороннее подтверждение, смена `owner_user_id`, audit в `virtual_user_transfers`.
- Публикации/memberships принимают обычный `user_id`; сервер пишет `actor_user_id` в `virtual_user_audit_log`, не вводит `acting_as_identity_id`.
- Лимиты MVP: 5 ВП / human account, 20 operators / ВП, **no calls** from VP.
- Канон: [virtual-user.md](../01-product/social-objects/virtual-user.md).

## 5j. SocialInbox Service

- Окно 4: `inbox_threads` (FSM обращений), `appeal_messages`, `entity_messages` + immutable revisions, `inbox_events`, `social_inbox_preferences`.
- **Обращения к ВП:** E2E в `chats` + thread metadata (`chat_id`); статус/assignee серверные, общие для команды.
- **Обращения к сущностям:** plaintext в `appeal_messages`; боты/операторы отвечают от `entity`.
- **События:** comments, reactions, mentions, assignments → `inbox_events` (личный read-state).
- **Entity messages:** SocialInbox — SoT; `source_type=owner_api|bot`, bot provenance (`app_id`, `installation_id`) nullable и обязателен только для bot. `inbox_events` хранит FK и `target_ref.type=entity_message`. Это не appeal.
- FSM: `new → taken → snoozed → closed`; snooze с `snoozed_until`.
- WS: `inbox.thread`, `inbox.event`; push — [push-payloads.md](../05-api/push-payloads.md).
- UI: [10-free-communication.md](../doc_UI/10-free-communication.md).

## 5k. BotPlatform Service

- CRUD `bot_applications`, `bot_installations`, `bot_tokens` (hash-only), `bot_webhooks`, `bot_commands`.
- Bot API ingress: `/v1/bot/*`; `Authorization: Bot {token}`.
- **InstallationPolicy:** author = installation target; deny private DM, arbitrary sender, cross-object.
- Методы: publish post, group message, `notifyUser`, comments, emotions, callbacks, commands, media (presigned).
- Update delivery: transactional outbox → EventBus (Redis Streams beta, Kafka production/GA) → webhook worker / polling buffer.
- Адресные сообщения создаются через интерфейс SocialInbox → `entity_messages` + projection в `inbox_events`, **не** `personal_messages` и не BotPlatform-owned table.
- Audit: `bot_audit_log` с `app_id`, `installation_id`, `actor_user_id` (owner).
- Канон: [bot-application.md](../01-product/social-objects/bot-application.md), [bot-platform.md](./bot-platform.md).

## 6. Media Service

- Domain endpoints: `/v1/chats/{chat_id}/media/uploads`, `/v1/groups/{group_id}/media/uploads`, `/v1/posts/assets`; generic `/media/init` запрещён.
- Init, complete и read требуют auth + authorization по chat/group/publication owner.
- Presigned PUT/GET URL: TTL **не более 15 минут**, один object key + один HTTP method + ожидаемые size/content headers.
- Private pipeline: sender создаёт `thumbnail`, `preview`, `full`, валидирует MIME/magic bytes, лимиты, dimensions и decode, затем E2E-шифрует; recipient повторяет проверки после decrypt. Сервер получает ciphertext и технический manifest, не выполняет AV/transcode.
- Public pipeline: исходный upload попадает в quarantine; server AV scan → decode/sanitize → transcode.
- В постоянном storage и API существуют ровно три variant: `thumbnail`, `preview`, `full`. `original` не хранится и не выдаётся; quarantine source удаляется после success/reject.
- Executable/active content (`script`, macro, active HTML/SVG, event handlers, polyglot executable) блокируется. `code` в DocumentV2 остаётся inert text.
- Chunk assembly metadata не может переопределить подписанный DocumentV2 media binding.
- Retention применяется к variants и immutable revisions. Legal hold приостанавливает physical purge, включая soft-deleted; hold/release/purge пишутся в WORM audit.
- Phase 1 executable contract: source image ≤25 MiB and each of exactly three
  ciphertext variants ≤100 MiB. The former 2 GB/chunked target is future scope
  and is not implemented.

## 7. Realtime Gateway

- WebSocket: auth, subscribe chats, receive events.
- Presence: Redis TTL keys (ephemeral).
- **Не** парсит ciphertext.

## 8. Feed & Reactions Workers

- Consume `post.published`, `emotion.changed`, `recommendation.changed` → update Redis feeds / `rating_counters`.
- `emotion.changed` и `recommendation.changed` — внутренние EventBus events; WebSocket проецирует их в `emotion.added|removed|counters` и `recommendation.added|removed`.
- `shelf-fanout-worker`: `favorite.public.added` → `feed_friends:{friend_id}`.
- Idempotent by `(event_id)`; DLQ for poison events.

## 9. Message Workers

- Consume `message.ingest` → fan-out to recipient WS via internal bus.
- Ordering: partition key = `chat_id`.
- Retry + DLQ for poison messages.

## 10. Notification Service

- FCM/APNs/WNS; E2E-safe payloads (см. [push-payloads.md](../05-api/push-payloads.md)).
- Collapse key per chat, rate limit 1/5min (UI rule).

## 11. Call Service + LiveKit

- Create room, mint JWT (server-sdk-go).
- Map call_id ↔ livekit_room_name.
- Webhooks from LiveKit → call history.

## 11a. Search

- Beta engine: PostgreSQL FTS/trigram; unified facade `GET /v1/search` с type/community/author/date filters.
- Private E2E plaintext индексируется только локально и в server API не попадает.
- OpenSearch не является dependency до trigger: устойчивый p95 > 500 ms после PG tuning или >10M активных индексируемых документов.
- После trigger outbox + `search-index-worker` меняют engine без изменения API/cursor contract.

## 12. Escrow Service

- Isolated network segment.
- Public API: signed `GET /v1/escrow/config`; canonical response подписан offline/root config key, имеет version/validity и допускает rollback protection.
- Internal APIs: legal request workflow only.
- HSM decapsulate; audit append-only.
- Generic `legal_holds(target_type,target_id)` приостанавливает physical purge для любого поддержанного объекта.
- `escrow_audit_events` append-only hash chain; периодические `escrow_merkle_batches` подписывают Merkle root и могут anchored externally.
- **No** dependency from hot message path except blob storage.

## 13. Moderation (public)

- Report queue, admin tools (Phase 3).
- ML classification — optional, out of MVP.

## 14. Internal communication

| From → To | Protocol |
|-----------|----------|
| Client → Gateway | HTTPS / WSS |
| Gateway → Monolith | HTTP/gRPC internal |
| Monolith/outbox relay → Workers | EventBus: Redis Streams beta, Kafka production/GA |
| Workers → Realtime GW | gRPC / Redis pub (internal signals only) |
| Call Service → LiveKit | HTTPS + server API key |

## 14a. Schema-first phases

- **Phase 0:** создать и валидировать core Client OpenAPI, crypto/event Protobuf и DocumentV2/event/push JSON Schemas; подключить codegen/contract checks. Пока артефакты отсутствуют, prose остаётся временной каноникой.
- **Phase 3b:** Bot Platform и bot OpenAPI/codegen. Bot schema не входит в Phase 0.

## 15. Ссылки

- [module-boundaries.md](./module-boundaries.md)
- [feed-ranking.md](../04-data/feed-ranking.md)

- [social-objects/00-index.md](../01-product/social-objects/00-index.md)
- [public-content-format.md](../01-product/public-content-format.md)
- [bot-platform.md](./bot-platform.md)
- [data-model.md](../04-data/data-model.md)
- [rest-api.md](../05-api/rest-api.md)
- [ADR-0001](../adr/0001-evolutionary-services.md)
