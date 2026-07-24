# Roadmap

> **Crypto/recovery канон:** [ADR-0014](../adr/0014-participant-e2e-and-recovery.md)
>
> **Release policy:** Alpha и beta распространяются по внутренним трекам; публичная публикация Android/iOS/Windows в stores разрешена только после прохождения gates Phase 5. GA не является частью Phase 5 и объявляется только после её успешного exit.
>
> **Capacity scope:** invited cohort до 100 пользователей перенесён в Phase 5
> Closed Beta и не является архитектурным пределом. Базовая target architecture
> проектируется и проверяется на **100k MAU**; Phase 6 развивает её до 10M MAU.

## Phase 0: Foundation

- [x] Kodium library integrated as vendor
- [x] LiveKit server vendored
- [x] UI-ТЗ 38 screens
- [x] Architecture documentation (this doc set)
- [x] Core machine contracts: Client OpenAPI, crypto/event Protobuf, DocumentV2 JSON Schema and committed Go/Kotlin models
- [x] Core domain schema and migrations (users, devices, conversations, messages, keys, media)
- [x] MVP VPS compose manifest ([mvp-server-setup.md](../07-operations/mvp-server-setup.md)) in `infra/`

**Exit:** core machine contracts и schema зафиксированы; миграции воспроизводимы; Caddy + backend healthz работают на staging VPS.

## Phase 1: Messaging Alpha (Months 1–2)

- [x] KMP project scaffold + SQLDelight
- [x] `messenger-crypto` module (envelope, signatures, escrow client, wrapped-key fallback)
- [x] Go tima-server: auth, users, messages, keys
- [x] Separate Realtime GW + workers; transactional outbox → Redis Streams в beta profile
- [x] MinIO media pipeline (client encrypt)
- [x] `DocumentV2` contract: `nodes`, `markup`, private `encrypted_metadata`; executable content blocked
- [x] Dual media pipeline: private ciphertext / public processing; public exactly 3 variants, no `Original`
- [x] Auth-gated presigned URL with fixed TTL 15 minutes
- [x] Immutable message revisions + `message.edited`
- [x] Domain API and SDK formats ([domain-api-formats.md](../10-sdk/domain-api-formats.md))
- [x] Attestation iOS/Android production adapter boundaries; real vendor verification deferred to Phase 5
- [x] Windows QR linking — server, DPAPI-backed client flow and black-box roundtrip
- [x] Private 1:1 participant E2E (envelope + escrow)
- [x] Hybrid generic notification delivery: repository-owned Go gateway, FCM/APNs/WNS adapters, Android UnifiedPush fallback, generic payload policy and shared WS/REST catch-up
- [x] Unsigned Android/iOS/Windows validation workflows; signed/internal-track artifacts deferred to Phase 5
- [x] Encrypted-at-rest SQLDelight UI cache on Android/iOS/Windows with platform-protected row key and logout wipe
- [x] Durable post-encryption client send/retry outbox across process restart on Android/iOS/Windows
- [x] Private 1:1 single-image alpha shared pipeline: exactly three encrypted JPEG variants, durable retry and media-only DocumentV2
- [x] Android Views and Windows Swing image picker, progress, thumbnail and in-app preview
- [x] iOS PHPicker/UIKit normalization and SwiftUI thumbnail/preview pass hosted Xcode Swift simulator build/package validation
- [ ] Complete native messaging UI and Phase 1 acceptance journeys

**Exit:** hosted repository-controlled CI matrix проходит; hybrid notification
fallback и native messaging acceptance подтверждены; local message smoke
выполнен; каждое private message имеет `escrow_blob`;
content/media/revision contract gates проходят. Signed PreKey не блокирует
envelope-only development и становится blocking перед внешним тестированием
ratchet path A.

