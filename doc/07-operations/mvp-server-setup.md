# Развертывание MVP (VPS + docker-compose)

> Пошаговая инструкция поднятия полного стека MVP на одном VPS. **Дополнение** к целевой топологии [deployment-topology.md](../02-architecture/deployment-topology.md) (§1 — multi-AZ K8s); для бета-когорты и раннего staging достаточно этого compose. Решения: Caddy на edge (см. [scaling-capacity.md](../02-architecture/scaling-capacity.md) §0), PostgreSQL ([ADR-0002](../adr/0002-storage-sharding.md)). Эволюция нагрузки — [scaling-capacity.md](../02-architecture/scaling-capacity.md).

## 1. Требования

| Ресурс | Минимум (dev/бета) | Рекомендуется |
|--------|--------------------|---------------|
| VPS | 4 vCPU, 8 ГБ RAM, 160 ГБ NVMe | 8 vCPU, 16 ГБ RAM, 500 ГБ NVMe |
| ОС | Ubuntu 24.04 LTS | — |
| Сеть | Публичный IPv4, без NAT провайдера | + IPv6 |
| DNS | A-записи: `api.beta.`, `s3.beta.`, `rtc.beta.`, `turn.beta.example.com` → IP VPS | |

LiveKit требует реального публичного IP (WebRTC): убедитесь, что UDP не фильтруется хостером.

**Ёмкость ступени 0:** ~10k активных пользователей / ~10k одновременных WS (см. [scaling-capacity.md](../02-architecture/scaling-capacity.md) §0).

## 2. Подготовка VPS

```bash
# Пользователь и базовая гигиена
adduser deploy && usermod -aG sudo deploy
# SSH: только ключи
sed -i 's/#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart ssh

# Firewall
ufw allow OpenSSH
ufw allow 80,443/tcp                 # Caddy (HTTP для ACME, HTTPS)
ufw allow 443/udp                    # HTTP/3
ufw allow 7881/tcp                   # LiveKit signaling fallback (TCP)
ufw allow 50000:60000/udp            # LiveKit WebRTC media
ufw allow 3478/udp                   # TURN
ufw allow 5349/tcp                   # TURN TLS
ufw enable

# Docker
curl -fsSL https://get.docker.com | sh
usermod -aG docker deploy

# Автообновления безопасности
apt install unattended-upgrades && dpkg-reconfigure -plow unattended-upgrades
```

## 3. Структура на сервере

```
/opt/tima/
├── docker-compose.yml
├── .env                    # секреты (chmod 600, в git не попадает)
├── caddy/Caddyfile
├── livekit/livekit.yaml
└── volumes/                # postgres/ redis/ minio/ caddy/
```

## 4. `.env` (шаблон)

```dotenv
POSTGRES_USER=tima
POSTGRES_PASSWORD=<openssl rand -hex 24>
POSTGRES_DB=tima
REDIS_PASSWORD=<openssl rand -hex 24>
MINIO_ROOT_USER=tima-admin
MINIO_ROOT_PASSWORD=<openssl rand -hex 24>
MINIO_PUBLIC_ENDPOINT=https://media.example.com
LIVEKIT_API_KEY=<openssl rand -hex 16>
LIVEKIT_API_SECRET=<openssl rand -hex 32>
JWT_SIGNING_KEY=<openssl rand -hex 32>
SMS_PROVIDER_KEY=...
FCM_SERVICE_ACCOUNT_JSON=/run/secrets/fcm.json
APNS_KEY_PATH=/run/secrets/apns.p8
DOMAIN=example.com
ENVIRONMENT=beta
EVENT_BUS=redis-streams
ESCROW_STRICT=true
PUSH_TOKEN_ENCRYPTION_KEY=<base64-encoded-32-byte-key>
PUSH_GATEWAY_URL=https://push-gateway.internal.example/v1/send
PUSH_GATEWAY_TOKEN=<secret>
ATTESTATION_GATEWAY_URL=https://attestation-gateway.internal.example/v1/verify
ATTESTATION_GATEWAY_TOKEN=<secret>
TIMA_VERSION=<immutable-release-tag-or-sha>
```

