# Dependency Policy

## 1. Vendor libraries

| Dependency | Pin | Upgrade process |
|------------|-----|-----------------|
| Kodium | `eu.livotov.labs:kodium:1.0.0` | Security patch: 48h review; minor: quarterly |
| LiveKit Server | git tag from upstream | Match client SDK matrix |
| LiveKit Android/Swift SDK | pinned release tag | Must match server protocol |
| LiveKit C++ SDK (Windows) | `1.0.x`, exact release pin | Communication MVP dependency; update only through JNI/JNA `CallRepository` compatibility suite |
| golang-migrate | pinned v4 image/module | Phase 0 core migration baseline; forward-only release process |
| Redis | pinned 7.x image | Beta Streams event bus; persistence/consumer-group compatibility test |
| Kafka client/broker | pinned compatible matrix | Production/GA event bus |
| Caddy | `caddy:2` (MVP VPS) | Minor: quarterly; security: 7d |
| Python `tima-bot-sdk` | semver at publish | Match [bot-api.md](../05-api/bot-api.md) OpenAPI major |

## 2. SBOM

- Generate CycloneDX on each release for client + server.
- Store in artifact registry.

## 3. License compliance

| Lib | License | Attribution |
|-----|---------|-------------|
| Kodium | Apache 2.0 | NOTICE in app |
| LiveKit | Apache 2.0 | NOTICE in app |
| KyberKotlin (via Kodium) | Check upstream | README attribution |

## 4. Vulnerability scanning

- Dependabot / Renovate on GitHub.
- Critical CVE: patch within 7 days or documented exception.

## 5. Fork policy

- `Kodium git/` and `livekit-master git/` are **vendor snapshots**.
- Prefer Maven/Go modules for builds; vendor dir for reference only.
- Track divergence in CHANGELOG when patching locally.

## 6. Kodium audit gate

No production release until [ADR-0005](../adr/0005-kodium-readiness-gate.md) checklist complete.

## 7. LiveKit SDK compatibility

Before upgrade:

1. Read upstream CHANGELOG.
2. Run call E2E tests staging.
3. Verify JWT grant compatibility.
4. Run Windows MSIX call E2E through the official C++ SDK adapter; no native LiveKit types may cross `CallRepository`.

## 8. Bot SDK / API compatibility (Phase 3b)

Before bot platform release:

1. OpenAPI major bump → coordinated server + `tima-bot-sdk` release.
2. Run [bot-platform-test-plan.md](../08-quality/bot-platform-test-plan.md) §6 (DM matrix) on staging.
3. Webhook URL validation rules unchanged without migration notice.
4. Domain types remain aligned with [domain-api-formats.md](../10-sdk/domain-api-formats.md): `DocumentV2`, media pipelines/3 variants without `Original`, immutable revisions and `message.edited`.
5. SDK upgrade cannot relax executable blocking, auth + presigned TTL 15 minutes, retention or legal-hold enforcement.

## 9. Ссылки

- [README.md](../README.md)
- [security-test-plan.md](../08-quality/security-test-plan.md)
- [bot-api.md](../05-api/bot-api.md)
- [mvp-server-setup.md](../07-operations/mvp-server-setup.md)
