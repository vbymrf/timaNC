# Threat Model (STRIDE)

## 1. Scope

| In scope | Out of scope (v1) |
|----------|-------------------|
| Client apps (Android/iOS/Windows) | Web client |
| API Gateway, backend, realtime | Physical datacenter |
| LiveKit SFU | User device malware (mitigate, not eliminate) |
| Escrow HSM workflow | Nation-state on client |

## 2. Assets

| Asset | Classification |
|-------|----------------|
| Identity private keys | Critical |
| Message plaintext | Critical |
| Wrapped keys | High (encrypted to recipient) |
| Escrow blobs | High (legal access) |
| Public posts | Medium |
| LiveKit media streams | High (plaintext at SFU) |
| Audit logs | Critical |

## 3. STRIDE by component

### Client

| Threat | Mitigation |
|--------|------------|
| **S** Spoofing device | App Attest / Play Integrity; Windows QR trust |
| **T** Tampering app | Store signing, integrity checks, obfuscation (secondary) |
| **R** Repudiation | Ed25519 message signatures |
| **I** Information disclosure | Keystore, SQLCipher for local DB optional layer |
| **D** Denial of service | Local queue limits |
| **E** Elevation | Biometric app lock, session revoke |
| **T/E** Malicious private media | Sender validates before encrypt; recipient independently validates MIME/magic bytes, limits and decode after decrypt; executable/active content never launches |

### API / Backend

| Threat | Mitigation |
|--------|------------|
| **S** Token theft | Short-lived JWT, binding device_id |
| **T** API abuse | Rate limits, attestation, HMAC request signing (Phase 2) |
| **R** — | Audit logs for admin actions |
| **I** DB breach | Ciphertext-only messages; wrapped keys useless without device keys |
| **D** Flood | Gateway rate limit, WAF |
| **E** IDOR | Chat membership checks on every fetch |
| **T** Open JSONB changed | Canonical `markup`/`metadata` included in Ed25519 signature and AEAD AAD |
| **E** Presigned URL replay/scope escape | Auth + domain authorization before mint; TTL ≤15m; one key/method/size/content scope |

### Media Service

| Threat | Mitigation |
|--------|------------|
| Private ciphertext carries malware | Server cannot inspect without breaking participant E2E; mandatory sender and recipient client validation, safe decoder sandbox, no auto-open |
| Public upload carries malware/polyglot | Quarantine → AV → decode/sanitize → transcode before publish |
| Original leaks sensitive bytes | `original` is not a variant; quarantine source purged after success/reject |
| Active SVG/HTML, macro or executable | Block executable/active formats and handlers; `code` node is inert text |
| Unauthorized variant read/write | Domain ACL on init/complete/read plus scoped presigned URL ≤15m |

### Virtual users

| Threat | Mitigation |
|--------|------------|
| **S** Impersonate VP | Operator capability check + VP signing keys |
| **I** Revoked operator reads future msgs | Mandatory key rotation on revoke |
| **I** Owner identity leak | Owner/operators hidden in public profile; audit only |
| **E** Unauthorized act as VP | Server capability gate; `virtual_user_audit_log` |
| **I** Transfer buyer loses future access | Key rotation; honest UI warnings about old owner copies |
| **S** VP live call | Blocked MVP — voice de-anonymizes operator |

### Bot Platform

| Threat | Mitigation |
|--------|------------|
| **S** Token theft | Hash-only storage; revoke; short-lived optional rotation |
| **E** Scope escalation | deny-by-default capabilities; per-installation scopes |
| **E** DM bypass | `BOT_PRIVATE_MESSAGING_FORBIDDEN`; no `personal_messages` path |
| **S** Author spoofing | Server-derived author from installation; reject `sender_id` in body |
| **E** Cross-object publish | `INSTALLATION_TARGET_MISMATCH`; DB unique install per target |
| **T** Webhook spoofing | `X-TIMA-Webhook-Secret`; HTTPS only |
| **T** SSRF via webhook URL | URL validation; block private IPs |
| **I** Update leakage | Per-app update queue; no cross-tenant reads |
| **T** Callback tampering | Signed `callback_data` optional (Phase 2); server-side validation |
| **D** Bot spam | Rate limits; per-recipient social inbox cap; suspend |
| **E** Bot acting as VP | Separate tables; `BOT_VP_FORBIDDEN` |

### LiveKit