**Current review:** [Phase 1 exit is BLOCKED](./phase1-exit-review.md). Hybrid
notification implementation and the hosted CI matrix pass; native acceptance
remains open. Android Views, iOS SwiftUI and Windows Swing now have first
private 1:1 slices over shared core-data. Android/iOS provide secure session
restore, development registration/login, chat/thread operations, encrypted
send/retry/edit/read/delete and foreground/resume catch-up. Windows preserves QR
start/claim, reuses its DPAPI-backed linked identity/session, adds equivalent
chat/thread and encrypted message operations, wipes its encrypted offline cache
and protected key on logout, and advertises periodic foreground REST catch-up
rather than WNS.
Development OTP/HMAC and escrow fixtures require explicit platform build/runtime
flags; Windows additionally permits HTTP only for loopback in that mode.
Production profiles fail closed with encrypted writes disabled until platform
attestation enrollment where applicable and verified production escrow roots
are provisioned. iOS without APNs advertises foreground/resume catch-up only.
Encrypted durable chat/history restoration and the post-encryption send/retry
outbox are implemented on all clients. The exact ciphertext request and
idempotency key survive restart; reservation must still complete online before
the durable boundary, so completely offline composition/send is not claimed.
Android/iOS/Windows private image alpha is implemented over the shared secure
pipeline. Hosted platform tests, unsigned packages and the iOS Xcode Swift
simulator build pass in run `30117799598`. Windows additionally passes local
UI startup and the HTTP link/encrypted roundtrip harness; steps 1–9 execution
manifests remain incomplete, so Phase 1 stays BLOCKED.
Notification behavior is specified in
[hybrid-notification-delivery.md](../02-architecture/hybrid-notification-delivery.md);
Phase 2 does not start while Phase 1 remains blocked.

## Phase 2: Communication MVP (Month 3)

- [ ] LiveKit deploy + Call Service
- [ ] 1:1 and group calls (SFU) on Android and iOS
- [ ] Windows calls via official LiveKit C++ SDK + narrow JNI/JNA adapter
- [ ] Private groups + Sender Keys + GK escrow
- [ ] Voice messages (Opus → Kodium)
- [ ] Production-shaped generic push without plaintext payloads

**Exit:** Android ↔ iOS ↔ Windows calls stable; group call на 20 пользователей; messaging, groups, voice messages and offline generic push form the Communication MVP.

## Phase 3: Social Beta (Months 4–5)

- [ ] Public channels, groups plaintext
- [ ] Feed fan-out (worker) + server scoring ([feed-ranking.md](../04-data/feed-ranking.md))
- [ ] Attributes/genres registry, `declared` → `approved` moderation
- [ ] Two favorites shelves (public fan-out / private encrypted `shelf_key`)
- [ ] **9-emotion scale:** 8 валентных эмоций → раздельные rating counters «+»/«−», нейтральная не влияет на рейтинг; [+]/[−] recommendations (public only)
- [ ] **Окно 4 — inbox:** `inbox_threads`, `inbox_events`; `entity_message` via `POST /inbox/notify`, appeals остаются отдельными тредами
- [ ] **Peer recovery:** group → 1:1 protocol ([crypto-protocol.md](../03-security/crypto-protocol.md) §4)
- [ ] **Identity phrase** proof for new devices
- [ ] PostgreSQL FTS for public search (+ attributes type)
- [ ] Search metrics and documented OpenSearch activation triggers; migration only when a trigger fires
- [ ] Stories, collections (public)
- [ ] Moderation queue API (`/posts/{id}/approve|reject`)

**Exit:** общая + друзья ленты и PostgreSQL FTS работают в single-region beta; peer recovery on staging; moderation API live; OpenSearch decision подтверждён метриками.

## Phase 3b: Bot Beta (Month 5–6)

- [ ] Bot-specific schema and migrations
- [ ] Bot API spec implementation (`/v1/bot/*`, installation-only)
- [ ] App registry, installations, token service
- [ ] `notifyUser` → `entity_message` projection
- [ ] Webhook delivery + long polling
- [ ] Python SDK reference (`tima-bot-sdk`)
- [ ] Bot platform test plan
- [ ] Caddy route `/v1/bot/*` на beta VPS
- [ ] Schema-first Bot API: `schema/openapi/bot-api.yaml` ([ADR-0012](../adr/0012-schema-first-api.md)); core schema remains Phase 0 scope

**Exit:** developer can install app in channel and send `entity_message` via `notifyUser`; bot schema migration and compatibility tests pass.

