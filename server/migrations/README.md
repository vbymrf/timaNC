# Phase 0 migrations

Миграции предназначены для PostgreSQL 16 и `golang-migrate` v4.

Применение:

```sh
migrate -path server/migrations -database "$DATABASE_URL" up
psql "$DATABASE_URL" -f server/migrations/verify_phase0.sql
```

Политика миграций — forward-only: `.down.sql` намеренно отсутствует. Откат
релиза выполняется отключением функции или новой компенсирующей migration, а не
удалением схемы. После публикации versioned SQL-файлы неизменяемы; исправления
добавляются новой forward migration.

`verify_phase0.sql` не является versioned migration: он только проверяет наличие
основных объектов, N=1 hash partition, отложенного FK ревизии, append-only
триггеров и outbox-индекса.
