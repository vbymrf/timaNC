# DocumentV2: формат сообщений и публичного контента

> **Статус:** normative · **Версия:** 2.0 · **Дата:** 2026-07-22  
> Применяется к личным и групповым сообщениям, постам, комментариям и `entity_message`. Криптографический контур private-сообщений определён в [crypto-protocol.md](../03-security/crypto-protocol.md).

## 1. Модель документа

`DocumentV2` заменяет `body + entities(offset, length)` и символы-заглушки.

```text
encrypted_nodes[]  — optional: зашифрованный текст private/E2E
nodes[]            — optional: plaintext-текст public/social
markup JSONB       — optional: entities/layout/media bindings
encrypted_metadata — optional: зашифрованные URL, имена файлов, ключи медиа
metadata JSONB     — required: версия, ревизия, content_mode
```

Для public/social вместо `encrypted_nodes[]` хранится plaintext `nodes TEXT[]`; чувствительных private-полей в public-контуре нет.

### 1.1. Nullability

| Сценарий | Text field | `markup` | `encrypted_metadata` | `metadata` |
|----------|------------|----------|----------------------|------------|
| Private text-only | `encrypted_nodes` заполнен | `NULL` | `NULL` | required, `private` |
| Private media-only | `encrypted_nodes = NULL` | media binding + `secret_ref` | required media key; optional name/caption | required, `private` |
| Private rich text/link | `encrypted_nodes` заполнен | entities/layout | `NULL` либо secrets по `secret_ref` | required, `private` |
| Public text-only | `nodes` заполнен | `NULL` | отсутствует | required, `public` |
| Public media-only | `nodes = NULL` | media binding | отсутствует | required, `public` |

- В PostgreSQL отсутствие optional-поля хранится как SQL `NULL`.
- В REST/SDK optional-поле опускается; явный JSON `null` нормализуется в отсутствие до канонизации.
- Пустые arrays `[]`, пустой object `{}` и AEAD от пустого объекта не являются отдельным состоянием: write boundary нормализует их в отсутствие.
- `metadata` никогда не бывает `NULL`.

## 2. Узлы и содержимое

- Индекс элемента массива является `node_id`.
- Private: каждый непустой текстовый узел сериализуется и шифруется клиентом; сервер хранит `BYTEA[]`.
- Public/social: сервер хранит `TEXT[]` и валидирует текст.
- Если текста нет, соответствующее поле `nodes` / `encrypted_nodes` отсутствует в API и хранится как SQL `NULL`.
- Непустой text array содержит только непустые узлы.
- Документ без текста и реального media/content binding отклоняется.
- Пустой layout, контейнер или `children=[]` сам по себе содержимым не является.
- Невидимые символы-якоря и media-placeholder запрещены.
- Суммарный текст документа — не более 4096 Unicode code points.

Формальный `has_content(document)`:

1. `cardinality(nodes|encrypted_nodes) > 0`; или
2. `markup.entities` содержит `media` с `media_id`; или
3. layout рекурсивно содержит media/content binding с `media_id`.

Иначе документ невалиден. Для draft допускается временно пустое состояние, но publish/send применяет `has_content`.

## 3. Открытая разметка

```json
{
  "entities": [
    {"type": "bold", "nodes": [0]},
    {"type": "text_link", "nodes": [1], "secret_ref": "link-1"},
    {"type": "media", "media_id": "uuid", "secret_ref": "media-1"}
  ],
  "layout": {
    "type": "document",
    "children": [
      {"type": "paragraph", "nodes": [0, 1]}
    ]
  }
}
```

Допустимые entity: `bold`, `italic`, `underline`, `strikethrough`, `code`, `pre`, `text_link`, `text_mention`, `hashtag`, `spoiler`, `custom_emoji`, `media`.

Допустимые layout-блоки: `document`, `paragraph`, `heading`, `quote`, `list`, `list_item`, `div`, `table`, `flex`, `grid`. Максимальная глубина — 10.

`markup` не содержит пользовательский текст, URL, исходные имена файлов, ключи или captions. Индексы узлов, типы блоков, `media_id`, размеры layout и `secret_ref` открыты серверу.

Для простого текста без entities/layout/media поле `markup` отсутствует и хранится как SQL `NULL`. `{}` не хранится.

