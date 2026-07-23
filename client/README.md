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
- `modules/platform/platform-core` — fail-closed attestation/push orchestration and Windows
  one-time QR claim boundaries.
- `apps/android` — minimal API 26+ app/AAB target with Play Integrity, Android Keystore and
  FCM host lifecycle adapters.
- `apps/ios-framework` + `apps/ios` — static XCFramework and XcodeGen iOS 15 app project with
  App Attest, this-device-only Keychain storage and APNs lifecycle adapters.
- `apps/windows` — minimal JVM/Swing target with DPAPI storage, phone-anchored QR linking and
  local unsigned MSIX packaging inputs.
- `integration-harness` — in-process crypto coverage plus a compose-backed JVM HTTP/WS
  roundtrip with mobile attestation, Windows QR linking, revisions and dual media.

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

Run from this directory with a Gradle 8.14+ installation:

```text
gradle jvmTest
```

The HTTP roundtrip is skipped in ordinary local unit runs. To require it against the development
stack:

```text
TIMA_E2E_BASE_URL=http://localhost:8080 TIMA_REQUIRE_HTTP_E2E=true gradle jvmTest
```

Local platform build entry points:

```text
gradle :apps:android:bundleDebug
gradle :apps:ios-framework:assembleMessNcPlatformDebugXCFramework  # macOS only
xcodegen generate --spec apps/ios/project.yml                      # macOS only
gradle :apps:windows:windowsAppImage
gradle :apps:windows:packageUnsignedMsix                            # requires makeappx.exe
```

Android Play Integrity requires `TIMA_PLAY_CLOUD_PROJECT_NUMBER`; FCM requires
`TIMA_FIREBASE_PROJECT_ID`, `TIMA_FIREBASE_APPLICATION_ID`, and `TIMA_FIREBASE_SENDER_ID`.
The Windows app requires an HTTPS `TIMA_API_BASE_URL`. Missing vendor configuration fails
closed; production code has no development-HMAC fallback. Android/iOS store signing and MSIX
certificate signing remain external release steps.
