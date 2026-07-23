# Runbooks

## Messaging: event bus / outbox lag

1. Check `tima_outbox_pending` and `tima_event_consumer_lag_seconds`, first identifying profile: Redis Streams (beta) or Kafka (production/GA).
2. Scale/restart `tima-worker`; do not delete outbox rows to reduce the graph.
3. Beta: inspect Redis Stream consumer-group pending entries, claim only idle deliveries and process idempotently.
4. Production: inspect Kafka broker/consumer health; expand disk if >80%.
5. Quarantine poison events in the profile-specific DLQ and preserve event/outbox IDs for replay.

## Postgres: replication lag

1. Check replica IO.
2. Reduce heavy analytics queries.
3. Failover only if primary down — follow DR doc.

## Escrow unavailable (private send blocked)

1. Check Escrow Service health + HSM connectivity.
2. Production: `escrow_strict` cannot be disabled, bypassed or overridden even with Security approval; stop private send until HSM recovery.
3. Beta: verify the isolated escrow stub only; never point production traffic to it.
4. Communicate ETA on status page.
5. Public messaging unaffected — verify separately.

## LiveKit: calls failing

1. Check Redis connectivity from LiveKit pods.
2. Verify UDP 50000-60000 open on nodes.
3. Test TURN with `lk` CLI.
4. Scale SFU nodes if CPU > 80%.
5. Enable TCP fallback if UDP blocked (client config).

## Realtime GW: connection storm

1. Scale realtime-gw horizontally.
2. Enable connection rate limit at gateway.
3. Check auth service latency.
4. Verify routing: beta `wss://api.beta.../v1/ws`, production `wss://realtime.../v1/ws`; never route `/v1/ws` to `tima-server`.

## Media: private/public pipeline violation

1. Stop acceptance into the affected media pipeline; do not reroute private assets through public processing.
2. Identify affected asset IDs from redacted correlation fields; do not inspect or log private plaintext, `encrypted_metadata` or presigned URLs.
3. Verify public assets have exactly 3 variants and no `Original`; quarantine non-conforming results.
4. Revoke active access capabilities where supported and treat leaked private bytes or originals as a security incident.
5. Resume only after auth issuance and fixed presigned TTL 15 minutes pass smoke tests.

## Messaging: revision inconsistency

1. Disable edits while preserving message reads and new-message sends.
2. Check that persisted messages/revisions were not updated in place.
3. Compare acknowledged edits with `message.edited` events by message/revision/event IDs.
4. Replay only idempotently; never overwrite the original or fabricate a missing revision.
5. Escalate any unauthorized mutation as a security incident.

## Retention: purge backlog or legal-hold conflict

1. Pause the affected purge cohort if any legal-hold violation or incomplete object graph is detected.
2. Verify scope covers message, all revisions, media variants and associated metadata.
3. Legal hold always wins over retention expiry and deletion requests.
4. Record counts and opaque IDs only; do not expose hold rationale to unauthorized responders.
5. Resume after a dry run reports zero held-object deletions and no orphaned related objects.

## Attestation vendor outage

1. Confirm vendor/network unavailability separately from invalid proof. Canonical routes are `POST /v1/verify/attestation/ios` and `POST /v1/verify/integrity/android`.
2. Enable the configured grace only for devices trusted before the outage; duration is configurable but must be ≤30 days.
3. Grace may preserve private send. It must block new registration and device linking.
4. Failed or forged proof is never treated as outage and never receives grace.
5. Record device ID, prior trust decision, reason and grace expiry without logging proof/token material.
6. Disable new grace grants after vendor recovery; reverify devices before expiry.

## Deployment rollback

1. Roll back `tima-server`, `realtime-gw` and `tima-worker` to one compatible release set.
2. Verify migration compatibility (backward only)
3. Monitor error rate 15m

## Feature flags emergency

| Flag | Effect |
|------|--------|
| `calls.enabled=false` | Disable new calls |
| `private_send.enabled=false` | Extreme — maintenance mode private |
| `public_feed.enabled=false` | Public read-only |
| `message_edit.enabled=false` | Disable new edits; immutable history remains readable |
| `media_public_processing.enabled=false` | Stop public processing; never reroute to private pipeline |
| `retention_purge.enabled=false` | Pause purge during hold/scope investigation |

## Contacts

- On-call: PagerDuty rotation `tima-primary`
- Security: security@tima.example
- Escrow custodians: internal vault doc

## Ссылки

- [observability.md](./observability.md)
- [livekit-operations.md](../06-realtime/livekit-operations.md)