## 4. Зашифрованные метаданные

`encrypted_metadata BYTEA` — не более одного AEAD-blob на ревизию:

```json
{
  "link-1": {"href": "https://example.test/private"},
  "media-1": {
    "file_name": "document.pdf",
    "media_key": "base64",
    "caption": "optional"
  }
}
```

- `secret_ref` уникален в пределах ревизии.
- Если `secret_ref` отсутствует, `encrypted_metadata` отсутствует и хранится как SQL `NULL`; пустой объект не шифруется.
- Если существует хотя бы один `secret_ref`, `encrypted_metadata` обязателен, и каждый ref должен разрешаться после decrypt.
- Ключи расшифрования никогда не включаются в URL или открытый JSONB.
- `encrypted_metadata` без соответствующего `secret_ref` отклоняется.

## 5. Служебные метаданные

```json
{
  "format_version": 2,
  "revision_number": 1,
  "content_mode": "private"
}
```

Обязательны `format_version=2`, положительный `revision_number` и `content_mode=private|public`. Для групп сервер сверяет `content_mode` с `groups.kind`; для 1:1 допустим только `private`. Поля, позволяющие восстановить пользовательское содержание, переносятся в `encrypted_metadata`.

## 6. Private/E2E

- Один `message_key` защищает присутствующие узлы и, при наличии, `encrypted_metadata`; для каждого ciphertext используется уникальный nonce.
- Wrapped keys, ratchet и обязательный escrow сохраняются по [crypto-protocol.md](../03-security/crypto-protocol.md).
- Сервер валидирует nullability, `content_mode`, форму JSONB, индексы, лимиты и подпись, но не plaintext.
- Canonical encoder сначала нормализует `null`, `[]` и `{}` optional-полей в отсутствие, затем формирует 4-битный presence bitmap в фиксированном порядке: `encrypted_nodes`, `nodes`, `markup`, `encrypted_metadata`. В private 1:1 бит `nodes` всегда `0`; в group text-биты не могут быть одновременно `1`, но оба могут быть `0` для media-only.
- Подпись покрывает presence bitmap, идентификаторы сообщения, ordered ciphertexts, nullable `markup`, обязательный `metadata`, nullable `encrypted_metadata` и ссылки на медиа.
- AEAD AAD включает тот же presence bitmap, canonical `markup` либо null sentinel и canonical `metadata`; отсутствие нельзя подменить пустым значением.

## 7. Медиа

В `markup` хранится только открытый `media_id` и технический тип. В текущем private pipeline каждый вариант шифруется отдельным `media_key`, поэтому private media binding всегда содержит `secret_ref`, а `encrypted_metadata` — соответствующий ключ; имя и caption опциональны. Text field при media-only равен SQL `NULL`.

Клиент запрашивает доступ через доменный API, получает presigned URL на 15 минут, скачивает ciphertext и расшифровывает нужный вариант. Правила обработки определены в [media-storage.md](./media-storage.md).

## 8. Неизменяемые ревизии

- Редактирование создаёт новую ревизию, а не изменяет существующую.
- Активная ревизия определяется UUID `current_revision_id`; `revision_number` задаёт порядок.
- Предыдущие ревизии сохраняются по retention/legal-hold.
- Доменное событие `message.edited`, `post.edited` или `comment.edited` содержит `revision_id`, `parent_revision_id` и `revision_number`; клиент инвалидирует render cache.
- Удаление сообщения остаётся soft delete.

## 9. API-проекция

- Личные сообщения: `/v1/chats/{chat_id}/messages`.
- Групповые сообщения: `/v1/groups/{group_id}/messages`.
- Публичный контент: `/v1/posts`, `/v1/comments`.
- Все endpoints используют `DocumentV2`; private передаёт `encrypted_nodes`, public — `nodes`. Optional-поля опускаются, `metadata` всегда передаётся.

## 10. Миграция

- `format_version=1` с `offset/length` и placeholder считается legacy read-only.
- Новые записи создаются только как `format_version=2`.
- Клиент преобразует legacy-документ в узлы локально; сервер не пересчитывает offsets private-контента.
- Bot API и SDK принимают только node-index entities после переходного периода версии API.

