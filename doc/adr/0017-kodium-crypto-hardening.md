# ADR-0017: Kodium Crypto Hardening (Key Commitment, Canonical Signatures, MAC Policy, Side-Channel Targets)

## Status

Accepted · 2026-07-22

## Context

Аудит [kodium-security-audit.md](../03-security/kodium-security-audit.md) подтвердил 4 свойства TweetNaCl/Kodium 1.0.0, которые требуют прикладных мер:

- **R-1** — XSalsa20-Poly1305 (`SecretBox`/`Box`) не является key-committing: один шифртекст валидно расшифровывается под двумя разными ключами. Критично, т.к. один `message_key` доставляется по нескольким независимым путям (ratchet / wrapped / escrow) с silent fallback.
- **R-2** — Ed25519 `crypto_sign_open` не отвергает не-канонический `S` (malleability).
- **R-3** — SHA-512 (`crypto_hash`) уязвим к length-extension (безопасно только внутри Ed25519; риск при самодельном MAC).
- **R-4** — pure-Kotlin/JS не гарантирует constant-time.

## Decision

### 1. Key commitment для конвертного слоя (R-1)

Ввести коммит ключа сообщения:

```text
key_commitment = HKDF-SHA256(message_key, info="tima/commit/v1", L=32)
```

- `key_commitment` — explicit required field на **каждом private key path**: envelope/ratchet payload, participant device wrap, escrow blob, GK/user wrap, `shelf_key`/grantee wrap, recovery transfer и phrase backup. Отсутствие commitment делает path невалидным. Значение входит:
  1. в подписываемые заголовки envelope (Ed25519 signature input);
  2. в AEAD AAD **каждого присутствующего** `encrypted_nodes[i]` и `encrypted_metadata`;
  3. в `WrappedKeyRecord` и в `escrow_blob` (открытый заголовок).
- Nullable DocumentV2 канонизируется до подписи/шифрования: optional `null`, `[]` и `{}` (`encrypted_nodes`, `nodes`, `markup`, `encrypted_metadata`) нормализуются в отсутствие; в SQL это `NULL`, в API поле опущено. `metadata` обязателен и содержит `format_version=2`, `revision_number`, `content_mode`.
- `presence_bitmap` — отдельное explicit required поле wire, API request/response и DDL, а не вычисляемая JSON-проекция. Signature input содержит bitmap и для каждого nullable подписываемого поля его canonical value либо deterministic null sentinel. AEAD AAD содержит тот же bitmap, обязательный canonical `metadata` и canonical `markup` либо его deterministic null sentinel.
- Envelope `protocol_version` и Document `metadata.format_version` остаются двумя отдельными обязательными полями. Current pair = `(2,2)`; до нового ADR версии повышаются lockstep, смешанные пары отклоняются.
- Пустой `encrypted_metadata` не создаётся: ciphertext присутствует только при непустом наборе private sensitive values, на которые entities (включая `text_link`) ссылаются через `secret_ref`. Private ciphertext-поля optional.
- На **каждом** пути расшифровки (ratchet, wrapped, escrow/HSM) сторона восстанавливает `message_key`, пересчитывает `key_commitment` и сверяет с подписанным значением. Несовпадение → `commitment_mismatch`, payload отвергается (клиент) или помечается (HSM investigation package).
- **Инвариант:** все пути к одному сообщению обязаны давать байтово одинаковый `message_key`. То же для `GK` и `shelf_key` per `key_version`.
- Групповой/PQ-варианты используют ту же схему.
- Перед send/publish применяется единый `has_content`: непустой text array либо реальный media/content binding; пустой layout содержимым не является. Для групп `metadata.content_mode` обязан совпадать с `groups.kind`.

### 2. Канонические подписи (R-2)

- Байты подписи **никогда** не используются как идентификатор, ключ дедупликации или идемпотентности. Идентичность сообщения — только `(chat_id, message_id, revision_id)`.
- Приёмная сторона отвергает не-канонический `S` (проверка `S < L`, low-S) до принятия подписи — прикладная обёртка над `verifyDetached`.
- Peer-recovery и tombstone verify используют канонизированную проверку.

### 3. MAC / hash policy (R-3)

- Запрещены самодельные конструкции `H(secret ‖ message)` на голом SHA-256/512.
- Аутентификация и деривация — только HMAC-SHA256, HKDF-SHA256, Poly1305 (как сейчас).
- CAS-дедуп `SHA-256(plaintext)` допустим (нет секретного префикса), но помечен как metadata-leak trade-off; не является MAC.
- Правило вносится в code-review checklist и [security-test-plan.md](../08-quality/security-test-plan.md).

### 4. Side-channel targets (R-4)

- Операции с секретными ключами (identity, `deriveKeyFromPassword`, ratchet, ML-KEM decap) выполняются **только** на native/JVM-таргетах; браузерный JS для этих операций запрещён (Web client уже out of scope, [threat-model.md](../03-security/threat-model.md) §1).
- Долговременные ключи — в Keystore/Secure Enclave; время жизни секретов в heap минимизируется, `ByteArray` зануляются после использования ([client-hardening.md](../03-security/client-hardening.md) §4).
- Constant-time поведение на целевых платформах — обязательный пункт независимого аудита ([ADR-0005](./0005-kodium-readiness-gate.md)).

## Consequences

**Positive:** закрыт критичный R-1 (расхождение путей/escrow невозможно незаметно); malleability и length-extension нейтрализованы политикой; side-channel-поверхность ограничена таргетами.

**Negative:** +32 байта commitment на сообщение и в каждый wrap/escrow blob; дополнительная проверка на всех путях расшифровки; прикладная low-S обёртка поверх Kodium; ограничение JS-таргета для крипто.

## References

- [kodium-security-audit.md](../03-security/kodium-security-audit.md) R-1..R-4
- [crypto-protocol.md](../03-security/crypto-protocol.md) §3
- [ADR-0016](./0016-escrow-key-hierarchy-and-threshold.md) §5 (escrow commitment check)
- [ADR-0005](./0005-kodium-readiness-gate.md)
- [client-hardening.md](../03-security/client-hardening.md)
- [security-test-plan.md](../08-quality/security-test-plan.md)
