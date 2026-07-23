# Architecture Decision Records

| ADR | Title |
|-----|-------|
| [0001](./0001-evolutionary-services.md) | Evolutionary service-oriented architecture |
| [0002](./0002-storage-sharding.md) | PostgreSQL with app-level sharding |
| [0003](./0003-kafka-outbox.md) | Transactional outbox + staged EventBus (Redis beta → Kafka production) |
| [0004](./0004-controlled-escrow.md) | Controlled escrow for private content |
| [0005](./0005-kodium-readiness-gate.md) | Kodium production readiness gate |
| [0006](./0006-livekit-media-policy.md) | LiveKit media policy (no app E2EE v1) |
| [0007](./0007-search-split.md) | Split search: client FTS vs OpenSearch |
| [0008](./0008-multi-region-strategy.md) | Multi-region strategy |
| [0009](./0009-native-bot-app-platform.md) | Native Bot/App Platform (no Telegram compat) |
| [0010](./0010-mvp-storage-profile.md) | MVP storage: PostgreSQL + Redis + MinIO |
| [0011](./0011-mvp-caddy-edge.md) | Caddy as MVP edge |
| [0012](./0012-schema-first-api.md) | Schema-first API (Client / Bot) |
| [0013](./0013-double-ratchet-phase.md) | Double Ratchet — phase 5 (path A) |
| [0014](./0014-participant-e2e-and-recovery.md) | Participant E2E + mandatory escrow + recovery |
| [0015](./0015-document-v2-and-media-pipeline.md) | DocumentV2 + split private/public media pipeline |
| [0016](./0016-escrow-key-hierarchy-and-threshold.md) | Escrow key hierarchy (epoch×shard) + threshold decapsulation + scope invariant |
| [0017](./0017-kodium-crypto-hardening.md) | Kodium crypto hardening (key commitment, canonical signatures, MAC policy, side-channel) |
| [0018](./0018-dual-region-ru-eu-production-architecture.md) | Dual-region RU/EU production architecture |

Template for new ADRs:

```markdown
# ADR-NNNN: Title
## Status
Proposed | Accepted | Deprecated
## Context
## Decision
## Consequences
## References
```
