# Масштабирование и ёмкость

## 0. Ступень 0 — один VPS (MVP, бета)

Один VPS с docker-compose + Caddy ([mvp-server-setup.md](../07-operations/mvp-server-setup.md)), но отдельными процессами `tima-server`, `realtime-gw`, `tima-worker`. Держит порядка **10k активных пользователей** / **10k одновременных WS**.

| Компонент | Sizing (VPS) | Примечание |
|-----------|--------------|------------|
| `tima-server` + `realtime-gw` + `tima-worker` | 4 vCPU, 8 ГБ RAM | раздельные контейнеры; worker: outbox, fan-out, inbox push, `rating_counters` |
| PostgreSQL | на том же VPS | один инстанс, без реплики |
| Redis | на том же VPS | Redis Streams event bus + `feed:{user}`, presence, rate limits |
| MinIO | на том же VPS | presigned media |
| LiveKit | host network | TURN + UDP 50000–60000 |

**Триггер ступени 1:** CPU > 70 % устойчиво, диск БД конкурирует с медиа, или > 10k concurrent WS → разделение узлов (§2 compute, [deployment-topology.md](./deployment-topology.md) §1).

## 1. Допущения нагрузки (10M MAU)

| Metric | Value | Note |
|--------|-------|------|
| DAU/MAU | 35% | 3.5M DAU |
| Messages/user/day | 30 | blended public+private |
| Peak factor | 3× | evening peak |
| Avg message size (encrypted) | 400 B | incl. overhead |
| Media uploads/DAU | 0.2 | |
| Concurrent calls | 0.5% DAU | ~17.5k |

**Peak ingest:** 3.5M × 30 / 86400 × 3 ≈ **3.6k msg/s** (plan headroom **50k msg/s** at 10M with growth).

> Требует валидации [load-test-plan.md](../08-quality/load-test-plan.md).

## 2. Compute sizing (indicative)

### MVP 100k MAU

| Component | Instances | vCPU | RAM |
|-----------|-----------|------|-----|
| tima-server | 2 | 4 | 8 GB |
| realtime-gw | 2 | 4 | 8 GB |
| tima-worker | 2 | 2 | 4 GB |
| LiveKit | 2 | 8 | 16 GB |
| PostgreSQL | 1 primary + 1 replica | 8 | 32 GB |

### 10M MAU (target)

| Component | Scale lever |
|-----------|-------------|
| tima-server | 20–40 pods, stateless |
| realtime-gw | 50–100 pods, sticky sessions optional |
| tima-worker | Kafka consumer groups, ~30 pods |
| PostgreSQL | 16–32 shards, 64 vCPU each |
| LiveKit | 20–50 nodes regional |
| MinIO | Erasure 12+ drives, lifecycle policies |

## 3. Storage growth

### Private E2E messages

- Overhead: ~400 B/msg ciphertext + ~1 KB escrow blob (amortized per GK period in groups).
- **No text dedup** (unique nonce → unique ciphertext).
- Client holds primary history; server retention per [retention-archival.md](../04-data/retention-archival.md).

### Public content

- Plaintext + OpenSearch index.
- Forward references allowed (unlike E2E).
- Media CAS for public optional.

### Illustrative (NOT SLA)

Active public group 1000 users, 10k msg/day, 20 B binary ≈ 200 KB/day — **only for public plaintext path**. E2E groups scale with ciphertext size; load tests must measure real protobuf+zstd ratios.

## 4. Event bus

Beta использует Redis Streams + PostgreSQL transactional outbox. Production/GA использует Kafka; таблица ниже задаёт production topics/partitions. Outbox остаётся источником публикации при смене транспорта.

### Kafka topics & partitions (production/GA)

| Topic | Partition key | Partitions (10M) |
|-------|---------------|-------------------|
| `message.ingest` | chat_id | 256 |
| `message.fanout` | user_id | 512 |
| `public.post.fanout` | author_id | 128 |
| `audit.escrow` | request_id | 32 |
| `notification.dispatch` | user_id | 256 |

## 5. Redis usage (ephemeral only)

| Key pattern | TTL | Max memory strategy |
|-------------|-----|---------------------|
| `presence:{user}` | 120s | allkeys-lru |
| `ratelimit:{ip}` | 1m | volatile |
| `feed:{user}` (public) | 7d | LRU trim 10k items |

**Not stored:** message bodies, wrapped keys long-term (Postgres SoT).

## 6. Bottleneck watchlist

1. Postgres write rate on hot chats → partition + async fan-out.
2. WebSocket connection count → horizontal realtime-gw.
3. LiveKit bandwidth → regional SFU + simulcast.
4. Escrow blob storage → blob compression, period-based escrow in groups.
5. Client FTS rebuild → background incremental index.

## 7. Autoscaling signals

| Service | Scale out when |
|---------|----------------|
| realtime-gw | connections > 40k/pod |
| tima-worker | consumer lag > 10s p99 |
| tima-server | CPU > 70% 5m |
| LiveKit | `livekit_room_participants` > threshold |

## 8. Cost controls

- Lifecycle MinIO: protected content/media/`escrow_blob` hot→warm within the 6-month retention, then physical purge unless legal hold is active. Только escrow/legal-hold/WORM audit proofs имеют 7-летний schedule.
- ClickHouse sampling for client analytics (opt-in).
- OpenSearch ILM for public indices.

## 9. Ссылки

- [mvp-server-setup.md](../07-operations/mvp-server-setup.md) — ступень 0, docker-compose
- [deployment-topology.md](./deployment-topology.md) — MVP path §0, target multi-AZ §1
- [nfr-slo.md](../01-product/nfr-slo.md)
- [storage-sharding.md](../04-data/storage-sharding.md)
- [ADR-0002](../adr/0002-storage-sharding.md)
