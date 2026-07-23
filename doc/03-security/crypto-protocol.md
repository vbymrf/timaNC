# Crypto Protocol Specification (TIMA)

> **Version:** 1.3 · **Library:** Kodium 1.0.0 · **Normative for:** private messaging  
> **Decisions:** [ADR-0014](../adr/0014-participant-e2e-and-recovery.md), [ADR-0015](../adr/0015-document-v2-and-media-pipeline.md), [ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md), [ADR-0017](../adr/0017-kodium-crypto-hardening.md)  
> Overview (legacy): [messenger-crypto-architecture.md](../messenger-crypto-architecture.md) · Audit: [kodium-security-audit.md](./kodium-security-audit.md)

## 1. Design principle

> **Participant E2E = основной контур. Ratchet = PFS. Wrapped keys = delivery fallback. Escrow = compliance (обязателен). Recovery = peer/device/phrase (не escrow).**

Четыре независимых слоя. Потеря ratchet-сессии **must not** блокировать доставку (fallback на wrapped keys). Escrow **must** присутствовать на каждом private сообщении.

**Юридически** продукт не является strict E2E из-за обязательного escrow ([glossary.md](../01-product/glossary.md)).

## 2. Algorithms (Kodium mapping)

| Purpose | Algorithm | Kodium API |
|---------|-----------|------------|
| Identity | X25519 + Ed25519 | `Kodium.generateKeyPair()` |
| Message encrypt | XSalsa20-Poly1305 (SecretBox) | `encryptSymmetric` |
| Key wrap | Curve25519 Box | `encrypt` / `decrypt` |
| Signatures | Ed25519 detached | `signDetachedToEncodedString` |
| KDF | HKDF-SHA256, PBKDF2 | `deriveKeyFromPassword` |
| Key commitment | HKDF-SHA256(message_key, info="tima/commit/v1") | app-layer ([ADR-0017](../adr/0017-kodium-crypto-hardening.md)) |
| Escrow | Hybrid X25519-threshold + ML-KEM-768 to `Escrow_Public[region,epoch,shard]` | signed config + `MLKEM.encapsulate` + threshold ([ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md)) |
| Participant 1:1 | X3DH + Double Ratchet | `X3DH`, `DoubleRatchetSession` ([ADR-0013](../adr/0013-double-ratchet-phase.md)) |
| Identity phrase | PBKDF2 → HKDF subkeys | `deriveKeyFromPassword` |
| `shelf_key` | 32 random bytes | Wrapped on owner devices; escrow per `key_version` |
| Encoding | Base64 + 4-byte checksum | `*ToEncodedString` |

## 3. Personal message envelope (1:1)

### 3.1. Wire format (protobuf logical)

```text
PersonalMessage {
  message_id:       uint64
  revision_id:      UUID
  parent_revision_id: optional UUID
  chat_id:          UUID
  sender_id:        UUID
  sender_device:    UUID
  protocol_version: uint32  // REQUIRED, current=2; lockstep with metadata.format_version

  encrypted_nodes:  optional repeated bytes // nullable BYTEA[]; one AEAD ciphertext per present text node
  markup:            optional canonical JSON // nullable open JSONB; allowlisted layout/routing only
  metadata:          canonical JSON // REQUIRED: format_version=2, revision_number, content_mode=private
  encrypted_metadata: optional bytes // nullable sensitive values referenced by secret_ref
  presence_bitmap:   uint32         // REQUIRED explicit wire/API/DDL field; 4-bit order from D-08
  message_key_id:    uint32  // rotation epoch
  key_commitment:    bytes   // REQUIRED on every private key path; HKDF-SHA256(message_key,"tima/commit/v1")

  escrow_blob:       bytes   // epoch_id||shard_id||eph_pub||kem_ct||sym_wrap of message_key (REQUIRED)

  ratchet_envelope:  optional bytes  // DoubleRatchet ciphertext (primary path)

  signature:         bytes   // Ed25519(sender_signing_key, canonical bytes incl. bitmap/null sentinels/commitment)
}
```

До криптографической канонизации optional `null`, `[]` и `{}` нормализуются в отсутствие: SQL хранит `NULL`, API опускает поле. `metadata` отсутствовать не может. `encrypted_metadata` создаётся только при наличии хотя бы одного `secret_ref`; пустой объект не шифруется. `presence_bitmap` — отдельное обязательное поле во wire, API request/response и DDL (не вычисляемая проекция JSON); для каждого nullable-поля canonical bytes содержат bitmap и либо значение, либо deterministic null sentinel.

