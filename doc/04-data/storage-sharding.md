# Storage и Sharding

## 1. Strategy ([ADR-0002](../adr/0002-storage-sharding.md))

| Phase | Approach |
|-------|----------|
| MVP | Single PostgreSQL 16, hash partitions on `chat_id` |
| Growth | Read replicas + PgBouncer |
| 10M | App-level shard router, 16–32 physical shards |

**Not used as SoT:** RocksDB, Redis Pub/Sub.

## 2. Shard routing

```go
func ShardID(chatID uuid.UUID, numShards int) int {
    h := fnv32a(chatID)
    return int(h % uint32(numShards))
}
```

- Router in `tima-server` / sidecar.
- Each shard: dedicated Postgres instance or database.
- Cross-shard queries forbidden for messaging hot path.

## 3. Partitioning (single DB phase)

```sql
-- 64 hash partitions on personal_messages
CREATE TABLE personal_messages_p0 PARTITION OF personal_messages
  FOR VALUES WITH (MODULUS 64, REMAINDER 0);
```

## 4. Public vs private storage

| Domain | Primary store | Index |
|--------|---------------|-------|
| Private messages | Postgres shards | Client FTS |
| Public posts | Postgres + Kafka fan-out | OpenSearch |
| Media ciphertext | MinIO | Postgres metadata |
| Media public | MinIO + CDN | OpenSearch |

## 5. Hot / warm / cold

| Tier | Age / data class | Storage | Access SLA |
|------|------------------|---------|------------|
| Hot | protected content 0–30d | SSD/NVMe | < 5 ms |
| Warm | protected content 30d–6 months | HDD fast | < 50 ms |
| Compliance archive | transmission metadata до 3y; escrow/legal-hold/WORM audit до 7y | Archive + ZSTD | < 500 ms |

Migration: nightly job by `created_at` and data class. Protected content, revisions, media, `escrow_blob` and participant wraps physically purge at 6 months unless legal hold is active; они не переходят в 3y/7y compliance archive.

## 6. MinIO lifecycle

- Rule: `escrow_blob` purge вместе с protected content по достижении 6 месяцев; GLACIER-equivalent разрешён только для audit/proofs с отдельным schedule.
- Public media: CDN cache 24h.

## 7. Rebalancing

- New shard: dual-write period → backfill → cutover.
- Use consistent hashing with virtual nodes when > 32 shards.

## 8. Ссылки

- [scaling-capacity.md](../02-architecture/scaling-capacity.md)
- [retention-archival.md](./retention-archival.md)
