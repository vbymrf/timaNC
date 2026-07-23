# Аудит мержа doc_ver2 → doc

> **Дата:** 2026-07-22 · **Каноника:** [`doc/`](./)  
> **Итоговый аудит:** результаты и закрытые конфликты зафиксированы ниже · **E2E/recovery:** [ADR-0014](./adr/0014-participant-e2e-and-recovery.md)

## Статус этапов

| Этап | Тема | Статус |
|------|------|--------|
| 1 | 9 эмоций + feed-ranking / атрибуты / полки | done |
| 2 | Сообщества ACL, UI 35–37, публикации | done |
| 3 | Окно 4, ВП, inbox FSM, entity_message | done |
| 4 | Identity, временный аккаунт, E2E/escrow терминология | done |
| 5 | Bot Platform + schema-first (ADR-0012) | done |
| 6 | MVP ops supplement, quality/delivery | done |
| 7 | Архитектура, DDL, ADR-0010…0013 | done |
| 8 | Повторный аудит | done |
| 9 | Participant E2E + escrow + recovery (ADR-0014) | done |
| 10 | DocumentV2 + media pipeline (ADR-0015) | done |
| 11 | Nullable DocumentV2 + canonical presence binding | done |

## Пост-аудит противоречий (2026-07-22)

Полный реестр решений: [CONTRADICTION-REGISTER.md](./CONTRADICTION-REGISTER.md). Решения C-001…C-040 применены к активной нормативной документации:

- [x] release scope: Android API 26+, iOS 15+, Windows 10+; Messaging Alpha → Communication MVP → Social Beta → Closed Beta/RC → GA;
- [x] beta profile: separate `tima-server`/`realtime-gw`/`tima-worker`, transactional outbox → Redis Streams; production/GA → Kafka;
- [x] crypto contract: explicit `key_commitment`, `presence_bitmap`, lockstep `(protocol_version, format_version)=(2,2)`;
- [x] signed regional `/v1/escrow/config`, strict escrow без beta/production bypass и HSM gate до production;
- [x] retention: transmission metadata 3y; protected content/escrow/wraps 6m; WORM audit/proofs 7y; generic legal hold;
- [x] unified SocialInbox `entity_messages`, owner path `/v1/inbox/notify`, `target_ref.type=entity_message`;
- [x] channels CRUD/subscriptions, `voice_rooms`, unified `/v1/search`, PostgreSQL FTS до OpenSearch trigger;
- [x] canonical URLs: WS `/v1/ws`, LiveKit `rtc.*`, operational endpoints unversioned;
- [x] ADR-0018: RU/EU regional cells до GA, ciphertext-only relay и независимые regional escrow hierarchies;
- [x] проверены относительные Markdown links и IDE diagnostics.

## Закрытые конфликты (из Canvas)

| ID | Решение |
|----|---------|
| c01 | Inbox FSM: `new → taken → snoozed → closed` |
| c02 | MVP: `entity_message` без ботов; Bot Platform Phase 3b |
| c03 | Double Ratchet rollout — Phase 5; канон participant E2E — [ADR-0014](./adr/0014-participant-e2e-and-recovery.md) |
| c04 | ADR-0003 amended: transactional outbox + staged EventBus (Redis Streams beta → Kafka production); storage — [ADR-0010](./adr/0010-mvp-storage-profile.md) |
| c05 | Multi-region ADR-0008 сохранён; MVP edge — [ADR-0011](./adr/0011-mvp-caddy-edge.md) |
| c06 | Bot platform ADR-0009 сохранён; schema-first — [ADR-0012](./adr/0012-schema-first-api.md) |
| c07 | Шкала **9** эмоций |
| c08 | Community ACL: `preview` / `open` / `restricted` (+ migration aliases) |
| c09 | Glossary: participant E2E + обязательный controlled escrow |
| c10 | Полный аккаунт: SMS + email + recovery codes |
| c13 | UI `35-community.md` создан |
| c11 | GK rotation: join/leave/kick + 100 msg |
| c12 | Bot lifecycle: UI states ↔ `bot_installations.status` |
| c14 | Friendship API: `/friends/*` в [rest-api.md](./05-api/rest-api.md) |
| c16 | `[+5]` → «прокрутка вверх на 5 сообщений» |
| c20 | Коллекции: окно 4 = агрегатор, окно 5 = управление профилем |
| c21 | Private content: `encrypted_nodes[]` + open authenticated markup/metadata + `encrypted_metadata` |
| c22 | Offset/placeholder заменены node-index `DocumentV2` |
| c23 | Private media проверяется отправителем и получателем; public — сервером |
| c24 | Только thumbnail/preview/full; Original и direct transfer исключены; executable блокируются |
| c25 | Media access: auth + presigned URL 15 min; deletion follows retention/legal hold |
| c26 | Edit создаёт immutable revision; API разделён на chats/groups/posts/comments |
| c27 | `metadata` обязателен; text arrays, `markup`, `encrypted_metadata` отсутствуют как API omit / SQL `NULL`, без empty containers/blob |
| c28 | `metadata.content_mode` различает private/public media-only; presence bitmap/null sentinel защищают nullable-поля в signature/AAD |

