# Security Test Plan

## 1. Scope

- Cryptographic correctness
- Protocol fuzzing
- Penetration testing
- Dependency scanning

## 2. Crypto test vectors

Golden vectors in repo (future): `tests/crypto/vectors/v1/`

| Vector | Description |
|--------|-------------|
| V1-001 | 1:1 envelope encrypt/decrypt roundtrip |
| V1-002 | Wrapped key unwrap Path B |
| V1-003 | Escrow blob decapsulate (HSM sim) |
| V1-004 | Group GK rotation + decrypt |
| V1-005 | Chunked media 3 chunks |
| V1-006 | Signature verify fail on tamper |
| V1-007 | Cross-platform Android→iOS |
| V1-008 | Private `shelf_key` wrap/unwrap; revoke = rotation |
| V1-009 | Emotion idempotency: one per (user, message) |
| V1-010 | `entity_message` path: no `chats` row, no E2E envelope |
| V1-011 | Peer recovery: rewrapped history + signature verify |
| V1-012 | Recovery request declined → no chunks delivered |
| V1-013 | Escrow decapsulate soft-deleted message |
| V1-014 | Phrase backup roundtrip (opt-in) |
| V1-015 | Self-only content: no peer path, phrase required |
| V1-016 | `DocumentV2`: `nodes`/`markup` integrity; private `encrypted_metadata` opaque to server |
| V1-017 | Private media upload contains ciphertext only; pipeline confusion rejected |
| V1-018 | Public media has exactly 3 variants and never exposes `Original` |
| V1-019 | Auth required to issue presigned URL; valid at 15m boundary, rejected after expiry |
| V1-020 | Executable node/attachment rejected before upload and render |
| V1-021 | Edit preserves original, creates revision, emits idempotent `message.edited` |
| V1-022 | Retention expiry cannot purge message/revisions/media under active legal hold |
| V1-023 | Key commitment: ratchet/wrapped/escrow paths yield identical `message_key`; commitment verifies (R-1) |
| V1-024 | Key commitment negative: crafted blob where wrapped≠escrow key → `commitment_mismatch` rejected (R-1) |
| V1-025 | Signature: non-canonical `S` (malleated) rejected by low-S check; sig bytes not used as id (R-2) |
| V1-026 | Escrow threshold-decap: t-of-n partial DH recovers `message_key` without assembling private scalar (ADR-0016) |
| V1-027 | MAC policy: HMAC/HKDF/Poly1305 only; no bare `H(secret‖msg)` (R-3) |
| V1-028 | Escrow scope invariant: HSM returns only in-scope `message_key`s, never `Escrow_Private` (ADR-0016) |
| V1-029 | Escrow key destruction: after epoch purge, `escrow_blob[epoch,shard]` undecryptable (ADR-0016) |
| V1-030 | Transparency log: every decapsulation produces verifiable Merkle inclusion proof (ADR-0016) |
| V1-031 | Nullable DocumentV2: text-only/media-only/text+media normalize `null`, `[]`, `{}` to canonical omit/SQL `NULL`; `metadata` remains required |
| V1-032 | Presence tamper: bitmap, null sentinel or omitted/present field substitution fails signature and AEAD verification |
| V1-033 | Private refs: dangling or extra `secret_ref`, missing/empty `encrypted_metadata`, and secret-bearing `markup` mismatch are rejected |
| V1-034 | Group mode confusion: `metadata.content_mode` mismatch with `groups.kind` or mixed private/public fields is rejected |
| V1-035 | Every private key path (ratchet, device/GK/shelf/recovery/backup wrap, escrow) requires matching explicit `key_commitment`; missing field rejected |
| V1-036 | `presence_bitmap` is required independently in wire/API/DDL; omission or server recomputation instead of supplied canonical value rejected |
| V1-037 | Envelope `protocol_version` and Document `metadata.format_version` are distinct; `(2,2)` accepted, mixed/unsupported pairs rejected |
| V1-038 | Signed `/v1/escrow/config`: pinned root, signature, region, epoch, shard, key_id, validity and current+next public keys verified |
| V1-039 | Escrow config negatives: unsigned/expired/not-yet-valid/cross-region/unknown-key bundle and private-key field fail closed; beta stub passes same contract |
| V1-040 | Production private send has no escrow bypass via flag/operator/fallback; config/HSM failure rejects send |
| V1-041 | RU/EU cells use independent roots/HSM hierarchies; conversation home region selects key and cross-region blob/config is rejected |
| V1-042 | Recovery limits: init 3/day/account and 10/day/IP, proof 5/session, TTL 24h; configurable changes and overrides emit audit |
| V1-043 | Retention boundaries: transmission metadata 3y; content/ciphertext/media/revisions/escrow/wraps 6m; audit 7y |
| V1-044 | Generic legal hold selectors preserve complete object graph and escrow epoch keys; release resumes schedule; no purge bypass |
| V1-045 | Generic WORM audit detects edit/delete/reorder via hash-chain and verifies Merkle inclusion/consistency proofs |
| V1-046 | Signed PreKey failure blocks every external ratchet tester; ratchet is required-by-default before GA |

Generate reference implementation using Kodium CLI harness.

## 3. Property-based tests

