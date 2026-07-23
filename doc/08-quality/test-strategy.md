# Test Strategy

## 1. Pyramid

```text
        / E2E (10%) \
       / Integration (30%) \
      / Unit (60%)         \
```

## 2. Scope by layer

| Layer | Tools | Coverage target |
|-------|-------|-----------------|
| Kodium (vendor) | Kotest | Upstream `./gradlew check` |
| messenger-crypto | Kotest | 90% critical paths |
| Backend Go | testing + testify | 80% services |
| Client KMP | Kotest + Compose UI tests | 70% domain |
| Contract | OpenAPI diff + Pact | All public endpoints |
| E2E | Appium / XCUITest / custom | Critical flows |

## 3. Critical user journeys (E2E)

1. Register + attestation → send private message → offline receive
2. Upload encrypted photo → download decrypt
3. 1:1 audio call Android ↔ iOS
4. Group call 5 participants SFU
5. Windows QR link → sync messages
6. Public channel post → fan-out → search
7. **Feed:** подписка + «мои атрибуты» → общая лента с `approved` постами; [+]/[−] меняет выдачу
8. **Emotions:** шкала 9 на публичном посте → `rating_counters` автора; повторная оценка того же сообщения отклоняется
9. **Shelves:** сохранение на публичную полку → появление в ленте друзей; личная полка 🔒 — только владелец, `shelf_key` не на сервере
10. **Attributes:** `declared` → `approved`/`rejected` → пост в/вне тематического среза
11. **Inbox (окно 4):** `POST /inbox/notify` (`entity_message`) → карточка без строки в `chats`; ВП managed inbox: `inbox_thread` FSM `new → taken → snoozed → closed`
12. **Bots (фаза 3b):** install app в канал → `notifyUser` → окно 4 + webhook callback ([bot-platform-test-plan.md](./bot-platform-test-plan.md))
13. **Peer recovery:** new device → peer consent → history with valid signatures
14. **Escrow legal sim:** soft-deleted message still in investigation package
15. **DocumentV2:** text-only, rich text, media-only и text+media roundtrip; optional-поля сохраняют каноническое отсутствие, private `encrypted_metadata` остаётся opaque для сервера
16. **Media:** private ciphertext и public processing идут разными pipeline; public API возвращает 3 варианта без `Original`
17. **Edit:** исходное сообщение неизменно, создаётся revision и доставляется `message.edited`
18. **Retention/legal hold:** expiry удаляет полный связанный набор только без hold; hold приостанавливает удаление
19. **Regional escrow:** signed config выбирается по conversation home region; RU/EU cross-cell substitution отклоняется
20. **Recovery abuse:** account/IP/proof limits и 24h session expiry блокируют transfer и оставляют audit evidence

## 4. Social / feed / inbox (integration)

| Область | Фокус | Документ |
|---------|-------|----------|
| Feed scoring | Redis `feed:{user}`, воркер, атрибутные срезы | [feed-ranking.md](../04-data/feed-ranking.md) |
| Emotions + rating | Идемпотентность, валентность ±1, 🧘 без рейтинга | [glossary.md](../01-product/glossary.md) |
| Shelves | Публичный fan-out vs encrypted private grants | [feed-ranking.md](../04-data/feed-ranking.md) §2 |
| Attributes | `post_attributes` FSM, merge дублей | [public-content-format.md](../01-product/public-content-format.md) |
| Inbox | `inbox_threads`, `inbox_events`, appeals; сущности **не** в личку | [rest-api.md](../05-api/rest-api.md), UI `10-free-communication` |
| Bots | InstallationPolicy, DM negative matrix | [bot-platform-test-plan.md](./bot-platform-test-plan.md) |

## 5. Crypto testing

- [security-test-plan.md](./security-test-plan.md) — vectors, property tests
- Cross-platform: encrypt Android → decrypt iOS
- Private media bytes загружаются только после client-side encryption; `encrypted_metadata` не попадает в plaintext logs/indexes.
- Private protocol contract: explicit `key_commitment` на каждом key path, explicit wire/API/DDL `presence_bitmap`, distinct lockstep `(protocol_version, metadata.format_version) = (2,2)`.
- Escrow config contract: signed `GET /v1/escrow/config`, pinned regional root, epoch/shard/key_id/validity/current+next keys; beta stub identical; private key never leaves HSM; no production bypass.
- Ratchet gates: Signed PreKey verification blocks external testers; ratchet required-by-default before GA.

