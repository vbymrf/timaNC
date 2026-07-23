# Bot API

> **Статус:** `done` (spec) · **Версия:** 0.3 · **Дата:** 2026-07-13 · **Реализация:** Phase 3b  
> **Канон:** [bot-application.md](../01-product/social-objects/bot-application.md), [ADR-0009](../adr/0009-native-bot-app-platform.md)  
> **Schema-first:** [ADR-0012](../adr/0012-schema-first-api.md) — `schema/openapi/bot-api.yaml`

## 1. Принципы

1. **Бот — не пользователь.** Нет `@username`, профиля, `user_id` и личных чатов. Bot Application — приложение с токеном, **установленное** в group/channel/community; аудитория видит сущность, не бота.
2. **Installation-only author.** Сервер выводит `author_id` из `bot_installations`; клиент не передаёт `sender_id`, ВП или human identity.
3. **Сущности не пишут в личку.** Сообщения пользователю — карточки `entity_message` в окне 4; уважается `block_messages` и лимиты (как `POST /inbox/notify` в MVP без ботов).
4. **Только публичный контур.** E2E/private groups, envelope path, звонки — запрещены.
5. **Совместимость:** только аддитивные изменения; deprecation ≥ 6 месяцев.

## 2. Base URL

```text
Production: https://api.tima.example/v1/bot
```

Отдельный ingress от user REST (`/v1/*`). Realtime delivery — webhook или long polling, **не** user WebSocket.

## 3. Authentication

```http
Authorization: Bot {token}
Idempotency-Key: {uuid}   # POST mutations
```

- Token привязан к `app_id`; capabilities наследуются от активных `bot_installations`.
- Token hash-only на сервере; plaintext показывается один раз при создании.
- User JWT **не** используется для Bot API.

## 4. Обязательные параметры

Все write-методы требуют:

| Поле | Описание |
|------|----------|
| `installation_id` | UUID активной установки |
| `target_id` | UUID group/channel/community (должен совпадать с installation) |

Сервер выводит `author_id` = `target_id`. Поля `author_id`, `sender_id`, `acting_as` в body **отклоняются**.

## 5. Error model

```json
{
  "error": {
    "code": "BOT_PRIVATE_MESSAGING_FORBIDDEN",
    "message": "Bots cannot send to private chats",
    "details": {
      "installation_id": "uuid",
      "method": "notifyUser"
    },
    "request_id": "uuid"
  }
}
```

| Code | HTTP | Описание |
|------|------|----------|
| `BOT_TOKEN_INVALID` | 401 | Невалидный или отозванный token |
| `BOT_INSTALLATION_INACTIVE` | 403 | Установка suspended/revoked |
| `BOT_SCOPE_DENIED` | 403 | Нет capability для метода |
| `BOT_AUTHOR_FORBIDDEN` | 403 | Попытка передать произвольный author/sender |
| `BOT_PRIVATE_MESSAGING_FORBIDDEN` | 403 | Любая отправка в private 1:1 |
| `INSTALLATION_TARGET_MISMATCH` | 403 | `target_id` ≠ installation target |
| `BOT_E2E_FORBIDDEN` | 403 | Envelope / wrapped_keys path |
| `BOT_CALLS_FORBIDDEN` | 403 | Звонки от bot |
| `BOT_VP_FORBIDDEN` | 403 | Acting as virtual user |
| `BOT_RATE_LIMITED` | 429 | Rate limit; `retry_after` в details |
| `WEBHOOK_URL_INVALID` | 400 | SSRF / invalid URL |
| `VALIDATION_ERROR` | 400 | Schema validation |

## 6. App management (owner JWT — user API)

> Регистрация приложений — через **user** REST с owner JWT. Bot token — только для runtime методов.

| Method | Path (user API) | Description |
|--------|-----------------|-------------|
| POST | `/apps` | Create bot application |
| GET | `/apps` | List apps owned by user |
| GET | `/apps/{id}` | App details |
| PATCH | `/apps/{id}` | Update name, description |
| DELETE | `/apps/{id}` | Soft-delete app |
| POST | `/apps/{id}/tokens` | Issue new token (returns plaintext once) |
| DELETE | `/apps/{id}/tokens/{token_id}` | Revoke token |
| POST | `/apps/{id}/installations` | Request install in group/channel/community |
| GET | `/apps/{id}/installations` | List installations |
| DELETE | `/apps/{id}/installations/{installation_id}` | Revoke installation |

## 7. Bot API methods

### 7.1. App info

| Method | Path | Description |
|--------|------|-------------|
| GET | `/getApp` | Current app metadata + active installations |

### 7.2. Webhook & updates

| Method | Path | Description |
|--------|------|-------------|
| POST | `/setWebhook` | Set webhook URL + secret |
| DELETE | `/deleteWebhook` | Remove webhook |
| GET | `/getWebhookInfo` | Webhook status |
| GET | `/getUpdates` | Long polling (fallback) |

См. [bot-updates.md](./bot-updates.md).

### 7.3. Channel & media publishing

