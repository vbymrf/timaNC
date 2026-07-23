# Границы модулей (группы · каналы · аудио-чаты · сообщества)

> Каноническое разделение доменов. Причина: функционал этих сущностей будет правиться регулярно — правки одной сущности не должны задевать соседние. Разделение **полное**: свои таблицы, свои API-неймспейсы, свои модули бэкенда и клиента.

## 1. Принцип: разделение по функции

| Модуль | Суть | Владеет таблицами | API |
|--------|------|-------------------|-----|
| `group` | **Переписка** | `groups`, `group_messages`, `group_key_history`, `user_wrapped_keys` | `/groups/*` |
| `channel` | **Публикации** (постов без чат-потока), всегда внутри community | `channels` | `/channels/*` |
| `voiceroom` | **Live-аудио** | `voice_rooms` | `/voice-rooms/*` |
| `community` | **Контейнер** (состав, доступ, подписка, витрина) | `communities` | `/communities/*` |

Контент канала — **только посты** (создаются редактором, [37-content-editor](../doc_UI/37-content-editor.md)); обсуждение поста — комментарии. Чат-поток есть только у групп.

## 2. Общие подсистемы (полиморфные)

Механики, нужные нескольким сущностям, выносятся в отдельные модули с привязкой `target_type/target_id` — сущности зависят от них, но не друг от друга:

| Подсистема | Владеет | Кто использует |
|-----------|---------|----------------|
| `membership` | `memberships` (роли: owner/admin/moderator/author/member/speaker, ban) | group, channel, voiceroom, community |
| `publications` | `posts`, immutable `post_revisions` с **DocumentV2**, `post_drafts`, отложенная публикация | channel (посты канала), личная страница/медиа-лента (посты пользователя), виртуальные пользователи |
| `comments` | `comments` | posts, публичные медиа, публичные коллекции |
| `reactions` | `emotions`, `rating_counters`, `recommendations` | всё, что оценивается |
| `attributes` | `attributes` (= хэштеги), `genres`, `post_attributes`, `user_attributes` | publications, feeds, search ([feed-ranking.md](../04-data/feed-ranking.md)) |
| `social_inbox` | `inbox_threads`, `appeal_messages`, `entity_messages`, `entity_message_revisions`, `inbox_events`, `social_inbox_preferences` (окно 4) | owner API и BotPlatform создают `entity_message`; reactions, membership ([virtual-user.md](../01-product/social-objects/virtual-user.md)) |
| `bot_gateway` | `bot_applications`, `bot_installations`, `bot_update_deliveries`; `/v1/bot/*`, updates-очереди | group, channel, community, publications, social_inbox — по scopes; только публичный контур ([bot-api.md](../05-api/bot-api.md)) |
| `invites` | `invites` (target: group/channel/community) | group, channel, community |
| `catalog` | `chat_folders`, `chat_folder_items` | окна 2–3 (каталог), папки |
| `feeds` | fan-out (Redis/Kafka), выдача лент | publications, subscriptions |
| `reports` | `reports` | модерация всего |

## 3. Правила зависимостей

```
            ┌───────────────────────────────────────────┐
            │ Сущности:  group   channel   voiceroom     │   ← НЕ зависят друг от друга
            │                └───────┬───────┘           │
            │ community ─── знает только (type, id) ─────│   ← контейнер, без знания внутренностей
            └───────────────┬───────────────────────────┘
                            ▼
            Общие подсистемы: membership · publications · comments ·
            reactions · attributes · invites · catalog · feeds · reports
                            ▼
            Инфраструктура: PG · Redis · MinIO · LiveKit · EventBus
```

1. **Сущности не импортируют друг друга.** Связь «аудио-чат прикреплён к группе» — это `attached_type/attached_id` (слабая ссылка), а не вызов модуля.
2. **Community оперирует только `(target_type, target_id, community_access)`** — не знает, что внутри элемента.
3. Общие подсистемы не знают о конкретных сущностях — только `target_type` как строка.
4. Кросс-модульные сценарии (создание аудио-чата с авто-сообществом) — на уровне application-сервиса (wizard), не внутри модулей.
5. `channels.community_id` обязателен. Подписка на community и подписка на channel — независимые записи `subscriptions`; первая не создаёт вторую автоматически.
6. `entity_message` не является appeal: SocialInbox владеет карточкой и её revisions, а `inbox_events.target_ref={type:"entity_message",id}` только проецирует ссылку.
7. EventBus transport не входит в domain contract: beta использует Redis Streams, production/GA — Kafka; transactional outbox обязателен в обоих профилях.

