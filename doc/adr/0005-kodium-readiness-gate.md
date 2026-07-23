# ADR-0005: Kodium Production Readiness Gate

## Status

Accepted · 2026-07-12

## Context

Kodium README claims production readiness but includes **not audited** disclaimer. X3DH in Kodium lacks signed prekey verification in bundle parsing.

## Decision

**Block production launch** until:

1. Independent third-party crypto audit of Kodium (or TIMA fork) completed with critical issues resolved (incl. constant-time on target platforms, R-4).
2. Signed PreKey verification implemented in X3DH handshake (app or upstream Kodium).
3. Cross-platform crypto test vectors V1-001..V1-007 pass ([security-test-plan.md](../08-quality/security-test-plan.md)).
4. Crypto hardening from [ADR-0017](./0017-kodium-crypto-hardening.md): key commitment enforced on all paths (R-1), low-S signature check (R-2), MAC policy (R-3) — vectors V1-023..V1-025, V1-027.
5. Escrow hardening from [ADR-0016](./0016-escrow-key-hierarchy-and-threshold.md): threshold-decap, scope invariant, per-epoch destruction, transparency log — vectors V1-026, V1-028..V1-030.

Beta may proceed with documented risk acceptance by Security lead.

## Consequences

**Positive:** Reduces catastrophic crypto failure risk.

**Negative:** Timeline dependency on audit vendor.

## References

- Kodium: `Kodium git/kodium-main/README.md`
- [kodium-security-audit.md](../03-security/kodium-security-audit.md)
- [ADR-0016](./0016-escrow-key-hierarchy-and-threshold.md), [ADR-0017](./0017-kodium-crypto-hardening.md)
