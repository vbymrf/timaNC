# Сообщество (Community)

> **Статус:** `done` · **Владелец модуля:** Community Service  
> **Индекс:** [00-index.md](./00-index.md)

## Назначение

Администраторский серверный контейнер: объединяет группы, каналы, аудиочаты. Управляет подпиской, discoverability и attach/detach дочерних объектов.

**Не является:** папкой каталога, коллекцией, чатом.

## Функционал

- CRUD сообщества, owner/admin/moderator/subscriber
- Подписка / отписка
- Список дочерних объектов с `community_access`
- Attach существующей группы/канала или создание внутри
- Агрегированная лента и каталог внутри контейнера

## Подписка и ACL

Подписка на сообщество **не гарантирует** доступ к private group, закрытому каналу или аудиочату. Детали — [communities.md](../communities.md) §3.

| `community_access` | Без подписки | С подпиской |
|--------------------|--------------|-------------|
| `preview` | ✅ контент | ✅ контент |
| `open` | список виден, контент закрыт | ✅ контент в лентах |
| `restricted` | скрыт или без доступа* | только по приглашению |

\* `restricted_visible` — показывать название без доступа.

## Роли

| Роль | Права |
|------|-------|
| owner | Полное управление, передача, удаление |
| admin | Объекты, `community_access`, модераторы, политики |
| moderator | Модерация `open`/`preview` элементов |
| subscriber | Лента, каталог (в рамках ACL) |

## UI

- [35-community.md](../../doc_UI/35-community.md) — страница сообщества
- [36-create-group-channel.md](../../doc_UI/36-create-group-channel.md) — мастер создания
- [04-news-window.md](../../doc_UI/04-news-window.md) — каталог
- [14-personal-page.md](../../doc_UI/14-personal-page.md) — «Мои сообщества»
- [16-profile-popup.md](../../doc_UI/16-profile-popup.md) — карточка
- [17-global-search.md](../../doc_UI/17-global-search.md)
- [28-qr-invites.md](../../doc_UI/28-qr-invites.md)

## Data / API / Events

- Таблицы: `communities`, `community_memberships`, `community_objects` — [data-model.md](../../04-data/data-model.md)
- REST: `/communities/*` — [rest-api.md](../../05-api/rest-api.md)
- WS: `community.*` — [realtime-events.md](../../05-api/realtime-events.md)

## Безопасность

Контейнер **не задаёт** шифрование дочерних объектов. Изменение подписки **не** ротирует GK private group.

## История

| Версия | Дата | Изменение |
|--------|------|-----------|
| 0.1 | 2026-07-12 | Выделено из communities-groups-channels.md |
| 0.2 | 2026-07-13 | ACL v2: `preview` / `open` / `restricted` |