Ротация серверных секретов — каждые 90 дней ([key-lifecycle.md](../03-security/key-lifecycle.md) §7). При росте — вынести в Vault/SOPS.

`ESCROW_STRICT=false` разрешён только в local dev/test без пользовательских данных. Beta использует stub как backend ключей, но сохраняет строгий инвариант: `ESCROW_STRICT=true`, private send без валидного `escrow_blob` блокируется. Production startup обязан завершаться ошибкой при `ESCROW_STRICT!=true`, отсутствии regional HSM или попытке включить bypass.

`PUSH_TOKEN_ENCRYPTION_KEY` шифрует APNs/FCM/WNS device tokens в PostgreSQL и не должен совпадать с token/OTP pepper. `PUSH_GATEWAY_URL` обязан использовать HTTPS; gateway получает только generic private-message payload без sender, текста и caption.

App Attest и Play Integrity в production требуют отдельных Apple/Google credentials и реальных signed internal-track builds. Development HMAC verifier предназначен только для compose/CI и не является заменой vendor verification.

## 5. `docker-compose.yml`

```yaml
name: tima

services:
  caddy:
    image: caddy:2
    restart: unless-stopped
    ports: ["80:80", "443:443", "443:443/udp"]
    volumes:
      - ./caddy/Caddyfile:/etc/caddy/Caddyfile:ro
      - ./volumes/caddy:/data
    depends_on: [tima-server, realtime-gw, minio]

  tima-server:
    image: ghcr.io/<org>/tima-server:${TIMA_VERSION}
    restart: unless-stopped
    env_file: .env
    environment:
      DATABASE_URL: postgres://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}
      REDIS_URL: redis://:${REDIS_PASSWORD}@redis:6379
      EVENT_BUS: redis-streams
      S3_ENDPOINT: http://minio:9000
      LIVEKIT_URL: http://livekit:7880
      ESCROW_BACKEND_URL: http://escrow-stub:8082
    depends_on:
      postgres: { condition: service_healthy }
      redis:    { condition: service_healthy }
      minio:    { condition: service_started }
      escrow-stub: { condition: service_healthy }
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/healthz"]
      interval: 15s
      retries: 5

  realtime-gw:
    image: ghcr.io/<org>/realtime-gw:${TIMA_VERSION}
    restart: unless-stopped
    env_file: .env
    environment:
      DATABASE_URL: postgres://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}
      REDIS_URL: redis://:${REDIS_PASSWORD}@redis:6379
      EVENT_BUS: redis-streams
    depends_on:
      postgres: { condition: service_healthy }
      redis:    { condition: service_healthy }
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8081/readyz"]
      interval: 15s
      retries: 5

  tima-worker:                                  # outbox relay/consumers, fan-out, push, GC, counters
    image: ghcr.io/<org>/tima-worker:${TIMA_VERSION}
    restart: unless-stopped
    env_file: .env
    environment:
      DATABASE_URL: postgres://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}
      REDIS_URL: redis://:${REDIS_PASSWORD}@redis:6379
      EVENT_BUS: redis-streams
      S3_ENDPOINT: http://minio:9000
    depends_on:
      postgres: { condition: service_healthy }
      redis:    { condition: service_healthy }

  migrate:
    image: migrate/migrate:v4
    volumes: ["./migrations:/migrations:ro"]
    depends_on:
      postgres: { condition: service_healthy }

  escrow-stub:
    image: ghcr.io/<org>/tima-escrow-stub:${TIMA_VERSION}
    restart: unless-stopped
    env_file: .env
    environment:
      ENVIRONMENT: beta
      ESCROW_STRICT: "true"
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8082/healthz"]
      interval: 15s
      retries: 5

  postgres:
    image: postgres:16
    restart: unless-stopped
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: ${POSTGRES_DB}
    volumes: ["./volumes/postgres:/var/lib/postgresql/data"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER}"]
      interval: 10s
      retries: 10
    shm_size: 256mb

  redis:
    image: redis:7
    restart: unless-stopped
    command: ["redis-server", "--requirepass", "${REDIS_PASSWORD}", "--appendonly", "yes"]
    volumes: ["./volumes/redis:/data"]
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD}", "ping"]
      interval: 10s
      retries: 10

  minio:
    image: minio/minio:latest
    restart: unless-stopped
    command: ["server", "/data", "--console-address", ":9001"]
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    volumes: ["./volumes/minio:/data"]

  livekit:
    image: livekit/livekit-server:latest
    restart: unless-stopped
    network_mode: host              # WebRTC: реальные UDP-порты и внешний IP
    command: ["--config", "/etc/livekit.yaml"]
    volumes: ["./livekit/livekit.yaml:/etc/livekit.yaml:ro"]
```