`key_commitment` ([ADR-0017](../adr/0017-kodium-crypto-hardening.md)) — обязательное явное поле **каждого private key path**: envelope/ratchet payload, каждый `WrappedKeyRecord`, `escrow_blob`, GK/user wrap, `shelf_key` blob/grantee wrap, recovery transfer и phrase backup. Оно входит в signature input и AEAD AAD каждого присутствующего `encrypted_nodes[i]`/`encrypted_metadata`. XSalsa20-Poly1305 не является key-committing (R-1): пути ratchet/wrapped/escrow могли бы дать **разный** валидный plaintext. Каждый путь расшифровки пересчитывает commitment из восстановленного ключа и сверяет; отсутствие или `commitment_mismatch` → payload отвергается.

`protocol_version` конверта и `metadata.format_version` документа — два разных обязательных поля. Сейчас оба равны `2`; валидатор требует равенство и поддерживаемое значение. До отдельного ADR они повышаются **lockstep**: смешанные пары (`2/3`, `3/2`) отклоняются, но поля не объединяются.

`DocumentV2` полностью заменяет `body + entities(offset/length)` и placeholder. `has_content` требует непустой text array либо реальный media/content binding; пустой layout/container/`children=[]` не считается содержимым и send отклоняется. В private `text_link` и другие чувствительные значения находятся в `encrypted_metadata` и связываются с открытой entity через `secret_ref`.

`presence_bitmap`, deterministic null sentinels, `markup` и обязательный `metadata` канонизируются детерминированно и входят:

1. в Ed25519 signature input вместе с envelope headers, присутствующими `encrypted_nodes`, nullable `encrypted_metadata` и media bindings;
2. в AEAD AAD **каждого присутствующего** `encrypted_nodes[i]` и `encrypted_metadata`.

Следовательно, backend не может незаметно изменить открытый JSONB. Исполняемые nodes/markup запрещены; `code` — только inert text.

### 3.2. Wrapped key (server table, fallback delivery)

```text
WrappedKeyRecord {
  message_id:    uint64
  chat_id:       UUID
  device_id:     UUID
  wrapped_key:   bytes     // Box(ephemeral, device_identity_pub, message_key)
  key_commitment: bytes    // must equal envelope.key_commitment (R-1 invariant)
}
```

Отсутствующий `key_commitment` делает private wrap невалидным; legacy/private обхода без commitment нет.

### 3.2a. Signed escrow config

Клиент получает bundle через authenticated `GET /v1/escrow/config`. Ответ подписан ключом, цепочка которого заканчивается на pinned signing root клиента:

```text
EscrowConfigBundle {
  config_version, region, epoch_id, shard_id, key_id
  valid_from, valid_until
  current_public_keys { x25519_threshold, mlkem768 }
  next_public_keys?   { x25519_threshold, mlkem768 }
  signature
}
```

Подпись покрывает все поля и canonical ordering. Клиент проверяет pinned root, signature, `region`, validity, epoch/shard/key_id и выбирает только current/next key для home region беседы. Expired, unsigned, cross-region или unknown-key bundle fail-closed. Приватные escrow-ключи никогда не возвращаются API и не покидают HSM. Beta stub обязан реализовать тот же endpoint, поля, подпись и ошибки; тестовый signing root pin отделён от production.

### 3.3. Send algorithm

