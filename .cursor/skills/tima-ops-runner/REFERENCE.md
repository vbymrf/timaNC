# TIMA Windows operations reference

Run from the repository root in PowerShell. Inspect before changing state.

## Preflight

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
if (-not (Test-Path infra/.env)) { Copy-Item infra/.env.example infra/.env }
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml ps
& $adb devices -l
Get-Process emulator,qemu-system-x86_64,Tima -ErrorAction SilentlyContinue
Get-PSDrive C
Get-Command gradle -ErrorAction SilentlyContinue
Get-Command go -ErrorAction SilentlyContinue
java -version
go version
git status --short --branch
```

Do not rely on JDK/Gradle installed under `%TEMP%`. The active AVD belongs under
`%USERPROFILE%\.android\avd`, not VMware shared `Z:`.

## Host tooling

Use the existing interactive installer/verifier:

```powershell
.\scripts\setup-windows.bat
```

Its package map is:

1. Docker Desktop and Compose v2
2. Microsoft OpenJDK 17
3. Git
4. GitHub CLI
5. Windows SDK / `makeappx.exe`
6. Gradle 8.14.3+
7. Android SDK base
8. Android system image and AVD `Tima_API_34`
9. pinned `schema/.tools`

Prefer its `Test-*` verification functions and PASS-only
`scripts/setup-windows-log.txt`. Install one missing component at a time after
approval.

The current installer does not include Go. Server tests and builds require the
Go version pinned by `.github/workflows/server.yml` (currently 1.24.5). Diagnose
with `Get-Command go` and `go version`; after approval install that toolchain
persistently, then verify it before running server gates.

WSL is not a TIMA command surface, but Docker Desktop may require its WSL2
backend. Diagnose with:

```powershell
wsl --status
wsl --version
wsl --list --verbose
docker version
docker compose version
```

`wsl --update`, enabling Windows features, shutdown/restart, and elevated
repairs require approval.

## Development stack

Canonical Compose arguments must always be present:

```powershell
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml config
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml up -d
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml ps
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8080/healthz
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8080/readyz
```

Add `--build` only after Dockerfile, server, migration, or packaged
configuration changes. A newly added migration requires a rebuilt packaged
migrate image. Manual migration is state-changing and needs approval:

```powershell
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml run --rm migrate
```

A normal `down` preserves named volumes. `down -v` deletes PostgreSQL, Redis,
MinIO, and Caddy state and is forbidden during acceptance.

## Android emulator and app

Start only when `adb devices -l` has no intended emulator:

```powershell
& "$sdk\emulator\emulator.exe" -avd Tima_API_34 -no-snapshot -no-boot-anim -netdelay none -netspeed full
```

Build from `client`:

```powershell
Push-Location client
gradle :apps:android:assembleDebug `
  "-Ptima.android.baseUrl=http://10.0.2.2:8080" `
  "-Ptima.android.integrityProjectNumber=0" `
  "-Ptima.android.enableDevelopmentAuth=true"
Pop-Location
```

Install and launch:

```powershell
& $adb -s emulator-5554 install -r "client\apps\android\build\outputs\apk\debug\android-debug.apk"
& $adb -s emulator-5554 reverse tcp:9000 tcp:9000
& $adb -s emulator-5554 shell am start -n com.tima.client/.android.MainActivity
```

The emulator API is `http://10.0.2.2:8080`; MinIO presigned traffic needs port
9000 reverse. With multiple devices always specify `-s`. Killing an unintended
emulator or uninstalling an app requires approval.

## Windows peer

Build from `client`:

```powershell
Push-Location client
$env:TIMA_API_BASE_URL = "http://127.0.0.1:8080"
$env:TIMA_WINDOWS_ENABLE_DEVELOPMENT_ESCROW = "true"
gradle :apps:windows:packageWindowsAppImage "-Ptima.windows.enableDevelopmentEscrow=true"
Pop-Location
```

Before launching, inspect all `Tima.exe` processes and identify the visible UI
owner. Stop stale processes only after approval, then start:

```powershell
Start-Process "client\apps\windows\build\jpackage\Tima\Tima.exe"
```

Acceptance-only variables:

- `TIMA_WINDOWS_ACCEPTANCE_AUTOSTART_LINK`
- `TIMA_WINDOWS_ACCEPTANCE_AUTOOPEN_CHAT`
- `TIMA_WINDOWS_ACCEPTANCE_MARK_READ`
- `TIMA_WINDOWS_ACCEPTANCE_OPEN_PREVIEW`
- `TIMA_WINDOWS_ACCEPTANCE_REPLY_TEXT`

Set them only for the specific scenario and remove them afterward.

## Machine transfer and restore

Use `perenos/HOW_TO_TRANSFER.md` as the authority. Verify archive checksums and
available disk first. On VMware shared folders, copy archives to a local
Windows path before mounting them into Linux containers.

Docker restore empties named-volume contents before extraction. It therefore
requires explicit destructive approval and a verified backup. Confirm actual
volume names with `docker volume ls`; do not assume the `infra_` prefix.

Restore Android system images under `%LOCALAPPDATA%\Android\Sdk` and AVD data
under `%USERPROFILE%\.android\avd`. Do not run the emulator while replacing its
files.

## End of an operations block

```powershell
Push-Location client
gradle jvmTest
Pop-Location
git status --short --branch
gh run list --branch main --limit 6
```

Run tests through `tima-test-runner`. Commit, push, and `gh run watch` happen
only when the user requested those external changes.

Record which containers and emulators remain running. Clear task-only
environment variables and temporary fault/network rules.

## Primary repository authorities

- `scripts/setup-windows.ps1`
- `infra/docker-compose.dev.yml`
- `infra/README.md`
- `client/README.md`
- `perenos/HOW_TO_TRANSFER.md`
- `doc/07-operations/credential-free-development.md`
- `doc/08-quality/phase1-native-acceptance.md`
