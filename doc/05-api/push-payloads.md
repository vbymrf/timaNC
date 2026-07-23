# Push Payloads

## 1. Principles

- **Never** include private message plaintext.
- Collapse notifications per chat (UI: max 1 per 5 min).
- Night mode: silent push with data-only flag.

## 2. Payload by type

### Private message (E2E)

```json
{
  "type": "message",
  "chat_id": "uuid",
  "preview": "Новое сообщение",
  "encrypted": true,
  "collapse_key": "chat:{uuid}"
}
```

Phase 1 generic push не содержит sender, message text, media caption или другую private plaintext metadata. Client wakes, fetches envelope via REST/WS and decrypts locally; локальный UI может построить preview после decrypt согласно настройкам пользователя.

### Public post

```json
{
  "type": "public_post",
  "title": "Channel name",
  "body": "First 100 chars plaintext...",
  "encrypted": false
}
```

### Incoming call

```json
{
  "type": "call",
  "call_id": "uuid",
  "caller_name": "Alex",
  "media": "audio|video",
  "priority": "high"
}
```

VoIP push (APNs PushKit / FCM high priority).

### Mention

```json
{
  "type": "mention",
  "chat_id": "uuid",
  "preview": "Вас упомянули"
}
```

### Inbox thread (обращение к ВП / сущности)

```json
{
  "type": "inbox_thread",
  "thread_id": "uuid",
  "identity_name": "@redakcia_it",
  "status": "new",
  "preview": "Новое обращение",
  "collapse_key": "inbox:thread:{uuid}"
}
```

Клиент открывает окно 4; E2E preview расшифровывается локально, если доступен wrap.

### Entity message (окно 4, не личка)

```json
{
  "type": "entity_message",
  "message_id": "uuid",
  "source_type": "owner_api|bot",
  "source_entity_type": "channel|group|community",
  "source_name": "Канал «Новости IT»",
  "target_ref": {"type": "entity_message", "id": "uuid"},
  "preview": "Завтра стрим в 19:00",
  "encrypted": false,
  "collapse_key": "inbox:event:{uuid}"
}
```

Сущности **не пишут в личку** — только карточка `entity_message` в окне 4. Plaintext preview допустим; полный текст — `GET /v1/inbox/entity-messages/{id}`. Appeals — отдельные треды и этот payload не используют.

### Recovery request

```json
{
  "type": "recovery_request",
  "request_id": "uuid",
  "requester_name": "Alex",
  "chat_preview": "Личный чат",
  "encrypted": false,
  "priority": "high",
  "collapse_key": "recovery:{uuid}"
}
```

Текст: «Запрошено восстановление истории — это вы?» — без содержимого сообщений.

## 3. Platform specifics

| Platform | Channel |
|----------|---------|
| Android | FCM data + notification |
| iOS | APNs alert + PushKit for calls |
| Windows | WNS toast / polling fallback |

## 4. Privacy settings (UI 3 levels)

1. Global: all / important / silent
2. Per chat override
3. Per event type

Setting «hide preview» → generic «Новое сообщение» only.

## 5. Rate limiting

- Server: max 12 push/hour/user default.
- Burst for calls exempt.

## 6. Ссылки

- [doc_UI/26-notifications.md](../doc_UI/26-notifications.md)
