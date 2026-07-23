# Матрица безопасности контента

Единая таблица: тип контента → крипто → хранение → поиск → push → звонки.

> **Сообщество (Community)** не задаёт шифрование дочерних объектов — [social-objects/00-index.md](./social-objects/00-index.md).

## Граница Comments

| Контур | Обсуждение | Подсистема |
|--------|------------|------------|
| Public channel post | Комментарии к посту | **Comments** (`target_type=post`) |
| Public media post | Комментарии к медиа | **Comments** (`target_type=media`) |
| Public collection item | Комментарии | **Comments** (`target_type=collection_item`) |
| Private / E2E group | Message threads | **не** Comments |
| Private / E2E collection (MVP) | — | **без комментариев** |

Comments и Emotions на комментарии — plaintext на сервере, модерация через Reports.

| Тип контента | UI label | Client encrypt | Server plaintext | Escrow | Wrapped keys | Search | Push preview | Real-time |
|--------------|----------|----------------|------------------|--------|--------------|--------|--------------|-----------|
| Личный чат 1:1 | E2E | Опциональные `DocumentV2.encrypted_nodes`/`encrypted_metadata` + Ratchet/SecretBox | Обязательный открытый `metadata`, опциональный `markup`; payload — ciphertext | **Обязателен** | Fallback per-device | Local FTS | Generic | WS |
| Личный чат с ВП | E2E | Опциональные `DocumentV2.encrypted_nodes`/`encrypted_metadata` + envelope to VP identity key | Обязательный открытый `metadata`, опциональный `markup`; payload — ciphertext | Да | Per operator device wrap | Local FTS (if wrap) | Encrypted/generic | WS |
| Окно 4 inbox card | — | — | Metadata only | — | — | — | Configurable / redacted | WS |
| Bot social inbox message | — | Нет | Да (plaintext) | Нет | — | — | Generic | WS → window 4 |
| Private group | E2E | Опциональные `DocumentV2.encrypted_nodes`/`encrypted_metadata` + GK/Sender Keys | Обязательный открытый `metadata`, опциональный `markup`; payload — ciphertext | **Обязателен** per GK | Per member | Local FTS | Generic | WS |
| Private collection | E2E | GK/media_key | Нет | Per period | Per member | Local | Generic | — |
| Public group | — | Нет | Да | Нет | — | OpenSearch | Full | WS |
| Channel | — | Нет | Да | Нет | — | OpenSearch | Full | WS |
| Public media | — | Нет | Да | Нет | — | OpenSearch | Thumbnail | — |
| Story (private) | E2E | Envelope | Нет | Да | Да | — | Generic | — |
| Story (public) | — | Нет | Да | Нет | — | — | Full | — |
| Голосовое сообщение | E2E | Opus → SecretBox | Ciphertext only | Per period | Да | — | «Голосовое» | — |
| Фото/файл E2E | E2E | Chunked SecretBox | Ciphertext in MinIO | Per period | Да | Filename local | Generic | — |
| Аудио/видео звонок | Не E2E* | SRTP (WebRTC) | SFU видит поток** | N/A | N/A | — | «Входящий звонок» | LiveKit |
| Звонок **от ВП** | — | **Запрещён MVP** | — | — | — | — | — | — |
| Аудиочат (голосовая комната) | Не E2E* | SRTP | SFU | N/A | N/A | — | «Голосовой чат» | LiveKit |
| Comment (public) | — | Нет | Да | Нет | — | OpenSearch | Generic | WS |
| Emotion on comment | — | Нет | Да (метаданные) | Нет | — | — | Generic | WS |
| Emotion on message (E2E) | — | Нет | Да (метаданные) | Нет | — | — | Generic | WS |
| Recommendation [+]/[−] | — | Нет | Да | Нет | — | — | — | WS |
| Публичная полка избранного | — | Нет | Да (метаданные) | N/A | — | Серверный | — | WS |
| Личная полка избранного | E2E | SecretBox(shelf_key) | Ciphertext blob | **Обязателен** | Owner devices | Локально | — | — |
| Self-only notes/media | E2E | SecretBox(content_key) | Ciphertext only | **Обязателен** | Owner only | Локально | — | — |
| Запись звонка (Egress) | — | Нет | Plaintext at export | Audit only | — | — | — | Egress |