```kotlin
val messageKey = Kodium.generateHighEntropyKey()
// R-1: key commitment binds every delivery path to the same message_key
val keyCommitment = hkdfSha256(messageKey, info = "tima/commit/v1", length = 32)
val normalized = normalizeOptionalFields(document) // null/[]/{} -> absent
require(envelope.protocolVersion == 2)
require(normalized.metadata.formatVersion == 2)
require(envelope.protocolVersion == normalized.metadata.formatVersion) // lockstep pair
require(normalized.metadata.contentMode == PRIVATE)
require(hasContent(normalized)) // non-empty text array or real media/content binding
val presenceBitmap = presenceBitmap(normalized)
val aad = canonicalEnvelopeHeaders + keyCommitment + presenceBitmap +
  canonicalNullable(normalized.markup, NULL_SENTINEL) + canonicalJson(normalized.metadata)
val plaintextTextNodes = normalized.privatePlaintextTextNodes // local-only; это не public API-field `nodes`
val encryptedNodes = plaintextTextNodes?.mapIndexed { index, textNode ->
  aeadEncrypt(messageKey, zstdCompress(encodeUtf8(textNode)), aad + index)
}
val encryptedMetadata = normalized.sensitiveMetadata
  ?.takeIf { it.isNotEmpty() }
  ?.let { aeadEncrypt(messageKey, serialize(it), aad + "encrypted_metadata") }

// Escrow REQUIRED — no production bypass. Signed config by (region, epoch, shard), ADR-0016
val escrowPublicKey = verifiedEscrowConfig(homeRegion(chatId), quarterOf(now), shardOf(chatId))
val (kemCt, ssPq) = MLKEM.encapsulate(escrowPublicKey.mlkemPub)
val eph = Kodium.generateKeyPair()
val ssCl = beforeNm(escrowPublicKey.x25519ThresholdPub, eph.secret)
val kEscrow = hkdfSha256(ssCl + ssPq, info = "tima/escrow/v1")
val escrowBlob = regionId + epochId + shardId + keyId + keyCommitment +
  eph.publicKey + kemCt + Kodium.encryptSymmetric(kEscrow, messageKey)

// Primary: ratchet (participant E2E)
val ratchetEnv = ratchetSession.encrypt(
  serializeNullable(messageKey, keyCommitment, encryptedNodes, encryptedMetadata, presenceBitmap, NULL_SENTINEL)
)

// Fallback: wrapped keys per device (same commitment)
for (device in recipient.devices + sender.devices) {
  val ephemeral = Kodium.generateKeyPair()
  val wrapped = Kodium.encrypt(ephemeral, device.identityPublic, messageKey)
  uploadWrappedKey(messageId, device, wrapped, keyCommitment)
}

val sig = Kodium.signDetached(
  signingKey,
  canonicalBytes(
    headers, keyCommitment, presenceBitmap,
    nullable(encryptedNodes, NULL_SENTINEL),
    nullable(normalized.markup, NULL_SENTINEL),
    normalized.metadata,
    nullable(encryptedMetadata, NULL_SENTINEL),
    mediaBindings
  )
)
```

### 3.4. Receive algorithm

```kotlin
fun decrypt(msg: PersonalMessage, session: DoubleRatchetSession?): DocumentV2 {
  verifySignature(msg)  // REQUIRED; canonical Ed25519 (reject non-canonical S, R-2)
  requireCanonicalPresence(msg) // bitmap agrees with fields; empty optional values rejected
  require(msg.protocolVersion == 2)
  require(msg.metadata.formatVersion == 2 && msg.metadata.contentMode == PRIVATE)
  require(msg.protocolVersion == msg.metadata.formatVersion) // distinct fields, lockstep

  // R-1: on EVERY path recompute commitment from recovered key and compare
  fun requireCommit(key: ByteArray) =
    require(hkdfSha256(key, "tima/commit/v1", 32).contentEquals(msg.keyCommitment)) {
      "commitment_mismatch" // path yielded a different key → reject
    }

  // Path A: ratchet (primary participant E2E)
  msg.ratchetEnvelope?.let { env ->
    session?.decrypt(env)?.let { ratchetPayload ->
      requireCommit(ratchetPayload.messageKey)
      return decryptDocumentV2FromRatchet(ratchetPayload, msg.markup, msg.metadata)
    }
  }
  // Path B: wrapped key fallback
  val messageKey = Kodium.decrypt(identityPrivate, fetchWrappedKey(msg.id))
  requireCommit(messageKey)
  return decryptDocumentV2(messageKey, msg.encryptedNodes, msg.encryptedMetadata, msg.markup, msg.metadata)
}
```

`verifySignature` использует канонизированную проверку Ed25519: не-канонический `S` (`S ≥ L`) отвергается (R-2). Байты подписи **не** используются как идентификатор/ключ дедупа — идентичность только `(chat_id, message_id, revision_id)`.

### 3.5. Double Ratchet (participant path)

Canonical participant E2E for 1:1. Rollout by named gates per [ADR-0013](../adr/0013-double-ratchet-phase.md); ratchet is required-by-default before GA.

- X3DH via PreKey bundle; **Signed PreKey verification is a blocking gate before any external ratchet tester** ([ADR-0005](../adr/0005-kodium-readiness-gate.md)).
- `DoubleRatchetSession` (Kodium); `maxSkippedMessages = 2000`.
- Desync → silent fallback to wrapped keys; background re-X3DH.
- PQ variant — post-classical, feature flag.

## 3.6. Crypto hardening (Kodium audit R-1..R-4, [ADR-0017](../adr/0017-kodium-crypto-hardening.md))

