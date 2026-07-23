# MessNC minimal KMP/JVM client slice

Modules:

- `modules/core/core-domain` — transport-independent DocumentV2/envelope/key models.
- `modules/core/core-network` — isolated boundary for generated Wire/OpenAPI adapters.
- `modules/messenger-crypto` — Kodium-backed identities, commitments, text encryption,
  Path-B wraps, canonical signatures, signed escrow config verification, and hybrid escrow.
- `integration-harness` — JVM two-logical-device roundtrip.

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
ephemeral X25519 public key followed by the Kodium Box ciphertext. History mapping deliberately
returns a partial crypto projection because the REST history shape does not contain enough signed
headers to reconstruct a `PersonalMessageEnvelope`.

The server development escrow root (`dev-ed25519-1`) is pinned only in test sources as a public
Ed25519 key. No private development signing material is included in the client tree.

Run from this directory with a Gradle 8.14+ installation:

```text
gradle jvmTest
```