## 6. Realtime testing

- WS reconnect chaos
- Kafka partition failure injection (production-like staging+); Redis Streams consumer-group/replay failure injection на beta VPS
- Message ordering property tests per chat_id
- `message.edited` duplicate/reorder tests: одна immutable revision, корректная актуальная проекция
- WS `inbox.thread` / `inbox.event` ordering per `thread_id`

## 7. Contract and policy matrix

| Decision | Required checks |
|----------|-----------------|
| `DocumentV2` | required `metadata`; optional text/markup/secrets; private/public и nullable/omit contract; compatible non-executable roundtrip |
| Executable blocked | executable node/attachment rejected before upload and render |
| Dual media pipeline | private plaintext never reaches server; public pipeline emits exactly 3 variants; no `Original` |
| Auth + presigned | unauthenticated issue denied; TTL = 15m; expired URL denied |
| Immutable revisions | no in-place UPDATE; edit creates revision + `message.edited`; retry idempotent |
| Domain API | SDK contract tests use domain entities and typed errors, not raw transport payloads |
| Retention/legal hold | cascade covers revisions/media/metadata; active hold wins over expiry and deletion request |
| Crypto envelope | commitment required on every private key path; bitmap explicit in wire/API/DDL; protocol/document versions distinct and lockstep |
| Regional escrow | RU/EU independent hierarchies; signed current+next config; immutable conversation home region; HSM private-key non-export |
| Recovery protection | 3/day/account, 10/day/IP init; 5 proofs/session; 24h TTL; configuration and override audit |
| Audit integrity | generic hold selectors; append-only hash-chain + signed Merkle checkpoints; 3y/6m/7y retention boundaries |

### 7.1. Blocking DocumentV2 cases

- Private/public text-only: соответствующий непустой text array присутствует, `markup` и private secrets опущены.
- Media-only: text field опущен, `markup` содержит реальный `media_id`; private media содержит разрешимый `secret_ref`.
- Rich text и text+media: node indexes валидны, canonical entity называется `text_link`, private URL разрешается через `secret_ref`.
- JSON `null`, `[]` и `{}` optional-полей дают один canonical result (API omit / SQL `NULL`); response не возвращает пустые значения.
- Полностью пустой документ, пустой layout/container и write с ненормализованными empty values отклоняются или нормализуются до последующей ошибки `has_content`.
- `metadata` без `format_version=2`, положительного `revision_number` или `content_mode` отклоняется.
- Private/public group mismatch с `groups.kind`, поля противоположного контура и dangling/лишний `secret_ref` отклоняются.
- Изменение presence bitmap, null sentinel или присутствия любого signed optional-поля ломает signature/AEAD verification.

## 8. CI gates

| Gate | Requirement |
|------|-------------|
| PR | Unit + lint + contract |
| Main | + integration (docker-compose) |
| Release | + E2E smoke + crypto vectors |
| External ratchet testers | Signed PreKey verification negative matrix — **blocking** |
| GA crypto | Ratchet required-by-default; signed regional escrow config; no escrow bypass — **blocking** |
| Phase 3 gate | Feed + emotions + attributes integration suite |
| Phase 3b gate | Bot DM negative matrix (§6 [bot-platform-test-plan.md](./bot-platform-test-plan.md)) — **blocking** |
| Content contract gate | Полная матрица §7 — **blocking** |

## 9. Test environments

| Env | Data |
|-----|------|
| unit | mocks |
| integration | Beta profile: docker-compose Postgres/Redis Streams/MinIO; production-contract profile дополнительно Kafka. Оба прогоняют один event schema/idempotency suite |
| staging | anonymized synthetic; [mvp-server-setup.md](../07-operations/mvp-server-setup.md) для ранней беты |
| prod | no test traffic |

## 10. Ссылки

- [load-test-plan.md](./load-test-plan.md)
- [bot-platform-test-plan.md](./bot-platform-test-plan.md)
- [feed-ranking.md](../04-data/feed-ranking.md)
- [ci-cd-release.md](../09-delivery/ci-cd-release.md)
- [domain-api-formats.md](../10-sdk/domain-api-formats.md)
