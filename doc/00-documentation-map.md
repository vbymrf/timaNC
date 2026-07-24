# Реестр документации TIMA

> **Версия реестра:** 1.4 · **Дата:** 2026-07-24
> **Мерж doc_ver2:** см. [MERGE-AUDIT.md](./MERGE-AUDIT.md)
> **Решения по противоречиям:** [CONTRADICTION-REGISTER.md](./CONTRADICTION-REGISTER.md)
> Легенда статусов: `done` — заполнено · `draft` — черновик · `planned` — запланировано · `legacy` — устаревший источник, сохранён для истории

## Сводка по разделам

| Раздел | Документов | Статус раздела |
|--------|------------|----------------|
| 01-product | 8 + social-objects | done |
| 02-architecture | 9 | done |
| 03-security | 7 + legacy | done |
| 04-data | 8 | done |
| 05-api | 9 | done |
| 06-realtime | 4 | done |
| 07-operations | 7 | done (merge этап 6 + Phase 1 release policy) |
| 08-quality | 5 | done (включая Phase 1 native acceptance gate) |
| 09-delivery | 4 | done |
| adr | 18 | done |
| doc_UI | 38 | done (merge doc_ver2 этап 2) |

---

## 01-product — продукт и NFR

| ID | Документ | Владелец | Фаза | Статус | Источники |
|----|----------|----------|------|--------|-----------|
| P-01 | [requirements.md](./01-product/requirements.md) | Product | MVP | done | merge этап 3: инвариант «сущности не пишут в личку» |
| P-02 | [glossary.md](./01-product/glossary.md) | Architecture | All | done | merge этап 5: `notifyUser`, `entity_message` |
| P-03 | [nfr-slo.md](./01-product/nfr-slo.md) | SRE | Scale | done | `Тима.docx`, план 10M MAU |
| P-04 | [content-security-matrix.md](./01-product/content-security-matrix.md) | Security | All | done | UI `27-security-privacy`, ADR-0004 |
| P-05 | [communities-groups-channels.md](./01-product/communities-groups-channels.md) | Product | MVP | legacy | redirect → [social-objects/](./01-product/social-objects/00-index.md) |
| P-05b | [communities.md](./01-product/communities.md) | Product | MVP | done | doc_ver2 merge этап 2; ACL `preview`/`open`/`restricted` |
| P-06 | [public-content-format.md](./01-product/public-content-format.md) | Product | MVP | done | UI `37`, `32`, `33`; unified `/posts` |
| P-07 | [social-objects/00-index.md](./01-product/social-objects/00-index.md) | Product | MVP | done | merge этап 5: bot-application `entity_message` |

---

## 02-architecture — системная архитектура

| ID | Документ | Владелец | Фаза | Статус | Источники |
|----|----------|----------|------|--------|-----------|
| A-01 | [system-architecture.md](./02-architecture/system-architecture.md) | Architecture | MVP | done | merge этап 7: feeds, reactions, inbox FSM |
| A-02 | [client-architecture.md](./02-architecture/client-architecture.md) | Client | MVP | done | merge этап 7: feature modules |
| A-03 | [backend-services.md](./02-architecture/backend-services.md) | Backend | MVP | done | merge этап 7: workers, attributes, reactions |
| A-08 | [module-boundaries.md](./02-architecture/module-boundaries.md) | Architecture | MVP | done | doc_ver2 merge этап 7 |
| A-09 | [tech-stack.md](./02-architecture/tech-stack.md) | Architecture | MVP | done | doc_ver2 merge этап 7 |
| A-07 | [bot-platform.md](./02-architecture/bot-platform.md) | Backend | Beta | done (spec) | Phase 3b implementation |
| A-04 | [data-flows.md](./02-architecture/data-flows.md) | Architecture | MVP | done | merge этап 7: feed/inbox flows |
| A-05 | [deployment-topology.md](./02-architecture/deployment-topology.md) | SRE | MVP | done | merge этап 7: Caddy MVP, ADR-0010/0011 |
| A-06 | [scaling-capacity.md](./02-architecture/scaling-capacity.md) | SRE | Scale | done | crypto doc §6, ADR-0008; merge этап 6: ступень 0 VPS |
| A-10 | [hybrid-notification-delivery.md](./02-architecture/hybrid-notification-delivery.md) | Client/Backend | Phase 1 | done (spec) | FCM/APNs + TIMA gateway/UnifiedPush + WS/REST catch-up |

---

## 03-security — безопасность

