# Аудиочат (Voice Chat)

> **Статус:** `done` · **Владелец модуля:** VoiceChat Service + LiveKit  
> **Индекс:** [00-index.md](./00-index.md)

## Назначение

Голосовая комната (LiveKit, SRTP). Всегда принадлежит сообществу (`community_id` обязателен).

## Правила

| Правило | Описание |
|---------|----------|
| `community_id` | Обязателен |
| `community_access` | `preview` / `open` / `restricted` внутри сообщества — [communities.md](../communities.md) §3 |
| Auto-create | Wizard сначала создаёт контейнер, затем вызывает create room с обязательным `community_id` |
| Контекст UI | Опциональная слабая ссылка `attached_type=group|channel` + `attached_id` |
| ACL | LiveKit token после проверки ACL **аудиочата**, не только подписки на community |
| E2E | Нет (SRTP, [ADR-0006](../../adr/0006-livekit-media-policy.md)) |
| Запись | Выключена по умолчанию |

## Роли

Слушатель, спикер, модератор — [07-voice-chat.md](../../doc_UI/07-voice-chat.md).

## UI

- [07-voice-chat.md](../../doc_UI/07-voice-chat.md)
- Окно `0` — [21-call.md](../../doc_UI/21-call.md)

## Data / API

- `voice_rooms` — [data-model.md](../../04-data/data-model.md)
- REST: `/voice-rooms/*` — [rest-api.md](../../05-api/rest-api.md)
- Signaling: [call-signaling.md](../../06-realtime/call-signaling.md)

## История

| Версия | Дата | Изменение |
|--------|------|-----------|
| 0.1 | 2026-07-12 | Выделено из communities-groups-channels.md |
