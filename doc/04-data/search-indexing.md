# Search и Indexing

## 1. Split model ([ADR-0007](../adr/0007-search-split.md))

| Content type | Index location | Engine |
|--------------|----------------|--------|
| Private E2E chats | Client SQLite FTS5 | Local only |
| Private groups E2E | Client FTS5 | Local only |
| Public posts, channels | Server | PostgreSQL FTS (Beta); OpenSearch only after trigger |
| **Attributes & genres** | Server | PostgreSQL (`attributes.name`, GIN/trigram) |
| Public media captions | Server | PostgreSQL FTS / OpenSearch |
| Users, handles | Server | Postgres + trigram |

**Server cannot index E2E plaintext** — UI global search for private messages uses **local index only**.

## 2. Client FTS pipeline

```mermaid
flowchart LR
  MSG[Decrypt_message] --> IDX[Insert_FTS]
  IDX --> QUERY[Global_search_UI]
```

- Index fields: decrypted `DocumentV2.nodes`, sender name (local), chat title.
- Rebuild: background job on app upgrade.
- Max index size: user-configurable cache setting.

## 3. Server search (Beta — PostgreSQL FTS)

- `posts.fts` (GIN, generated column) — title + `array_to_string(nodes, ' ')`.
- `attributes`: `name`, `display_name` — trigram `pg_trgm` для опечаток; префиксный поиск для `#хэштег`.
- `genres.title` — каталог тематических срезов ленты.
- Публичные `group_messages`: materialized `tsvector` по партициям (Growth).
- Профили: `username`, `display_name` + trigram.

Единый фасад не зависит от движка:

```http
GET /v1/search?q={query}&types=user,channel,group,post,public_message,attribute,genre&community_id={uuid}&author_id={uuid}&created_after={ISO8601}&created_before={ISO8601}&cursor={opaque}&limit=50
```

- `types` опционален; без него сервер ищет по всем разрешённым публичным типам.
- Private E2E результаты не возвращаются этим API: клиент объединяет server page с локальным FTS.
- ACL, soft-delete и moderation filters применяются до выдачи; `next_cursor` непрозрачен.

**OpenSearch включается только по измеримому trigger**, а не по фазе календаря: устойчивый p95 > 500 ms при исчерпанном PG tuning **или** > 10M активных индексируемых документов ([scaling-capacity.md](../02-architecture/scaling-capacity.md)):
- Индексы: `posts`, `public_messages`, `profiles`, `attributes`, `catalog`.
- Ingest: outbox + `search-index-worker`; API-фасад `GET /v1/search` не меняется.
- До срабатывания trigger OpenSearch не является runtime dependency.

## 4. Global search UI behavior

From [doc_UI/17-global-search.md](../doc_UI/17-global-search.md):

| Filter | Source |
|--------|--------|
| People | Server API |
| Chats | Local + server metadata |
| Messages (private) | **Local FTS** |
| Messages (public) | Server FTS / OpenSearch |
| Media | Hybrid |
| **Attributes** | Server (`/v1/search?types=attribute`) |
| **Genres** | Server (`/v1/search?types=genre`) |

- Debounce 300 ms; серверный запрос отменяется при вводе.
- Атрибуты в выдаче ведут на тематический срез ленты (`GET /feed?attribute=`).

## 5. Performance

- Local FTS: < 50ms for 100k messages.
- Server PG FTS: p99 < 200ms (Beta).
- OpenSearch after trigger: p99 < 200ms.

## 6. Privacy

- No private decrypted nodes or encrypted metadata are sent to server for search.
- Private shelf content never indexed on server (ciphertext only).
- Analytics on search queries: disabled for private branch.

## 7. Ссылки

- [feed-ranking.md](./feed-ranking.md)
- [data-model.md](./data-model.md) §7.2
- [content-security-matrix.md](../01-product/content-security-matrix.md)