| ID | Документ | Владелец | Фаза | Статус | Источники |
|----|----------|----------|------|--------|-----------|
| S-01 | [threat-model.md](./03-security/threat-model.md) | Security | MVP | done | STRIDE |
| S-02 | [crypto-protocol.md](./03-security/crypto-protocol.md) | Crypto | MVP | done | ADR-0014/0016/0017; participant E2E + escrow + key commitment |
| S-03 | [key-lifecycle.md](./03-security/key-lifecycle.md) | Crypto | MVP | done | peer recovery §12, phrase §8 |
| S-04 | [escrow-legal-access.md](./03-security/escrow-legal-access.md) | Legal/Sec | MVP | done | ADR-0004, ADR-0016 |
| S-08 | [kodium-security-audit.md](./03-security/kodium-security-audit.md) | Crypto/Sec | MVP | done | Kodium audit R-0..R-4; ADR-0016/0017 |
| S-05 | [client-hardening.md](./03-security/client-hardening.md) | Client | MVP | done | `Тима.docx`, UI `24`, `27` |
| S-07 | [client-attestation.md](./03-security/client-attestation.md) | Client | MVP | done | doc_ver2 S-07, UI `24`, `27` |
| S-06 | [privacy-compliance.md](./03-security/privacy-compliance.md) | Legal | Beta | done | GDPR, 152-FZ outline |
| S-00 | [messenger-crypto-architecture.md](./messenger-crypto-architecture.md) | Crypto | legacy | legacy | → ссылается на S-02 |

---

## 04-data — данные и синхронизация

| ID | Документ | Владелец | Фаза | Статус | Источники |
|----|----------|----------|------|--------|-----------|
| D-01 | [data-model.md](./04-data/data-model.md) | Backend | MVP | done | merge этап 7: posts unified, emotions, inbox FSM, shelves |
| D-02 | [storage-sharding.md](./04-data/storage-sharding.md) | Backend | Scale | done | ADR-0002 |
| D-03 | [sync-offline.md](./04-data/sync-offline.md) | Client | MVP | done | peer recovery sync §5 |
| D-04 | [search-indexing.md](./04-data/search-indexing.md) | Backend | Beta | done | merge этап 7: attributes/genres index |
| D-05 | [retention-archival.md](./04-data/retention-archival.md) | SRE | Scale | done | escrow retention |
| D-06 | [media-storage.md](./04-data/media-storage.md) | Backend | MVP | done | crypto doc §10 |
| D-07 | [feed-ranking.md](./04-data/feed-ranking.md) | Product | MVP | done | doc_ver2/04-data/feed-ranking.md |
| D-08 | [message-document-format.md](./04-data/message-document-format.md) | Architecture/Crypto | MVP | done | ТЗ сообщения v2; ADR-0015 |

---

## 05-api — контракты

| ID | Документ | Владелец | Фаза | Статус | Источники |
|----|----------|----------|------|--------|-----------|
| API-01 | [api-guidelines.md](./05-api/api-guidelines.md) | Backend | MVP | done | ADR-0012 schema-first |
| API-02 | [rest-api.md](./05-api/rest-api.md) | Backend | MVP | done | friends, recovery, unified `/posts` |
| API-03 | [realtime-events.md](./05-api/realtime-events.md) | Backend | MVP | done | WebSocket; merge этап 3: `inbox.thread`, `inbox.event` |
| API-04 | [push-payloads.md](./05-api/push-payloads.md) | Client | MVP | done | UI `26-notifications`; merge этап 3: inbox push |
| API-05 | [rate-limits.md](./05-api/rate-limits.md) | Backend | MVP | done | `Тима.docx` |
| API-06 | [bot-api.md](./05-api/bot-api.md) | Backend | Beta | done (spec) | Phase 3b |
| API-07 | [bot-objects.md](./05-api/bot-objects.md) | Backend | Beta | done (spec) | Phase 3b |
| API-08 | [bot-updates.md](./05-api/bot-updates.md) | Backend | Beta | done (spec) | Phase 3b |
| API-09 | [bot-rate-limits.md](./05-api/bot-rate-limits.md) | Backend | Beta | done (spec) | Phase 3b |

---

## 06-realtime — звонки и LiveKit