| Risk | Свойство Kodium | Прикладная мера (normative) |
|------|-----------------|-----------------------------|
| R-1 | Poly1305 не key-committing | `key_commitment` в envelope/AAD/wrapped/escrow; проверка на всех путях (§3.1, 3.3, 3.4) |
| R-2 | Ed25519 malleable `S` | Reject non-canonical `S` (low-S) при verify; подпись не используется как id/dedup |
| R-3 | SHA-512 length-extension | Только HMAC/HKDF/Poly1305; запрет `H(secret‖msg)`; CAS `SHA-256(plaintext)` — не MAC |
| R-4 | Нет constant-time в JS | Секретные операции только native/JVM; ключи в Keystore/Enclave ([client-hardening.md](./client-hardening.md)) |

## 4. Peer recovery (history transfer)

When requesting device has no local history and no trusted device:

```text
RecoveryTransfer {
  request_id:       UUID
  chat_id:          UUID
  requester_device: UUID
  responder_device: UUID

  // Responder packages local history
  chunks[]: {
    original_message: PersonalMessage  // signatures preserved
    rewrapped_payload: Box(requester_identity, message_key)  // or batch key
    key_commitment:    bytes  // REQUIRED; equals original envelope commitment
  }

  responder_signature: Ed25519
  requester_proof:     bytes  // identity derived from secret phrase (if new device)
}
```

Rules:

1. Responder **must** show consent UI; may decline.
2. Requester receives push «запрошено восстановление — это вы?».
3. Client verifies **every** message signature before display.
4. Server relays ciphertext only; rate-limits bulk recovery ([ADR-0014](../adr/0014-participant-e2e-and-recovery.md)).
5. Recovery init limits: `3/day/account` and `10/day/IP`. Identity-proof attempts: `5/session`.
6. Recovery session TTL: `24h`. Все лимиты/TTL configurable, изменения и срабатывания security-relevant limits аудитируются.

**Order of implementation:** private group → 1:1 → identity phrase proof → optional phrase backup.

## 5. Secret phrase (identity + optional backup)

- One phrase per account (BIP39-like mnemonic or user-chosen).
- Derives **identity proof key** via PBKDF2 — proves «это я» to peers on new device.
- Per-chat subkeys: `HKDF(phrase_root, info="chat:"+chat_id)` — isolated chats, one phrase to remember.
- Phrase **does not** replace device messaging keys for live traffic.
- Optional encrypted backup blob under phrase — opt-in insurance when peer unavailable.
- Phrase backup record **must** carry signed `key_commitment` for every backed-up private content key; restore rejects absent/mismatched commitment.

**Self-only content** (no second participant): recovery **only** via phrase backup or old device.

## 6. Private shelves (`shelf_key`)

Личная полка и self-only notes ([feed-ranking.md](../04-data/feed-ranking.md) §2):

```text
PrivateShelfBlob {
  owner_id
  encrypted_payload:  SecretBox(zstd(bookmark_list_json), shelf_key)
  escrow_blob:        EscrowPublic(region,epoch,shard,shelf_key) // REQUIRED per key_version
  key_commitment:     HKDF(shelf_key,"tima/commit/v1")       // R-1
  key_version:        int
}
```

- No peer recovery path — phrase backup or old device only.
- Grant to friends: owner wraps `shelf_key` on grantee `device_id`; each grantee wrap stores the same required `key_commitment`.

## 7. Private groups (Sender Keys)

```text
GroupMessage {
  group_id, message_id, revision_id, parent_revision_id?, sender_id, protocol_version, gk_version
  encrypted_nodes:    optional repeated bytes // nullable BYTEA[]; present DocumentV2 text nodes under GK
  markup:             optional canonical JSON // nullable open JSONB, signature + AAD
  metadata:           canonical JSON // REQUIRED: format_version=2, revision_number, content_mode
  encrypted_metadata: optional bytes // only non-empty secret_ref map
  presence_bitmap:    uint32         // REQUIRED explicit wire/API/DDL field
  escrow_blob:       EscrowPublic(region,epoch,shard,GK) // REQUIRED one per GK period
  key_commitment:    HKDF(GK,"tima/commit/v1")       // REQUIRED on GK/escrow/user-wrap paths
  signature:         Ed25519(headers + key_commitment + bitmap + nullable values/null sentinels + media bindings)
}
```

**GK rotation triggers:** every 100 messages, member join/leave, admin kick.

`user_wrapped_keys(group_id, gk_version, device_id, wrapped_gk, key_commitment)` on server; commitment required and equal to `GroupMessage.key_commitment`.

Peer recovery: any member with history may transfer to new device (signatures verified).