## 4. Проекция на код

**Бэкенд (Go, модульный монолит):** пакет на модуль (`internal/group`, `internal/channel`, `internal/voiceroom`, `internal/community`, `internal/publications`, `internal/reactions`, `internal/attributes`, `internal/social_inbox`, `internal/feeds`, …). Каждый пакет: свои handlers, своя схема миграций, экспортирует узкий интерфейс. Импорт между пакетами сущностей запрещён линтером (depguard/ревью).

**Клиент (KMP):** фич-модули `feature-group`, `feature-channel`, `feature-voiceroom`, `feature-community`, `feature-editor`, `feature-inbox`, `feature-reactions` + `core-*` (общие подсистемы). Границы контролирует **Konsist** в CI (правило: feature-модули не зависят друг от друга, только от core).

**API:** отдельные неймспейсы ([rest-api.md](../05-api/rest-api.md), [bot-api.md](../05-api/bot-api.md)); версии эволюционируют независимо.

## 5. Канонический формат контента: DocumentV2

По [ADR-0015](../adr/0015-document-v2-and-media-pipeline.md) `DocumentV2` **полностью заменяет** legacy `body + entities`, диапазоны `offset/length` и media placeholder-символы во всех новых write API.

```json
{
  "nodes": ["Релиз 2.0", "Главные ", "изменения"],
  "markup": {
    "entities": [
      {"type": "bold", "nodes": [2]},
      {"type": "media", "media_id": "uuid"}
    ],
    "layout": {"type": "document", "children": [
      {"type": "heading", "level": 1, "nodes": [0]},
      {"type": "paragraph", "nodes": [1, 2]}
    ]}
  },
  "metadata": {"format_version": 2, "revision_number": 1, "content_mode": "public"}
}
```

- `metadata` обязателен и содержит как минимум `format_version=2`, `revision_number` и `content_mode=public|private`; для групп `metadata.content_mode` обязан совпадать с `groups.kind`.
- Текстовый массив (`nodes` для public, `encrypted_nodes` для private), `markup` и `encrypted_metadata` опциональны: отсутствующие значения не подменяются `[]`, `{}` или пустым ciphertext (в API поле omitted, в SQL — `NULL`).
- Индекс строки в присутствующем текстовом массиве является `node_id`. Entities и media/content binding находятся в `markup`; media-only документ не содержит текстового массива.
- `has_content` требует непустой текст или реальный media/content binding. Один пустой layout содержимым не считается; полностью пустой документ отклоняется.
- `code` — только отображаемый текст. `script`, macro, active HTML, event handlers, `javascript:` URL и любой executable content запрещены.
- Public (`content_mode=public`) может содержать plaintext `nodes` и `text_link` с открытым `href`, но не `encrypted_nodes`, `encrypted_metadata` или private `secret_ref`. Private (`content_mode=private`) использует entity `text_link` с открытым `secret_ref`; URL находится в `encrypted_metadata`, открытый `href` запрещён.
- Для private DocumentV2 канонизированные nullable `markup`, обязательный `metadata`, явные `presence_bitmap:uint32` и `key_commitment` входят в signature/AEAD AAD. `protocol_version=2` и `metadata.format_version=2` зафиксированы lockstep; wrapped/escrow records повторяют commitment. Public проходит server-side validation и immutable revision без private Ed25519 envelope.

Опубликованный/отправленный документ неизменяем: редактирование создаёт новую immutable revision (`revision_id`, `parent_revision_id`), delete создаёт tombstone. Private DocumentV2 остаётся внутри participant E2E envelope по [crypto-protocol.md](../03-security/crypto-protocol.md).

Domain API: `/v1/chats/{chat_id}/messages`, `/v1/groups/{group_id}/messages`, `/v1/posts`, `/v1/posts/{post_id}/revisions` и `/v1/comments`; generic `/messages` не используется.

## 6. Что где НЕ живёт (частые ошибки)

- Комментарии — не в канале и не в медиа-ленте: только модуль `comments`.
- Посты канала — не в `group_messages`: только `posts` (`author_type='channel'`).
- Роли — не в таблицах сущностей: только `memberships`.
- Редактор — один (`feature-editor`), режимы: пост канала / статья / медиа-пост / история.
- Подписчики — не в `memberships`: подписка это `subscriptions`; membership — роли управления/членство в личной группе.

## 7. Ссылки

- [system-architecture.md](./system-architecture.md)
- [backend-services.md](./backend-services.md)
- [data-model.md](../04-data/data-model.md)
- [social-objects/00-index.md](../01-product/social-objects/00-index.md)
