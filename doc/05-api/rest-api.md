# REST API

> Все пути ниже относительны к `https://api.tima.example/v1`. Формат контента — [DocumentV2](../04-data/message-document-format.md).

### Проекция `DocumentV2`

- `metadata` передаётся всегда и содержит `format_version=2`, положительный `revision_number` и `content_mode=private|public`.
- Private wire содержит явные `protocol_version=2`, `presence_bitmap` (`uint32`) и 32-byte `key_commitment`; `protocol_version` и `metadata.format_version` меняются только lockstep и в текущем контракте равны `2`.
- Private-документ может содержать только optional `encrypted_nodes`, `markup`, `encrypted_metadata`; public — только optional `nodes`, `markup`. Private- и public-поля не смешиваются, encrypted-поля public-документа опускаются (в хранилище — `NULL`).
- Отсутствующие optional-поля опускаются. Входной JSON `null`, `[]` и `{}` для них нормализуется в отсутствие; пустой private `encrypted_metadata` не шифруется.
- Для media-only соответствующее текстовое поле опускается. Send/publish требует `has_content`: полностью пустой документ и один лишь пустой layout отклоняются.
- В private-разметке `text_link` хранит открытый `secret_ref`, а URL находится в `encrypted_metadata`; все ссылки должны разрешаться взаимно.
- Для групп сервер сверяет `metadata.content_mode` с режимом группы.

## Auth

| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/register` | Phone OTP start |
| POST | `/auth/verify` | Complete registration |
| POST | `/auth/login` | Password login |
| POST | `/auth/refresh` | Refresh token |
| POST | `/auth/logout` | Revoke session |
| POST | `/verify/attestation/ios` | App Attest |
| POST | `/verify/integrity/android` | Play Integrity |

## Users & Devices

| Method | Path | Description |
|--------|------|-------------|
| GET | `/users/me` | Profile + immutable `account_home_region` |
| PATCH | `/users/me` | Update profile |
| GET | `/devices` | List sessions |
| DELETE | `/devices/{id}` | Revoke |
| POST | `/link/session` | Windows QR start |
| POST | `/link/confirm` | Mobile confirm |

## Keys

| Method | Path | Description |
|--------|------|-------------|
| PUT | `/keys/bundle` | Upload PreKey bundle |
| GET | `/keys/bundle/{user_id}` | Fetch bundle |
| POST | `/keys/prekeys/replenish` | Upload OTK batch |
| GET | `/escrow/config?conversation_type=chat|group&conversation_id={id}&epoch={YYYYQn}&shard={id}` | Signed regional current+next public escrow configuration; server resolves authoritative conversation home region |

### GET `/escrow/config` response

```json
{
  "config_version": 17,
  "region": "EU",
  "epoch_id": "2026Q3",
  "shard_id": 12,
  "key_id": "eu-2026q3-12",
  "valid_from": "ISO8601",
  "valid_until": "ISO8601",
  "current_public_keys": {
    "x25519_threshold": "base64",
    "mlkem768": "base64"
  },
  "next_public_keys": {
    "epoch_id": "2026Q4",
    "key_id": "eu-2026q4-12",
    "x25519_threshold": "base64",
    "mlkem768": "base64"
  },
  "signer_key_id": "eu-config-root-1",
  "signature": "base64"
}
```

Signature covers canonical JSON without `signature`; clients verify the pinned regional signing root and reject expired/not-yet-valid, rolled-back, wrong-region, wrong epoch/shard or invalidly signed configuration. Private escrow material never appears in this API. Beta stub exposes the same shape under a separate pinned test root.

## Chats & Messages

| Method | Path | Description |
|--------|------|-------------|
| GET | `/chats` | List chats |
| POST | `/chats` | Create 1:1 chat; server assigns immutable `conversation_home_region` |
| GET | `/chats/{id}/messages` | History / sync |
| POST | `/chats/{id}/messages` | Send private `DocumentV2` |
| DELETE | `/chats/{id}/messages/{msg_id}` | Soft delete |
| POST | `/chats/{id}/messages/{msg_id}/revisions` | Create immutable edit revision |
| POST | `/chats/{id}/read` | Read receipt cursor |

Клиент не задаёт home region произвольно. Registration policy назначает `account_home_region`, а создание chat/private group — `conversation_home_region`; ambiguity или запрещённый residency flow завершается fail-closed. Изменение региона доступно только отдельной legal-approved migration procedure ([ADR-0018](../adr/0018-dual-region-ru-eu-production-architecture.md)).

### POST `/chats/{id}/messages` body (JSON wrapper)

```json
{
  "idempotency_key": "uuid",
  "document": {
    "encrypted_nodes": ["base64"],
    "metadata": {"format_version": 2, "revision_number": 1, "content_mode": "private"},
    "presence_bitmap": 1,
    "key_commitment": "base64-32-bytes",
    "escrow_blob": "base64",
    "ratchet_envelope": "base64",
    "protocol_version": 2,
    "signature": "base64"
  },
  "wrapped_keys": [
    {"device_id": "uuid", "wrapped_key": "base64", "protocol_version": 2, "key_commitment": "base64-32-bytes"}
  ]
}
```

## Groups

| Method | Path | Description |
|--------|------|-------------|
| POST | `/groups` | Create group; server assigns immutable `conversation_home_region` |
| GET | `/groups/{id}/messages` | Group history / sync |
| POST | `/groups/{id}/messages` | Send private encrypted or public plaintext `DocumentV2` |
| POST | `/groups/{id}/messages/{msg_id}/revisions` | Create immutable edit revision |
| DELETE | `/groups/{id}/messages/{msg_id}` | Soft delete |
| POST | `/groups/{id}/members` | Add member → triggers GK rotation |
| DELETE | `/groups/{id}/members/{user_id}` | Remove |
| GET | `/groups/{id}/gk/{version}/wrapped` | Fetch wrapped GK |

Private group message/revision uses the same `protocol_version=2`, `presence_bitmap` and `key_commitment` fields as private chat wire. Public group payload omits all three. A wrapped GK record includes `protocol_version=2`, `key_commitment` and `wrapped_gk`; commitment must match the message envelope and escrow path.

## Channels

`community_id` is required on create and immutable through channel CRUD. Community subscription does not subscribe the user to any child channel.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/channels` | Create `{community_id, title, description?, avatar_media_id?, who_can_post?}` |
| GET | `/channels/{id}` | Channel metadata and current-user subscription state |
| PATCH | `/channels/{id}` | Update mutable metadata/policy |
| DELETE | `/channels/{id}` | Soft-delete channel |
| POST | `/channels/{id}/subscribe` | Subscribe to this channel only |
| DELETE | `/channels/{id}/subscribe` | Unsubscribe from this channel only |
| GET | `/channels/{id}/posts` | Channel post list |

