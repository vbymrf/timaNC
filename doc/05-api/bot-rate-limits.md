# Bot API — Rate limits

> **Статус:** `done` (spec) · **Версия:** 0.3 · **Дата:** 2026-07-13

## 1. Общие правила

- Отдельные лимиты от user API ([rate-limits.md](./rate-limits.md)).
- При превышении: HTTP 429 + `BOT_RATE_LIMITED` + `retry_after` (seconds).
- Suspend: при repeated abuse → `installation.status=suspended`.

## 2. Лимиты по умолчанию (MVP)

| Scope | Limit | Window |
|-------|-------|--------|
| Per `app_id` (global) | 1000 requests | 1 min |
| Per `installation_id` | 60 write ops | 1 min |
| `notifyUser` per recipient per entity | 1 message | 1 hour |
| `notifyUser` per installation | 5 messages | 1 min |
| `sendGroupMessage` per group | 30 messages | 1 min |
| `publishChannelPost` per channel | 10 posts | 1 hour |
| `createComment` per target | 20 comments | 1 min |
| `answerCallbackQuery` | 100 | 1 min |
| Webhook delivery failures | 100 consecutive | → suspend webhook |
| `getUpdates` long poll connections | 5 per app | concurrent |

### `notifyUser` — дополнительные правила

- Только подписчикам/участникам сущности установки.
- Уважает пер-сущностную настройку `block_messages` (как `POST /inbox/notify`).
- Push-группировка карточек — **per сущность** (аналог «≤1 за 5 мин из одного чата» в [push-payloads.md](./push-payloads.md)).

## 3. Burst & backpressure

- Token bucket per `app_id` в Redis.
- Webhook: если developer endpoint lag > 1000 pending updates → email owner + throttle new updates.
- `entity_message` spam: per-recipient cap предотвращает окно-4 flooding.

## 4. Response headers

```http
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 42
X-RateLimit-Reset: 1718123456
Retry-After: 12
```

## 5. Error body

```json
{
  "error": {
    "code": "BOT_RATE_LIMITED",
    "message": "Rate limit exceeded for installation",
    "details": {
      "retry_after": 12,
      "scope": "installation_id",
      "limit": 60,
      "window_seconds": 60
    },
    "request_id": "uuid"
  }
}
```

## 6. Escalation

| Уровень | Действие |
|---------|----------|
| Warning | Log + metric |
| Soft block | 429 на 15 min |
| Suspend installation | Admin review |
| Revoke app | Repeated ToS violations |

## 7. Ссылки

- [bot-api.md](./bot-api.md)
- [rate-limits.md](./rate-limits.md)
- [rest-api.md](./rest-api.md) — `POST /inbox/notify` (те же лимиты в MVP)
- [threat-model.md](../03-security/threat-model.md)
