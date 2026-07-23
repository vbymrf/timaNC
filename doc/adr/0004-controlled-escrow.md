# ADR-0004: Controlled Escrow for Private Content

## Status

Accepted · 2026-07-12 · **Amended by** [ADR-0016](./0016-escrow-key-hierarchy-and-threshold.md) (2026-07-22): единый `Escrow_Private` заменён иерархией `Escrow_Public[epoch, shard]` с threshold-decapsulation, scope-инвариантом HSM и transparency log (mitigation R-0).

## Context

Product requires client-side encryption for private chats. Stakeholders selected **controlled escrow** for legal intercept via HSM and M-of-N, not strict Signal-E2E without third-party access.

## Decision

Every private message/group period includes ML-KEM `escrow_blob`. Escrow private key in HSM. Access requires M-of-N custodians + audit log. UI may show «Защищённый чат» but privacy policy must disclose escrow capability.

Fail-closed: private send rejected if escrow service unavailable.

> **Amendment (ADR-0016):** «single global `Escrow_Public` + full-key reconstruction in HSM» из этого решения **устарело**. Каноника — иерархия `Escrow_Public[epoch, shard]`, threshold-decapsulation (полный приватный ключ не собирается), scope-инвариант (HSM отдаёт message-ключи по scope, не приватный ключ) и per-epoch уничтожение. См. [ADR-0016](./0016-escrow-key-hierarchy-and-threshold.md).

## Consequences

**Positive:** Compliance path; async delivery via wrapped keys independent of ratchet.

**Negative:** Not marketing as «Signal-grade»; audit and transparency required; user trust considerations.

## References

- [ADR-0016](./0016-escrow-key-hierarchy-and-threshold.md) — key hierarchy + threshold + scope invariant
- [escrow-legal-access.md](../03-security/escrow-legal-access.md)
- [crypto-protocol.md](../03-security/crypto-protocol.md)
