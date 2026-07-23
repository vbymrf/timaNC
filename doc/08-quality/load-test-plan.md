# Load Test Plan

## 1. Goals

Validate [nfr-slo.md](../01-product/nfr-slo.md) and [scaling-capacity.md](../02-architecture/scaling-capacity.md) assumptions.

| Phase | Target | Tool |
|-------|--------|------|
| MVP VPS | 10k concurrent WS, 100 msg/s | k6 on [mvp-server-setup.md](../07-operations/mvp-server-setup.md) stack |
| MVP | 100k MAU, 500 msg/s peak | k6 |
| Growth | 1M MAU, 5k msg/s | k6 + custom WS client |
| Target | 10M MAU, 50k msg/s | Locust distributed |

## 2. Scenarios

### S1: Message ingest

- 10k virtual users send 1 msg/10s
- Measure ack p50/p99, Kafka lag, Postgres write TPS

### S2: Fan-out hot chat

- 1 chat, 1000 members, 10 msg/s
- Verify ordering, no duplicate delivery

### S3: WS connections

- 50k concurrent connections, 1 event/min each
- Measure realtime-gw CPU, memory

### S4: Media upload

- 500 concurrent 5MB ciphertext uploads
- Presigned URL latency, MinIO throughput
- Separate traffic profiles for private ciphertext and public processing; no cross-pipeline fallback
- Verify every public result has exactly 3 variants and no `Original`
- Authenticated URL issuance and expiry churn with fixed TTL 15 minutes

### S5: Public feed fan-out

- 1 author, 100k followers, post every 5 min
- Kafka consumer lag, Redis feed build time
- Attribute slice: 10k users with overlapping `user_attributes` — p95 feed refresh

### S6: LiveKit

- 20 rooms × 20 participants video
- Packet loss, CPU per SFU node

### S7: Emotions + rating_counters

- 1k users × 10 emotions/s on hot post
- Worker lag `emotions` → `rating_counters`; no double-count on retry

### S8: Friends feed / public shelves

- 500 users add to public shelf simultaneously
- Fan-out to friends' `feed_friends:{user}`; private shelf events **absent** from fan-out

### S9: Attributes moderation queue

- 10k `declared` pairs/min; auto-approve vs manual queue depth
- Thematic query p95 with `approved` filter only

### S10: Inbox (`entity_message`)

- Channel broadcasts `POST /inbox/notify` to 50k subscribers
- WS `inbox.event` delivery p99; no `chats` row growth

### S11: Bot webhook + social inbox

- 100 apps × 30 rps `notifyUser`
- Webhook delivery lag; 429 under [bot-rate-limits.md](../05-api/bot-rate-limits.md)

### S12: Immutable edits

- Concurrent edits of hot messages create revisions without in-place mutation
- Measure `message.edited` publish/delivery lag and duplicate rate under retry

### S13: Retention and legal hold

- Expire batches containing messages, revisions, media and metadata
- Mixed cohort with active legal hold: zero held-object deletions; measure purge backlog/age

## 3. Success criteria

| Metric | Pass |
|--------|------|
| Message ack p99 | < SLO |
| Error rate | < 0.1% |
| Kafka / worker lag | < 10s sustained |
| Feed build p95 | < 2s per user batch (MVP VPS: < 5s) |
| No data loss | 0 missing acked messages |
| Media isolation | 0 private/public pipeline crossings; 0 public `Original` responses |
| Edit consistency | 0 mutated/duplicate revisions; 0 missing `message.edited` for acknowledged edits |
| Legal hold safety | 0 held-object deletions |

## 4. Environment

- Isolated staging cluster, production-like sizing
- Early beta: single VPS per [mvp-server-setup.md](../07-operations/mvp-server-setup.md) with scaled-down S7–S13
- Seed data generator for users/chats/feeds/attributes

## 5. Reporting

- Store results in `load-test-results/YYYY-MM/` (future repo)
- Compare run-over-run regression

## 6. Disclaimer

Telegram-style storage estimates in legacy crypto doc **must be validated** by S1–S13 before capacity commits.

## 7. Ссылки

- [mvp-server-setup.md](../07-operations/mvp-server-setup.md) — baseline VPS stack
- [scaling-capacity.md](../02-architecture/scaling-capacity.md)
- [feed-ranking.md](../04-data/feed-ranking.md)
- [bot-platform-test-plan.md](./bot-platform-test-plan.md) §10