```json
{"community_id":"uuid","title":"Новости","who_can_post":"admins_authors"}
```

## Communities

> Канон: [social-objects/00-index.md](../01-product/social-objects/00-index.md).

| Method | Path | Description |
|--------|------|-------------|
| POST | `/communities` | Create community |
| GET | `/communities/{id}` | Community + child objects |
| POST | `/communities/{id}/subscribe` | Subscribe to community only; no child channel side effects |
| DELETE | `/communities/{id}/subscribe` | Unsubscribe |
| POST | `/communities/{id}/objects` | Attach or create child object |
| PATCH | `/communities/{id}/objects/{object_id}/acl` | Visibility and permissions |

## Voice rooms

| Method | Path | Description |
|--------|------|-------------|
| POST | `/voice-rooms` | Create with required `community_id`; optional `attached_type=group|channel`, `attached_id` |
| POST | `/voice-rooms/{id}/join` | Join → LiveKit token (ACL check on voice room) |
| POST | `/voice-rooms/{id}/leave` | Leave room |

```json
{"community_id":"uuid","title":"Эфир","attached_type":"channel","attached_id":"uuid"}
```

## Media

| Method | Path | Description |
|--------|------|-------------|
| POST | `/chats/{chat_id}/media/uploads` | Create private chat upload |
| POST | `/groups/{group_id}/media/uploads` | Create private/public group upload according to group policy |
| POST | `/posts/assets` | Create public post staging upload |
| POST | `/media/uploads/{media_id}/complete` | Finalize private upload |
| POST | `/media/{media_id}/access` | Authorize and issue presigned GET URL for one variant, TTL 15 min |