## Post-MVP реестр (фазированный)

| Пункт | Фаза | Gate / владелец | Канон |
|-------|------|-----------------|-------|
| Рекламный кабинет блогера | 6+ | UI-only; Product | [29-blogger-media-window.md](./doc_UI/29-blogger-media-window.md) |
| Маркетплейс передачи ВП | 6+ | Legal review | [virtual-user.md](./01-product/social-objects/virtual-user.md) |
| Web-клиент | 6+ | Out of scope v1 | [requirements.md](./01-product/requirements.md) §5 |
| Поведенческий скоринг ленты | 6+ | Privacy review | [feed-ranking.md](./04-data/feed-ranking.md) §5 |
| OpenSearch cluster migration | 4–6 | ADR-0007 | [search-indexing.md](./04-data/search-indexing.md) |
| Moderation backoffice UI | 4 | API ready Phase 3 | [rest-api.md](./05-api/rest-api.md) `/posts/{id}/approve` |
| `schema/` (OpenAPI + Protobuf) | 3b | ADR-0012 | [api-guidelines.md](./05-api/api-guidelines.md) |
| Crypto test vectors V1-* | 4 | ADR-0005 | [security-test-plan.md](./08-quality/security-test-plan.md) |
| Kodium audit + Signed PreKey | prod | ADR-0005 | [00-documentation-map.md](./00-documentation-map.md) P0 |
| «Общие комнаты обсуждений» | — | **Исключено** из scope; нет модели | — |
| Double Ratchet PQ | 6+ | Feature flag | [ADR-0013](./adr/0013-double-ratchet-phase.md) |
| Peer recovery (full) | 3–4 | [roadmap.md](./09-delivery/roadmap.md) | [ADR-0014](./adr/0014-participant-e2e-and-recovery.md) |
| Phrase backup | 4 | Opt-in | [key-lifecycle.md](./03-security/key-lifecycle.md) §8 |

## Закрытые уточнения

| Тема | Решение |
|------|---------|
| Публикации API | Единый контур `/posts/drafts`, `/posts` ([37-content-editor.md](./doc_UI/37-content-editor.md)) |
| NFR «24 ч» | TTL stories 24ч; chat history — retention/escrow policy |
| Moderation API | `POST /posts/{id}/approve\|reject` в rest-api |
| Canvas vs канон | Canvas обновлён: resolved / phased / production gate |

## Ключевые документы (финализация)

- [adr/0014-participant-e2e-and-recovery.md](./adr/0014-participant-e2e-and-recovery.md)
- [03-security/crypto-protocol.md](./03-security/crypto-protocol.md) v1.3
- [03-security/key-lifecycle.md](./03-security/key-lifecycle.md) — recovery §12
- [05-api/rest-api.md](./05-api/rest-api.md) — friends, recovery, unified posts
- [04-data/data-model.md](./04-data/data-model.md) — `recovery_*` tables
- [09-delivery/roadmap.md](./09-delivery/roadmap.md) — phased backlog

## Проверки трассировки

- [x] Нет активных ссылок из `doc/` на `doc_ver2/` как на канонику
- [x] Participant E2E + mandatory escrow + peer recovery в ADR/security/API/DDL
- [x] Unified `/posts` API согласован с UI `37`
- [x] Friendship + moderation API в rest-api
- [x] ADR-0001…0013 не перезаписаны; ADR-0014 добавлен
- [x] Post-MVP пункты фазированы, не смешаны с MVP exit criteria
- [x] `DocumentV2` согласован между DDL, crypto, API, UI и SDK
- [x] Nullable/omit, `has_content`, `text_link`/`secret_ref` и private/public group-инварианты согласованы
- [x] Private/public media pipelines разделены без нарушения participant E2E
