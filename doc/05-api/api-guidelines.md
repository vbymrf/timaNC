# API Guidelines

## 1. Base URL

```text
Production: https://api.tima.example/v1
Realtime:   wss://realtime.tima.example/v1/ws
Media RTC:  wss://rtc.tima.example
```

## 2. Versioning

- URL prefix `/v1`.
- Breaking change → `/v2` with 6-month overlap.
- `protocol_version` in crypto separate from API version. Для private wire текущие `protocol_version=2` и `metadata.format_version=2` фиксированы lockstep; независимое изменение запрещено.

## 3. Authentication

### User API

```http
Authorization: Bearer {access_token}
X-Device-Id: {uuid}
X-Attestation-Token: {optional, required mobile}
Idempotency-Key: {uuid}  # POST mutations
```

### Bot API

```http
Authorization: Bot {token}
Idempotency-Key: {uuid}  # POST mutations
```

> Bot API: отдельный base URL `/v1/bot/*`. См. [bot-api.md](./bot-api.md). User JWT не используется.

## 4. Error model

```json
{
  "error": {
    "code": "ATTESTATION_FAILED",
    "message": "Human readable",
    "details": {},
    "request_id": "uuid"
  }
}
```

| HTTP | Usage |
|------|-------|
| 400 | Validation |
| 401 | Auth expired |
| 403 | Forbidden / attestation |
| 404 | Not found |
| 409 | Idempotency conflict |
| 429 | Rate limit |
| 503 | Escrow/HSM unavailable (private send) |
| 403 | Bot: `BOT_PRIVATE_MESSAGING_FORBIDDEN`, `BOT_AUTHOR_FORBIDDEN`, `INSTALLATION_TARGET_MISMATCH` |

## 5. Pagination

```text
?cursor={opaque}&limit=50
```

Response:

```json
{
  "items": [],
  "next_cursor": "..."
}
```

## 6. Idempotency

- Required: message/revision writes, domain media upload creation (`/chats/{id}/media/uploads`, `/groups/{id}/media/uploads`, `/posts/assets`), upload completion and `POST /calls`.
- Server stores result 24h keyed by `Idempotency-Key` + device_id.

## 7. Content types

| Type | Usage |
|------|-------|
| `application/json` | REST metadata |
| `application/x-protobuf` | Private `DocumentV2` envelopes |
| `application/octet-stream` | Media upload to MinIO |

### `DocumentV2` presence rules

- `metadata` required: `format_version=2`, positive `revision_number`, `content_mode=private|public`.
- Private envelope/revision additionally requires explicit `protocol_version=2`, `presence_bitmap:uint32` and 32-byte `key_commitment`. Wrapped message/GK/shelf key records repeat the same `protocol_version` and `key_commitment`; mismatch rejects the payload.
- Private accepts optional `encrypted_nodes` / `markup` / `encrypted_metadata`; public accepts optional `nodes` / `markup`. The two sets MUST NOT be mixed; public encrypted fields are omitted in API and stored as `NULL`.
- Optional fields are omitted. Input JSON `null`, empty arrays and empty objects are normalized to absence before canonicalization; empty private metadata is not encrypted.
- Media-only documents omit the text field. Send/publish rejects both a fully empty document and markup containing only an empty layout (`has_content=false`).
- A private `text_link` carries `secret_ref`; its URL is resolved from non-empty `encrypted_metadata`. Group writes validate `content_mode` against the group mode.

## 8. TLS

- TLS 1.3 only.
- Certificate pinning on clients — SPKI hash in app config.

## 9. Schema-first delivery

> **ADR-0009** ([native Bot/App Platform](../adr/0009-native-bot-app-platform.md)) — принято: installation-only модель, `/v1/bot/*`, webhook/polling.  
> **ADR-0012** — schema-first API: каталог `schema/` как машинная истина, кодогенерация.

Два контура с разными гарантиями:

| | Client REST | Bot API |
|---|---|---|
| Стиль | REST `/v1/*` | Method-oriented `/v1/bot/{method}` |
| Схема | `schema/openapi/client-api.yaml` (Phase 0 machine truth) | `schema/openapi/bot-api.yaml` (Phase 3b, план) |
| Стабильность | Меняем свободно (владеем клиентом) | Публичный контракт: только аддитивные изменения |
| Auth | Device JWT + attestation | `Authorization: Bot {token}` |
| Область | Полный продукт | Только публичный контур, scopes по installation |

**Phase 0 machine contracts:**

- `schema/openapi/client-api.yaml` — core REST `/v1/*`;
- `schema/proto/tima/v1/{common,crypto,realtime}/*.proto` — binary WebSocket, crypto envelopes, revisions и key delivery;
- `schema/json/document-v2.schema.json`, `private-document-envelope.schema.json` и связанные JSON Schemas.

Core machine contracts созданы, проходят validation/compile checks и являются contract truth. Сохранённые модели находятся в `gen/go` и `gen/kotlin`; команды и закреплённые версии описаны в `schema/README.md`. Человекочитаемые документы ниже служат картами и объяснениями. Bot OpenAPI (`schema/openapi/bot-api.yaml`) и bot codegen остаются deliverable **Phase 3b**.

Человекочитаемые карты: [rest-api.md](./rest-api.md), [bot-api.md](./bot-api.md).

## 10. Ссылки

- [rest-api.md](./rest-api.md)
- [bot-api.md](./bot-api.md)
- [rate-limits.md](./rate-limits.md)
