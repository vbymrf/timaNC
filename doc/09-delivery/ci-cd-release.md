# CI/CD и Release

## 1. Repository structure (target)

```text
tima/
├── client/          # KMP
├── server/          # Go
├── infra/           # K8s, terraform
├── docs/            # symlink or copy from doc/
└── .github/workflows/
```

Current workspace contains generated contracts, KMP client libraries and platform
shells, Go services, Compose infrastructure, and Phase 1 release/SLO workflows.
Until Phase 5 the active policy is
[credential-free development](../07-operations/credential-free-development.md):
unsigned validation and the repository-owned notification path run without
Apple/Google store accounts; credentialed release jobs remain manual.

## 2. Pipelines

### Client (KMP)

| Stage | Actions |
|-------|---------|
| lint | detekt, ktlint |
| test | `shared:*Test`, crypto vectors |
| contract | Phase 0 core schema; Phase 3b bot schema; `DocumentV2`, domain API, media variants, revision/event formats |
| build | Android APK/AAB, iOS framework, Windows MSIX (primary) |
| sign | Play/App Store, code sign Windows |

### Server (Go)

| Stage | Actions |
|-------|---------|
| lint | golangci-lint |
| test | unit + integration (testcontainers) |
| policy | executable block, auth + presigned 15m, dual media pipeline, retention/legal hold |
| build | docker images `tima-server:{sha}`, `realtime-gw:{sha}`, `tima-worker:{sha}` |
| scan | Trivy |

### Deploy

| Env | Trigger | Target |
|-----|---------|--------|
| dev | PR / local | `infra/docker-compose.dev.yml` |
| beta VPS | tagged `beta-*` or manual | single-VPS compose; Redis Streams + transactional outbox |
| staging | merge to `main` | K8s / scaled compose |
| prod | tagged release `v*` + manual approve | Kafka, multi-AZ regional cells; dual cells required pre-GA |

**Beta VPS deploy:** `docker compose pull tima-server realtime-gw tima-worker && docker compose up -d --no-deps tima-server realtime-gw tima-worker` + backward-compatible `golang-migrate` run.

Release smoke проверяет URL-контракт: `/healthz`, `/readyz`, `/metrics` без версии; WS `/v1/ws`; beta WS на `api.*`, production WS на `realtime.*`; LiveKit на `rtc.*` во всех средах.

## 3. Release phases (feature gates)

| Phase | Server modules | Client flags | CI gate |
|-------|----------------|--------------|---------|
| 0 | core schema + migration baseline | — | schema validation + clean `golang-migrate up` |
| 1 | `auth`, `messages`, `media`, realtime, hybrid push | — | messaging HTTP/WS/push E2E + content/media/revision + unsigned platform matrix |
| 2 | `calls` | `calls` | not started while Phase 1 is BLOCKED; call interoperability and Communication MVP gates |
| 3 | `feed`, `emotions`, `attributes`, `shelves`, `inbox` | `public_feed`, `social_inbox`, `emotions` | [test-strategy.md](../08-quality/test-strategy.md) §8 Phase 3 |
| 3b | `bot_gateway`, webhook workers | `bot_platform` | [bot-platform-test-plan.md](../08-quality/bot-platform-test-plan.md) §12 |
| 4+ | sharding, HSM escrow, retention/legal hold | `blogger_mode` | HSM mandatory before production; load S1–S13 + hold drill |

## 4. Mobile release

- Staged rollout: 5% → 25% → 100% over 7 days.
- Force update channel for HMAC secret rotation (Phase 2).
- Min supported version policy: N-2.

## 5. Windows release

- Signed MSIX is the primary and mandatory release artifact; portable is optional.
- Communication MVP gate includes calls through official LiveKit C++ SDK 1.0 behind the narrow JNI/JNA `CallRepository` adapter.
- Auto-update check on launch.
- Code signing certificate required.

## 6. Database migrations

- `golang-migrate` in server repo; migration files are immutable after release.
- Backward compatible migrations only for rolling deploy.
- Expand-contract pattern for breaking schema.
- Messages and revisions are immutable: an edit migration may add projections/indexes, but must not rewrite prior message/revision bodies.
- Retention migrations preserve legal-hold precedence and cover the related message/revision/media/metadata scope.

## 7. Feature flags

- LaunchDarkly or in-house Redis flags.
- Runtime feature flags: `calls`, `public_feed`, `feed_friends`, `emotions`, `attributes`, `shelves`, `social_inbox`, `bot_platform`, `blogger_mode`, `message_edit`, `media_public_processing`, `retention_purge`.
- Disabling public media processing must not reroute assets into private processing; disabling retention purge must leave legal holds intact.
- `ESCROW_STRICT` — deployment invariant, а не runtime feature flag. Beta stub запускается со strict mode и блокирует private send без валидного escrow; `false` допустим только local dev/test без пользовательских данных. Production config validation rejects false/missing strict mode, missing regional HSM and every bypass flag.

## 8. Rollback

- K8s rollout undo < 5 min.
- DB: forward-only migrations; rollback = feature disable not schema revert.

## 9. Local dev

```bash
docker compose -f infra/docker-compose.dev.yml up
# postgres, redis streams, minio, tima-server, realtime-gw, tima-worker
# LiveKit is Phase 2-only and requires: --profile phase2
```

Production-like beta stack: [mvp-server-setup.md](../07-operations/mvp-server-setup.md).

## 10. Ссылки

- [test-strategy.md](../08-quality/test-strategy.md)
- [mvp-server-setup.md](../07-operations/mvp-server-setup.md)
- [release-gates.md](../07-operations/release-gates.md)
- [credential-free-development.md](../07-operations/credential-free-development.md)
- [phase1-exit-review.md](./phase1-exit-review.md)
- [dependency-policy.md](./dependency-policy.md)
- [roadmap.md](./roadmap.md)