## Phase 4: Compliance/Scale Preparation (Months 6–9)

- [ ] Validate 100k MAU target architecture; enable sharding only by capacity triggers
- [ ] **Escrow HSM production integration** — full legal access incl. soft-deleted; no production deployment before this gate
- [ ] Retention policy execution over messages, revisions, media and metadata
- [ ] Legal hold overrides retention/deletion for the complete related object graph
- [ ] **Optional phrase backup** for 1:1/group history
- [ ] Kodium external audit closure ([ADR-0005](../adr/0005-kodium-readiness-gate.md))
- [ ] Load test 100k MAU target profile
- [ ] Prepare dual RU/EU production topology, data placement, failover runbooks and observability
- [ ] Blogger windows 6–7

**Exit:** HSM integration test, 100k MAU profile and compliance drills pass; RU/EU production-readiness plan is executable. Beta itself remains single-region and non-production.

## Phase 5: Closed Beta / RC

- [ ] Signed PreKey verification gate до подключения любых внешних ratchet testers
- [ ] **Double Ratchet required and default** for active 1:1 ([ADR-0013](../adr/0013-double-ratchet-phase.md))
- [ ] Local FTS (private) + push polish
- [ ] Settings, help, app lock, blacklist, invisible mode, disappearing chats
- [ ] Observability hardening
- [ ] Closed beta via TestFlight / internal track / internal MSIX
- [ ] RC builds for Android, iOS and Windows; MSIX is the primary Windows package
- [ ] Real App Attest / Play Integrity / FCM / APNs end-to-end verification
- [ ] Signed AAB, iOS archive and MSIX installed on representative devices
- [ ] Invited cohort до 100 пользователей with immutable delivery/SLO evidence
- [ ] Dual RU/EU production readiness drill
- [ ] Public store metadata and submissions prepared, but not released before exit

**Exit / pre-GA gates:** Double Ratchet is default; Signed PreKey, Kodium audit, HSM, legal escrow, retention/legal-hold and content contract gates pass; RC acceptance passes; both RU and EU production regions are ready. Only after this exit may public store rollout and GA begin.

## GA milestone (after Phase 5)

- [ ] Controlled public store rollout for Android/iOS and Windows MSIX
- [ ] GA declaration after production smoke checks in the ready RU/EU topology

## Phase 6: 10M Growth (Year 2+)

- [ ] Scale multi-region topology toward 10M MAU
- [ ] Service extraction (media, notification)
- [ ] Advanced analytics ClickHouse
- [ ] Optional LiveKit E2EE evaluation (separate ADR)

## Post-MVP backlog (phased, not in MVP exit)

| Item | Phase | Gate |
|------|-------|------|
| Рекламный кабинет блогера | 6+ | UI-only spec exists |
| Маркетплейс передачи ВП | 6+ | Legal review |
| Web-клиент | Post-GA | Separate product and trust-model decision |
| Поведенческий скоринг ленты | 6+ | Privacy review |
| OpenSearch cluster migration | Trigger-based after Phase 3 | Search capacity/relevance trigger |
| Moderation backoffice UI | 4 | API ready Phase 3 |
| Bot `schema/` machine truth | 3b | ADR-0012; core schema is Phase 0 |
| Crypto test vectors V1-* | 4 | ADR-0005 |
| Double Ratchet PQ | 6+ | Feature flag |

## Dependencies (production gates)

| Blocker | Phase |
|---------|-------|
| Signed PreKey verify | Before any external ratchet testers |
| Double Ratchet required/default | Before GA |
| Kodium audit | Before GA |
| Legal escrow review | Before any production |
| Escrow HSM integration test | Before any production |
| Dual RU/EU production readiness | Before GA |
| Content/media/revision contract matrix | Before Messaging Alpha exit |
| Retention purge + legal hold drill | Before retention enforcement in production |

## Ссылки

- [ADR-0014](../adr/0014-participant-e2e-and-recovery.md)
- [mvp-server-setup.md](../07-operations/mvp-server-setup.md)
- [ci-cd-release.md](./ci-cd-release.md)
