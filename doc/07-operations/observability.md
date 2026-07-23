# Observability

## 1. Stack

| Layer | Tool |
|-------|------|
| Metrics | Prometheus + Grafana |
| Logs | Loki (JSON structured) |
| Traces | OpenTelemetry → Tempo |
| Errors | Sentry (client + server) |
| Uptime | External synthetic probes |

## 2. SLI metrics

| SLI | Metric | Target |
|-----|--------|--------|
| Message ack latency | `tima_message_ack_seconds` histogram | p99 < 800ms |
| WS delivery | `tima_ws_delivery_seconds` | p99 < 2s |
| API availability | `up{job="tima-api"}` | 99.9% |
| Event consumer lag | `tima_event_consumer_lag_seconds{bus="redis_streams|kafka"}` | < 10s |
| Outbox backlog | `tima_outbox_pending`, oldest row age | 0 sustained; age < 10s |
| Call setup | `tima_call_setup_seconds` | p95 < 4s |
| Escrow send failures | `tima_escrow_unavailable_total` | 0 sustained |
| Media pipeline success | successful terminal media jobs / accepted media jobs | Раздельно private/public; target задаётся SLO до release |
| Public variant completeness | assets with exactly 3 variants and no `Original` / completed public assets | 100% |
| Presigned URL policy | correctly auth-gated URLs with TTL 15m / issued URLs | 100% |
| Edit event delivery | `message.edited` publish-to-delivery latency | Target задаётся SLO до release |
| Revision consistency | duplicate or mutated revisions | 0 |
| Retention backlog age | age of oldest eligible non-held object | Target задаётся retention policy |
| Legal hold violations | held objects deleted | 0 |

SLI без утверждённого числового SLO не получает выдуманный target: baseline измеряется на staging, затем target фиксируется перед release gate.

## 3. Log fields (mandatory)

```json
{
  "timestamp": "ISO8601",
  "level": "info",
  "service": "tima-server",
  "request_id": "uuid",
  "user_id": "hash",
  "device_id": "uuid",
  "message": "..."
}
```

**Never log:** message plaintext, private `encrypted_metadata`, keys, wrapped keys, tokens, presigned URLs, legal-hold rationale.

## 3.1. Operational endpoints

Все runtime-процессы используют unversioned endpoints:

- `/healthz` — только process liveness, без блокирующих dependency probes;
- `/readyz` — готовность принимать трафик/partition; проверяет обязательные зависимости профиля;
- `/metrics` — Prometheus exposition, доступ только из scrape-сети.

Для beta readiness проверяет PostgreSQL и Redis Streams; для production — PostgreSQL и Kafka. `realtime-gw` дополнительно проверяет возможность auth/subscribe, `tima-worker` — consumer group и outbox relay. Эти пути никогда не публикуются под `/v1`.

## 4. Dashboards

| Dashboard | Audience |
|-----------|----------|
| TIMA Overview | On-call |
| Messaging pipeline | Backend |
| LiveKit media | Media team |
| Escrow audit | Security |
| Client crashes | Mobile |

## 5. Alerting (P1 examples)

| Alert | Condition |
|-------|-----------|
| APIDown | up == 0 for 2m |
| HighErrorRate | 5xx > 1% 5m |
| EventBusLagCritical | Redis Streams или Kafka consumer lag > 60s |
| OutboxBacklogCritical | oldest unpublished outbox row > 60s |
| EscrowBlocked | escrow errors > 10/min |
| LiveKitCPU | > 90% 15m |
| PublicMediaVariantInvalid | completed public asset has count != 3 or contains `Original` |
| PrivateMediaPipelineCrossed | private asset observed in public processor |
| RevisionMutationDetected | stored message/revision changed in place |
| LegalHoldDeletionAttempt | deletion attempted for held object |
| RetentionBacklogGrowing | oldest eligible-object age exceeds approved policy threshold |

## 6. Client telemetry

- Opt-in anonymous analytics (UI settings).
- Crash reports auto with redaction.
- Performance: cold start, sync duration.

## 7. Correlation

- `request_id` propagated Gateway → services → workers.
- Client sends `X-Request-Id` on support reports.
- Correlation для edit связывает `message_id`, `revision_id` и `message.edited` event id без логирования document body.
- Media correlation включает pipeline (`private|public`) и variant count, но не object URL.

## 8. Ссылки

- [nfr-slo.md](../01-product/nfr-slo.md)
- [runbooks.md](./runbooks.md)
