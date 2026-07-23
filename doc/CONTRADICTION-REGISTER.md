# Реестр противоречий документации TIMA

> **Дата фиксации:** 2026-07-22  
> **Статус:** verified — решения C-001…C-040 применены к активной нормативной документации; итоговая проверка 2026-07-22.  
> **Профиль поставки:** single-region beta VPS → RU/EU regional cells до GA.

## 1. Правила каноники

1. Нормативны Markdown-документы в `doc/`; исследовательские DOCX и `messenger-crypto-architecture.md` не являются релизной спецификацией.
2. При конфликте ADR задаёт архитектурный инвариант, PRD — продуктовый scope, schema/API/DDL — исполнимый контракт. После принятия решения все слои должны быть синхронизированы.
3. `Beta VPS`, `MVP production`, `Growth` и `10M` — разные профили; послабление beta не переносится в production автоматически.
4. `done (spec)` не означает `implemented`: готовность спецификации, артефакта и production gate учитывается отдельно.

## 2. Принятые решения

| ID | Приоритет | Тема | Принятое решение | Основные документы | Статус |
|----|-----------|------|-------------------|---------------------|--------|
| C-001 | P0 | Минимальные ОС | Android API 26+, iOS 15+, Windows 10+ | `01-product/requirements.md`, `02-architecture/tech-stack.md` | resolved |
| C-002 | P0 | Event bus beta/prod | Beta VPS: transactional outbox + Redis Streams; production/GA: Kafka; единый EventBus contract | ADR-0003/0010, `mvp-server-setup.md`, `test-strategy.md` | resolved |
| C-003 | P0 | Legacy OS security | Снято поднятием минимумов в C-001; App Attest/Play Integrity остаются обязательными платформенными механизмами | `client-attestation.md`, `nfr-slo.md` | resolved |
| C-004 | P0 | DB migrations | `golang-migrate` + versioned SQL migrations | `tech-stack.md`, `ci-cd-release.md`, `mvp-server-setup.md` | resolved |
| C-005 | P0 | 100 users vs 100k MAU | 100 пользователей — первая invited cohort; 100k MAU — целевой production profile | `roadmap.md`, `nfr-slo.md`, `scaling-capacity.md` | resolved |
| C-006 | P0 | Beta RPO/RTO | Beta: external WAL archive, PostgreSQL RPO ≤5 мин, RTO ≤4 ч; production RTO ≤30 мин | `nfr-slo.md`, `mvp-server-setup.md`, `disaster-recovery.md` | resolved |
| C-007 | P0 | Фаза machine schemas | Client OpenAPI + core Protobuf/JSON Schema — Phase 0; Bot OpenAPI — Phase 3b | ADR-0012, `api-guidelines.md`, `roadmap.md` | resolved |
| C-008 | P0 | Названия релизов | Messaging Alpha → Communication MVP → Social Beta → Compliance/Scale → Closed Beta/RC → GA | `requirements.md`, `roadmap.md`, `ci-cd-release.md` | resolved |
| C-009 | P0 | HSM phase | Stub только dev/закрытая beta; regional HSM/Enclave обязателен до production, планово Phase 4 | `tech-stack.md`, `roadmap.md`, ADR-0005/0016 | resolved |
| C-010 | P1 | Windows calls scope | Windows calls обязательны для Communication MVP | `requirements.md`, `client-architecture.md`, `roadmap.md` | resolved |
| C-011 | P2 | Windows package | MSIX — основной подписываемый пакет; portable ZIP опционально | `ci-cd-release.md`, `roadmap.md` | resolved |
| C-012 | P1 | LiveKit Windows SDK | Официальный LiveKit C++ SDK 1.0 + узкий JNI/JNA adapter за `CallRepository`; blocking spike Phase 2 | `client-architecture.md`, `dependency-policy.md` | resolved |
| C-013 | P0 | Store release до beta | Phase 4 — compliance/scale preparation; Closed Beta/RC — Phase 5; public stores/GA только после Phase 5 gates | `roadmap.md`, `ci-cd-release.md` | resolved |
| C-014 | P0 | Double Ratchet GA | Envelope/wrapped path — baseline ранних фаз; Double Ratchet audited и default до GA | ADR-0013/0014, `crypto-protocol.md`, `roadmap.md` | resolved |
| C-015 | P0 | `key_commitment` | Явное обязательное поле во всех private message/revision/wrapped/group/shelf/escrow key paths | ADR-0017, `crypto-protocol.md`, `data-model.md`, `rest-api.md` | resolved |
| C-016 | P0 | Версии crypto/DocumentV2 | `protocol_version` и `metadata.format_version` остаются отдельными полями; текущее значение обоих — 2; дальнейшее повышение lockstep | `crypto-protocol.md`, `message-document-format.md`, DDL/API | resolved |
| C-017 | P0 | `presence_bitmap` | Явное поле wire/API/DDL; входит в signature/AAD и валидируется | ADR-0017, `crypto-protocol.md`, DDL/API | resolved |
| C-018 | P0 | Retention | Transmission metadata — 3 года; content/ciphertext/media/revisions/escrow blob/participant wrapped keys — 6 месяцев; active hold блокирует purge | `retention-archival.md`, `key-lifecycle.md`, `privacy-compliance.md` | resolved |
| C-019 | P0 | `entity_message` storage | Единая `entity_messages`, `source_type=owner_api|bot`, nullable bot refs; `inbox_events` ссылается на карточку | `data-model.md`, `backend-services.md`, `bot-platform.md` | resolved |
| C-020 | P0 | Legal hold model | Generic scoped `legal_holds` + immutable audit; purge всегда проверяет active hold | `data-model.md`, `retention-archival.md`, security/ops | resolved |
| C-021 | P0 | Escrow public keys | Signed `GET /v1/escrow/config` bundle: current/next epoch×shard keys, validity, key IDs; клиент pin-ит signing root | ADR-0016, `key-lifecycle.md`, `rest-api.md` | resolved |
| C-022 | P0 | Attestation unavailable | Только ранее подтверждённое устройство получает configurable grace ≤30 дней; new register/link запрещены; failed/forged всегда блокируется | `client-attestation.md`, `nfr-slo.md` | resolved |
| C-023 | P1 | Attestation endpoints | `/v1/verify/attestation/ios` и `/v1/verify/integrity/android` | `client-attestation.md`, `client-hardening.md`, `rest-api.md` | resolved |
| C-024 | P1 | Search engine | PostgreSQL FTS на beta/Phase 3; OpenSearch после p95 >500 ms, >10M docs или Growth trigger | `search-indexing.md`, `roadmap.md`, `tech-stack.md` | resolved |
| C-025 | P1 | Search API | Единый `GET /v1/search` с filters/cursor; storage engine скрыт | `rest-api.md`, `search-indexing.md` | resolved |
| C-026 | P1 | WebSocket URL | Путь `/v1/ws`; beta `api.*`, production `realtime.*` | `api-guidelines.md`, `realtime-events.md`, `mvp-server-setup.md` | resolved |
| C-027 | P1 | LiveKit DNS | `rtc.{domain}` во всех средах | `api-guidelines.md`, `call-signaling.md`, `mvp-server-setup.md` | resolved |
| C-028 | P1 | Channel CRUD | Полный `/v1/channels` CRUD; `community_id` обязателен; UI создаёт auto-community перед channel при необходимости | `rest-api.md`, `module-boundaries.md`, UI 36 | resolved |
| C-029 | P1 | Channel subscription | Отдельные subscribe/unsubscribe; community subscription не подписывает автоматически на channels | `rest-api.md`, `communities.md`, DDL | resolved |
| C-030 | P1 | Push Phase 1 | Минимальный generic push без plaintext — Phase 1; calls/categories/settings — Phase 2 | `roadmap.md`, `push-payloads.md`, UI 26 | resolved |
| C-031 | P0 | Signed PreKey gate | Блокирует включение ratchet path A для любых внешних тестировщиков; envelope-only internal alpha не блокирует | ADR-0005/0013, `roadmap.md` | resolved |
| C-032 | P2 | Health endpoints | `/healthz`, `/readyz`, `/metrics` unversioned | `mvp-server-setup.md`, `ci-cd-release.md` | resolved |
| C-033 | P1 | Beta process topology | API, realtime gateway и worker запускаются отдельными процессами уже на beta | `system-architecture.md`, `mvp-server-setup.md` | resolved |
| C-034 | P0 | Entity message ownership | SocialInbox владеет `entity_messages`; BotGateway и owner API вызывают application interface | `module-boundaries.md`, `backend-services.md`, `bot-platform.md` | resolved |
| C-035 | P0 | Entity message read API | `target_ref.type=entity_message`; `GET /v1/inbox/entity-messages/{id}` с recipient authorization | `rest-api.md`, `realtime-events.md`, `push-payloads.md` | resolved |
| C-036 | P1 | Recovery anti-abuse | Init: 3/день/account, 10/день/IP; proof: 5/session; session TTL 24 ч; config + audit | `rate-limits.md`, `key-lifecycle.md`, threat model | resolved |
| C-037 | P0 | Escrow audit | Append-only hash chain/Merkle batches; подписанные roots публикуются во внешнем immutable storage | ADR-0016, `escrow-legal-access.md`, DDL/ops | resolved |
| C-038 | P0 | Production residency | До GA две regional cells RU/EU; beta остаётся single-region | ADR-0008/0018, deployment/roadmap | resolved |
| C-039 | P0 | Audit retention | Escrow/legal-hold/WORM audit — 7 лет; transmission metadata — 3 года; content/keys — 6 месяцев | `nfr-slo.md`, `retention-archival.md`, compliance | resolved |
| C-040 | P0 | `escrow_strict` | Production bypass невозможен; флаг допустим только local dev/test и отсутствует в production config | `ci-cd-release.md`, security/ops | resolved |

