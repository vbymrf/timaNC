# ADR-0001: Evolutionary Service-Oriented Architecture

## Status

Accepted · 2026-07-12

## Context

TIMA must scale from 100k to 10M MAU. Full microservices on day one adds operational cost without proven bottlenecks. Team size assumed 2–3 engineers initially.

## Decision

Start with a **Go modular monolith** (`tima-server`) containing clear module boundaries (auth, messages, keys, groups, media metadata, calls). Deploy separately from day one:

- `realtime-gw` (WebSocket)
- `message-worker` (Kafka consumers)
- LiveKit cluster
- Escrow service (isolated)

Extract services when metrics justify (Kafka lag, CPU, release cadence).

## Consequences

**Positive:** Faster MVP, simpler debugging, clear migration path.

**Negative:** Monolith scaling limits until split; requires discipline on module boundaries (Konsist/arch-unit style checks).

## References

- [backend-services.md](../02-architecture/backend-services.md)
