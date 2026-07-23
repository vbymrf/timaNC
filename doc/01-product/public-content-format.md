# DocumentV2 — публичная проекция

> **Статус:** `done` · **Версия:** 2.0  
> Полная нормативная схема: [message-document-format.md](../04-data/message-document-format.md).

Публичный контент использует массив текстовых узлов и отдельный JSONB markup. Формат полностью заменяет `text/body + entities(offset, length)` и media-placeholder.

```json
{
  "nodes": ["Пример ", "ссылки"],
  "markup": {
    "entities": [
      {"type": "bold", "nodes": [0]},
      {"type": "text_link", "nodes": [1], "href": "https://example.org"}
    ],
    "layout": {
      "type": "document",
      "children": [{"type": "paragraph", "nodes": [0, 1]}]
    }
  },
  "metadata": {
    "format_version": 2,
    "revision_number": 1,
    "content_mode": "public",
    "attribute_ids": ["uuid"]
  }
}
```

## Правила

- `metadata` обязателен: `format_version=2`, `revision_number`, `content_mode=public`.
- `nodes` и `markup` опциональны; отсутствующие поля опускаются в API и представлены `NULL` в SQL. `[]` и `{}` не заменяют отсутствие.
- Индекс строки в присутствующем `nodes` является `node_id`.
- Entities ссылаются на узлы массивом `nodes`; offsets и placeholders запрещены.
- Media-only документ опускает `nodes`. `has_content` требует непустой текст или реальный media/content binding; пустой layout и полностью пустой документ отклоняются.
- Public DocumentV2 не может содержать `encrypted_nodes`, `encrypted_metadata` или private `secret_ref`; публичная ссылка задаётся `text_link`.
- Максимальная глубина layout — 10; суммарный текст — не более 4096 Unicode code points.
- HTML/Markdown преобразуются в DocumentV2 до сохранения.
- Executable и активный контент блокируются; `code` отображается как inert text.
- Редактирование создаёт неизменяемую ревизию.

## Медиа

- Entity `media` содержит `media_id`; бинарные данные не встраиваются.
- Варианты: `thumbnail`, `preview`, `full`; Original отсутствует.
- Public media проходит server-side MIME/AV/sanitize/transcode до `ready`.
- Доступ начинается с авторизованного запроса; presigned URL действует 15 минут.

## API и хранение

- `/v1/posts`, `/v1/comments` и public `/v1/groups/{group_id}/messages` принимают публичный DocumentV2.
- Входные `null`, пустые `[]` и `{}` optional-полей нормализуются в отсутствие до канонизации; API опускает поле, SQL хранит `NULL`, storage не сохраняет пустые контейнеры.
- `metadata.attribute_ids` ссылается на реестр attributes; статус связи хранится в `post_attributes`.

## Где используется

- [37-content-editor.md](../doc_UI/37-content-editor.md)
- [32-channel-editor.md](../doc_UI/32-channel-editor.md)
- [33-media-editor.md](../doc_UI/33-media-editor.md)
- [19-attachments-media.md](../doc_UI/19-attachments-media.md)
