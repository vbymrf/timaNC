# Domain API and SDK formats

> **Статус:** decision contract · **Дата:** 2026-07-22

Этот документ фиксирует публичные доменные типы SDK. Конкретные transport endpoints, классы хранения и алгоритмы обработки здесь не задаются.

## 1. Domain API

SDK предоставляет операции над доменными сущностями `Message`, `DocumentV2`, `MediaAsset` и `MessageRevision`. HTTP-запросы, presigned URL и внутренние идентификаторы объектов инкапсулированы transport-слоем и не являются основным публичным API.

## 2. `DocumentV2`

```json
{
  "nodes": ["text node"],
  "metadata": {"format_version": 2, "revision_number": 1, "content_mode": "public"}
}
```

- `metadata` всегда обязателен: `format_version=2`, положительный `revision_number`, `content_mode=private|public`.
- Public-проекция содержит optional `nodes` и `markup`; private-проекция — optional `encrypted_nodes`, `markup`, `encrypted_metadata`. SDK не позволяет смешивать private- и public-поля. Для public encrypted-поля отсутствуют в API и соответствуют `NULL` в хранилище.
- `nodes` / `encrypted_nodes` — упорядоченный непустой массив текстовых узлов; индекс является `node_id`. `markup` — отдельная структура entities/styles/layout со ссылками по индексам.
- SDK опускает отсутствующие optional-поля при encode. При decode JSON `null`, `[]` и `{}` для optional-полей нормализуются в отсутствие; пустой private metadata object не шифруется.
- Media-only документ не содержит `nodes` / `encrypted_nodes`. `has_content` отклоняет полностью пустой документ и markup, содержащий только пустой layout.
- Private `text_link` содержит открытый `secret_ref`, URL находится в `encrypted_metadata`. При наличии хотя бы одного `secret_ref` непустой `encrypted_metadata` обязателен, и все ссылки должны разрешаться в обе стороны.
- Для групп SDK и сервер проверяют соответствие `metadata.content_mode` режиму группы.
- Исполняемое содержимое запрещено: SDK не создаёт executable nodes/attachments, а неизвестный исполняемый тип должен завершаться typed error, не fallback-рендерингом.
- SDK сохраняет неизвестные неисполняемые поля совместимой версии при decode/encode roundtrip.

## 3. Media

`MediaAsset` явно содержит `visibility: "private" | "public"` и не позволяет смешивать pipeline:

- `private`: клиент шифрует bytes до upload; сервер и SDK не интерпретируют plaintext; чувствительные свойства находятся в `DocumentV2.encrypted_metadata`.
- `public`: серверный pipeline принимает media на обработку и публикует ровно три производных варианта.
- В public API нет варианта `Original`; SDK не запрашивает и не возвращает original.
- Любой executable media/attachment отклоняется до upload.

Upload/download требует пользовательской аутентификации для выдачи URL. Presigned URL имеет TTL **15 минут** и является краткоживущей capability, которую нельзя логировать или кэшировать дольше TTL.

## 4. Immutable messages and revisions

- Сохранённый `Message` не изменяется in place.
- Edit создаёт новый `MessageRevision`, связанный с исходным `message_id`.
- Успешное редактирование публикует доменное событие `message.edited` с идентификаторами сообщения и новой ревизии.
- SDK отображает актуальную ревизию, сохраняя доступ к разрешённой политикой истории; повторная доставка события идемпотентна по event/revision id.

## 5. Retention and legal hold

- Retention применяется по политике к сообщениям, всем их ревизиям, media-вариантам и связанным метаданным.
- Legal hold приостанавливает удаление всех связанных объектов независимо от истечения retention.
- SDK не обещает физическое удаление по локальному таймеру: результат удаления/недоступности определяется серверной retention/legal-hold policy.
- Public API не раскрывает основание или детали legal hold пользователям без соответствующего разрешения.

## 6. Typed errors

Минимальные категории ошибок SDK:

- authentication required/expired;
- presigned URL expired;
- executable content blocked;
- media pipeline mismatch;
- immutable revision conflict;
- retention/legal-hold restriction.

Имена transport-кодов генерируются из схемы API; этот документ не назначает им значения.
