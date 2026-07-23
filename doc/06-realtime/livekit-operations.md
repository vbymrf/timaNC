# LiveKit Operations

## 1. Reference config

Based on `livekit-master git/config-sample.yaml`.

| Setting | Production value |
|---------|------------------|
| `port` | 7880 (behind TLS LB) |
| `redis.address` | redis-sentinel:26379 |
| `rtc.port_range_start/end` | 50000–60000 |
| `rtc.use_external_ip` | true |
| `rtc.allow_tcp_fallback` | true |
| `keys` | Vault-managed, rotate 90d |
| `room.empty_timeout` | 300 |
| `prometheus_port` | 6789 |

## 2. Deployment

- K8s DaemonSet or dedicated nodes (avoid CPU steal from app pods).
- Host network optional for UDP performance.
- Co-locate with TURN in same region.
- Publish signaling only on `rtc.*` in every environment (`rtc.dev.*`, `rtc.beta.*`, `rtc.staging.*`, production `rtc.*`); never reuse `api.*` or `realtime.*`.

## 3. Ports firewall

| Port | Direction | Purpose |
|------|-----------|---------|
| 443 | Inbound | WSS signaling |
| 50000-60000 UDP | Inbound | WebRTC media |
| 7881 TCP | Inbound | ICE TCP fallback |
| 3478/5349 | Inbound | TURN if enabled |

## 4. Monitoring

Use upstream Grafana dashboard: `livekit-master git/deploy/grafana/livekit-server-overview.json`.

| Metric | Alert |
|--------|-------|
| `livekit_room_total` | Capacity planning |
| Packet loss | > 5% sustained |
| CPU | > 80% 10m |
| Redis connectivity | Critical |

## 5. Scaling

- Horizontal: add nodes + Redis cluster.
- `node_selector.kind: sysload` for room placement.
- Bandwidth: plan ~1 Mbps per HD video participant forwarded.

## 6. Upgrades

1. Drain rooms (stop new calls via Call Service flag).
2. Rolling update LiveKit pods.
3. Verify SDK/server protocol compatibility matrix.

## 7. Disaster recovery

- LiveKit state ephemeral; rooms recreated on reconnect.
- Redis persistence for distributed routing — restore from replica.

## 8. Runbook snippets

See [runbooks.md](../07-operations/runbooks.md#livekit).

## 9. Ссылки

- [deployment-topology.md](../02-architecture/deployment-topology.md)
- LiveKit docs: https://docs.livekit.io/deploy/
