# ADR-0012: Schema-first API и два контура (Client / Bot)

**Статус:** принят · **Дата:** 2026-07-13

## Контекст

API мессенджера потребляют три стороны: KMP-клиенты, воркеры бэкенда и сторонние разработчики ботов. Ручная синхронизация типов между Go, Kotlin и SDK ботов гарантированно расходится.

[ADR-0009](./0009-native-bot-app-platform.md) фиксирует **продуктовую модель** Bot Platform (нативный API, окно 4, installation policy). Этот ADR фиксирует **процесс контрактов** — schema-first и разделение контуров.

## Решение

1. **Схема — источник истины.** Каталог `schema/` (в монорепо): `openapi/client-api.yaml`, будущий `openapi/bot-api.yaml`, `proto/tima/v1/{common,crypto,realtime}/*.proto`, `json/*.schema.json` и `canonical/`. Go/Kotlin модели сохраняются в `gen/`; Bot SDK и портал документации добавляются в своей фазе.
   - Крипто-ядро: `proto/tima/v1/crypto/*.proto`, DocumentV2 JSON Schemas, правила canonical encoding и golden/tamper fixtures — контракт, воспроизводимый независимой реализацией.
2. **Правка API = правка схемы** (PR со схемой); CI проверяет обратную совместимость (oasdiff) и регенерирует артефакты.
3. **Два контура с разными гарантиями:**

| | Client API | Bot API |
|---|---|---|
| Стиль | REST `/v1/*` | Method-oriented `/v1/bot/{method}` ([bot-api.md](../05-api/bot-api.md)) |
| Стабильность | Меняем свободно (владеем обоими концами) | Контракт: только аддитивные изменения; deprecation-цикл ≥ 6 месяцев |
| Auth | Device JWT + аттестация | Bot token + HMAC + webhook secret |
| Область | Всё | Только публичный контур, права по scopes |

## Отклонено

- Ручные типы на каждой стороне — источник рассинхрона.
- Единый контур для клиентов и ботов — публичный контракт заморозил бы внутренние итерации.
- REST-стиль для Bot API — метод-ориентированный RPC знаком экосистеме Telegram-ботов.

## Последствия

- Документы [rest-api.md](../05-api/rest-api.md) и [bot-api.md](../05-api/bot-api.md) — человекочитаемые карты; машинная истина — `schema/`.
- Схема-репозиторий и кодогенерация закладываются в фазе 0 ([roadmap.md](../09-delivery/roadmap.md)).
- Официальный aiogram-подобный SDK — отдельный проект поверх HTTP API, пост-MVP ([python-bot-sdk.md](../10-sdk/python-bot-sdk.md)).

## References

- [ADR-0009](./0009-native-bot-app-platform.md)
- [api-guidelines.md](../05-api/api-guidelines.md)
