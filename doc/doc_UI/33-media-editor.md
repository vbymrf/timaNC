# Редактор медиа-поста (публичный)

> **Формат:** WireMD · **Подпись:** [public-content-format.md](../01-product/public-content-format.md)  
> **Единый редактор:** [37-content-editor.md](./37-content-editor.md) (ветка «медиа-пост»)  
> **Отдельный** от [32-channel-editor.md](./32-channel-editor.md)

## Назначение

Media-first публикация: фото, видео, альбом, подпись как `DocumentV2`. Публичный контур: медиа-лента, блогер, лента сообщества.

## Точки входа

- [29-blogger-media-window.md](./29-blogger-media-window.md) — [+ Новый пост]
- [08-media-window.md](./08-media-window.md) — создание (роль автора)
- Черновики блогера

## Шапка

::: row
[[ [<] ]]
[[ **Новый медиа-пост** ]]
[[ Предпросмотр ]]
:::

::: row
Публикация в: [Медиа-лента v]  ((или сообщество / ВП — `author_user_id`))
:::

## Медиа-зона

::: card

::: columns-3
::: column [img превью 1]
::: column [img превью 2]
::: column [+ Добавить]
:::

Порядок: drag / [◄][►]

Вариант: [Full v]  Обложка видео: [Выбрать кадр]

:::

## Подпись

::: row
[[ Подпись... @ # ]]
[[ :emoji: ]]
:::

> Public DocumentV2 всегда содержит metadata (`format_version=2`, `revision_number`, `content_mode=public`). Текстовая подпись (caption) добавляет `nodes`; без caption media-only документ опускает `nodes`, не передаёт `[]`. `markup` присутствует только с реальным media binding, encrypted-поля запрещены. Hashtag-node или поле атрибутов → единый реестр ([34-attributes-genres](./34-attributes-genres.md)). Alt-текст — в metadata media-node.

## Нижняя панель

::: row
[[ Черновик ]] [[ Запланировать ]] [[ Опубликовать ]]{primary}
:::

## Состояния

| Состояние | UI |
|-----------|-----|
| `uploading` | Прогресс per asset |
| `processing` | Видео: «Обработка…» |
| `ready` | Можно опубликовать |
| `draft` / `scheduled` / `published` / `failed` | Как в channel editor |

> `ready` включает публикацию только при наличии реального media/content binding; пустой layout не считается содержимым.

## Видео

::: row
Upload [########____] 60%
Обработка: [====------] 40%
:::

> WS: `media.processing`, `media.ready`, `media.failed` — [realtime-events.md](../05-api/realtime-events.md)

## Комментарии

После публикации обсуждение — [15-comments.md](./15-comments.md), `target_type=media`.

## API

`POST /v1/posts`, `POST /v1/posts/drafts`, `PUT /v1/posts/drafts/{id}`, `POST /v1/posts/drafts/{id}/publish` — [rest-api.md](../05-api/rest-api.md)

## Upload

Публичный pipeline — [19-attachments-media.md](./19-attachments-media.md) § Public editor (не E2E chat path).
