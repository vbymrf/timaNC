# ADR-0006: LiveKit Media Policy (No App E2EE v1)

## Status

Accepted · 2026-07-12

## Context

UI-ТЗ states «E2E звонков: нет». LiveKit supports optional E2EE and Egress (server-side plaintext). Kodium does not encrypt RTP.

## Decision

- **v1 calls:** LiveKit SFU + SRTP; **no** application-level E2EE; **no** LiveKit Egress for private calls.
- **Async voice:** Kodium encrypt on client → MinIO only.
- **Recording:** Off by default; future only for public/compliance rooms with consent ([recording-policy.md](../06-realtime/recording-policy.md)).
- LiveKit E2EE may be re-evaluated in separate ADR after v1.

## Consequences

**Positive:** Aligns product, UI, and ops; simpler SFU debugging.

**Negative:** SFU can forward media; users must understand call privacy model.

## References

- [doc_UI/21-call.md](../doc_UI/21-call.md)
- [livekit-integration.md](../06-realtime/livekit-integration.md)