| Method | Path | Scope | Description |
|--------|------|-------|-------------|
| POST | `/publishChannelPost` | `post:channel` | Publish post от имени channel |
| POST | `/createChannelPostRevision` | `post:channel` | Create immutable revision |
| POST | `/publishMediaPost` | `post:media` | Publish media feed post |

Body: public `DocumentV2` — optional `nodes` / node-index `markup` и required `metadata` с `format_version=2`, положительным `revision_number`, `content_mode=public` — [message-document-format.md](../04-data/message-document-format.md). Отсутствующие optional-поля опускаются; входные `null`, `[]` и `{}` нормализуются в отсутствие. `encrypted_nodes` / `encrypted_metadata` и private/public mixing отклоняются. Media-only документ опускает `nodes`; полностью пустой документ и пустой layout отклоняются по `has_content`. `text_link` с private `secret_ref` недопустим, поскольку Bot API не принимает `encrypted_metadata`.

### 7.4. Group messages (public only)

| Method | Path | Scope | Description |
|--------|------|-------|-------------|
| POST | `/sendGroupMessage` | `message:group` | Message в public group установки |

Только `group.visibility=public`; сервер также требует `document.metadata.content_mode=public`. Private/E2E groups → `BOT_E2E_FORBIDDEN`.

### 7.5. Entity messages (окно 4)

| Method | Path | Scope | Description |
|--------|------|-------|-------------|
| POST | `/notifyUser` | `notify` | Карточка `entity_message` получателю в окно 4 |
| POST | `/createEntityMessageRevision` | `notify` | Create immutable revision (если не прочитано) |

**Не создаёт** private chat. Автор в UI = group/channel/community. Эквивалент MVP-пути `POST /inbox/notify` (owner/admin JWT), но через Bot token + installation.

Ограничения (как в [bot-rate-limits.md](./bot-rate-limits.md)):
- Только подписчикам/участникам сущности установки.
- Уважает пер-сущностную настройку `block_messages`.
- ≤ 1 карточка пользователю от сущности в час.

#### POST `/notifyUser` body

```json
{
  "installation_id": "uuid",
  "target_id": "uuid",
  "recipient_user_id": "uuid",
  "idempotency_key": "uuid",
  "document": {
    "nodes": ["Ваш заказ готов"],
    "markup": {
      "layout": {"type": "document", "children": [{"type": "paragraph", "nodes": [0]}]}
    },
    "metadata": {"format_version": 2, "revision_number": 1, "content_mode": "public"}
  },
  "link": { "type": "post", "id": "uuid" },
  "inline_keyboard": {
    "rows": [[{"text": "Подробнее", "callback_data": "order:123"}]]
  }
}
```

Response:

```json
{
  "message_id": "uuid",
  "entity_message_id": "uuid",
  "author": {
    "type": "channel",
    "id": "uuid",
    "display_name": "Магазин"
  },
  "created_at": "ISO8601"
}
```

### 7.6. Comments & emotions

| Method | Path | Scope | Description |
|--------|------|-------|-------------|
| POST | `/createComment` | `comment:write` | Comment на post/media/collection |
| POST | `/addEmotion` | `emotion:write` | Reaction |
| DELETE | `/removeEmotion` | `emotion:write` | Remove reaction |

### 7.7. Callbacks & commands

| Method | Path | Scope | Description |
|--------|------|-------|-------------|
| POST | `/answerCallbackQuery` | `callback:answer` | Answer callback query |
| POST | `/setCommands` | `command:manage` | Set bot commands for installation |
| DELETE | `/deleteCommands` | `command:manage` | Remove commands |

### 7.8. Media

| Method | Path | Description |
|--------|------|-------------|
| POST | `/initMediaUpload` | Presigned URL (public bucket) |
| POST | `/completeMediaUpload` | Finalize → `media_id` |

## 8. Idempotency

- Required: все POST mutations.
- Key: `Idempotency-Key` header или `idempotency_key` в body.
- Dedup window: 24h per `app_id` + key.

## 9. Pagination

```text
?cursor={opaque}&limit=50
```

## 10. Capability matrix (summary)

| Method | group | channel | community |
|--------|-------|---------|-----------|
| `sendGroupMessage` | ✓ (public) | — | — |
| `publishChannelPost` | — | ✓ | — |
| `notifyUser` | ✓ | ✓ | ✓ |
| `publishMediaPost` | — | — | ✓* |

\* Если community владеет media feed scope.

## 11. Запрещённые методы (не существуют)

- `sendPrivateMessage` / `createChat`
- `sendAsBot` / произвольный `sender_id`
- `sendEnvelope` / E2E path
- `initiateCall`
- `actAsVirtualUser`

## 12. Ссылки

- [bot-objects.md](./bot-objects.md)
- [bot-updates.md](./bot-updates.md)
- [bot-rate-limits.md](./bot-rate-limits.md)
- [api-guidelines.md](./api-guidelines.md)
- [rest-api.md](./rest-api.md) — `POST /inbox/notify` (MVP без ботов)
- [bot-platform.md](../02-architecture/bot-platform.md)
