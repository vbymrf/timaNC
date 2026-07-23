# Bot Platform — test plan

> **Статус:** `done` (spec) · **Версия:** 0.3 · **Дата:** 2026-07-13 · **Реализация:** Phase 3b

## 1. Scope

| Area | Coverage |
|------|----------|
| Bot API contract | OpenAPI schema, codegen roundtrip |
| InstallationPolicy | Author derivation, target match, scopes |
| Window 4 delivery | `notifyUser` → `inbox_events` (`event_type=entity_message`) |
| MVP parity | `POST /inbox/notify` и `notifyUser` — одинаковые лимиты/`block_messages` |
| Webhook | Delivery, retry, secret, SSRF |
| Long polling | Offset, dedup, timeout |
| Rate limits | Per app/installation/recipient |
| Negative security | DM bypass, author spoof, cross-object |
| SDK | Dispatcher, filters, FSM (unit) |

## 2. Contract tests

- OpenAPI spec ↔ Go handlers: every method has request/response schema.
- Generated Python types deserialize sample payloads.
- Error codes map to HTTP status per [bot-api.md](../05-api/bot-api.md).
- Public `DocumentV2.metadata` обязателен; optional `nodes`/`markup` опускаются, а encrypted-поля отклоняются.
- Text-only, media-only и text+media проходят; fully empty, empty layout и private/public mixing отклоняются.

## 3. InstallationPolicy (integration)

| Case | Expected |
|------|----------|
| Valid install + scope | 201 |
| Missing `installation_id` | 400 |
| Revoked installation | 403 `BOT_INSTALLATION_INACTIVE` |
| Missing scope | 403 `BOT_SCOPE_DENIED` |
| `target_id` mismatch | 403 `INSTALLATION_TARGET_MISMATCH` |
| Body contains `author_id` | 403 `BOT_AUTHOR_FORBIDDEN` |
| Body contains `sender_id` | 403 `BOT_AUTHOR_FORBIDDEN` |

## 4. Author derivation

| Installation target | Method | UI author |
|---------------------|--------|-----------|
| channel | `publishChannelPost` | channel display_name |
| group | `sendGroupMessage` | group display_name |
| community | `notifyUser` | community display_name |

Assert: response `author.type` = installation `target_type`; `provenance.app_id` present; no `users` row for bot.

## 5. Window 4 delivery (`entity_message`)

| Case | Expected |
|------|----------|
| `notifyUser` | `inbox_events` with `event_type=entity_message` |
| No `chats` row for recipient | PASS |
| WS `inbox.event` fired | PASS |
| User opens card | Shows group/channel/community as author |
| `entity_message_reply` update to webhook | PASS |
| `block_messages=true` for entity | 403 or silent drop (same as `/inbox/notify`) |
| Non-subscriber recipient | 403 |

## 6. Private DM negative matrix (mandatory)

| Attempt | Expected |
|---------|----------|
| `notifyUser` with private chat target | 403 |
| `sendGroupMessage` to E2E private group | 403 `BOT_E2E_FORBIDDEN` |
| Internal call with `chat.type=1:1` | 403 |
| INSERT `personal_messages` with bot provenance | DB reject |
| `POST /chats/{id}/messages` with bot token | 401/403 |
| Acting as VP `sender_id` | 403 `BOT_VP_FORBIDDEN` |
| `initiateCall` | 403 `BOT_CALLS_FORBIDDEN` |

## 7. Cross-object spoofing

| Case | Expected |
|------|----------|
| Install in channel A, post to channel B | 403 |
| Install in group, publish to channel | 403 |
| Reuse `installation_id` with different `target_id` | 403 |

## 8. Webhook tests

| Case | Expected |
|------|----------|
| Valid secret | 200 |
| Invalid secret | 401 |
| SSRF URL (127.0.0.1) | 400 `WEBHOOK_URL_INVALID` |
| Developer 500 | Retry with backoff |
| Duplicate `update_id` | Idempotent ack |
| `allowed_updates` filter | Only subscribed types enqueued |

## 9. Rate limit tests

| Case | Expected |
|------|----------|
| Exceed installation write limit | 429 + `retry_after` |
| Exceed per-recipient `notifyUser` limit (1/hour) | 429 |
| Sustained abuse | installation suspended |

## 10. Load (smoke)

- 100 concurrent `getUpdates` long polls per app.
- 1000 webhook deliveries/min per worker.
- `entity_message` fan-out to 10k recipients (batch).

## 11. SDK unit tests

- Router first-match routing.
- Filter AND chain + context enrichment.
- FSM state transitions (Redis mock).
- Exception mapping from HTTP responses.
- Domain API сериализует required `metadata` и только присутствующие public `nodes`/`markup`; `null`, `[]` и `{}` нормализуются в omit.
- Executable content maps to a typed blocked-content exception.
- Public media exposes 3 variants and no `Original`; private media cannot select public processing.
- Presigned URL issuance requires auth and models the fixed 15-minute expiry.
- Duplicate `message.edited` resolves to one immutable revision.
- Retention/legal-hold restrictions map to typed exceptions.

## 12. Acceptance gate

**Release blocked** if any test in §6 (DM negative matrix) fails.

## 13. Ссылки

- [bot-api.md](../05-api/bot-api.md)
- [threat-model.md](../03-security/threat-model.md)
- [test-strategy.md](./test-strategy.md)
- [python-bot-sdk.md](../10-sdk/python-bot-sdk.md)
