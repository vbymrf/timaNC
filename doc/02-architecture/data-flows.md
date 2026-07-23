# Потоки данных

> `EventBus` во всех диаграммах означает Redis Streams на beta VPS и Kafka в production/GA. Domain write и outbox row всегда фиксируются одной PostgreSQL transaction; публикацию выполняет outbox relay.

## 1. Отправка личного сообщения (E2E + escrow)

```mermaid
sequenceDiagram
  participant A as Client_A
  participant GW as API_Gateway
  participant MS as Message_Service
  participant PG as PostgreSQL
  participant K as EventBus
  participant W as Message_Worker
  participant RG as Realtime_GW
  participant B as Client_B

  A->>A: generate message_key, encrypt payload
  A->>A: ML-KEM escrow_blob, wrap keys per device
  A->>GW: POST /v1/chats/{chat_id}/messages (Idempotency-Key)
  GW->>MS: forward + attestation check
  MS->>PG: txn messages + wrapped_keys + outbox
  PG->>K: outbox relay publishes message.ingest
  MS-->>A: 201 ack (message_id)
  K->>W: consume
  W->>RG: push to recipient sessions
  RG->>B: WS message.created
  B->>B: decrypt path B wrapped key or path A ratchet
```

**Path A (ratchet):** быстрый, PFS, может desync.  
**Path B (wrapped key):** надёжный offline delivery — source of truth.

## 2. Sync / history fetch

```mermaid
sequenceDiagram
  participant C as Client
  participant GW as Gateway
  participant MS as Message_Service

  C->>GW: GET /v1/chats/{chat_id}/messages?after_message_id={id}
  GW->>MS: authorized fetch
  MS-->>C: ciphertext envelopes + wrapped_keys for this device
  C->>C: decrypt, update SQLite, FTS index
```

## 3. Private media upload (participant E2E)

```mermaid
sequenceDiagram
  participant C as Client
  participant Med as Media_Service
  participant S3 as MinIO
  participant MS as Message_Service

  C->>C: validate source; create thumbnail/preview/full
  C->>C: validate + encrypt all three variants
  C->>Med: POST /v1/chats/{chat_id}/media/uploads (or group domain)
  Med->>Med: authenticate + authorize domain owner
  Med-->>C: scoped presigned PUT URLs (TTL <= 15m)
  C->>S3: PUT ciphertext chunks
  C->>Med: complete upload
  C->>MS: POST /v1/chats/{chat_id}/messages (signed DocumentV2 media node)
  Note over C,MS: no original; server cannot AV-scan ciphertext
```

Получатель после decrypt **повторно** проверяет MIME/magic bytes, размер, dimensions, decode и запрет executable/active content. Sender validation не считается достаточной для недоверенного входящего private media.

## 3a. Public media upload

```mermaid
sequenceDiagram
  participant C as Client
  participant Med as Media_Service
  participant Q as Quarantine
  participant S3 as MinIO

  C->>Med: POST /v1/posts/assets
  Med->>Med: authenticate + authorize publication owner
  Med-->>C: scoped presigned PUT URL (TTL <= 15m)
  C->>Q: PUT source
  C->>Med: complete upload
  Med->>Q: AV scan + decode/sanitize
  Med->>S3: transcode thumbnail/preview/full
  Med->>Q: delete source after success/reject
  Note over Med,S3: original is neither retained nor served
```

Любой executable/active payload отклоняется. Read URL также требует domain authorization и имеет TTL не более 15 минут.

## 4. Public post fan-out (unified `posts`)

```mermaid
sequenceDiagram
  participant C as Client
  participant API as API
  participant PG as PostgreSQL
  participant K as EventBus
  participant FW as Feed_Scoring_Worker
  participant RW as Reactions_Counter_Worker
  participant R as Redis

  C->>API: POST /v1/posts
  Note over C,API: plaintext DocumentV2 nodes + markup + metadata
  API->>PG: INSERT immutable post_revision + outbox post.published
  PG->>K: outbox relay publishes post.published
  FW->>K: consume
  FW->>R: ZADD feed:user_id (scored timeline)
  Note over RW: on emotion/recommendation events
  RW->>PG: UPSERT rating_counters by subject
```

## 4a. Friends feed (public shelf fan-out)

```mermaid
sequenceDiagram
  participant C as Client
  participant API as API
  participant K as EventBus
  participant SW as Shelf_Fanout_Worker
  participant R as Redis

  C->>API: POST /favorites?shelf=public
  API->>K: transactional outbox → favorite.public.added
  SW->>K: consume
  loop each friend of owner
    SW->>R: RPUSH feed_friends:friend_id
  end
```

