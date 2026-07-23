# Disaster Recovery

## 1. Tier classification

| Tier | RPO | RTO | Components |
|------|-----|-----|------------|
| T0 Critical (production) | 0–5 min | ≤30 min | Postgres primary, Kafka |
| T1 High | 15 min | 1 h | MinIO, Redis |
| T2 Medium | 1 h | 4 h | OpenSearch |
| T3 Ephemeral | N/A | 15 min | LiveKit rooms |

## 2. Postgres

- **Beta:** single Postgres on VPS, continuous WAL archive to external object storage outside the VPS/failure provider; target RPO ≤5 min, RTO ≤4 h.
- **Production:** primary + synchronous replica across AZ внутри одной regional cell; async DR replica/site только внутри той же разрешённой residency boundary; target RTO ≤30 min. RU и EU не являются DR-копиями друг друга.
- PITR WAL archiving to object storage is mandatory in both profiles.
- **Failover:** manual promotion DR replica (Phase 1); automated Patroni (Phase 2).

## 3. Event bus

- Beta: Redis Streams consumer groups; durable domain state and unpublished events recover from PostgreSQL transactional outbox, then replay idempotently.
- Production/GA: Kafka is mandatory.
- 3 brokers, replication factor 3, min.insync.replicas=2.
- MirrorMaker 2 to DR cluster внутри той же residency boundary (Growth phase); RU↔EU replication domain events запрещена.

## 4. MinIO

- Erasure coding EC:4+2 minimum.
- Cross-AZ/DR-site bucket replication только внутри той же residency boundary (Growth).
- Restore validation keeps private ciphertext separate from public processed media.
- Public restore accepts exactly three variants and must not recreate or expose `Original`.

## 5. Redis

- Sentinel 3-node.
- Accept data loss on failover — rebuild presence from client reconnect.

## 6. Escrow HSM

- Beta may use an isolated escrow stub.
- HSM is mandatory before production (Phase 4); production `escrow_strict` cannot be disabled or bypassed during DR.
- Vendor-defined backup; M-of-N recovery ceremony.
- **Never** auto-failover without human approval.

## 7. DR drill

- Quarterly: restore Postgres snapshot to staging.
- Quarterly beta drill must restore a clean VPS from base backup + external WAL and prove RPO ≤5 min / RTO ≤4 h.
- Production drill must prove service recovery in ≤30 min.
- Verify outbox replay into Redis Streams (beta) or Kafka (production) is idempotent.
- Semi-annual: full region failover simulation.
- Verify immutable message/revision links and replay of `message.edited` without duplicate revisions.
- Restore message, all revisions, media variants and metadata as one retention scope.
- Reapply legal holds before any retention purge; a restored held object must not become purge-eligible.

## 8. Multi-region

See [ADR-0018](../adr/0018-dual-region-ru-eu-production-architecture.md) и amended [ADR-0008](../adr/0008-multi-region-strategy.md). Cross-region RU/EU delivery использует ciphertext-only relay и не является backup/failover.

## 9. Ссылки

- [deployment-topology.md](../02-architecture/deployment-topology.md)
- [incident-response.md](./incident-response.md)
