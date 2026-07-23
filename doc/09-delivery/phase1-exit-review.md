# Phase 1 exit review

Review date: 2026-07-23  
Baseline: `870849e`  
Final reviewed commit: the commit containing this review  
Decision: **BLOCKED**

Phase 1 is not declared exited. Repository-controlled server, contract, migration,
JVM, Compose, black-box messaging and bounded local SLO gates pass. The remaining
gates require real vendor/signing credentials, hosted CI runners and cohort
evidence that are not available in this repository or workstation.

## Verified gates

| Gate | Result | Evidence |
|---|---|---|
| OpenAPI, JSON Schema and Protobuf validation | PASS | `schema/codegen/validate.ps1` |
| Generated source reproducibility and compilation | PASS | `generate.ps1`, zero `gen/` drift, `compile.ps1` |
| Phase 1 OpenAPI route coverage | PASS | 32 operations checked by `check_phase1_routes.py` |
| Go formatting, vet, unit tests and builds | PASS | `gofmt -l`, `go vet ./...`, `go test ./...`, `go build ./...` |
| PostgreSQL/Redis integration tests | PASS | `go test -tags=integration ./internal/phase1 ./internal/realtime` |
| Migrations 000005–000006 | PASS | `verify_phase1.sql`, including trust objects, attestation backfill and monotonic read state |
| KMP JVM tests | PASS | `gradle jvmTest` |
| iOS Kotlin targets | PASS | x64, arm64 and simulator-arm64 compilation |
| Windows JVM package input | PASS | tests, `packageWindowsAppImage`, `prepareMsixInputs` |
| Docker Compose rebuild | PASS | clean volumes, default Phase 1 profile, all services healthy |
| Mandatory HTTP/WebSocket black-box E2E | PASS | registration, Android dev attestation, Windows link, escrow send/decrypt, media, edit, read and delete |
| Local send-to-ack gate | PASS | 7/7 successful samples in the server `le="0.8"` bucket |
| Local online-delivery smoke | PASS | k6: 5/5 sends and WS deliveries; send p95 36.095 ms, WS p95 133.6 ms |
| Outbox/Redis delivery | PASS | zero unpublished rows; exactly seven clean-run stream events |
| Workflow static validation | PASS | all GitHub workflows pass `actionlint` |

The k6 run is a bounded development smoke, not statistical proof for an invited
100-user cohort. It therefore supports implementation readiness but does not
satisfy the alpha-cohort SLO exit criterion by itself.

## Implemented but not externally verified

- Android application target with Play Integrity, Android Keystore and FCM.
- iOS XCFramework/Xcode shell with App Attest, Keychain and APNs boundaries.
- Windows application image with DPAPI storage and the QR link/claim flow.
- Unsigned Android, iOS and MSIX validation workflows.
- Fail-closed credentialed Android, iOS, Windows and server gateway workflows.
- Exact protected-environment variables and secrets in
  [release-gates.md](../07-operations/release-gates.md).

Production source does not fall back to development HMAC attestation or generated
push/signing credentials. Kodium remains pinned to `1.0.0`. LiveKit and every
other Phase 2 service remain outside the default stack and this review.

## Blocking evidence still required

1. Configure a GitHub remote and protected `android-release`, `ios-release`,
   `windows-release` and `server-production` environments. The current local
   repository has no Git remote, so hosted workflows cannot be dispatched.
2. Supply real Apple, Google, push-gateway and signing credentials and pass
   `client-credentialed-release.yml` plus `server-credential-gate.yml`.
3. Pass `client-platform-validation.yml` on Linux, macOS/Xcode and Windows SDK
   runners. Locally there is no Android SDK, macOS/Xcode, or `makeappx.exe`.
4. Produce signed internal AAB, iOS archive and MSIX artifacts and install them
   on representative devices.
5. Complete App Attest key enrollment against the real attestation gateway and
   verify Play Integrity, FCM and APNs end to end. The repository currently
   verifies only fail-closed adapters and the development gateway path.
6. Run the invited-cohort single-region alpha and retain SLO evidence for up to
   100 users. The current five-sample local smoke is intentionally not promoted
   to cohort evidence.
7. Complete native acceptance journeys. Current platform shells wire trust,
   storage, push and Windows linking, but do not yet constitute the full
   end-user messaging UI required for an invited cohort.

## Exit decision rule

Change the decision to **EXITED** only after every item above has an immutable
artifact or CI/deployment link and the Phase 1 roadmap gates are checked. A green
local development stack is necessary but is not sufficient for Phase 1 exit.
