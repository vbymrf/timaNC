# Phase 1 exit review

Review date: 2026-07-26
Baseline: `870849e`
Hosted-CI commit: `159e2ad`
Native-media hosted commit: `2ceaefe`
Native-acceptance commit: `f17548e`
Decision: **EXITED WITH NON-BLOCKING DEFERRED iOS EXCEPTION**

Phase 1 is declared exited for continued repository development under an
explicit non-blocking iOS exception. Repository-controlled server, contract,
migration, JVM, Compose, black-box messaging and bounded local SLO gates passed locally.
After the GitHub remote was configured, the first hosted run exposed reproducible
CI/platform failures. Their fixes and hybrid notification delivery now pass both
local verification and the hosted matrix. Android and Windows native journeys
pass on `f17548e`. The iOS native journey is **NOT RUN / DEFERRED** and will be
executed later on a separate Mac with Xcode.

## Current development posture

- `main` through `6b22a50` tracks `timaNC/main`; authenticated pushes and hosted
  workflow queries succeed. The earlier no-remote/no-API-access blocker is
  closed.
- Development continues without Apple Developer or Google Play Console accounts
  under [credential-free-development.md](../07-operations/credential-free-development.md).
- FCM/APNs remain optional future vendor channels. Phase 1 adds a repository-owned
  gateway, Android UnifiedPush and common WS/REST catch-up.
- Signed artifacts, real vendor attestation/push and invited-cohort evidence are
  deferred to Phase 5 and are not claimed as complete.
- Platform shells are integration boundaries, not a complete end-user messaging UI.
- Phase 2 and LiveKit work may start. iOS-native Phase 1 acceptance remains
  deferred and must not be reported as passed.

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
| iOS native journey | DEFERRED | Not run; reserved for a separate macOS/Xcode computer. Hosted build evidence is not native acceptance. |
| Windows JVM package input | PASS | tests, `packageWindowsAppImage`, `prepareMsixInputs` |
| Android native journey | PASS | [Android `f17548e` manifest](../08-quality/evidence/android-f17548e/manifest.md), steps 1–9 |
| Windows native journey | PASS | [Windows `f17548e` manifest](../08-quality/evidence/windows-f17548e/manifest.md), steps 1–9 |
| Docker Compose rebuild | PASS | clean volumes, default Phase 1 profile, all services healthy |
| Hybrid notification routing | PASS | Go gateway/unit/integration tests, Android FCM + UnifiedPush registration, primary/fallback worker tests and common wake-to-sync tests |
| Client package/unit evidence with encrypted media | PASS | Live-verified [run 30150128033](https://github.com/vbymrf/timaNC/actions/runs/30150128033) on `f17548e`: 3/3 jobs passed for Android AAB, iOS XCFramework + unsigned simulator app and Windows MSIX |
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
| Client platform validation on native-acceptance SHA | PASS | Live-verified [run 30150128033](https://github.com/vbymrf/timaNC/actions/runs/30150128033), `f17548e`, 3/3 jobs |
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
- Secure shared private-image alpha with exactly three independently keyed
  ciphertext variants, encrypted restart queue and media-only DocumentV2;
  Android/iOS/Windows picker, normalization, progress, thumbnail and in-app
  preview compile in the hosted platform matrix. This is not three-platform
  journey evidence.

Production source does not fall back to development HMAC attestation or generated
push/signing credentials. Kodium remains pinned to `1.0.0`. LiveKit and every
other Phase 2 service remain outside the default stack and this review.

## Non-blocking deferred iOS exception

Later, on a separate Mac with Xcode, complete the deferred iOS journey on
`f17548e` and retain the manifest required by
[phase1-native-acceptance.md](../08-quality/phase1-native-acceptance.md).
Android and Windows have reviewed PASS manifests on that commit; the
three-platform evidence set remains incomplete. Current iOS status is **NOT RUN
/ DEFERRED**, not failed and not passed. This exception does not block Phase 2
repository development, but it blocks any claim that three-platform native
acceptance is complete.

The iOS PHPicker/UIKit normalization and SwiftUI render bridge now pass a real
hosted Xcode Swift simulator build and packaging step in live-verified run
`30150128033`.
Signed device archive/install remains a deferred Phase 5 credentialed gate.

Android has retained reviewed
[PASS evidence on `f17548e`](../08-quality/evidence/android-f17548e/manifest.md)
for secure session restore, peer text exchange, forced durable-outbox recovery,
edit/read/delete restrictions, three-variant private media, peer decrypt and
preview, offline cache and logout wipe.

Windows has retained reviewed
[PASS evidence on `f17548e`](../08-quality/evidence/windows-f17548e/manifest.md)
for DPAPI session restore, peer text exchange, a post-encryption transport fault
and restart recovery, edit/read/delete restrictions, private media upload and
peer preview, encrypted offline cache and logout wipe.

iOS native acceptance is deliberately deferred to another computer: a Mac with
Xcode or a representative iOS device capable of executing and retaining the
same native steps 1–9. Hosted simulator build/package evidence does not replace
that journey.

The durable post-encryption send/retry item is complete. Android, iOS and
Windows persist the exact canonical ciphertext body, message/revision identity
and idempotency key before the first send; stale sends recover on restore and
wake/catch-up, terminal failures require an explicit manual retry, and logout
wipes the outbox with the encrypted UI cache. Reservation and encryption still
precede this durable boundary, so composing/sending while fully offline before
reservation remains unsupported.

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

The approved decision is **EXITED WITH NON-BLOCKING DEFERRED iOS EXCEPTION**.
Phase 2 repository development may proceed. The decision may be simplified to
unqualified **EXITED** only after the iOS steps 1–9 have an immutable manifest on
the accepted commit. Deferred iOS and Phase 5 items must remain explicitly open
and may not be reported as completed.
