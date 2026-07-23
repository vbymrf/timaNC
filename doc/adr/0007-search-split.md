# ADR-0007: Split Search — Client FTS vs OpenSearch

## Status

Accepted · 2026-07-12

## Context

UI requires global search including private messages. Server cannot index E2E plaintext.

## Decision

- **Private E2E content:** SQLite FTS5 on client after decrypt.
- **Public content:** OpenSearch indexed from Kafka.
- **Global search UI:** merges local + server results with clear source labeling.

Server-side full-text on private messages is **forbidden**.

## Consequences

**Positive:** Cryptographically consistent; no plaintext leakage to search cluster.

**Negative:** Private search only on devices that synced history; new device partial index until backfill.

## References

- [search-indexing.md](../04-data/search-indexing.md)
- [doc_UI/17-global-search.md](../doc_UI/17-global-search.md)
