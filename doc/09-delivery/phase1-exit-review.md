# Phase 1 exit review

Review date: 2026-07-24
Baseline: `870849e`
Hosted-CI commit: `159e2ad`
Decision: **BLOCKED**

Phase 1 is not declared exited. Repository-controlled server, contract, migration,
JVM, Compose, black-box messaging and bounded local SLO gates passed locally.
After the GitHub remote was configured, the first hosted run exposed reproducible
CI/platform failures. Their fixes and hybrid notification delivery now pass both
local verification and the hosted matrix. The full messaging UI is still
incomplete.

## Current development posture

- `main` at `e384fbb` tracks `timaNC/main`; `git push --dry-run timaNC main`
  succeeds, so the earlier no-remote blocker is closed.
- Development continues without Apple Developer or Google Play Console accounts
  under [credential-free-development.md](../07-operations/credential-free-development.md).
- FCM/APNs remain optional future vendor channels. Phase 1 adds a repository-owned
  gateway, Android UnifiedPush and common WS/REST catch-up.
- Signed artifacts, real vendor attestation/push and invited-cohort evidence are
  deferred to Phase 5 and are not claimed as complete.
- Platform shells are integration boundaries, not a complete end-user messaging UI.
- Phase 2 and LiveKit work do not start while this review remains **BLOCKED**.

## Verified gates

| Gate | Result | Evidence |
|---|---|---|
| OpenAPI, JSON Schema and Protobuf validation | PASS | `schema/codegen/validate.ps1` |
| Generated source reproducibility and compilation (local) | PASS | `generate.ps1`, zero local `gen/` drift, `compile.ps1` |
| Phase 1 OpenAPI route coverage | PASS | 32 operations checked by `check_phase1_routes.py` |
| Go formatting, vet, unit tests and builds | PASS | `gofmt -l`, `go vet ./...`, `go test ./...`, `go build ./...` |
| PostgreSQL/Redis integration tests | PASS | `go test -tags=integration ./internal/phase1 ./internal/realtime` |
| Migrations 000005–000007 | PASS | `verify_phase1.sql`, including trust objects, attestation backfill, monotonic read state and hybrid push routing |
| KMP JVM tests | PASS | `gradle jvmTest` |
| iOS Kotlin targets | PASS | x64, arm64 and simulator-arm64 compilation |
| Windows JVM package input | PASS | tests, `packageWindowsAppImage`, `prepareMsixInputs` |
| Docker Compose rebuild | PASS | clean volumes, default Phase 1 profile, all services healthy |
| Hybrid notification routing | PASS | Go gateway/unit/integration tests, Android FCM + UnifiedPush registration, primary/fallback worker tests and common wake-to-sync tests |
| Mandatory HTTP/WebSocket black-box E2E | PASS | registration, Android dev attestation, Windows link, escrow send/decrypt, media, edit, read and delete |
| Local send-to-ack gate | PASS | 7/7 successful samples in the server `le="0.8"` bucket |
| Local online-delivery smoke | PASS | k6: 5/5 sends and WS deliveries; send p95 36.095 ms, WS p95 133.6 ms |
| Outbox/Redis delivery | PASS | zero unpublished rows; exactly seven clean-run stream events |
| Workflow static validation | PASS | all GitHub workflows pass `actionlint` |

The k6 run is a bounded development smoke, not statistical proof for an invited
100-user cohort. It therefore supports implementation readiness but does not
satisfy the deferred Phase 5 cohort gate by itself.

## Current hosted CI rerun

| Workflow | Result | Evidence |
|---|---|---|
| Phase 1 contract gate | PASS | [run 30076152784](https://github.com/vbymrf/timaNC/actions/runs/30076152784) |
| Phase 1 Server | PASS | [run 30076152779](https://github.com/vbymrf/timaNC/actions/runs/30076152779) |
| Phase 1 Dev Stack | PASS | [run 30076152839](https://github.com/vbymrf/timaNC/actions/runs/30076152839) |
| Client platform validation | PASS | [run 30076152813](https://github.com/vbymrf/timaNC/actions/runs/30076152813) |
| Phase 0 Contracts | PASS | [run 30076152829](https://github.com/vbymrf/timaNC/actions/runs/30076152829) |

This rerun closes the repository-controlled hosted CI blocker.

## Previous hosted CI run at `e384fbb`

| Workflow | Result | Evidence |
|---|---|---|
| Phase 1 contract gate | PASS | [run 30071068107](https://github.com/vbymrf/timaNC/actions/runs/30071068107) |
| Phase 1 Server | FAIL | [run 30071068103](https://github.com/vbymrf/timaNC/actions/runs/30071068103): PostgreSQL migration job |
| Phase 1 Dev Stack | FAIL | [run 30071068081](https://github.com/vbymrf/timaNC/actions/runs/30071068081): bounded k6 smoke/summary |
| Client platform validation | FAIL | [run 30071068088](https://github.com/vbymrf/timaNC/actions/runs/30071068088): Android, iOS and Windows packaging |
| Phase 0 Contracts | FAIL | [run 30071068104](https://github.com/vbymrf/timaNC/actions/runs/30071068104): generated-file drift on Windows |

These historical failures were repository-controlled and are superseded by the
green rerun above.

## Implemented but not externally verified

- Android application target with Play Integrity, Android Keystore and FCM.
- iOS XCFramework/Xcode shell with App Attest, Keychain and APNs boundaries.
- Windows application image with DPAPI storage and the QR link/claim flow.
- Unsigned Android, iOS and MSIX validation workflows.
- Fail-closed credentialed Android, iOS, Windows and server gateway workflows.
- Exact protected-environment variables and secrets in
  [release-gates.md](../07-operations/release-gates.md).
- Repository-owned Go `push-gateway`, simultaneous Android FCM/UnifiedPush
  registration, soft-fail fallback routing, common wake-to-sync behavior and the
  architecture in
  [hybrid-notification-delivery.md](../02-architecture/hybrid-notification-delivery.md).

Production source does not fall back to development HMAC attestation or generated
push/signing credentials. Kodium remains pinned to `1.0.0`. LiveKit and every
other Phase 2 service remain outside the default stack and this review.

## Current Phase 1 blockers

1. Complete native acceptance journeys. Current platform shells wire trust,
   storage, push and Windows linking, but do not yet constitute the full
   end-user messaging UI.
2. Persist send/retry operations and idempotency material in a durable client
   outbox. The encrypted SQLDelight UI cache survives restart, but coordinator
   `PendingSend` state remains memory-only and must not be described as durable
   retry.
3. Complete encrypted media UI journeys and retain hosted Android/iOS/Windows
   native evidence.

## Deferred Phase 5 evidence

The following remains required before Closed Beta/RC, but does not block
credential-free repository development:

1. Configure protected `android-release`, `ios-release`, `windows-release` and
   `server-production` environments.
2. Supply real signing and vendor credentials and pass credentialed workflows.
3. Produce signed AAB, iOS archive and MSIX artifacts and install them on
   representative devices.
4. Complete App Attest enrollment and verify Play Integrity, FCM and APNs end
   to end.
5. Run the invited cohort up to 100 users and retain immutable SLO evidence.

## Exit decision rule

Change the decision to **EXITED** only after every item under **Current Phase 1
blockers** has an immutable artifact or CI link and the revised Phase 1 roadmap
gates are checked. Deferred Phase 5 items must remain explicitly open and may
not be reported as completed. A green local development stack is necessary but
is not sufficient for Phase 1 exit.