Правила nullable DocumentV2, `has_content`, запрет executable и immutable revisions идентичны 1:1. Сервер сверяет `metadata.content_mode` с `groups.kind`: private-группа принимает только `private`, public-группа — только `public`.

Для private groups `protocol_version` и `metadata.format_version` остаются отдельными обязательными полями, оба сейчас `2` и повышаются lockstep.

## 8. Public content

No Kodium. TLS in transit, nullable plaintext `DocumentV2.nodes` + nullable open `markup` и обязательный `metadata` JSONB at rest (encrypted disk at infra level only). `metadata` содержит `format_version=2`, `revision_number`, `content_mode=public`; `encrypted_nodes`/`encrypted_metadata` отсутствуют. Optional `null`/`[]`/`{}` нормализуются в SQL `NULL`/API omit. Offset/length entities, placeholders и legacy body не принимаются новыми write API.

Public и private revisions immutable: edit создаёт новый `revision_id` с `parent_revision_id`; delete создаёт signed tombstone. Изменение открытого JSONB in-place запрещено.

## 9. Media

- В permanent storage и wire manifest допустимы ровно три variant: `thumbnail`, `preview`, `full`; `original` отсутствует.
- Private: sender валидирует MIME/magic bytes, size, dimensions, decode и запрет executable, создаёт три variant, затем шифрует. Recipient после decrypt повторяет те же проверки перед decode/open. Сервер видит только ciphertext и не заявляет AV/sanitize/transcode.
- Public: source загружается во временный quarantine; сервер выполняет AV scan, decode/sanitize и transcode в три variant, после чего source удаляется. При reject source также удаляется.
- Voice: Opus → client validation → SecretBox → MinIO ciphertext.
- Large files: chunked HKDF per chunk ([media-storage.md](../04-data/media-storage.md)).
- Escrow via `period_id` / GK — not per file.
- `script`, macro, executable, active HTML/SVG, event handlers, polyglot executable и `javascript:` URL запрещены. `code` node не исполняется.
- Init/complete/read требуют auth и domain authorization. Presigned PUT/GET URL scoped к одному key/method/headers и имеет TTL не более 15 минут.
- Media binding (id, variant, hash ciphertext, size, MIME declaration) входит в DocumentV2 signature/AAD.

## 10. Soft delete vs escrow

| `deleted` | Client | Escrow / legal |
|-----------|--------|----------------|
| `false` | Visible | Available |
| `true` | Hidden | **Still available** until physical purge |

## 11. Protocol versioning

| version | Changes |
|---------|---------|
| 1 | Envelope + ML-KEM escrow + signatures |
| 2 | DocumentV2 encrypted nodes, signed/AAD-bound open JSONB, immutable revisions, three media variants |
| 3 (future) | PQ ratchet default, peer recovery wire v2 |

Envelope `protocol_version` и Document `metadata.format_version` не синонимы, но compatibility policy требует их lockstep migration; current pair = `(2,2)`.

## 12. Production gates ([ADR-0005](../adr/0005-kodium-readiness-gate.md))

- [ ] Independent Kodium audit
- [ ] Signed PreKey verification in X3DH bundle — **до первого external ratchet tester**
- [ ] Cross-platform test vectors (Q-03)
- [ ] Escrow HSM integration test (incl. soft-deleted messages)
- [ ] Signed `/v1/escrow/config` current+next bundle verified against pinned root; no production bypass
- [ ] Ratchet required-by-default before GA

## 13. Known limitations

- Kodium SecretBox is in-memory (chunk for large video).
- No MLS; groups use Sender Keys (max ~1000 members recommended).
- Forward = full re-encrypt (no reference links).
- Peer recovery requires online consenting peer (unless phrase backup enabled).
- Key commitment is app-layer (Kodium AEAD is not committing, R-1); all decrypt paths MUST verify it.
- PQ-threshold для escrow пока частичный (classical-слой обеспечивает threshold, ML-KEM — в enclave), см. [ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md) §2.

## 14. References

- [ADR-0014](../adr/0014-participant-e2e-and-recovery.md)
- [ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md), [ADR-0017](../adr/0017-kodium-crypto-hardening.md)
- [kodium-security-audit.md](./kodium-security-audit.md)
- [key-lifecycle.md](./key-lifecycle.md)
- [escrow-legal-access.md](./escrow-legal-access.md)
- Исследовательский источник: `е2е личная переписка .md`; нормативное решение — [ADR-0014](../adr/0014-participant-e2e-and-recovery.md).
- Kodium: `Kodium git/kodium-main/docs/e2ee/double-ratchet.md`
