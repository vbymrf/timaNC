# ADR-0015: DocumentV2 и раздельный media pipeline

**Статус:** принят · **Дата:** 2026-07-22

## Контекст

Формат `body + entities(offset, length)` и символы-placeholder связывают модель хранения с конкретным текстовым представлением, плохо поддерживают блочный layout, вложенные media и неизменяемые ревизии. Общий media pipeline также не может одновременно обеспечить participant E2E для private-контента и серверную проверку public-контента.

[ADR-0014](./0014-participant-e2e-and-recovery.md) остаётся каноном participant E2E, escrow и recovery. Этот ADR не изменяет криптографическую модель ADR-0014, а определяет представление документа, binding открытых полей и media processing.

## Решение

### 1. DocumentV2

`DocumentV2` полностью заменяет `body + entities`, диапазоны `offset/length` и placeholder-символы. Смешивать обе модели в одном write API запрещено.

```text
DocumentV2 {
  encrypted_nodes: optional bytes[]       // private text
  nodes: optional string[]                 // public/social text
  markup: optional JSON object             // entities/styles/layout/media bindings
  encrypted_metadata: optional bytes       // private sensitive values by secret_ref
  metadata: JSON object                    // REQUIRED: format_version=2,
                                           // revision_number, content_mode
}
```

- Индекс строки в `nodes` является `node_id`; типизированные entities и layout находятся в `markup`.
- `metadata` обязателен и содержит `format_version=2`, положительный `revision_number` и `content_mode=private|public`. Для групп сервер сверяет `content_mode` с `groups.kind`; для 1:1 допустим только `private`.
- `markup` и `metadata` — открытые versioned JSON objects. Неизвестные разрешённые ключи сохраняются и игнорируются старым клиентом.
- `encrypted_nodes`, `nodes`, `markup` и `encrypted_metadata` optional: в PostgreSQL отсутствие хранится как SQL `NULL`, в API поле опускается. Явный `null`, пустые `[]` и `{}` нормализуются в отсутствие до канонизации.
- `has_content` истинно только при непустом text array (`nodes` либо `encrypted_nodes`) или реальном media/content binding. Пустые layout/container/`children=[]` содержимым не являются; пустой документ при publish/send отклоняется.
- Для private чувствительные значения entity `text_link` и media (URL, имя файла, ключ, caption) находятся в `encrypted_metadata` и адресуются открытым `secret_ref`; пустой объект чувствительных metadata не шифруется.
- Исполняемый контент запрещён: `script`, macro, embedded executable, active HTML, event-handler и `javascript:` URL отклоняются; `code` является только текстом.

### 2. Storage и криптографический binding

Public-документ хранит nullable plaintext `nodes TEXT[]`, nullable `markup JSONB` и обязательный `metadata JSONB`; `encrypted_nodes` и `encrypted_metadata` отсутствуют.

Private-документ хранит:

- nullable `encrypted_nodes BYTEA[]` — по одному независимо аутентифицированному ciphertext на присутствующий text node;
- nullable `markup JSONB` и обязательный `metadata JSONB` — только разрешённые серверу routing/layout/служебные поля;
- nullable `encrypted_metadata BYTEA` — чувствительные значения, только если существует `secret_ref`; пустой blob не создаётся;
- обязательные envelope, wrapped keys, escrow и signature по ADR-0014.

Canonical encoder сначала нормализует optional `null`, `[]` и `{}` в отсутствие, затем формирует явный presence bitmap для `encrypted_nodes`, `nodes`, `markup` и `encrypted_metadata`. Подпись включает bitmap, обязательный canonical `metadata` и для каждого nullable подписываемого поля его значение либо deterministic null sentinel. AEAD AAD присутствующих private ciphertext включает тот же bitmap, обязательный canonical `metadata` и canonical `markup` либо его deterministic null sentinel. Поэтому отсутствие нельзя подменить пустым значением, а изменение открытого JSONB без перевыпуска подписанной immutable revision делает документ невалидным.

### 3. Immutable revisions

Опубликованные и отправленные документы не обновляются in-place. Любое изменение создаёт новую immutable revision с новым `revision_id`, `parent_revision_id`, полной подписью и новым криптографическим binding. Delete — отдельная tombstone/revision; физическое удаление определяется retention и legal hold.

### 4. Media pipelines

Private media остаётся participant E2E. Сервер получает только ciphertext и технический manifest. Отправитель до шифрования, а получатель после расшифрования обязаны независимо проверить MIME/magic bytes, лимиты, размеры, декодируемость и запрет executable/active content. Сервер не заявляет AV-гарантию для private media.

Public media проходит серверные AV scan, decode/sanitize и transcode до публикации. В storage и выдаче существуют ровно три нормализованных variant:

1. `thumbnail`;
2. `preview`;
3. `full`.

Variant `original` отсутствует. Исходный upload — временный quarantine object и удаляется после успешной обработки либо отказа. Для private media эти три variant создаёт клиент до E2E-шифрования; original не загружается.

Доступ к init/complete/read требует аутентификации и авторизации на доменном объекте. Presigned PUT/GET URL живёт не более 15 минут, scope ограничен одним object key, методом и ожидаемыми size/content headers.

### 5. Domain endpoints

Generic write endpoints `/messages` и `/media` не являются каноническими. API выражает владельца данных:

- `/v1/chats/{chat_id}/messages` — private 1:1;
- `/v1/groups/{group_id}/messages` — private/public group согласно ACL;
- `/v1/posts`, `/v1/posts/{post_id}/revisions` и `/v1/comments`;
- `/v1/chats/{chat_id}/media/uploads`, `/v1/groups/{group_id}/media/uploads`, `/v1/posts/assets`;
- `/v1/media/{media_id}/access` — authorizes one variant and returns a presigned URL.

### 6. Retention и legal hold

Retention применяется к immutable revisions, ciphertext variants, quarantine failures, audit и tombstones по классу данных. Активный legal hold приостанавливает physical purge всех попавших в scope revisions и media variants, включая soft-deleted. Снятие hold возвращает объект к обычному retention schedule; само снятие не означает немедленное удаление. Все постановки/снятия hold и purge записываются в WORM audit.

## Последствия

- Требуется schema/API migration с legacy `body/entities`; новые write API принимают только `DocumentV2`.
- Public индексирование читает plaintext nodes; private индексирование остаётся клиентским.
- Private media нельзя серверно AV-сканировать без нарушения participant E2E; риск закрывается двойной клиентской валидацией.
- Три фиксированных variant упрощают кэширование и исключают случайную выдачу исходника.
- ADR-0014 остаётся без изменений и имеет приоритет для participant E2E, escrow и recovery.

## Отклонено

- Offset/length entities и media placeholders.
- Хранение или выдача `original`.
- Серверная расшифровка private media ради AV/transcode.
- Mutable update существующей revision.
- Generic endpoints без domain owner.

## References

- [ADR-0014](./0014-participant-e2e-and-recovery.md)
- [module-boundaries.md](../02-architecture/module-boundaries.md)
- [backend-services.md](../02-architecture/backend-services.md)
- [crypto-protocol.md](../03-security/crypto-protocol.md)
- [threat-model.md](../03-security/threat-model.md)
