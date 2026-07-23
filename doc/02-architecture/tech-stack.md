# Технологический стек

> **Актуализировано:** 2026-07-13. Конкретные версии проверяются на момент старта каждой фазы — не копировать из исследовательских docx (там версии устарели и внутренне несогласованы).

## Клиент

| Компонент | Выбор | Примечание |
|-----------|-------|-----------|
| Язык / шаринг кода | Kotlin Multiplatform | [client-architecture.md](./client-architecture.md) |
| UI | Compose Multiplatform | Android, iOS, Desktop (Windows) |
| Локальная БД | SQLDelight | + SQLite FTS5 для локального поиска |
| HTTP/WS | Ktor Client | |
| DI | Koin | |
| Сериализация | kotlinx.serialization + Protobuf | Конверты — Protobuf ([crypto-protocol.md](../03-security/crypto-protocol.md)) |
| Криптография | **Kodium** (`eu.livotov.labs:kodium`) | Единственная криптобиблиотека, [ADR-0005](../adr/0005-kodium-readiness-gate.md) |
| Сжатие | zstd (до шифрования) | expect/actual биндинги |
| Архитектурный контроль | Konsist | Правила в CI |
| Минимальные ОС | Android API 26+ · iOS 15+ · Windows 10+ | Binding baseline для release scope |
| Windows-звонки | Официальный LiveKit C++ SDK | Узкий JNI/JNA-адаптер к KMP; обязательны в Phase 2 |
| Windows packaging | MSIX | Основной формат поставки |
| Web-клиент | Не входит в release scope | Возможность после GA требует отдельного решения; UI/привязка Web сейчас не обещаны |

## Сервер

| Компонент | Выбор | Примечание |
|-----------|-------|-----------|
| Язык | Go (актуальный stable) | [ADR-0001](../adr/0001-evolutionary-services.md) |
| HTTP router | chi или echo | Выбрать при старте фазы 0 |
| WebSocket | nhooyr/websocket (coder/websocket) | |
| PostgreSQL driver | pgx v5 | + golang-migrate для миграций |
| Redis | go-redis v9 | Beta event bus (Streams), Pub/Sub, presence |
| Kafka | franz-go / sarama | Production/GA fan-out после beta EventBus profile ([ADR-0003](../adr/0003-kafka-outbox.md)) |
| S3 | minio-go v7 | |
| LiveKit | livekit/server-sdk-go | Токены комнат, webhooks |
| Push | FCM HTTP v1 + APNs (token-based) | |
| Метрики | prometheus/client_golang | |

## Инфраструктура

| Компонент | Выбор | Версия |
|-----------|-------|--------|
| БД | PostgreSQL | 16+ ([ADR-0010](../adr/0010-mvp-storage-profile.md)) |
| Кэш/очереди | Redis | 7+ |
| Event bus | Redis Streams (beta) → Kafka (production/GA) | Transactional outbox и единый EventBus contract |
| Объектное хранилище | MinIO | актуальный stable |
| Медиасервер | LiveKit (self-hosted) + встроенный TURN | актуальный stable |
| Edge | Caddy | 2.x, [ADR-0011](../adr/0011-mvp-caddy-edge.md) |
| Контейнеризация | Docker + docker-compose (beta) → K8s regional cells (production) | [deployment-topology.md](./deployment-topology.md) |
| Наблюдаемость | Prometheus + Grafana (+ Loki) | |
| Поиск | PostgreSQL FTS (Phase 3) | OpenSearch подключается только по измеримым триггерам |
| Escrow | HSM (план Phase 4) | Обязательный gate до любого production; в alpha/beta допустим только non-production контур |
| Региональность | Beta single-region → production RU/EU | Готовность двух production-регионов обязательна до GA |

## Безопасность клиента

| Платформа | Механизм |
|-----------|----------|
| iOS | App Attest (DeviceCheck) |
| Android | Play Integrity API |
| Windows | QR-привязка через доверенный телефон ([client-attestation.md](../03-security/client-attestation.md)) |
| Транспорт | TLS 1.3 + certificate pinning (SPKI) |

## Отложено (подключается по триггерам, [scaling-capacity.md](./scaling-capacity.md))

OpenSearch (после Phase 3 при срабатывании триггеров публичного поиска) · Envoy/Kong (микросервисный gateway) · Kubernetes (полный mesh) · TimescaleDB/шардинг PG. Архитектурная цель до GA — готовность production в RU/EU; beta остаётся single-region ([ADR-0008](../adr/0008-multi-region-strategy.md)).

## Ссылки

- [module-boundaries.md](./module-boundaries.md)
- [system-architecture.md](./system-architecture.md)
- [dependency-policy.md](../09-delivery/dependency-policy.md)