| ID | Документ | Владелец | Фаза | Статус | Источники |
|----|----------|----------|------|--------|-----------|
| R-01 | [livekit-integration.md](./06-realtime/livekit-integration.md) | Media | MVP | done | LiveKit README |
| R-02 | [call-signaling.md](./06-realtime/call-signaling.md) | Backend | MVP | done | UI `21-call` |
| R-03 | [recording-policy.md](./06-realtime/recording-policy.md) | Product | Beta | done | ADR-0006 |
| R-04 | [livekit-operations.md](./06-realtime/livekit-operations.md) | SRE | MVP | done | config-sample.yaml |

---

## 07-operations — SRE

| ID | Документ | Владелец | Фаза | Статус | Источники |
|----|----------|----------|------|--------|-----------|
| O-01 | [observability.md](./07-operations/observability.md) | SRE | MVP | done | — |
| O-02 | [disaster-recovery.md](./07-operations/disaster-recovery.md) | SRE | Scale | done | ADR-0008 |
| O-03 | [incident-response.md](./07-operations/incident-response.md) | SRE | MVP | done | — |
| O-04 | [runbooks.md](./07-operations/runbooks.md) | SRE | MVP | done | — |
| O-05 | [mvp-server-setup.md](./07-operations/mvp-server-setup.md) | SRE | MVP | done | doc_ver2/07-deployment/server-setup.md; VPS Caddy compose |
| O-06 | [release-gates.md](./07-operations/release-gates.md) | DevOps/Security | Phase 1–5 | done | unsigned/credentialed environment contracts |
| O-07 | [credential-free-development.md](./07-operations/credential-free-development.md) | Architecture/DevOps | Phase 1 | done | development posture before store accounts |

---

## 08-quality — тестирование

| ID | Документ | Владелец | Фаза | Статус | Источники |
|----|----------|----------|------|--------|-----------|
| Q-01 | [test-strategy.md](./08-quality/test-strategy.md) | QA | MVP | done | merge этап 6: emotions, feed, shelves, attributes, inbox, bots |
| Q-02 | [load-test-plan.md](./08-quality/load-test-plan.md) | SRE | Scale | done | merge этап 6: S7–S11 social/bot scenarios |
| Q-03 | [security-test-plan.md](./08-quality/security-test-plan.md) | Security | Beta | done | merge этап 6: social/inbox/bot threats |
| Q-04 | [bot-platform-test-plan.md](./08-quality/bot-platform-test-plan.md) | QA | Beta | done (spec) | Phase 3b gate |
| Q-05 | [phase1-native-acceptance.md](./08-quality/phase1-native-acceptance.md) | QA | Phase 1 | done (spec) | Android/iOS/Windows immutable acceptance evidence |

---

## 09-delivery — поставка

| ID | Документ | Владелец | Фаза | Статус | Источники |
|----|----------|----------|------|--------|-----------|
| DEL-01 | [roadmap.md](./09-delivery/roadmap.md) | PM | All | done | merge этап 5–6: Phase 3b Bot, `entity_message` MVP |
| DEL-02 | [ci-cd-release.md](./09-delivery/ci-cd-release.md) | DevOps | MVP | done | merge этап 6: MVP VPS deploy, phase gates |
| DEL-03 | [dependency-policy.md](./09-delivery/dependency-policy.md) | DevOps | All | done | merge этап 6: Caddy, bot SDK |
| DEL-04 | [phase1-exit-review.md](./09-delivery/phase1-exit-review.md) | PM/Architecture | Phase 1 | done | dated evidence and BLOCKED decision |

---

## ADR — Architecture Decision Records