| Threat | Mitigation |
|--------|------------|
| **I** SFU sees media | Accepted for v1 (ADR-0006); TLS signaling |
| **S** Room hijack | JWT scoped room+identity, short TTL |
| **T** Signaling MITM | TLS 1.3 + pinning |

### Escrow ([ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md))

| Threat | Mitigation |
|--------|------------|
| **E** Unauthorized decrypt | M-of-N, dual control, warrant workflow |
| **I** Insider abuse | Audit, alerting on bulk access, **verifiable transparency log** (Merkle inclusion proofs) |
| **R** Deny access log | WORM audit store + public transparency log |
| **I/E** `Escrow_Private` leak → decrypt all (R-0) | Иерархия `Escrow_Public[epoch, shard]` (радиус = квартал×шард); **threshold-decap** (полный ключ не собирается); per-epoch **уничтожение** ключа; scope-инвариант (HSM отдаёт message-ключи по scope, не приватный ключ) |
| **T** Escrow ≠ recipient content (R-1) | `key_commitment` проверяется в enclave; `commitment_mismatch` в investigation package |
| **I** Harvest-now-decrypt-later | Per-epoch destruction закрывает окно; ML-KEM PQ-слой |

## 4. Attacker personas

| Persona | Capability | Goal |
|---------|------------|------|
| Network MITM | TLS break attempt | Read traffic |
| Malicious client | Modified APK | Scrape API, spam |
| Compromised server | DB access | Bulk ciphertext, metadata |
| Insider | Escrow API access | Targeted decrypt |
| Legal adversary | Valid warrant | Escrow path |

## 5. Residual risks

| Risk | Severity | Status |
|------|----------|--------|
| Kodium not audited | High | Gate ADR-0005 |
| X3DH signed prekey not verified | High | Must fix pre-prod |
| SFU sees call media | Medium | Accepted v1 |
| CAS hash leaks file existence | Low | Opt-in |
| Escrow perception as backdoor | Medium | Transparency policy + verifiable transparency log ([ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md)) |
| `Escrow_Private` leak раскрывает всё (R-0) | High → Medium | Key hierarchy epoch×shard + threshold-decap + per-epoch destruction ([ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md)) |
| Path divergence / escrow≠recipient (R-1) | High → Low | Key commitment on all paths ([ADR-0017](../adr/0017-kodium-crypto-hardening.md)) |
| Signature malleability misuse (R-2) | Medium | No sig-bytes as id; low-S check ([ADR-0017](../adr/0017-kodium-crypto-hardening.md)) |
| Side-channel on JS target (R-4) | Medium | Secret ops native/JVM only; Keystore ([ADR-0017](../adr/0017-kodium-crypto-hardening.md)) |
| Phrase theft + mass peer recovery | High | Consent + rate limits ([ADR-0014](../adr/0014-participant-e2e-and-recovery.md)) |
| Forged history in peer recovery | High | Signature verify on every message |
| Escrow access to soft-deleted | Medium | By design; audit + retention |
| Legal-hold data purged early | High | Hold blocks physical purge of revisions/media; hold/release/purge recorded in WORM audit |
| Private media lacks server AV | Medium | Inherent E2E trade-off; dual client validation + sandbox |

## 6. Recovery threats ([ADR-0014](../adr/0014-participant-e2e-and-recovery.md))

| Threat | Mitigation |
|--------|------------|
| Attacker with stolen phrase requests history | Peer consent required; owner notification |
| Mass recovery across all chats | Server rate limits + pattern detection |
| Tampered peer transfer | Ed25519 signature verify before display |
| Insider uses escrow outside warrant | M-of-N + WORM audit |
| User expects escrow self-recovery | UX: no user-facing escrow recovery path |

## 7. Trust assumptions

1. User devices not fully compromised at keygen time.
2. HSM and M-of-N procedures trustworthy.
3. Apple/Google attestation APIs available.
4. TLS PKI intact (pinning reduces risk).

## 8. Ссылки

- [crypto-protocol.md](./crypto-protocol.md)
- [escrow-legal-access.md](./escrow-legal-access.md)
- [kodium-security-audit.md](./kodium-security-audit.md) — R-0..R-4
- [ADR-0014](../adr/0014-participant-e2e-and-recovery.md)
- [client-hardening.md](./client-hardening.md)
- [ADR-0004](../adr/0004-controlled-escrow.md)
- [ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md)
- [ADR-0017](../adr/0017-kodium-crypto-hardening.md)
- [ADR-0009](../adr/0009-native-bot-app-platform.md)
- [bot-api.md](../05-api/bot-api.md)
