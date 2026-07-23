# ADR-0003: Transactional Outbox + staged EventBus

## Status

Accepted · 2026-07-12 · amended 2026-07-22 (beta transport profile)

## Context

Clients must receive fast ack while fan-out to many recipients must be async. Redis Pub/Sub is not durable.

## Decision

- Message write + `outbox_events` row in **same Postgres transaction**.
- Domain producer зависит от EventBus interface, а не от конкретного broker.
- Beta VPS: outbox relay публикует в Redis Streams consumer groups.
- Production/GA: outbox relay публикует в Kafka topic `message.ingest`; Kafka обязателен до production cutover.
- Fan-out workers deliver to WebSocket layer.
- Partition key = `chat_id` for ordering.
- Смена Redis Streams → Kafka не меняет event schema, idempotency keys, ordering key или durable ack: source of truth до публикации — PostgreSQL outbox.

## Consequences

**Positive:** At-least-once delivery with idempotent consumers; no message loss on worker crash; beta не несёт Kafka ops overhead, а production сохраняет масштабируемый broker.

**Negative:** Два transport profile требуют contract/transition tests; Kafka ops overhead появляется до production; delivery остаётся eventual.

## References

- [data-flows.md](../02-architecture/data-flows.md)
