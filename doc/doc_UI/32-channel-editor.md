# Редактор публикации канала

> **Формат:** WireMD · **Канон контента:** [public-content-format.md](../01-product/public-content-format.md)  
> **Единый редактор:** [37-content-editor.md](./37-content-editor.md) (ветка «пост канала / статья»)  
> **Модель канала:** только посты — [channel.md](../01-product/social-objects/channel.md)

## Назначение

Полноценный редактор публикации в канал: черновики, предпросмотр, расписание, `DocumentV2`.

Отдельный от [33-media-editor.md](./33-media-editor.md) и от чатового composer в [06-channel.md](./06-channel.md).

## Точки входа

- [06-channel.md](./06-channel.md) — «Развернуть редактор» из compact composer
- [30-blogger-news-window.md](./30-blogger-news-window.md) — публикация в канал блогера
- Черновики на [14-personal-page.md](./14-personal-page.md) (роль автора)

## Шапка

::: row
[[ [<] ]]
[[ **Новая публикация** ]]
[[ Предпросмотр ]]
[[ :more: ]]
:::

::: row
Канал: [Мой канал IT v]
Автор: [@brand_bot v]  ((основной аккаунт или доступный ВП; `author_user_id`))
:::

## Основная зона

::: card

Заголовок (опционально)
[_________________________]

Текст
[_________________________]{rows:10}

Панель форматирования:
[**B**] [_I_] [U] [S] [</>] [🔗] [@] [#]

:::

> Форматирование визуальное; public `DocumentV2` всегда содержит metadata (`format_version=2`, `revision_number`, `content_mode=public`). `nodes` и `markup` добавляются только при наличии и не заменяются `[]`/`{}`; encrypted-поля запрещены. Media-only опускает `nodes`; HTML не сохраняется, offsets и placeholders не используются.
> **[# атрибут]** — атрибуты поста (= хэштеги, единый реестр): автодополнение; нет совпадений → создание нового с предложением жанра ([34-attributes-genres](./34-attributes-genres.md)). Hashtag-node резолвится в тот же реестр.

## Вложения через nodes

::: row
[[ + Медиа ]] [[ + Файл ]]
:::

> Media-node с `media_id` после server-side проверки upload ([19-attachments-media.md](./19-attachments-media.md) § Public editor pipeline).
> Опубликовать можно только непустой текст или готовый реальный media/content binding; пустой layout не считается содержимым.

## Нижняя панель

::: row
[[ Сохранить черновик ]] [[ Запланировать ]] [[ Опубликовать ]]{primary}
:::

## Запланировать

::: modal
## Публикация по расписанию

Дата и время
[12.07.2026 18:00___________]

[Сохранить]*
:::

## Состояния

| Состояние | UI |
|-----------|-----|
| `editing` | Редактор активен |
| `draft` | «Сохранено в черновики» |
| `scheduled` | Бейдж «Запланировано» |
| `publishing` | Прогресс upload + spinner |
| `published` | Закрытие → лента канала |
| `failed` | Alert + [Повторить] |

## Права

| Роль | Действия |
|------|----------|
| Автор | Черновик, публикация в разрешённые каналы от `author_user_id` (human или ВП); audit: `actor_user_id` |
| Модератор | + редактирование чужих постов |
| Админ | + удаление, расписание от имени канала |

## Опубликованный пост

- Редактирование → новая immutable revision со ссылкой на предыдущую; опубликованная ревизия не перезаписывается
- `comments_count` обновляется Comments subsystem, не Channel module

## Ошибки

::: alert error
Не удалось загрузить медиа. [Повторить]*
:::

::: alert error
Недостаточно прав для публикации в этот канал.
:::

## API

`POST /v1/posts`, `POST /v1/posts/drafts`, `PUT /v1/posts/drafts/{id}`, `POST /v1/posts/drafts/{id}/publish`; edit опубликованного поста — `POST /v1/posts/{id}/revisions` — [rest-api.md](../05-api/rest-api.md)
