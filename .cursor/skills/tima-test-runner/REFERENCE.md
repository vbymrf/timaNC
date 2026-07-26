# TIMA test reference

All paths are relative to the repository root. Use PowerShell on Windows.

## Preflight

Before tiers that use runtime services:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml ps
& $adb devices -l
Get-Process emulator,qemu-system-x86_64,Tima -ErrorAction SilentlyContinue
Get-PSDrive C
git status --short --branch
```

Also check `Get-Command gradle` and `java -version`. Gradle 8.14+ and JDK 17
are mandatory for client tests. Check `Get-Command go` and `go version` before
server tests; `.github/workflows/server.yml` is the current version authority.

## Test tiers

### T0: targeted or fast unit

Use the narrowest affected target.

```powershell
Push-Location server
go test ./...
Pop-Location

Push-Location client
gradle --no-daemon jvmTest
# Or one target:
gradle --no-daemon :modules:messenger-crypto:jvmTest
Pop-Location
```

Plain `jvmTest` does not prove HTTP E2E; that test skips unless its environment
is enabled.

### T1: contracts

```powershell
.\schema\codegen\validate.ps1
python schema/codegen/check_phase1_routes.py

Push-Location server
go test ./internal/phase1 ./internal/push
Pop-Location

Push-Location client
gradle --no-daemon jvmTest
Pop-Location
```

For full generated-code validation:

```powershell
.\schema\codegen\generate.ps1
.\schema\codegen\compile.ps1
git diff --exit-code
```

Generation is allowed only when contract drift is in scope. Never hide a
generated diff.

### T2: database and HTTP/WebSocket integration

The dev stack must already be healthy or be started through `tima-ops-runner`.

```powershell
$env:DATABASE_URL = "postgres://tima:dev_postgres_change_me@127.0.0.1:5432/tima?sslmode=disable"
$env:REDIS_URL = "redis://127.0.0.1:6379"
Push-Location server
go test -tags=integration ./internal/phase1 ./internal/realtime
Pop-Location

$env:TIMA_E2E_BASE_URL = "http://127.0.0.1:8080"
$env:TIMA_REQUIRE_HTTP_E2E = "true"
Push-Location client
gradle --no-daemon :integration-harness:jvmTest
Pop-Location
```

Unset task-specific environment variables after the run. Treat messages such
as `DATABASE_URL is not set` as a failed required gate, not a pass.

### T3: full development-stack gate

Mirror `.github/workflows/dev-stack.yml`: healthy Compose stack, required JVM
HTTP E2E, generated k6 fixture, bounded k6 smoke, metrics budget, and outbox
checks.

```powershell
$env:TIMA_E2E_BASE_URL = "http://127.0.0.1:8080"
$env:TIMA_REQUIRE_HTTP_E2E = "true"
$env:TIMA_E2E_K6_FIXTURE_PATH = (Resolve-Path build).Path + "\phase1-k6-fixture.json"
$env:TIMA_E2E_K6_SAMPLE_COUNT = "5"
Push-Location client
gradle --no-daemon :integration-harness:jvmTest
Pop-Location

$env:TIMA_K6_FIXTURE = "build/phase1-k6-fixture.json"
k6 run --summary-export build/phase1-k6-summary.json tests/load/phase1_slo_smoke.js
```

Use `.github/workflows/dev-stack.yml` as the authority for current metrics and
outbox assertions.

### T4: platform validation

Android requires Android SDK:

```powershell
Push-Location client
gradle --no-daemon :modules:core:core-media:jvmTest :modules:core:core-data:jvmTest :apps:android:testReleaseUnitTest :apps:android:bundleRelease
Pop-Location
```

Windows requires JDK/jpackage and Windows SDK for MSIX:

```powershell
Push-Location client
gradle --no-daemon :modules:core:core-media:jvmTest :modules:core:core-data:jvmTest :apps:windows:test :apps:windows:packageMsixUnsigned
Pop-Location
```

iOS XCFramework and simulator app parity requires macOS/Xcode. Windows can run
the iOS JVM target but cannot claim native iOS acceptance.

### T5: native acceptance

Follow `doc/08-quality/phase1-native-acceptance.md`. Android, iOS, and Windows
steps 1–9 must use one final commit. Preserve old FAIL evidence and create a new
commit-bound directory for a completed run.

## Authoritative outputs

- KMP JVM JUnit:
  `client/modules/**/build/test-results/jvmTest/TEST-*.xml`
- iOS JVM JUnit:
  `client/apps/ios/build/test-results/jvmTest/*.xml`
- Android unit JUnit:
  `client/apps/android/build/test-results/testReleaseUnitTest/*.xml`
- Windows JUnit:
  `client/apps/windows/build/test-results/test/*.xml`
- Gradle HTML:
  `client/**/build/reports/tests/*/index.html`
- k6 fixture: `build/phase1-k6-fixture.json`
- k6 summary: `build/phase1-k6-summary.json`
- platform evidence: `client/build/*-validation-evidence.txt`
- native evidence:
  `doc/08-quality/evidence/<platform>-<commit>/manifest.md`

Go tests have no repository-standard JUnit file; use process exit code and the
package/test names in output.

## Concurrency

- One Compose project, one active Android emulator, and one visible Tima peer.
- Ports 8080, 5432, 6379, and 9000 are shared runtime resources.
- Gradle already enables project parallelism. Do not launch concurrent heavy
  Gradle commands against the same checkout.
- Required HTTP E2E disables Gradle up-to-date reuse and must actually execute.

## Current authorities

- `.github/workflows/server.yml`
- `.github/workflows/dev-stack.yml`
- `.github/workflows/contracts.yml`
- `.github/workflows/phase1-contract.yml`
- `.github/workflows/client-platform-validation.yml`
- `doc/08-quality/test-strategy.md`
- `doc/08-quality/load-test-plan.md`
- `doc/08-quality/phase1-native-acceptance.md`
- `doc/09-delivery/phase1-exit-review.md`
