# Bot API — Updates (webhook & long polling)

> **Статус:** `done` (spec) · **Версия:** 0.3 · **Дата:** 2026-07-13

## 1. Delivery modes

| Mode | Приоритет | Описание |
|------|-----------|----------|
| **Webhook** | Primary | HTTP POST на URL разработчика |
| **Long polling** | Fallback | `GET /getUpdates` когда webhook не настроен |

User WebSocket (`/v1/ws`) **не** используется для Bot API.

## 2. Webhook

### setWebhook

```http
POST /v1/bot/setWebhook
Authorization: Bot {token}
```

```json
{
  "url": "https://dev.example.com/tima/webhook",
  "secret_token": "random-secret",
  "allowed_updates": ["message", "callback_query", "comment", "entity_message_reply"],
  "max_connections": 40
}
```

### Incoming POST (server → developer)

```http
POST {url}
Content-Type: application/json
X-TIMA-Webhook-Secret: {secret_token}
```

Body: [Update](./bot-objects.md#update) JSON.

- Fast ACK: сервер возвращает 200 до завершения обработки у разработчика (если `handle_in_background=true`).
- Timeout: 55s; при превышении — retry.

### Security

| Мера | Описание |
|------|----------|
| Secret header | `X-TIMA-Webhook-Secret` обязателен |
| HTTPS only | TLS 1.3 |
| SSRF protection | No private IPs, no localhost, URL validation |
| Certificate pinning | Optional custom cert (post-MVP) |

## 3. Long polling

```http
GET /v1/bot/getUpdates?offset=123&timeout=30&allowed_updates=message,callback_query
Authorization: Bot {token}
```

| Param | Description |
|-------|-------------|
| `offset` | Acknowledge updates with `update_id < offset` |
| `timeout` | Long poll seconds (max 50) |
| `limit` | Max updates per response (default 100) |
| `allowed_updates` | Filter event types |

Response:

```json
{
  "ok": true,
  "result": [
    { "update_id": 124, "installation_id": "uuid", "callback_query": { } }
  ]
}
```

## 4. Update types

| Field | Trigger |
|-------|---------|
| `message` | Inbound message в public group установки |
| `comment` | Новый comment на target в scope установки |
| `callback_query` | Нажатие inline button |
| `entity_message_reply` | Ответ пользователя на карточку `entity_message` в окне 4 |
| `installation` | Install approved / revoked / capabilities changed |

Вложенный `document` — public `DocumentV2`: required `metadata` (`format_version=2`, положительный `revision_number`, `content_mode=public`) и optional `nodes` / `markup`. Отсутствующие поля опускаются; `null`, `[]` и `{}` нормализуются в отсутствие. Encrypted/private-поля не доставляются. Media-only update не содержит `nodes`; `has_content=false`, включая один пустой layout, не enqueue-ится.

### entity_message_reply example

```json
{
  "update_id": 125,
  "installation_id": "uuid",
  "entity_message_reply": {
    "reply_to_message_id": "uuid",
    "entity_message_id": "uuid",
    "from": { "user_id": "uuid", "username": "@alice" },
    "document": {
      "nodes": ["Спасибо!"],
      "markup": {"layout": {"type": "document", "children": [{"type": "paragraph", "nodes": [0]}]}},
      "metadata": {"format_version": 2, "revision_number": 1, "content_mode": "public"}
    },
    "created_at": "ISO8601"
  }
}
```

## 5. Ordering & idempotency

- `update_id` — monotonic per `app_id`.
- Developer MUST process with `offset = last_update_id + 1`.
- Duplicate delivery possible; dedup by `update_id`.

## 6. Retry policy

| Attempt | Delay |
|---------|-------|
| 1 | immediate |
| 2 | 1s |
| 3 | 5s |
| 4 | 30s |
| 5 | 300s |
| 6+ | 3600s (max 24h retention) |

After max retries: `pending_update_count` in `getWebhookInfo`; alert owner.

## 7. allowed_updates

Как aiogram `resolve_used_update_types`:

- Если не указано — все типы, на которые у installation есть scope.
- Фильтрация на сервере до enqueue.

## 8. Ссылки

- [bot-api.md](./bot-api.md)
- [bot-objects.md](./bot-objects.md)
- [bot-platform.md](../02-architecture/bot-platform.md)