`media_id` is stored in open `DocumentV2.markup`. For private DocumentV2, file name, caption and media keys are resolved through `secret_ref` from `encrypted_metadata`; public media uses validated open metadata/media manifest and has no encrypted fields.

## Publications (unified editor)

> Канон: [37-content-editor.md](../doc_UI/37-content-editor.md), [public-content-format.md](../01-product/public-content-format.md). Единый контур для channel/media/user/ВП.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/posts/drafts` | Create public `DocumentV2` draft |
| GET | `/posts/drafts/{id}` | Get draft |
| PUT | `/posts/drafts/{id}` | Update draft (autosave) |
| DELETE | `/posts/drafts/{id}` | Delete draft |
| POST | `/posts/drafts/{id}/publish` | Publish now |
| POST | `/posts/drafts/{id}/schedule` | Schedule `{publish_at}` |
| POST | `/posts` | Publish directly (skip draft) |
| GET | `/posts/{id}` | Get published post |
| POST | `/posts/{id}/revisions` | Create immutable revision of a published post |
| POST | `/posts/{id}/approve` | Moderation approve (mod/admin) |
| POST | `/posts/{id}/reject` | Moderation reject `{reason}` |
| POST | `/media/uploads/{media_id}/complete` | Finalize, scan and process asset created by `/posts/assets` |

> Legacy aliases `/publications/channel/*` и `/publications/media/*` — deprecated; маппятся на `/posts/drafts` с `author_context.type`.

### Draft body example

```json
{
  "author_context": {"type": "channel", "id": "uuid"},
  "document": {
    "nodes": ["Hello", " world"],
    "markup": {
      "entities": [{"type": "bold", "nodes": [0]}],
      "layout": {"type": "document", "children": [{"type": "paragraph", "nodes": [0, 1]}]}
    },
    "metadata": {"format_version": 2, "revision_number": 1, "content_mode": "public"}
  },
  "attribute_ids": []
}
```

## Social graph (friends)

> DDL: `friend_requests`, `subscriptions` в [data-model.md](../04-data/data-model.md) §3. UI: [14-personal-page.md](../doc_UI/14-personal-page.md).

| Method | Path | Description |
|--------|------|-------------|
| POST | `/friends/requests` | Send friend request `{to_user_id}` |
| GET | `/friends/requests?direction=incoming\|outgoing` | List pending requests |
| POST | `/friends/requests/{from_user_id}/accept` | Accept request |
| POST | `/friends/requests/{from_user_id}/decline` | Decline request |
| DELETE | `/friends/{user_id}` | Remove friend (both sides) |
| GET | `/friends` | List friends (accepted mutual) |
| GET | `/subscriptions?target_type=user` | Subscriptions on people (may overlap friends) |

## Recovery (peer / device)

> Канон: [ADR-0014](../adr/0014-participant-e2e-and-recovery.md), [crypto-protocol.md](../03-security/crypto-protocol.md) §4.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/recovery/requests` | Initiate `{chat_id\|group_id, device_id, scope}` |
| GET | `/recovery/requests/{id}` | Status |
| POST | `/recovery/requests/{id}/respond` | Peer uploads rewrapped chunks |
| POST | `/recovery/requests/{id}/decline` | Peer declines |
| POST | `/recovery/requests/{id}/confirm` | Requester confirms completion |
| POST | `/recovery/phrase-backup` | Upload optional encrypted backup blob |
| GET | `/recovery/phrase-backup` | Download backup (requires phrase proof client-side) |
| DELETE | `/recovery/phrase-backup` | Remove backup |

`POST /recovery/requests` creates a recovery session valid for at most 24h:

```json
{"scope":"chat","chat_id":"uuid","device_id":"uuid","proof":"base64"}
```

```json
{"request_id":"uuid","session_id":"uuid","status":"pending","expires_at":"ISO8601","proof_attempts_remaining":4}
```

Proof verification allows at most 5 attempts per session. Limits are 3 request sessions/day/account and 10/day/IP; exhaustion returns `429` with `Retry-After`. Expired or consumed sessions cannot accept chunks.

## Comments (shared subsystem)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/comments` | `?target_type=post&target_id={canonical-string-id}` |
| POST | `/comments` | Create public `DocumentV2` comment or reply |
| POST | `/comments/{id}/revisions` | Create immutable edit revision |
| DELETE | `/comments/{id}` | Soft delete (author/mod) |

## Emotions & recommendations (shared subsystem)

> Шкала **9 эмоций** (1–9): одна на target. [+]/[−] — только публичный контент. Канон: [18-content-actions.md](../doc_UI/18-content-actions.md), [feed-ranking.md](../04-data/feed-ranking.md).

| Method | Path | Description |
|--------|------|-------------|
| PUT | `/emotions` | Set emotion `{target_type, target_id, emotion}` where `emotion` ∈ 1..9 |
| DELETE | `/emotions` | Remove emotion |
| GET | `/emotions?target_type=&target_id=` | Emotion counters under target |
| GET | `/ratings?subject_type=&subject_id=` | Rating «+/−» (separate positive/negative counters) |
| POST | `/recommend` | [+]/[−] `{target_type, target_id, value: +1|-1}` — public only |

## Attributes & genres

| Method | Path | Description |
|--------|------|-------------|
| GET | `/attributes?q=` | Search/autocomplete registry |
| POST | `/attributes` | Create on publish: `{name, proposed_genre?}`; may return `similar[]` |
| GET | `/attributes/{id}` | Card: genre, counters, description |
| GET | `/attributes/{id}/posts?cursor=` | Approved posts for attribute |
| POST/DELETE | `/attributes/{id}/follow` | Add/remove from «my attributes» (`user_attributes`) |
| GET | `/genres` | List genres |
| GET | `/genres/{id}/attributes` | Attributes in genre (by popularity) |

> Genre assignment is server-only ([feed-ranking.md](../04-data/feed-ranking.md) §3).

## Feed & favorites

| Method | Path | Description |
|--------|------|-------------|
| GET | `/feed?tab=general\|friends&genre=&attribute=&cursor=` | General feed (scoring + thematic slice) / friends feed (shelves, chronological) |
| GET/POST/DELETE | `/favorites?shelf=public` | Public favorites shelf (feeds friends lenta) |
| GET/PUT | `/shelf/private` | Private shelf: encrypted blob `SecretBox(shelf_key)` |
| POST | `/shelf/access/request` | Request access to friend's private shelf |
| POST | `/shelf/access/grant` | Grant: wrapped `shelf_key` to requester's devices |
| POST | `/shelf/access/revoke` | Revoke: rotate `shelf_key` + re-wrap for remaining grantees |
| GET | `/search?q=&types=&community_id=&author_id=&created_after=&created_before=&cursor=` | Unified public/server search; PG FTS in Beta, engine-agnostic |

Private shelf GET/PUT wire includes `protocol_version=2`, `presence_bitmap`, `key_commitment`, `encrypted_payload`, `escrow_blob`, `key_version`; every `wrapped_key` repeats matching `protocol_version` and `key_commitment`.

## Calls

| Method | Path | Description |
|--------|------|-------------|
| POST | `/calls` | Initiate |
| POST | `/calls/{id}/accept` | Accept + LiveKit token |
| POST | `/calls/{id}/reject` | Reject |
| POST | `/calls/{id}/end` | Hangup |
| GET | `/calls/history` | Call log |

## Reports

| Method | Path | Description |
|--------|------|-------------|
| POST | `/reports` | User/message/group report |

## Virtual users

> Канон: [virtual-user.md](../01-product/social-objects/virtual-user.md). ВП — `users` с `account_type=virtual`; все действия через обычный `user_id`.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/virtual-users` | List VP owned or operated by current user |
| POST | `/virtual-users` | Create VP (owner = current user) |
| GET | `/virtual-users/{id}` | VP profile + operator summary |
| PATCH | `/virtual-users/{id}` | Update display name, avatar, bio |
| DELETE | `/virtual-users/{id}` | Soft-delete / block VP |
| GET | `/virtual-users/{id}/operators` | List operators |
| POST | `/virtual-users/{id}/operators` | Grant operator |
| DELETE | `/virtual-users/{id}/operators/{user_id}` | Revoke → triggers key rotation |
| POST | `/virtual-users/{id}/keys/rotate` | Manual key rotation |
| GET | `/virtual-users/{id}/key-wraps` | Wrapped private keys for this device |
| POST | `/virtual-users/{id}/transfer` | Initiate transfer |
| POST | `/virtual-users/{id}/transfer/{transfer_id}/accept` | New owner accepts |
| POST | `/virtual-users/{id}/transfer/{transfer_id}/reject` | Reject transfer |

### Acting as VP (publications, messages)

Existing endpoints accept `author_user_id` / `sender_id` = VP `user_id`. Server checks operator capability and logs `actor_user_id` in `virtual_user_audit_log`. **No** `acting_as_identity_id` field.

## Bot applications (owner JWT)

> Канон: [bot-application.md](../01-product/social-objects/bot-application.md). Runtime — [bot-api.md](./bot-api.md).

| Method | Path | Description |
|--------|------|-------------|
| POST | `/apps` | Create bot application |
| GET | `/apps` | List owned apps |
| GET | `/apps/{id}` | App details |
| PATCH | `/apps/{id}` | Update metadata |
| DELETE | `/apps/{id}` | Soft-delete |
| POST | `/apps/{id}/tokens` | Issue token (plaintext once) |
| DELETE | `/apps/{id}/tokens/{token_id}` | Revoke token |
| POST | `/apps/{id}/installations` | Request install |
| GET | `/apps/{id}/installations` | List installations |
| DELETE | `/apps/{id}/installations/{installation_id}` | Revoke install |

> Bot runtime methods (`/v1/bot/*`) используют `Authorization: Bot`. Запрещены: private DM, arbitrary `sender_id`, E2E envelope.

## Социальное взаимодействие (окно 4)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/inbox/threads?identity=&status=&assignee=&priority=` | Карточки обращений (managed inbox; статус/ответственный — серверные, общие для команды) |
| PATCH | `/inbox/threads/{id}` | Взять/Отложить/Закрыть: `{status, assignee_id?, snoozed_until?}` — FSM: `new → taken → snoozed → closed` |
| GET | `/inbox/events?cursor=` | Личные события (реакции, упоминания, `entity_message`); read-state per пользователь |
| POST | `/inbox/events/read` · `/hide` | Пачкой: прочитано / скрыть |
| GET/PUT | `/inbox/preferences` | Правила агрегации (source + event_type → вкладка/скрыть/push/приоритет); синхронизируются между устройствами |
| POST | `/appeals` | Пользователь пишет сущности `{target_type, target_id, text}`. К **ВП** — E2E-чат (боты недоступны); к **группе/каналу/сообществу** — публичный plaintext-тред (отвечают операторы или бот через [bot-api](./bot-api.md) `answerAppeal`) |
| POST | `/inbox/notify` | Создать `entity_message` от сущности через owner API (`source_type=owner_api`) |
| GET | `/inbox/entity-messages/{id}` | Получить полный `entity_message` по ACL получателя |

`entity_message` — отдельный SocialInbox resource, не сообщение appeal-треда. Bot path создаёт тот же resource с `source_type=bot` и nullable provenance, заполненным для бота.

```json
{
  "source_entity": {"type": "channel", "id": "uuid"},
  "recipient_user_id": "uuid",
  "document": {"nodes":["Завтра эфир"],"metadata":{"format_version":2,"revision_number":1,"content_mode":"public"}}
}
```

```json
{
  "message_id":"uuid",
  "source_type":"owner_api",
  "source_entity":{"type":"channel","id":"uuid"},
  "app_id":null,
  "installation_id":null,
  "document":{"nodes":["Завтра эфир"],"metadata":{"format_version":2,"revision_number":1,"content_mode":"public"}},
  "target_ref":{"type":"entity_message","id":"uuid"}
}
```

## Ссылки

- [social-objects/00-index.md](../01-product/social-objects/00-index.md)
- [public-content-format.md](../01-product/public-content-format.md)
- [realtime-events.md](./realtime-events.md)
- [bot-api.md](./bot-api.md)
- [call-signaling.md](../06-realtime/call-signaling.md)
