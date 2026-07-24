# NFR и SLO

## 1. Целевой масштаб (поэтапно)

| Фаза | MAU | Concurrent WS | Msg ingest/s (peak) | Регионы |
|------|-----|---------------|---------------------|---------|
| Messaging Alpha / beta VPS | repository/dev traffic; invited cohort до 100 deferred to Phase 5 | до ~10 тыс. capacity trigger | измеряется на staging | 1, non-production |
| Communication MVP / GA target | 100 тыс. | 15 тыс. | 500 | RU/EU regional cells; multi-AZ/DR внутри residency boundary |
| Growth | 1 млн | 150 тыс. | 5 000 | RU/EU cells + capacity cells по legal policy |
| Target | 10 млн | 1.5 млн | 50 000 | 2+ active cells (по SLO и residency) |

> Оценки требуют подтверждения [load-test-plan.md](../08-quality/load-test-plan.md). Telegram-подобные цифры хранения из legacy doc **не являются SLA**.

Invited-cohort evidence до 100 пользователей является operational gate Phase 5.
Phase 1 bounded smoke проверяет регрессию протокола, но не подтверждает cohort
capacity или vendor push SLO.

## 2. Service Level Objectives

| SLI | SLO (MVP) | SLO (10M) | Измерение |
|-----|-----------|-----------|-----------|
| Message send → ack (p99) | < 800 ms | < 500 ms | Client → durable store |
| Message delivery (online, p99) | < 2 s | < 1 s | Publish → WS push |
| REST API availability | 99.9% | 99.95% | Gateway success rate |
| WebSocket session stability | 99.5% reconnect < 30s | 99.9% | Client telemetry |
| Call setup (p95) | < 4 s | < 3 s | Invite → media |
| Media upload start (p95) | < 3 s | < 2 s | Presigned URL ready |
| Notification wake → sync (p95) | < 10 s | < 5 s | FCM/APNs/UnifiedPush; foreground/resume catch-up measured separately |

## 3. RPO / RTO

| Компонент | RPO | RTO |
|-----------|-----|-----|
| PostgreSQL (messages metadata) | 5 min | 30 min |
| Kafka | 0 (replicated) | 15 min |
| MinIO/S3 | 0 (versioned) | 1 h |
| Redis (ephemeral) | N/A | 5 min (rebuild) |
| LiveKit | best-effort state | 15 min new rooms |
| Escrow HSM keys | 0 | 4 h (manual M-of-N) |

Детали: [disaster-recovery.md](../07-operations/disaster-recovery.md).

Для single-VPS beta PostgreSQL сохраняет тот же RPO ≤5 минут через непрерывный WAL archive во внешнее хранилище, но допускает RTO ≤4 часов. Production/GA требует RTO ≤30 минут. RU и EU не используются как DR-копии друг друга; backup/failover остаётся внутри разрешённой residency boundary ([ADR-0018](../adr/0018-dual-region-ru-eu-production-architecture.md)).

## 4. NFR по клиенту

| Требование | Значение |
|------------|----------|
| Offline read | Полная история из SQLite |
| Offline send | Queue + retry с idempotency |
| Cold start (p95) | < 2.5 s Android, < 2 s iOS |
| RAM (baseline) | < 150 MB idle |
| Battery | Фоновый sync batch ≤ 1/min при idle |

## 5. NFR по безопасности

| Требование | Значение |
|------------|----------|
| TLS | 1.3 only, cert pinning |
| Attestation | Failed/forged — fail-closed. При vendor outage только ранее доверенное устройство получает configurable grace ≤30 дней; новая регистрация/linking запрещены |
| Key export | Password + optional biometric |
| Session TTL | Refresh 30d, revoke instant |
| Audit escrow | Append-only, 7 лет |

## 6. Деградационные режимы

1. **Kafka lag** — REST ack сохраняется; fan-out задерживается; UI «доставляется».
2. **LiveKit degraded** — звонки недоступны; сообщения работают.
3. **Search down** — public search fallback Postgres FTS; E2E local only.
4. **Escrow HSM down** — **блокировка send** в private chats (fail-closed для compliance).

## 7. Связанные документы

- [scaling-capacity.md](../02-architecture/scaling-capacity.md)
- [observability.md](../07-operations/observability.md)