Для private media отправитель проверяет файл до отправки, получатель — повторно после скачивания; server-side проверка ciphertext не считается проверкой содержимого. Public media проверяется server-side до публикации. Executable-файлы и активный контент блокируются во всех контурах.

Media доступны только через авторизованный доменный запрос и presigned URL на 15 минут. Прямого transfer между клиентами нет; варианты производных — только `thumbnail`, `preview`, `full`, без `Original`.

Для всех DocumentV2 `metadata` содержит `format_version=2`, `revision_number`, `content_mode`; в группах режим совпадает с `groups.kind`. Отсутствующие опциональные поля передаются omitted/`NULL`, без `[]`/`{}`/пустого ciphertext; media-only опускает текстовый массив. Public не несёт encrypted-поля и использует `text_link` с открытым `href`; private sensitive link — entity `text_link` с `secret_ref` на URL в `encrypted_metadata`. `has_content` требует непустой текст или реальный media/content binding, а не пустой layout.

\* Согласно [ADR-0006](../adr/0006-livekit-media-policy.md); LiveKit E2EE не включён в v1.  
\*\* Медиа терминируется на SFU для forwarding; не путать с хранением сообщений.

## Правила оценок и полок

1. **[+]/[−] (рекомендации)** — только публичный контент. Сервер не может ранжировать то, чего не читает ([feed-ranking.md](../04-data/feed-ranking.md) §5).
2. **Шкала эмоций (9)** — любой контент. Значение эмоции — **метаданные** (не шифруется): trade-off для агрегации счётчиков под сообщением и раздельных рейтинговых счётчиков «+/−» ([18-content-actions.md](../doc_UI/18-content-actions.md)). Сервер видит «кто какую эмоцию поставил на target_id», но не plaintext E2E-сообщения.
3. **Публичная полка** — plaintext метаданные на сервере; питает ленту друзей.
4. **Личная полка** — `shelf_key` + escrow; доступ по запросу через wrapped keys ([feed-ranking.md](../04-data/feed-ranking.md) §2, [ADR-0004](../adr/0004-controlled-escrow.md)).

## Семантика «удаления»

Контент хранится immutable revisions: редактирование создаёт новую ревизию, а delete меняет доступность доменного объекта, не переписывая историческую ревизию.

| Действие | Клиент | Сервер (relay) | Escrow archive |
|----------|--------|----------------|----------------|
| Delete for me | Скрыто локально | Без изменений | Без изменений |
| Delete for all | Скрыто у всех | `deleted=true`, не отдаётся | **Сохраняется** |
| Account delete (30d) | Revoke devices | Anonymize metadata | По retention policy |

## Forward / repost (E2E)

Пересылка = decrypt source + re-encrypt target (полная копия). Forward-ссылки как в Telegram **не применяются** к E2E.

## User-facing disclosure (рекомендация)

В [16-profile-popup.md](../doc_UI/16-profile-popup.md) для private чатов показывать:

- Fingerprint identity key (optional advanced)
- «Защищённый чат» + ссылка на политику escrow в настройках
- Статус звонка: «Звонок не использует сквозное шифрование приложения»

## Ссылки

- [social-objects/00-index.md](./social-objects/00-index.md)
- [public-content-format.md](./public-content-format.md)
- [crypto-protocol.md](../03-security/crypto-protocol.md)
- [escrow-legal-access.md](../03-security/escrow-legal-access.md)
- [bot-application.md](../01-product/social-objects/bot-application.md)
- [bot-api.md](../05-api/bot-api.md)
