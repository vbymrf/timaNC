# ADR-0002: PostgreSQL with App-Level Sharding

## Status

Accepted · 2026-07-12

## Context

`Тима.docx` mentions RocksDB for billions of messages. RocksDB as primary SoT adds ops complexity. PostgreSQL is already chosen for metadata.

## Decision

- **Primary SoT:** PostgreSQL 16 with hash partitioning on `chat_id`.
- **Scale path:** App-level shard router to N Postgres instances at ~10M MAU.
- **Not used:** RocksDB as message store, Redis as message SoT.

Public feed may use Redis lists as **cache** only (rebuild from Kafka).

## Consequences

**Positive:** ACID, familiar ops, transactional outbox natural fit.

**Negative:** Shard migration complexity; need load tests before 10M commit.

## References

- [storage-sharding.md](../04-data/storage-sharding.md)