| ID | Документ | Решение | Статус |
|----|----------|---------|--------|
| ADR-0001 | [0001-evolutionary-services.md](./adr/0001-evolutionary-services.md) | Модульный monolith → сервисы по нагрузке | accepted |
| ADR-0002 | [0002-storage-sharding.md](./adr/0002-storage-sharding.md) | PostgreSQL + app-level sharding | accepted |
| ADR-0003 | [0003-kafka-outbox.md](./adr/0003-kafka-outbox.md) | Transactional outbox + staged EventBus (Redis beta → Kafka production) | accepted (amended) |
| ADR-0004 | [0004-controlled-escrow.md](./adr/0004-controlled-escrow.md) | Controlled escrow (ML-KEM, HSM) | accepted (amended by 0016) |
| ADR-0005 | [0005-kodium-readiness-gate.md](./adr/0005-kodium-readiness-gate.md) | Production gate: audit + signed prekey | accepted |
| ADR-0006 | [0006-livekit-media-policy.md](./adr/0006-livekit-media-policy.md) | SRTP без app E2EE; Egress по политике | accepted |
| ADR-0007 | [0007-search-split.md](./adr/0007-search-split.md) | Client index E2E / OpenSearch public | accepted |
| ADR-0008 | [0008-multi-region-strategy.md](./adr/0008-multi-region-strategy.md) | Single region + DR → multi-region | accepted |
| ADR-0009 | [0009-native-bot-app-platform.md](./adr/0009-native-bot-app-platform.md) | Native Bot/App Platform | accepted |
| ADR-0010 | [0010-mvp-storage-profile.md](./adr/0010-mvp-storage-profile.md) | PG+Redis+MinIO; Redis Streams beta EventBus, Kafka production | accepted (amended) |
| ADR-0011 | [0011-mvp-caddy-edge.md](./adr/0011-mvp-caddy-edge.md) | Caddy edge MVP | accepted |
| ADR-0012 | [0012-schema-first-api.md](./adr/0012-schema-first-api.md) | Schema-first API, Client/Bot split | accepted |
| ADR-0013 | [0013-double-ratchet-phase.md](./adr/0013-double-ratchet-phase.md) | Double Ratchet — phase 5 | accepted |
| ADR-0014 | [0014-participant-e2e-and-recovery.md](./adr/0014-participant-e2e-and-recovery.md) | Participant E2E + escrow + recovery | accepted |
| ADR-0015 | [0015-document-v2-and-media-pipeline.md](./adr/0015-document-v2-and-media-pipeline.md) | DocumentV2 + dual media pipeline | accepted |
| ADR-0016 | [0016-escrow-key-hierarchy-and-threshold.md](./adr/0016-escrow-key-hierarchy-and-threshold.md) | Escrow key hierarchy (epoch×shard) + threshold + scope invariant | accepted |
| ADR-0017 | [0017-kodium-crypto-hardening.md](./adr/0017-kodium-crypto-hardening.md) | Kodium crypto hardening (commitment, canonical sig, MAC, side-channel) | accepted |
| ADR-0018 | [0018-dual-region-ru-eu-production-architecture.md](./adr/0018-dual-region-ru-eu-production-architecture.md) | RU/EU regional cells + ciphertext-only relay + regional escrow | accepted |

---

## 10-sdk — developer SDK

| ID | Документ | Владелец | Фаза | Статус | Источники |
|----|----------|----------|------|--------|-----------|
| SDK-01 | [python-bot-sdk.md](./10-sdk/python-bot-sdk.md) | Backend | Beta | planned | PyPI post Phase 3b |
| SDK-02 | [domain-api-formats.md](./10-sdk/domain-api-formats.md) | Architecture | MVP | done | DocumentV2, media, revisions |

---

## UI-ТЗ (существующее)

| Путь | Экранов | Статус | Синхронизация с архитектурой |
|------|---------|--------|------------------------------|
| [doc_UI/](./doc_UI/00-index.md) | 38 | done | Merge doc_ver2 этап 2: `35-community`, `36-create-group-channel`, `37-content-editor`, ACL v2 |

---

## Исходные материалы (provenance)

| Файл | Тип | Использование |
|------|-----|---------------|
| `doc/проектируем приложение Основное.docx` | Research DOCX | Блоки 1–17 → P-01 |
| `doc/Тима.docx` | Research DOCX | KMP, LiveKit, attestation → A-*, S-05 |
| `doc/📋 ТЕХНИЧЕСКОЕ ЗАДАНИЕ сообщения.docx` | Research spec | DocumentV2 → D-08, ADR-0015 |
| `doc/📋 ТЕХНИЧЕСКОЕ ЗАДАНИЕ меди файлы.docx` | Research spec | Media pipeline → D-06, ADR-0015 |
| `Kodium git/kodium-main/` | Vendor lib + recovery spec | S-02, ADR-0005, ADR-0014, `е2е личная переписка .md` |
| `livekit-master git/` | Vendor SFU | R-*, ADR-0006 |

---

## P0 для production (контрольный список)

- [x] Threat model (S-01)
- [x] Crypto protocol (S-02)
- [x] Escrow legal access (S-04)
- [x] REST + WebSocket specs (API-02, API-03)
- [x] System + deployment (A-01, A-05, O-05)
- [x] LiveKit integration + recording policy (R-01, R-03)
- [x] SLO/observability (P-03, O-01)
- [x] Test strategy + crypto vectors (Q-01, Q-03)
- [ ] Независимый аудит Kodium (вне doc — ADR-0005 gate)
- [ ] Signed PreKey verification в Kodium (вне doc — ADR-0005 gate)
