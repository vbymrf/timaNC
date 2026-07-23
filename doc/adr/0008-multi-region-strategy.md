# ADR-0008: Multi-Region Strategy

## Status

Accepted · 2026-07-12 · **Amended by** [ADR-0018](./0018-dual-region-ru-eu-production-architecture.md) (2026-07-22)

## Context

Target 10M MAU may require geographic distribution. Multi-region active-active is expensive and complicates escrow key jurisdiction.

## Decision

**Phase 1 (MVP–Growth):** Single primary region, multi-AZ, async DR region (standby Postgres replica, cold standby K8s).

**Phase 2 (10M):** Active-passive second region; users pinned to home region; cross-region chat via async replication (higher latency acceptable for history fetch).

**Not in scope v1:** Active-active multi-master Postgres; geo-routed LiveKit mesh (evaluate separately).

Trigger for Phase 2: sustained p99 cross-border latency complaints OR primary region > 70% capacity.

> **Amendment (ADR-0018):** откладывание второго региона целиком до Phase 2 / 10M **устарело**. Beta остаётся single-region VPS, но до GA обязательны две изолированные RU/EU regional cells, account/conversation home region и ciphertext-only cross-region relay без plaintext/ключей. Указанные выше cross-region async replication и DR-реплика за пределами разрешённой residency boundary больше не допустимы. Это compliance/readiness gate, не active-active масштабирование. Позднее active-passive/active-active масштабирование, multi-master и дополнительные capacity cells по-прежнему относятся к Phase 6 и запускаются по capacity/latency triggers. Канонические residency, routing, escrow и fail-closed правила определены в [ADR-0018](./0018-dual-region-ru-eu-production-architecture.md).

## Consequences

**Positive:** Simpler escrow jurisdiction; lower cost early.

**Negative:** DR failover is manual/event-driven initially.

## References

- [disaster-recovery.md](../07-operations/disaster-recovery.md)
- [deployment-topology.md](../02-architecture/deployment-topology.md)
- [ADR-0018](./0018-dual-region-ru-eu-production-architecture.md) — RU/EU production cells и cross-region residency
