# Группа (Group)

> **Статус:** `done` · **Владелец модуля:** Group Service  
> **Индекс:** [00-index.md](./00-index.md)

## Назначение

Текстовый чат с участниками: **личная (E2E)** или **публичная (plaintext)**. Обсуждение — поток сообщений и message threads, **не** подсистема Comments.

## Типы

| Тип | Crypto | Лента [+]/[−] |
|-----|--------|---------------|
| Private / личная | GK + Sender Keys | Нет |
| Public / публичная | Plaintext | Да |

## Функционал

- Участники, @упоминания, медиа, голосовые сообщения
- Админ, модерация, read-only режим
- Опционально: звонок, аудиочат сообщества
- Опциональный родитель: `community_id`; при вхождении в сообщество — `community_access` (`preview` / `open` / `restricted`). См. [communities.md](../communities.md) §3.

## Роли

Участник, админ — см. [05-group-chat.md](../../doc_UI/05-group-chat.md).

## Обсуждение

- Режим определяется совместно `groups.kind` и обязательным `metadata.content_mode`; несовпадение отклоняется.
- **Private/E2E:** `content_mode=private`; `encrypted_nodes`, открытый `markup` и единый `encrypted_metadata` опциональны. Ссылка использует entity `text_link` с открытым `secret_ref`; URL находится в `encrypted_metadata`, открытый `href` запрещён; threads внутри чата.
- **Public:** `content_mode=public`; опциональны plaintext `nodes` и `markup`, encrypted-поля и private `secret_ref` запрещены; публичные ссылки используют `text_link`.
- Отсутствующие опциональные поля передаются omitted/`NULL`; `[]`, `{}` и пустой ciphertext запрещены. Media-only опускает текстовый массив, а `has_content` требует непустой текст или реальный media/content binding — пустого layout недостаточно.
- **Comments subsystem не используется** — см. [content-security-matrix.md](../content-security-matrix.md)
- API сообщений: доменный `/v1/groups/{group_id}/messages`; редактирование создаёт immutable revision

## UI

- [05-group-chat.md](../../doc_UI/05-group-chat.md)
- [16-profile-popup.md](../../doc_UI/16-profile-popup.md)

## Data / API / Events

- GK: `group_key_history`, `user_wrapped_keys`
- REST: `/groups/*`, `/chats/*` — [rest-api.md](../../05-api/rest-api.md)
- WS: `message.created`, `message.edited`, `message.deleted`, `gk.rotate`

## Безопасность

- Private: [crypto-protocol.md](../../03-security/crypto-protocol.md); GK rotation on join/leave/kick + every 100 messages; peer recovery per [ADR-0014](../../adr/0014-participant-e2e-and-recovery.md)
- Community admin **не** получает GK автоматически

## История

| Версия | Дата | Изменение |
|--------|------|-----------|
| 0.1 | 2026-07-12 | Выделено из communities-groups-channels.md |
