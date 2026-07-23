# Bot API — объекты (types)

> **Статус:** `done` (spec) · **Версия:** 0.3 · **Дата:** 2026-07-13

Schema-first: `schema/openapi/bot-api.yaml` ([ADR-0012](../adr/0012-schema-first-api.md)); forward-compatible (`unknown` fields ignored by clients).

Контент всех публичных объектов использует [DocumentV2](../04-data/message-document-format.md).

Bot API использует только public-проекцию: `metadata` всегда содержит `format_version=2`, положительный `revision_number` и `content_mode=public`; optional `nodes` / `markup` опускаются при отсутствии. Входные `null`, `[]` и `{}` нормализуются в отсутствие. `encrypted_nodes` и `encrypted_metadata` не выдаются и не принимаются. Media-only документ не содержит `nodes`; полностью пустой документ и пустой layout отклоняются по `has_content`.

## App

```json
{
  "app_id": "uuid",
  "name": "Order Notifier",
  "description": "Уведомления о заказах",
  "owner_user_id": "uuid",
  "status": "active",
  "created_at": "ISO8601"
}
```

## Installation

```json
{
  "installation_id": "uuid",
  "app_id": "uuid",
  "target": {
    "type": "channel",
    "id": "uuid",
    "display_name": "Магазин"
  },
  "capabilities": ["post:channel", "notify", "callback:answer"],
  "status": "active",
  "installed_at": "ISO8601"
}
```

| `target.type` | `author_id` в контенте |
|---------------|------------------------|
| `group` | `group_id` |
| `channel` | `channel_id` |
| `community` | `community_id` |

## SocialObjectRef

```json
{
  "type": "channel",
  "id": "uuid",
  "display_name": "Магазин"
}
```

Публичный автор контента. **Не** bot, **не** user.

## Update

Корневой объект webhook / getUpdates:

```json
{
  "update_id": 123456789,
  "installation_id": "uuid",
  "message": { },
  "callback_query": { },
  "comment": { },
  "entity_message_reply": { }
}
```

Ровно одно из полей события (discriminated union). См. [bot-updates.md](./bot-updates.md).

## Message (public group)

```json
{
  "message_id": 123456789,
  "group_id": "uuid",
  "author": { "type": "group", "id": "uuid", "display_name": "Dev Chat" },
  "document": {
    "nodes": ["Hello"],
    "markup": {"layout": {"type": "document", "children": [{"type": "paragraph", "nodes": [0]}]}},
    "metadata": {"format_version": 2, "revision_number": 1, "content_mode": "public"}
  },
  "from_user": { "user_id": "uuid", "username": "@alice" },
  "created_at": "ISO8601",
  "provenance": {
    "app_id": "uuid",
    "installation_id": "uuid"
  }
}
```

- `author` — social object (видимый отправитель).
- `from_user` — human, инициировавший событие (если inbound).
- `provenance` — технический audit (не в UI автора).

## EntityMessage

Карточка сообщения от сущности в окно 4 (`event_type=entity_message` в user API).

```json
{
  "message_id": "uuid",
  "entity_message_id": "uuid",
  "author": { "type": "channel", "id": "uuid", "display_name": "Магазин" },
  "recipient_user_id": "uuid",
  "document": {
    "nodes": ["Ваш заказ готов"],
    "markup": {"layout": {"type": "document", "children": [{"type": "paragraph", "nodes": [0]}]}},
    "metadata": {"format_version": 2, "revision_number": 1, "content_mode": "public"}
  },
  "link": { "type": "post", "id": "uuid" },
  "inline_keyboard": { },
  "delivery": "window_4",
  "created_at": "ISO8601",
  "provenance": {
    "app_id": "uuid",
    "installation_id": "uuid"
  }
}
```

`delivery` всегда `"window_4"`. Private chat не создаётся.

## Post

```json
{
  "post_id": 123456789,
  "channel_id": "uuid",
  "author": { "type": "channel", "id": "uuid", "display_name": "News" },
  "document": {
    "nodes": ["Announcement"],
    "markup": {"layout": {"type": "document", "children": [{"type": "paragraph", "nodes": [0]}]}},
    "metadata": {"format_version": 2, "revision_number": 1, "content_mode": "public"}
  },
  "published_at": "ISO8601",
  "provenance": { "app_id": "uuid", "installation_id": "uuid" }
}
```

## Comment

```json
{
  "comment_id": 123456789,
  "target_type": "post",
  "target_id": "123456789",
  "author": { "type": "channel", "id": "uuid", "display_name": "News" },
  "document": {
    "nodes": ["Thanks!"],
    "markup": {"layout": {"type": "document", "children": [{"type": "paragraph", "nodes": [0]}]}},
    "metadata": {"format_version": 2, "revision_number": 1, "content_mode": "public"}
  },
  "created_at": "ISO8601"
}
```

## CallbackQuery

```json
{
  "id": "uuid",
  "from": { "user_id": "uuid", "username": "@alice" },
  "installation_id": "uuid",
  "message": { },
  "data": "order:123",
  "created_at": "ISO8601"
}
```

## InlineKeyboard

```json
{
  "rows": [
    [
      { "text": "Да", "callback_data": "yes" },
      { "text": "Нет", "callback_data": "no" }
    ],
    [
      { "text": "Открыть", "url": "https://example.com" }
    ]
  ]
}
```

| Поле кнопки | Описание |
|-------------|----------|
| `callback_data` | ≤ 64 bytes; triggers `callback_query` update |
| `url` | External link (no callback) |

## Command

```json
{
  "command": "start",
  "description": "Начать",
  "scope": "installation"
}
```

## WebhookInfo

```json
{
  "url": "https://dev.example.com/tima/webhook",
  "has_custom_certificate": false,
  "pending_update_count": 0,
  "last_error_date": null,
  "last_error_message": null,
  "max_connections": 40,
  "allowed_updates": ["message", "callback_query", "entity_message_reply"]
}
```

## Provenance (audit-only)

```json
{
  "app_id": "uuid",
  "installation_id": "uuid",
  "method": "notifyUser",
  "request_id": "uuid"
}
```

Не отображается как автор в UI. Доступен owner в audit log.

## Ссылки

- [bot-api.md](./bot-api.md)
- [public-content-format.md](../01-product/public-content-format.md)
- [realtime-events.md](./realtime-events.md) — `inbox.event` с `event_type=entity_message`