## 4b. Inbox projection (окно 4)

```mermaid
sequenceDiagram
  participant Src as Domain_Source
  participant K as EventBus
  participant IP as Inbox_Projection_Worker
  participant PG as PostgreSQL
  participant RG as Realtime_GW
  participant U as User_Client

  Src->>K: inbox.source.event
  K->>IP: consume
  alt appeal to entity
    IP->>PG: upsert inbox_threads + appeal_messages
  else personal event
    IP->>PG: INSERT inbox_events
  end
  IP->>RG: inbox.thread / inbox.event
  RG->>U: WS push
```

## 5. Звонок 1:1 / группа

```mermaid
sequenceDiagram
  participant A as Caller
  participant CS as Call_Service
  participant LK as LiveKit
  participant B as Callee

  A->>CS: POST /calls invite
  CS->>LK: CreateRoom + tokens
  CS->>B: WS call.incoming + push
  B->>CS: POST /calls/{id}/accept
  CS-->>B: LiveKit token
  A->>LK: WSS connect WebRTC
  B->>LK: WSS connect WebRTC
  Note over A,LK,B: SRTP media via SFU
```

## 6. Escrow legal access (offline)

```mermaid
sequenceDiagram
  participant L as Legal_Officer
  participant ES as Escrow_Service
  participant HSM as HSM_MofN
  participant PG as PostgreSQL
  participant AL as Audit_Log

  L->>ES: authorized request (warrant id)
  ES->>HSM: M-of-N unlock session
  ES->>PG: fetch escrow_blobs by scope
  HSM->>HSM: decapsulate → message_keys
  ES->>AL: append audit entry
  ES-->>L: key material package (secure channel)
```

## 7. Windows device linking

```mermaid
sequenceDiagram
  participant W as Windows_Client
  participant API as API
  participant M as Mobile_Client

  W->>W: generate device keypair
  W->>API: POST /link/session (pubkey, nonce)
  API-->>W: QR payload
  M->>M: scan QR, user confirm (attested)
  M->>API: POST /link/confirm
  API-->>W: session token (trusted_via_phone)
```

## 8. Ordering guarantees

| Scope | Guarantee |
|-------|-----------|
| Per `chat_id` | Total order of `message_id` |
| Cross-chat | No ordering |
| Public feed | Best-effort per user timeline |
| Calls | Independent of message order |

## 9. Idempotency

- Client generates `Idempotency-Key` (UUID) per send attempt.
- Server dedup window: 24h.
- Retry safe для domain writes: `/v1/chats/{id}/messages`, `/v1/groups/{id}/messages`, domain media uploads и `/v1/posts`.
- Редактирование создаёт новую immutable revision с новым idempotency key; in-place update опубликованного/отправленного DocumentV2 запрещён.

## 10. Bot Platform — `entity_message` в окно 4

```mermaid
sequenceDiagram
  participant Dev as Developer_App
  participant BG as Bot_Gateway
  participant PG as PostgreSQL
  participant K as EventBus
  participant SIP as SocialInbox_Worker
  participant RG as Realtime_GW
  participant U as User_Client

  Dev->>BG: POST /v1/bot/notifyUser
  BG->>BG: InstallationPolicy author=target_id
  BG->>PG: txn + outbox bot.entity_message.sent
  BG-->>Dev: 201 entity_message_id
  PG->>K: outbox relay publishes bot.entity_message.sent
  K->>SIP: consume
  SIP->>PG: upsert inbox_events (entity_message)
  SIP->>RG: inbox.event
  RG->>U: WS inbox.event
  Note over U: Окно 4, не личный чат
```

MVP без ботов: `POST /inbox/notify` → тот же `SocialInboxProjection` path.

## 11. Bot Platform — webhook update

```mermaid
sequenceDiagram
  participant Dom as Domain_Event
  participant K as EventBus
  participant W as Webhook_Worker
  participant Dev as Developer_URL

  Dom->>K: bot.update.pending
  K->>W: consume
  W->>Dev: POST Update + secret header
  alt 2xx
    Dev-->>W: 200 OK
  else retry
    W->>W: exponential backoff
  end
```

## 12. Ссылки

- [module-boundaries.md](./module-boundaries.md)
- [feed-ranking.md](../04-data/feed-ranking.md)
- [crypto-protocol.md](../03-security/crypto-protocol.md)
- [sync-offline.md](../04-data/sync-offline.md)
- [call-signaling.md](../06-realtime/call-signaling.md)
- [bot-platform.md](./bot-platform.md)
