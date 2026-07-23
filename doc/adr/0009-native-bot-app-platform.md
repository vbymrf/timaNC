# ADR-0009: Нативная Bot/App Platform TIMA

> **Статус:** `accepted` · **Дата:** 2026-07-12

## Контекст

TIMA нужна платформа для сторонних автоматизаций (ботов/приложений), вдохновлённая архитектурными идеями aiogram 3.x, но **без** Telegram Bot API-совместимости.

Ключевые продуктовые ограничения:
1. Бот **не имеет** собственной публичной личности — пишет только от имени группы, канала или сообщества.
2. Адресные сообщения попадают в **окно 4** «Социальное взаимодействие», не в личные чаты.
3. Виртуальные пользователи (ВП) — отдельная модель с E2E 1:1 и операторами.

## Решение

### 1. Нативный Bot API, не Telegram-compatible

- Отдельный контракт: `Authorization: Bot {token}`, base URL `/v1/bot/*`
- Schema-first types/methods (аналог aiogram butcher/codegen)
- Webhook primary, long polling fallback
- Router/Dispatcher/Filters/FSM — слой **SDK**, не серверной логики

**Отказ от Telegram-совместимости:** разные модели авторства, доставки и social objects TIMA.

### 2. Bot Application ≠ Virtual User

| | Bot Application | Virtual User |
|---|---|---|
| Таблица | `bot_applications` | `users` (`account_type=virtual`) |
| Автор в UI | group / channel / community | `@username` ВП |
| Private DM | Запрещён | Разрешён (E2E) |
| Окно 4 | Адресные сообщения от social object | Входящие DM к ВП |

### 3. Installation-only author model

- Каждая установка (`bot_installations`) привязана к **одному** `group`, `channel` или `community`.
- Сервер выводит `author_id` из установки; клиент не передаёт `sender_id`.
- `app_id` / `installation_id` — только provenance/audit.

### 4. Window 4 delivery для адресных сообщений

- Метод `notifyUser` создаёт карточку `entity_message` в окне 4 (`inbox_threads` / `inbox_events`); см. [bot-api.md](../05-api/bot-api.md).
- Private chat (`chats.type=1:1`) **не создаётся**.
- Пользователь видит карточку в окне 4 с автором = social object.

### 5. Defense in depth

| Слой | Проверка |
|------|----------|
| Bot Gateway | `InstallationPolicy`, scope, target match |
| Domain service | Author derivation, chat type gate |
| DB | Constraint: no bot content in `personal_messages` / private chats |

### 6. Архитектурный модуль

- `BotPlatform` — модуль Go monolith (MVP), отдельный ingress `/v1/bot/*`
- Outbox → EventBus (Redis Streams beta, Kafka production/GA) → webhook delivery + `SocialInboxProjection` consumer

## Последствия

### Положительные

- Чёткое разделение ВП и автоматизаций
- Нет обхода E2E через bot token
- Окно 4 — единая точка адресных обращений от social objects
- aiogram-паттерны переносятся в SDK без копирования Telegram API

### Отрицательные / trade-offs

- Существующие aiogram-боты **не** запускаются без адаптации
- Нет «написать пользователю в личку» — осознанное ограничение
- Дополнительный модуль и документация

## Альтернативы (отклонены)

| Альтернатива | Причина отклонения |
|--------------|-------------------|
| Telegram Bot API-compatible layer | Несовместимость с E2E, ВП, окном 4 |
| Bot как `users.account_type=bot` | Смешение с ВП, риск DM bypass |
| Bot с собственным `@username` | Противоречит «только от имени объекта» |
| Адресные сообщения в private chat | Противоречит продуктовой модели окна 4 |

## Ссылки

- [bot-application.md](../01-product/social-objects/bot-application.md)
- [virtual-user.md](../01-product/social-objects/virtual-user.md)
- [bot-platform.md](../02-architecture/bot-platform.md)
- [bot-api.md](../05-api/bot-api.md)
- [10-free-communication.md](../doc_UI/10-free-communication.md)
