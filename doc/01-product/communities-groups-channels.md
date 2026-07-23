# Сообщества, группы, каналы и аудиочаты

> **Статус:** `legacy` · **Версия:** 0.3 · **Дата:** 2026-07-13  
> **Канон:** [social-objects/00-index.md](./social-objects/00-index.md), [communities.md](./communities.md)
> **Роль:** точка входа. **Канон разделён** по профильным файлам — правки вносить туда, не сюда.

## Куда смотреть

| Тема | Документ |
|------|----------|
| **Индекс и границы модулей** | [social-objects/00-index.md](./social-objects/00-index.md) |
| **Сообщества (канон ACL)** | [communities.md](./communities.md) |
| Сообщество | [social-objects/community.md](./social-objects/community.md) |
| Группа | [social-objects/group.md](./social-objects/group.md) |
| Канал (только посты) | [social-objects/channel.md](./social-objects/channel.md) |
| Аудиочат | [social-objects/audio-chat.md](./social-objects/audio-chat.md) |
| Формат публикаций | [public-content-format.md](./public-content-format.md) |
| Комментарии (общая подсистема) | [15-comments.md](../doc_UI/15-comments.md) + [data-model.md](../04-data/data-model.md) § comments |

## Кратко (не дублировать детали)

- **Сообщество** — admin-контейнер; подписка ≠ доступ ко всем объектам; `community_access`: `preview` / `open` / `restricted`.
- **Папка каталога** — только клиент; ≠ сообщество.
- **Канал** — только посты; обсуждение в Comments к посту.
- **Группа** — переписка; E2E или public; threads, не Comments.
- **Аудиочат** — всегда в сообществе; auto-create community при создании.
- **Comments** — публичный контур: post, media, collection_item.

## Связанные документы

- [requirements.md](./requirements.md) · [glossary.md](./glossary.md) · [content-security-matrix.md](./content-security-matrix.md)
- [backend-services.md](../02-architecture/backend-services.md) · [data-model.md](../04-data/data-model.md) · [rest-api.md](../05-api/rest-api.md)

## История

| Версия | Дата | Изменение |
|--------|------|-----------|
| 0.1 | 2026-07-12 | Первый монолитный draft |
| 0.2 | 2026-07-12 | Разделение на social-objects/; этот файл — индекс |
| 0.3 | 2026-07-13 | ACL v2; ссылка на communities.md |
