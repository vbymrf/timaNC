# TIMA

Design-first monorepo for the TIMA messenger. The current implementation
milestone is Phase 0 (Foundation): machine contracts, reproducible database
migrations, a minimal health backend, and a single-host development/staging
stack.

## Repository layout

- `schema/` is the source of truth for OpenAPI, Protobuf, JSON Schema, and
  contract fixtures.
- `gen/` contains committed Go and Kotlin models generated from `schema/`.
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

## Phase 0 local run

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
