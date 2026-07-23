# Канал (Channel)

> **Статус:** `done` · **Владелец модуля:** Channel Service + PublicPublishing  
> **Индекс:** [00-index.md](./00-index.md)

## Назначение

Односторонняя или ограниченная публикация (plaintext). Подписчики читают; пишут админы и авторы.

## Модель «канал = только посты»

| Владеет Channel | Не владеет Channel |
|-----------------|-------------------|
| Посты (`posts`, `author_type=channel`) | Комментарии → **Comments** subsystem |
| Подписчики, авторы, роли | Эмоции на комментарии → **Emotions** |
| `comments_count` (денорм.) | Чат-поток (его нет) |

Обсуждение поста — тред комментариев: [15-comments.md](../../doc_UI/15-comments.md), `target_type=post`, `target_id=post_id`.

## Функционал

- Лента постов (`DocumentV2`, [public-content-format.md](../public-content-format.md))
- Подписчики, авторы, модераторы, админы
- Черновики, расписание, публикация — [37-content-editor.md](../../doc_UI/37-content-editor.md), [32-channel-editor.md](../../doc_UI/32-channel-editor.md)
- Обязательный `community_id`; `community_access` внутри сообщества — [communities.md](../communities.md) §3
- Подписка на канал выполняется отдельно через `/channels/{id}/subscribe`; подписка на сообщество не подписывает на дочерние каналы
- Аудиочат сообщества — отдельная сущность [audio-chat.md](./audio-chat.md)

## Роли

Подписчик, автор, модератор, админ — [06-channel.md](../../doc_UI/06-channel.md).

## UI

- [06-channel.md](../../doc_UI/06-channel.md) — лента + compact composer
- [32-channel-editor.md](../../doc_UI/32-channel-editor.md) — ветка редактора канала
- [37-content-editor.md](../../doc_UI/37-content-editor.md) — единый редактор
- [30-blogger-news-window.md](../../doc_UI/30-blogger-news-window.md) — блогерский вход

## Data / API

- `posts`, `post_drafts` — [data-model.md](../../04-data/data-model.md) §7
- REST: full CRUD `/channels/*`, отдельные subscribe/unsubscribe, доменные `/posts/*` — [rest-api.md](../../05-api/rest-api.md)
- Comments: `GET/POST /comments?target_type=post&target_id=`
- Редактирование опубликованного поста создаёт immutable revision; существующая ревизия не перезаписывается

## История

| Версия | Дата | Изменение |
|--------|------|-----------|
| 0.1 | 2026-07-12 | Выделено; зафиксировано «только посты» |
