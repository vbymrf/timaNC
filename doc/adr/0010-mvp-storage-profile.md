# ADR-0010: MVP storage profile (PostgreSQL + Redis + MinIO)

**Статус:** принят · **Дата:** 2026-07-13 · **Amended:** 2026-07-22 (beta EventBus profile)

## Контекст

В исследованиях рассматривались RocksDB и самописное Telegram-подобное хранилище «на миллиарды сообщений». Это оптимизации под масштаб, которого на старте нет.

Параллельно [ADR-0003](./0003-kafka-outbox.md) фиксирует transactional outbox и staged EventBus: Redis Streams на beta VPS, Kafka обязательно до production/GA. Этот ADR описывает профиль персистентного хранения и не меняет event contract.

## Решение

- **PostgreSQL 16** — единственная реляционная БД: пользователи, чаты, сообщения (ciphertext), ключи (wrapped/escrow blobs), группы, ленты, коллекции, реакции, inbox, боты.
- **Redis 7** — кэш, online-статусы, Pub/Sub для WS-нотификаций, материализованные ленты (`feed:{user_id}`), rate limiting и beta EventBus (Streams); **не** source of truth сообщений или unpublished events (ими владеет PostgreSQL outbox).
- **MinIO** (S3 API) — все бинарные данные; в PostgreSQL только метаданные ([media-storage.md](../04-data/media-storage.md)).
- В схему и интерфейсы заложены immutable message revisions, партиционирование по времени/hash и интерфейс шардинга `GetShard(chatID)` (на MVP всегда возвращает единственный узел — [ADR-0002](./0002-storage-sharding.md)).

## Отклонено (отложено)

- RocksDB / самописное KV-хранилище — преждевременная оптимизация.
- Cassandra — см. [scaling-capacity.md](../02-architecture/scaling-capacity.md).
- Redis Streams как production/GA bus — отклонено; он является только beta transport profile.

## Последствия

- Схема БД — [data-model.md](../04-data/data-model.md).
- Ревизии не изменяются на уровне строк: edit создаёт новую revision, delete — soft tombstone. Это сохраняет путь миграции на специализированное хранилище без смены модели.
- Outbox → EventBus остаётся каноном: Redis Streams в beta, Kafka в production/GA ([data-flows.md](../02-architecture/data-flows.md)).

## References

- [ADR-0002](./0002-storage-sharding.md)
- [ADR-0003](./0003-kafka-outbox.md)
- [deployment-topology.md](../02-architecture/deployment-topology.md)
