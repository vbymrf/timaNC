# TIMA infrastructure

The Compose files use Linux containers and named Docker volumes, so the
development stack works with Docker Desktop on Windows and Docker Engine on
Linux. All third-party images use fixed version tags (rather than `latest`).

## Prerequisites

- Docker Engine with Docker Compose v2.
- `server/Dockerfile` and `server/migrations/` in the repository. The Compose
  files deliberately reference those paths; this infrastructure change does
  not create application files.

## Local development

From the repository root:

```powershell
Copy-Item infra/.env.example infra/.env
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml config
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml up --build -d
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml ps
```

The same flow on Linux:

```sh
cp infra/.env.example infra/.env
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml config
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml up --build -d
```

Caddy exposes the API at `http://localhost:8080`. PostgreSQL, Redis, the MinIO
API, and the MinIO console are available on the ports in `.env`. The
`minio-bootstrap` job idempotently creates the private `media` and `previews`
buckets. The `migrate` image packages `server/migrations` at build time and
applies them before `tima-server` starts. Rebuild that image after adding a
migration. Caddy configuration is likewise packaged into its image so the
stack works when the checkout is located on a VMware shared drive. The optional
LiveKit renderer packages its template for the same reason.

Useful commands:

```powershell
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml logs -f
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml run --rm migrate
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml down
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml down -v # deletes local data
```

## Optional Phase 2 LiveKit

LiveKit is excluded by default. Start it with the `phase2` profile:

```powershell
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml --profile phase2 up -d
```

The `livekit-config` one-shot service renders
`livekit/livekit.yaml.template` into a named volume before LiveKit starts.
LiveKit does not expand `${VAR}` placeholders in mounted YAML by itself. The
same template is compatible with `envsubst` in other deployment systems.

The development profile publishes HTTP `7880`, TCP `7881`, and UDP
`50000-50020` by default. Change both ends of the UDP range together.

## Ubuntu VPS / staging

Copy the repository to the Ubuntu host, create `infra/.env`, replace every
placeholder, configure DNS, then run:

```sh
cp infra/.env.example infra/.env
chmod 600 infra/.env
$EDITOR infra/.env
docker compose --env-file infra/.env -f infra/docker-compose.vps.yml config
docker compose --env-file infra/.env -f infra/docker-compose.vps.yml up --build -d
```

Caddy obtains certificates automatically for `API_DOMAIN`, `MINIO_DOMAIN`,
and `LIVEKIT_DOMAIN`. Open inbound TCP `80` and `443` and UDP `443`. When
enabling Phase 2, also open TCP `7881` and the configured LiveKit UDP range:

```sh
docker compose --env-file infra/.env -f infra/docker-compose.vps.yml --profile phase2 up --build -d
```

Staging caveats:

- This is a single-host topology, not a high-availability production design.
- Replace all example credentials. Compose interpolation, PostgreSQL URLs, and
  Redis URLs make URL-special characters error-prone; use URL-safe random
  secrets or percent-encode them consistently.
- Keep `infra/.env` off source control and restrict its file permissions.
- The MinIO admin console, PostgreSQL, and Redis are intentionally not
  published by the VPS manifest. Do not expose them directly.
- Back up the named PostgreSQL, Redis, MinIO, and Caddy volumes before upgrades.
  A normal `down` keeps them; `down -v` permanently removes them.
- Migration files are packaged into an immutable image and should be
  forward-only. Take a database backup before applying staging migrations.
- LiveKit's profile covers basic SFU connectivity. Production TURN/TLS,
  firewall/NAT verification, external-IP behavior, and capacity sizing remain
  Phase 2 deployment work.
- Tags are fully version-pinned but not digest-pinned. Record image digests in
  release automation if supply-chain immutability is required.