- Ratchet forward secrecy: compromise at N does not decrypt N-1
- Idempotency: duplicate POST same key → single message_id
- Ordering: parallel sends same chat → monotonic ids
- **Emotions:** duplicate emotion on same message → rejected; 🧘 does not touch `rating_counters`
- **Attributes:** `declared` post invisible in thematic slice until `approved`
- **Inbox:** FSM transitions only by authorized assignee; `entity_message` cannot target 1:1 chat_id
- **DocumentV2:** tampering with text arrays, nullable `markup`, private `encrypted_metadata`, presence bitmap or null sentinel fails integrity checks applicable to the pipeline.
- **DocumentV2 nullability:** storage never retains empty arrays/objects/ciphertexts; fully empty document and empty layout fail `has_content`, while media-only omits the text field.
- **Media:** changing `visibility` cannot route private bytes into public processing or expose an original.
- **Revisions:** concurrent edits never mutate an existing revision; retries do not create duplicate revisions/events.
- **Retention/legal hold:** deletion order and retries cannot leave orphan revisions/media or bypass an active hold.
- **Key commitment (R-1):** for any message, no two delivery paths (ratchet/wrapped/escrow) can decrypt to distinct valid plaintexts; mismatch always rejected.
- **Signature (R-2):** malleated (non-canonical `S`) signature never accepted; message identity independent of signature bytes.
- **Escrow threshold (ADR-0016):** private key never materializes in full; fewer than `t` custodians cannot decapsulate.
- **Regional escrow:** no generated conversation can resolve an escrow key outside immutable `home_region`; RU/EU key hierarchies never overlap.
- **Retention graph:** for any deletion order, 6-month purge removes content graph atomically unless a matching generic hold exists; 3y metadata and 7y audit schedules remain independent.
- **Audit transparency:** arbitrary mutation of an append-only event sequence invalidates hash-chain or Merkle consistency proof.

## 4. Fuzzing

- Protobuf/JSON envelope parser (go-fuzz / kotlin-fuzz)
- WS frame parser
- `DocumentV2` node/markup parser, including unknown non-executable types and executable-type rejection
- Media manifest parser: variant count, `Original` injection and private/public pipeline confusion

## 5. SAST/DAST

| Tool | Target |
|------|--------|
| golangci-lint + gosec | Backend |
| detekt | KMP |
| Trivy | Container images |
| OWASP ZAP | Staging API |

## 6. Penetration test

- Before public beta: external firm
- Scope: API, WS, attestation bypass, IDOR, escrow workflow auth, **peer recovery abuse**
- **Social surface:** attribute spam / reputation gaming, inbox IDOR (`thread_id`), shelf grant escalation
- **Bot surface:** DM bypass, author spoof, webhook SSRF — см. [bot-platform-test-plan.md](./bot-platform-test-plan.md) §6–8

## 7. Social / feed / inbox security

| Threat | Test |
|--------|------|
| Private shelf leakage | Server API returns ciphertext only; no `shelf_key` in logs |
| `entity_message` → DM | All paths return 403; no row in `personal_messages` |
| Emotion flood | Rate limit per user; counter integrity under concurrent writes |
| Attribute mislabel appeal | Reporter cannot self-approve `post_attributes` |
| Inbox thread takeover | Assignee change requires role on target entity / VP operator |
| Bot acting as human | `author_id` / `sender_id` in body → 403 `BOT_AUTHOR_FORBIDDEN` |
| Executable content | Block before upload/render; no content-sniffing fallback to execution |
| Presigned URL theft | URL redacted from logs; auth required to mint; access denied after 15 minutes |
| Private/public confusion | Private ciphertext cannot enter public processor; public response has no original |
| Revision overwrite | Stored message/revision immutable; unauthorized edit and event spoof rejected |
| Premature purge | Legal hold overrides retention expiry for the complete object graph |

## 8. Kodium production gate ([ADR-0005](../adr/0005-kodium-readiness-gate.md))

- [ ] Independent crypto audit report (incl. constant-time on target platforms, R-4)
- [ ] Signed prekey verification implemented + tested
- [ ] Signed prekey gate blocks all external ratchet testers; ratchet required-by-default before GA — V1-046
- [ ] All V1-* vectors pass all platforms
- [ ] Key commitment enforced on all decrypt paths ([ADR-0017](../adr/0017-kodium-crypto-hardening.md), R-1) — V1-023/024
- [ ] Low-S (canonical signature) check on verify (R-2) — V1-025
- [ ] Escrow threshold-decap + scope invariant + per-epoch destruction ([ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md)) — V1-026/028/029
- [ ] Verifiable transparency log operational — V1-030
- [ ] Signed `/v1/escrow/config` pinned-root/current+next contract passes prod and beta stub; no bypass — V1-038/039/040
- [ ] RU/EU escrow isolation and conversation home-region routing pass — V1-041
- [ ] Retention/legal-hold/hash-chain+Merkle boundary tests pass — V1-043/044/045
- [ ] Recovery limits/TTL and audited configuration pass — V1-042

## 9. LiveKit security

- JWT scope tests (cannot join arbitrary room)
- TURN credential TTL
- No anonymous room create

## 10. Ссылки

- [threat-model.md](../03-security/threat-model.md)
- [crypto-protocol.md](../03-security/crypto-protocol.md)
- [kodium-security-audit.md](../03-security/kodium-security-audit.md)
- [ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md), [ADR-0017](../adr/0017-kodium-crypto-hardening.md)
- [bot-platform-test-plan.md](./bot-platform-test-plan.md)
- [feed-ranking.md](../04-data/feed-ranking.md)
