# ADR-0011: Caddy как edge (MVP)

**Статус:** принят · **Дата:** 2026-07-13

## Контекст

В исследованиях фигурировали Nginx, Caddy, HAProxy, Envoy и Kong. Для MVP на VPS / небольшом K8s-кластере нужен один reverse-proxy: TLS, HTTP/REST, WebSocket, раздача presigned-ссылок MinIO, проксирование LiveKit signaling.

Текущий [deployment-topology.md](../02-architecture/deployment-topology.md) на Growth-фазе допускает Envoy/Kong; этот ADR фиксирует **MVP edge**.

## Решение

- **Caddy 2** — единственная входная точка MVP: автоматический TLS (Let's Encrypt), WebSocket из коробки, декларативный Caddyfile, health checks и балансировка upstream'ов (второй узел бэкенда без простоя).
- Прямые порты наружу помимо Caddy: только LiveKit media (UDP/TCP диапазоны WebRTC) и TURN.
- MinIO presigned-ссылки публикуются через поддомен за Caddy (`s3.example.com` → MinIO), сам порт наружу не открыт.

## Отклонено (отложено)

- **Nginx** — рабочая альтернатива, но TLS-автоматизация и конфиг сложнее; выгоды на MVP нет.
- **HAProxy** — сценарий балансировки покрывается Caddy upstreams.
- **Envoy/Kong** — целевой API-gateway при переходе на микросервисы (mTLS, gRPC, WAF); триггер — выделение 3+ отдельных сервисов ([scaling-capacity.md](../02-architecture/scaling-capacity.md)).

## Последствия

- Certificate pinning клиентов настраивается на SPKI серверного сертификата — процедура ротации описывается вместе с деплоем.
- Realtime Gateway может быть colocated с `tima-server` за одним Caddy upstream на MVP.
- Multi-region edge — [ADR-0008](./0008-multi-region-strategy.md).

## References

- [tech-stack.md](../02-architecture/tech-stack.md)
- [deployment-topology.md](../02-architecture/deployment-topology.md)
