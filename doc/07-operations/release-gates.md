# Phase 1 release gates

Current development posture without store accounts:
[credential-free-development.md](./credential-free-development.md).

The repository has two deliberately separate paths:

- `client-platform-validation.yml` builds an unsigned Android AAB, an unsigned iOS
  simulator app plus Kotlin XCFramework, and an unsigned Windows MSIX. It runs without
  credentials on pull requests, `main`, and manual dispatch.
- `client-credentialed-release.yml` is manual only. Its three jobs always run, target
  protected GitHub environments, validate every required value before decoding credentials,
  sign the package, verify the signature, and upload the signed candidate. There is no
  input that silently skips a platform gate.
- `server-credential-gate.yml` is manual only and validates the production attestation and
  push contract in the protected `server-production` environment.

Repository administrators must create `android-release`, `ios-release`,
`windows-release`, and `server-production` as protected GitHub environments with required
reviewers. Environment protection is repository configuration and cannot be established by
workflow YAML.

## Credential-free hybrid notifications

The target Phase 1 stack includes a repository-owned Go `push-gateway`.
Its `unifiedpush` path must start and pass health checks without Apple/Google
credentials. FCM and APNs are optional adapters enabled only when their
environment contract is complete.

Credential-free behavior:

- Android uses UnifiedPush plus foreground WebSocket/REST catch-up.
- Windows uses WebSocket and periodic REST catch-up without WNS.
- iOS uses foreground/resume catch-up; reliable background wake remains
  unavailable without APNs entitlement.
- Missing vendor credentials disable only the matching adapter and must not
  cause placeholder credentials or development fallback in production.

The full channel selection, privacy and degradation contract is defined in
[hybrid-notification-delivery.md](../02-architecture/hybrid-notification-delivery.md).
Until the gateway is implemented and its E2E passes, this is target
architecture rather than verified Phase 1 evidence.

## GitHub environment contract

Names below are exact. Values must only be entered in GitHub environment configuration or
the deployment secret store; no value belongs in the repository.

### `android-release`

Environment variables:

- `TIMA_ANDROID_BASE_URL` — production HTTPS API root; mapped to
  `tima.android.baseUrl`.
- `PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER` — decimal Google Cloud project number; mapped to
  `tima.android.integrityProjectNumber`.

Environment secrets:

- `FIREBASE_ANDROID_GOOGLE_SERVICES_JSON_BASE64` — base64 of the Android
  `google-services.json`.
- `ANDROID_SIGNING_KEYSTORE_BASE64` — base64 of the release keystore.
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`
- `ANDROID_SIGNING_STORE_PASSWORD`

The result is a signed internal-distribution AAB artifact. Uploading it to a Play track is
an explicit release-manager action; this workflow does not mutate Play Console state.

### `ios-release`

Environment variables:

- `TIMA_IOS_BASE_URL` — production HTTPS API root.
- `APPLE_TEAM_ID`
- `APPLE_BUNDLE_ID`

Environment secrets:

- `APPLE_IOS_SIGNING_IDENTITY`
- `APPLE_IOS_CERTIFICATE_P12_BASE64`
- `APPLE_IOS_CERTIFICATE_PASSWORD`
- `APPLE_IOS_PROVISIONING_PROFILE_BASE64`

The certificate and profile are placed in an ephemeral keychain/profile directory, used to
produce a signed `.xcarchive`, verified with `codesign`, and removed in an `always()` step.
App Store upload/export is intentionally outside this gate.

### `windows-release`

Environment variables:

- `TIMA_WINDOWS_BASE_URL` — production HTTPS API root.
- `WINDOWS_SIGNING_CERTIFICATE_SHA256` — SHA-256 digest of the exact PFX bytes.
- `WINDOWS_SIGNING_TIMESTAMP_URL` — HTTPS RFC 3161 timestamp service.

Environment secrets:

- `WINDOWS_SIGNING_CERTIFICATE_PFX_BASE64`
- `WINDOWS_SIGNING_CERTIFICATE_PASSWORD`

The workflow verifies the PFX digest, signs the MSIX with `signtool`, verifies the package
signature, and uploads the signed artifact.

### `server-production`

These names cover both the existing server-to-gateway contract and the credentials that
the separately deployed attestation/push gateway needs for vendor APIs.

Environment variables:

- `ATTESTATION_GATEWAY_URL`
- `PUSH_GATEWAY_URL`
- `PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER`
- `APPLE_APP_ATTEST_TEAM_ID`
- `APPLE_APP_ATTEST_KEY_ID`
- `APPLE_APNS_TEAM_ID`
- `APPLE_APNS_KEY_ID`

Environment secrets:

- `ATTESTATION_GATEWAY_TOKEN`
- `PUSH_GATEWAY_TOKEN`
- `PUSH_TOKEN_ENCRYPTION_KEY`
- `FIREBASE_SERVICE_ACCOUNT_JSON_BASE64`
- `APPLE_APP_ATTEST_PRIVATE_KEY_P8_BASE64`
- `APPLE_APNS_PRIVATE_KEY_P8_BASE64`

`ATTESTATION_GATEWAY_URL`, `ATTESTATION_GATEWAY_TOKEN`, `PUSH_GATEWAY_URL`,
`PUSH_GATEWAY_TOKEN`, and `PUSH_TOKEN_ENCRYPTION_KEY` are the exact variables consumed by
the Phase 1 server. Vendor credentials are inputs to the gateway deployment and must not be
injected into client builds.

## Fail-closed preflight

`scripts/release/preflight.py <android|ios|windows|server>` rejects missing, blank, and
obvious placeholder values. It also validates base64, JSON payload shape, HTTPS URLs,
numeric Play project numbers, and the Windows PFX SHA-256 format without printing values.
Credentialed jobs call it unconditionally before materializing any file.