## 3. Dual-region production

- RU и EU — изолированные regional cells с собственными persistent stores, HSM и epoch×shard escrow hierarchy.
- Account получает immutable home region; conversation получает home region при создании.
- Cross-region доставка идёт через ciphertext-only relay. Relay не принимает plaintext, content keys, escrow private material или decrypted metadata.
- Conversation home region определяет authoritative storage, escrow и retention. Минимальный global routing directory не содержит message content.
- Неопределённость residency/route обрабатывается fail-closed. Cross-region release требует legal sign-off для обеих юрисдикций.

## 4. Прямые синхронизационные исправления

Эти пункты не требуют нового продуктового решения:

- различить `entity_message` (`POST /inbox/notify`) и appeal (`POST /appeals`);
- использовать `voice_rooms`, `attached_type`, `attached_id`;
- включить `snoozed` в inbox FSM tests;
- описывать эмоцию как отдельную запись, связанную с target, а не как часть message body;
- удалить ложное утверждение о Web-placeholder в UI v1;
- унифицировать image/process names: `tima-server`, `realtime-gw`, `tima-worker`;
- усилить legacy banner у `messenger-crypto-architecture.md`;
- заменить номерные ссылки на production phase в crypto ADR на именованные gates, если номер roadmap не является частью решения.

## 5. Контроль закрытия

Пункт переводится из `resolved` в `verified` после того, как:

1. исправлены все перечисленные нормативные документы;
2. нет активной старой формулировки вне явно помеченного legacy/history;
3. PRD → roadmap → ADR → DDL/API → tests/ops дают одну семантику;
4. относительные ссылки проходят проверку.

**Результат проверки 2026-07-22:** все решения таблицы считаются `verified`; значение `resolved` в строках обозначает состояние принятого решения, а не незавершённую синхронизацию. Broken relative links: 0; IDE diagnostics: 0. Исполнимые артефакты `schema/`, `infra/` и application code остаются delivery-задачами соответствующих roadmap phases и не считаются созданными этой документационной правкой.
