# TIMA — документация проекта

Кроссплатформенный мессенджер-комбайн (Android, iOS, Windows) с личным E2E-контентом, публичными лентами, медиа и real-time звонками.

## Быстрая навигация

| Раздел | Описание |
|--------|----------|
| [Реестр документов](./00-documentation-map.md) | Полный перечень, статусы, владельцы, фазы |
| [Реестр противоречий](./CONTRADICTION-REGISTER.md) | Принятые решения C-001…C-040 и контроль синхронизации |
| [Аудит мержа doc_ver2](./MERGE-AUDIT.md) | Статус этапов, закрытые конфликты, post-MVP |
| [01-product](./01-product/) | PRD, глоссарий, **сообщества** ([communities.md](./01-product/communities.md)), группы/каналы, NFR/SLO, матрица безопасности |
| [02-architecture](./02-architecture/) | Системная, клиентская, backend, **module-boundaries**, **tech-stack**, deployment; MVP VPS — [mvp-server-setup.md](./07-operations/mvp-server-setup.md) |
| [03-security](./03-security/) | Threat model, crypto protocol, escrow, compliance |
| [04-data](./04-data/) | `DocumentV2`, модель данных, media storage, sync, search, retention, **feed-ranking** |
| [05-api](./05-api/) | REST, WebSocket, push, rate limits, **Bot API** |
| [06-realtime](./06-realtime/) | LiveKit, signaling, recording policy |
| [07-operations](./07-operations/) | Observability, DR, incident response, runbooks, **[MVP server setup](./07-operations/mvp-server-setup.md)** (VPS + Caddy) |
| [08-quality](./08-quality/) | Test strategy, load/security tests |
| [09-delivery](./09-delivery/) | Roadmap, CI/CD, релизы |
| [adr](./adr/) | Architecture Decision Records |
| [doc_UI](./doc_UI/00-index.md) | UI-ТЗ (WireMD), 38 экранов |

## Ключевые архитектурные решения

- **Клиент:** Kotlin Multiplatform + Compose Multiplatform, offline-first (SQLDelight).
- **Backend:** Go, эволюционная service-oriented архитектура (модульный monolith → выделение сервисов по нагрузке).
- **Storage/EventBus:** PostgreSQL + Redis + MinIO ([ADR-0010](./adr/0010-mvp-storage-profile.md)); transactional outbox → Redis Streams на beta, Kafka до production/GA ([ADR-0003](./adr/0003-kafka-outbox.md)).
- **MVP edge:** Caddy ([ADR-0011](./adr/0011-mvp-caddy-edge.md)).
- **Крипто:** Kodium + [ADR-0014](./adr/0014-participant-e2e-and-recovery.md): participant E2E (ratchet/GK) + **обязательный controlled escrow** + peer/device/phrase recovery. Юридически не strict E2E.
- **Звонки:** LiveKit SFU, SRTP; application-level E2EE для звонков **не включён** ([ADR-0006](./adr/0006-livekit-media-policy.md)).
- **Double Ratchet rollout:** фаза 5 ([ADR-0013](./adr/0013-double-ratchet-phase.md)); канон participant path — [ADR-0014](./adr/0014-participant-e2e-and-recovery.md).
- **Масштаб:** 100 тыс. → 10 млн MAU; бета — VPS ([mvp-server-setup.md](./07-operations/mvp-server-setup.md)).
- **Production residency:** до GA — изолированные RU/EU cells, conversation home region и ciphertext-only relay ([ADR-0018](./adr/0018-dual-region-ru-eu-production-architecture.md)); beta остаётся single-region.
- **Bot Platform:** [ADR-0009](./adr/0009-native-bot-app-platform.md), Phase 3b; schema-first [ADR-0012](./adr/0012-schema-first-api.md).

## Источники требований

> Все DOCX являются исследовательскими материалами, а не релизной документацией. Нормативными являются связанные Markdown-спецификации в `doc`.

| Документ | Путь | Роль |
|----------|------|------|
| Концепция продукта (research) | `doc/проектируем приложение Основное.docx` | Источник продуктовых блоков 1–17 |
| Стек и инфра (research) | `doc/Тима.docx` | Источник решений по технологиям, attestation, LiveKit |
| ТЗ сообщений (research) | `doc/📋 ТЕХНИЧЕСКОЕ ЗАДАНИЕ сообщения.docx` | Источник решений для `DocumentV2`; нормативный текст — D-08 |
| ТЗ медиа (research) | `doc/📋 ТЕХНИЧЕСКОЕ ЗАДАНИЕ меди файлы.docx` | Источник media pipeline; нормативный текст — D-06 |
| Recovery spec | `Kodium git/kodium-main/е2е личная переписка .md` | Peer recovery, phrase → [ADR-0014](./adr/0014-participant-e2e-and-recovery.md) |
| Crypto protocol | [03-security/crypto-protocol.md](./03-security/crypto-protocol.md) | Нормативная спека E2E + escrow |
| Формат сообщений | [04-data/message-document-format.md](./04-data/message-document-format.md) | `DocumentV2`: nodes, markup, encrypted metadata, ревизии |
| Хранение медиа | [04-data/media-storage.md](./04-data/media-storage.md) | Private/public pipeline, три варианта, доступ и retention |
| Социальные сущности | [01-product/social-objects/00-index.md](./01-product/social-objects/00-index.md) | Индекс: community, group, channel, audio-chat, **virtual-user**, **bot-application** |
| Сообщества (ACL) | [01-product/communities.md](./01-product/communities.md) | Контейнер, подписка, `preview`/`open`/`restricted` |
| Формат публикаций | [01-product/public-content-format.md](./01-product/public-content-format.md) | Public `DocumentV2` |
| Ленты и атрибуты | [04-data/feed-ranking.md](./04-data/feed-ranking.md) | Две ленты, полки, скоринг, эмоции |
| Inbox / окно 4 | [doc_UI/10-free-communication.md](./doc_UI/10-free-communication.md) | `entity_message`, managed inbox ВП, appeals |
| MVP deploy | [07-operations/mvp-server-setup.md](./07-operations/mvp-server-setup.md) | VPS docker-compose + Caddy |
| UI-ТЗ | [doc_UI/00-index.md](./doc_UI/00-index.md) | Экраны и UX |

## Vendor-зависимости (не код TIMA)

| Компонент | Путь в репо | Лицензия | Версия (pin) |
|-----------|-------------|----------|--------------|
| Kodium | `Kodium git/kodium-main/` | Apache 2.0 | `eu.livotov.labs:kodium:1.0.0` |
| LiveKit Server | `livekit-master git/` | Apache 2.0 | по `go.mod` upstream |

Политика обновлений: [09-delivery/dependency-policy.md](./09-delivery/dependency-policy.md).

## Статус репозитория

На момент документирования: **design-first workspace** — UI-ТЗ и архитектурные спеки готовы; прикладной код клиента и backend **ещё не реализован**.
