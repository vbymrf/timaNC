# TIMA

TIMA messenger monorepo. The current implementation milestone is the Phase 1
Messaging Alpha exit candidate: machine contracts, KMP SDK and platform shells,
Go auth/messaging/media services, realtime delivery, reproducible migrations,
and a single-host development stack. The current exit decision is
[BLOCKED](doc/09-delivery/phase1-exit-review.md) on external credentials,
signed internal builds and invited-cohort evidence.

## Repository layout

- `schema/` is the source of truth for OpenAPI, Protobuf, JSON Schema, and
  contract fixtures.
- `gen/` contains committed Go and Kotlin models generated from `schema/`.
- `client/` contains the KMP libraries and Android, iOS and Windows shells.
- `server/` contains the Go services and forward-only database migrations.
- `infra/` contains local and VPS Docker Compose manifests.
- `doc/` contains the product and architecture specifications.

`schema/.tools/` is a local download cache and is not committed. Regenerate and
verify the machine contracts from Windows PowerShell:

```powershell
.\schema\codegen\bootstrap.ps1
.\schema\codegen\validate.ps1
.\schema\codegen\generate.ps1
.\schema\codegen\compile.ps1
```

Generated sources under `gen/` are committed. A generation run must leave the
working tree unchanged.

## Reference source trees

The local directories `kodium-main git/`, `livekit-master git/`, and
`aiogram-dev-3.x/` are intentionally ignored:

- Kodium is consumed through its pinned Maven coordinate.
- LiveKit is consumed through pinned images and SDK modules.
- aiogram is architecture reference material only and is not a dependency.

See `doc/09-delivery/dependency-policy.md` for the authoritative dependency
policy.

## Phase 1 local run

Copy the environment template and start the development stack:

```powershell
Copy-Item infra/.env.example infra/.env
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml up --build -d
```

Run the database migration and smoke checks:

```powershell
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml run --rm migrate
Invoke-WebRequest http://localhost:8080/healthz
Invoke-WebRequest http://localhost:8080/readyz
```

Stop the stack with:

```powershell
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml down
```

The VPS manifest and deployment notes are in `infra/README.md`.
