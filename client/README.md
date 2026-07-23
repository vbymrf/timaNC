# MessNC Phase 1 KMP client foundation

Modules:

- `modules/core/core-domain` — transport-independent DocumentV2/envelope/key models.
- `modules/core/core-network` — isolated boundary for generated Wire/OpenAPI adapters.
- `modules/core/core-database` — SQLDelight identity, revision, media queue and persistent outbox storage.
- `modules/core/core-sync` — crash recovery, idempotent outbox delivery and bounded retry scheduling.
- `modules/core/core-sdk` — transport-independent `Message`, `MessageRevision`, `MediaAsset`,
  `DocumentV2` and typed SDK errors.
- `modules/messenger-crypto` — Kodium-backed identities, commitments, text encryption,
  Path-B wraps, canonical signatures, signed escrow config verification, and hybrid escrow.
- `integration-harness` — in-process crypto coverage plus a compose-backed JVM HTTP/WS
  roundtrip with mobile attestation, Windows QR linking, revisions and dual media.
- `apps/android` — minimal Android shell, Play Integrity Standard requests, Android
  Keystore credential storage, and lifecycle-backed Firebase Messaging tokens.
- `apps/ios` — Kotlin/Native framework plus a minimal SwiftUI Xcode app, App Attest
  key/assertion lifecycle, Keychain credential storage, and APNs delegate adapter.
- `apps/windows` — minimal Swing QR-link shell, DPAPI credential storage, `jpackage`
  app image, and unsigned MSIX staging/packaging inputs.

`eu.livotov.labs:kodium:1.0.0` is pinned in `gradle/libs.versions.toml`. No local Kodium
source is compiled into this build.

Canonical bytes implement `schema/canonical/README.md`: exact NUL-terminated domains,
big-endian integer widths, network-order UUID bytes, optional/list/byte encodings, restricted
RFC 8785 metadata JSON, canonical escrow blobs/configs, and the personal-envelope field order.
Path-B wrapped-key records are intentionally not part of the personal signature input.

Kodium SecretBox does not expose an AAD parameter. The client produces the exact normative
`tima/document-aad/v2` node bytes, then uses those bytes in
`HKDF-SHA256(message_key, info = "tima/document-v2/node-key/v1" || node_aad)` and encrypts with
the derived key. This derived-node-key construction cryptographically binds the canonical AAD
but is an explicit client profile, not a claim that Kodium performs the normative AEAD-AAD
operation directly. Interoperability must standardize this profile or move to an AEAD API with
native AAD before protocol freeze.

Escrow is fail-closed. `HybridKodiumEscrowBlobBuilder` implements ML-KEM-768 plus ephemeral
X25519; `FailClosedEscrowBlobBuilder` blocks private send when escrow is unavailable. The only
fake escrow builder is under integration test sources.

`RestCryptoTransportAdapter` maps signed envelopes to the current `PrivateMessageWrite` REST
projection. Reservation IDs and signing/routing IDs remain top-level; the nested document contains
only DocumentV2 and envelope crypto fields. Binary values use canonical padded RFC 4648 Base64,
message IDs use positive decimal PostgreSQL BIGINT strings, and wrapped-key payloads are the
ephemeral X25519 public key followed by the Kodium Box ciphertext. Private history includes the
sender device, message-key ID, and nullable parent revision, so revision-1 responses reconstruct a
complete signed `PersonalMessageEnvelope`.

The server development escrow root (`dev-ed25519-1`) is pinned only in test sources as a public
Ed25519 key. No private development signing material is included in the client tree.

Platform trust fails closed. Android requires `tima.android.baseUrl` and
`tima.android.integrityProjectNumber` Gradle properties plus host Firebase initialization; the
adapter calls Google Play Integrity and FCM and never generates substitute tokens. iOS APNs tokens
must arrive through `UIApplicationDelegate`. App Attest one-time key enrollment is exposed
separately from recurring assertions because the current Phase 1 OpenAPI schema has no endpoint
for the Apple attestation object; the host must enroll it and call `markEnrolled` before assertions
are allowed. This limitation is deliberate rather than silently treating an assertion as enrollment.

Run from this directory with a Gradle 8.14+ installation. Existing JVM tests remain:

```text
gradle jvmTest
```

Platform build tasks:

```text
# Android SDK required; debug uses the local Android debug signing identity.
gradle :apps:android:assembleDebug \
  -Ptima.android.baseUrl=https://api.example.com \
  -Ptima.android.integrityProjectNumber=123456789

# macOS/Xcode required. Gradle builds the XCFramework; Xcode builds an unsigned simulator app.
gradle :apps:ios:assembleTimaIosAppDebugXCFramework
xcodebuild -project apps/ios/xcode/Tima.xcodeproj -scheme Tima \
  -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO

# Windows JDK with jpackage. Set the API endpoint before running the app.
set TIMA_API_BASE_URL=https://api.example.com
gradle :apps:windows:packageWindowsAppImage

# Windows SDK makeappx.exe required; output is intentionally unsigned.
gradle :apps:windows:packageMsixUnsigned
```

Android release signing, iOS device signing/provisioning, production App Attest enrollment,
Firebase configuration, APNs entitlements, and MSIX signing certificates are CI/deployment
inputs and are not committed. `packageMsixUnsigned` creates an artifact suitable for later CI
signing; Windows will not trust-install it as a release package until it is signed.

The HTTP roundtrip is skipped in ordinary local unit runs. To require it against the development
stack:

```text
TIMA_E2E_BASE_URL=http://localhost:8080 TIMA_REQUIRE_HTTP_E2E=true gradle jvmTest
```