> **Escrow stub** разрешён только в beta и обязан соблюдать тот же signed-config/`escrow_blob` contract и fail-closed send, что production backend. HSM/Nitro обязателен до production в Phase 4; production `escrow_strict` не имеет bypass. **Egress отсутствует намеренно** ([recording-policy.md](../06-realtime/recording-policy.md)).

## 6. `caddy/Caddyfile`

```caddyfile
api.beta.{$DOMAIN} {
    reverse_proxy /v1/ws realtime-gw:8081
    reverse_proxy /healthz tima-server:8080
    reverse_proxy /readyz tima-server:8080
    @metrics {
        path /metrics
        remote_ip private_ranges
    }
    reverse_proxy @metrics tima-server:8080
    reverse_proxy /v1/* tima-server:8080
    encode zstd gzip
    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains"
        -Server
    }
}

s3.beta.{$DOMAIN} {
    reverse_proxy minio:9000            # auth-issued presigned URL, фиксированный TTL 15 минут
    request_body { max_size 2GB }
}

rtc.beta.{$DOMAIN} {
    reverse_proxy localhost:7880        # LiveKit signaling (host network)
}
```

TLS-сертификаты Caddy получает и продлевает сам (Let's Encrypt). SPKI-пиннинг клиентов делать на **корневой/промежуточный** сертификат или собственный leaf-план с процедурой ротации — зафиксировать до релиза.

## 7. `livekit/livekit.yaml`

```yaml
port: 7880
rtc:
  tcp_port: 7881
  port_range_start: 50000
  port_range_end: 60000
  use_external_ip: true
keys:
  <LIVEKIT_API_KEY>: <LIVEKIT_API_SECRET>
turn:
  enabled: true
  domain: turn.example.com
  tls_port: 5349
  udp_port: 3478
```

## 8. Первый запуск

```bash
cd /opt/tima
docker compose up -d postgres redis minio        # база
docker compose run --rm migrate -path /migrations \
  -database "postgres://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}?sslmode=disable" up

# MinIO: bucket'ы и lifecycle
docker compose exec minio mc alias set local http://localhost:9000 $MINIO_ROOT_USER $MINIO_ROOT_PASSWORD
docker compose exec minio mc mb local/tima-media-e2e local/tima-media-public local/tima-public-staging
docker compose exec minio mc ilm rule add local/tima-media-e2e --transition-days 180 --transition-tier COLD   # cold-ярус ([media-storage.md](../04-data/media-storage.md) §6)

docker compose up -d                              # всё остальное
docker compose ps                                 # все healthy?
curl -fsS https://api.beta.example.com/healthz
curl -fsS https://api.beta.example.com/readyz
```

Конфигурация объектов обязана сохранять логическое разделение pipeline: private media принимается только как ciphertext, public media после обработки имеет ровно три варианта без `Original`. Executable content блокируется до upload. Команды bucket provisioning выше являются инфраструктурным примером и не отменяют это доменное разделение.

**Smoke-чеклист:** unversioned `/healthz` и `/readyz` через TLS; `/metrics` доступен только scrape-сети · WS `wss://api.beta.example.com/v1/ws` · Redis Streams consumer group и transactional outbox drain · auth обязателен для presigned URL с TTL 15 минут · private/public media isolation · immutable edit revision · legal hold · LiveKit через `rtc.beta.*` · attestation routes `POST /v1/verify/attestation/ios` и `POST /v1/verify/integrity/android` · vendor-outage grace только для ранее доверенного устройства (≤30d), без registration/linking и без grace для failed/forged · Phase 3 feed/inbox · Phase 3b bot webhook.

## 9. Обновление без простоя

```bash
docker compose pull tima-server realtime-gw tima-worker
docker compose up -d --no-deps tima-server realtime-gw tima-worker
docker compose run --rm migrate -path /migrations \
  -database "postgres://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}?sslmode=disable" up
```

Правило миграций: сначала выкладывается код, читающий обе схемы, затем миграция, затем очистка — никаких деструктивных изменений в одном релизе. См. [ci-cd-release.md](../09-delivery/ci-cd-release.md).

## 10. Резервное копирование

| Что | Как | Частота | Хранение |
|-----|-----|---------|----------|
| PostgreSQL | continuous WAL archive → внешний object storage + base backup (`pgBackRest`/WAL-G) | WAL непрерывно; base backup ежедневно | 30 дней |
| MinIO | `mc mirror` на offsite bucket | ежедневно | 30 дней |
| Redis | AOF включён; допустимо потерять (кэш/очереди восстановимы) | — | — |
| `.env`, конфиги | зашифрованная копия (age/SOPS) вне сервера | при изменении | — |

Beta цели: **RPO ≤5 минут, RTO ≤4 часов**; WAL archive обязан находиться вне VPS/провайдера отказа. Restore на чистую VM проверяется ежеквартально. Production цель: **RTO ≤30 минут**.

Обычный backup retention не удаляет объекты под legal hold. Hold охватывает сообщение, все immutable revisions, media-варианты и связанные метаданные; restore обязан восстановить этот scope до запуска purge.

## 11. Мониторинг

Минимальный набор (добавить в compose при фазе 5): Prometheus + Grafana + node_exporter + postgres_exporter + redis_exporter; LiveKit отдаёт `/metrics` сам. Алерты: диск > 80 %, отставание воркера (fan-out лент, `rating_counters`), error rate 5xx, недоступность WS, истечение сертификатов (Caddy сам продлевает — алерт на сбой продления). Детали — [observability.md](./observability.md).

## 12. Частые проблемы

| Симптом | Причина |
|---------|---------|
| Звонок соединяется, но нет звука | UDP 50000–60000 закрыт / `use_external_ip` не включён |
| WS рвётся каждые ~60 с | Проверить, что `/v1/ws` идёт в отдельный `realtime-gw`, а не в `tima-server` или сторонний CDN |
| Presigned PUT → 403 | Расхождение часов клиента/сервера или неверный `S3_ENDPOINT` (подпись содержит хост: снаружи должен быть `s3.example.com`) |
| ACME не выдаёт сертификат | Порт 80 закрыт или DNS ещё не расползся |
| LiveKit не стартует в compose | Занят порт из host-диапазона; проверить `network_mode: host` конфликты |
| Лента пустая при живых подписках | Воркер не запущен или Redis `feed:{user}` не строится — см. [feed-ranking.md](../04-data/feed-ranking.md) |
| `entity_message` не приходит в окно 4 | Проверить `POST /inbox/notify`, WS `inbox.event`, feature flag `social_inbox` |

## 13. Ссылки

- [deployment-topology.md](../02-architecture/deployment-topology.md) — целевая multi-AZ / K8s топология
- [scaling-capacity.md](../02-architecture/scaling-capacity.md) — триггеры перехода со ступени 0
- [ci-cd-release.md](../09-delivery/ci-cd-release.md) — пайплайны и релизы
- [disaster-recovery.md](./disaster-recovery.md) — DR при росте за пределы одного VPS
