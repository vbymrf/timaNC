# TIMA canonical encoding v1

This directory is normative for cryptographic input bytes. Protobuf is the
typed transport contract only: protobuf serialization, deterministic or
otherwise, is never a signature, MAC, commitment, KDF, or AEAD-AAD input.
Unknown protobuf fields, map ordering, default elision, and implementation
differences therefore cannot change cryptographic bytes.

## Primitive encoding

All concatenation below is byte concatenation. Encoders MUST reject values
that violate a stated size/range before producing bytes.

- `U8`, `U32`, and `U64`: unsigned big-endian integers of exactly 1, 4, and
  8 bytes. Enums use `U32`; booleans use `U8(0|1)`.
- `UUID`: exactly 16 RFC 4122 network-order bytes. UUID text never enters a
  canonical input.
- `FIXED32`: exactly 32 bytes, with no length prefix.
- `B(value)`: `U32(byte_length) || value`. Strings are shortest-form UTF-8,
  without BOM or Unicode normalization. Protocol identifiers are ASCII.
- `J(value)`: `B(RFC8785(value))`. RFC 8785/JCS applies recursively, including
  ECMAScript number formatting, lexicographic property ordering, and rejection
  of duplicate keys, lone surrogates, NaN and infinities.
- `TIME`: `U64(unix_seconds) || U32(nanos)` in UTC; nanos is 0..999999999.
- `LIST(values)`: `U32(count) || canonical(value[0]) || ...`. Ordering is
  semantic and MUST NOT be sorted unless the contract explicitly says so.
- `OPTIONAL(x)`: absent is the single deterministic marker byte `00`; present
  is `01 || canonical(x)`. No other marker is valid.
- Every top-level input starts with the exact ASCII domain string followed by
  one `00` byte. The terminator is part of the domain separation.

Optional `null`, empty arrays, empty objects, and empty ciphertexts normalize
to absent before encoding. A present collection MUST have at least one
non-empty item. `metadata` is never optional.

## DocumentV2 and presence

`presence_bitmap` is a required `U32`, not a computed protobuf/JSON projection.
Only the low four bits may be set, in this fixed order:

1. bit 0 — `encrypted_nodes`;
2. bit 1 — `nodes`;
3. bit 2 — `markup`;
4. bit 3 — `encrypted_metadata`.

The bitmap MUST exactly agree with field presence after normalization. Private
1:1 documents require bit 1 to be zero. Group documents cannot set bits 0 and
1 together. For every nullable field, canonical input contains both the bitmap
and its `OPTIONAL` encoding; this deliberate redundancy detects ambiguous or
non-canonical producers.

Canonical metadata is the RFC 8785 object with keys `content_mode`,
`format_version`, `revision_number`, plus allowlisted extension keys. Extension
keys MUST NOT replace those three names. `protocol_version` and
`metadata.format_version` are distinct required values; current values are
`(2,2)` and MUST migrate in lockstep. Mixed or unsupported pairs are rejected.

The canonical document body is:

```text
U32(presence_bitmap)
|| OPTIONAL(LIST(B(encrypted_node)))
|| OPTIONAL(LIST(B(UTF8 plaintext_node)))
|| OPTIONAL(J(markup))
|| J(metadata)
|| OPTIONAL(B(encrypted_metadata))
```

## Domain-separated inputs and field order

No field tags or names are encoded. The following order is fixed and must not
be inferred from protobuf field numbers.

`tima/personal-envelope/signature/v2`:

```text
U32(protocol_version) || U64(message_id)
|| UUID(revision_id) || OPTIONAL(UUID(parent_revision_id))
|| U64(revision_number) || UUID(chat_id) || UUID(sender_id)
|| UUID(sender_device_id) || document
|| U32(message_key_id) || FIXED32(key_commitment)
|| escrow_blob || OPTIONAL(B(ratchet_envelope))
|| LIST(media_binding)
```

`tima/group-envelope/signature/v2` uses:

```text
U32(protocol_version) || UUID(group_id) || U64(message_id)
|| UUID(revision_id) || OPTIONAL(UUID(parent_revision_id))
|| U64(revision_number) || UUID(sender_id) || UUID(sender_device_id)
|| U32(group_key_version) || document || FIXED32(key_commitment)
|| escrow_blob || LIST(media_binding)
```

A media binding is `UUID(media_id) || U32(variant) ||
FIXED32(ciphertext_sha256) || U64(ciphertext_size) || B(mime_type)`.
Variant values are thumbnail=1, preview=2, full=3; original is invalid.

`tima/escrow-blob/v1` uses:

```text
U32(region) || B(epoch_id) || U32(shard_id) || B(key_id)
|| FIXED32(key_commitment) || FIXED32(ephemeral_x25519_public_key)
|| B(mlkem768_ciphertext) || B(symmetric_key_wrap)
```

The `escrow_blob` token in envelope inputs is
`B(canonical tima/escrow-blob/v1 bytes)`.

`tima/escrow-config/signature/v1` uses:

```text
U32(config_version) || U32(region) || B(epoch_id) || U32(shard_id)
|| TIME(valid_from) || TIME(valid_until)
|| key_set(current) || OPTIONAL(key_set(next)) || B(signing_key_id)
```

A key set is `B(key_id) || FIXED32(x25519_threshold_public_key) ||
B(mlkem768_public_key)`. The bundle signature itself is excluded.

Other signed records use the following exact domains and orders. Each domain
has the required trailing `00` separator.

- `tima/tombstone/signature/v2`: `UUID(owner_id) || U64(object_id) ||
  UUID(revision_id) || OPTIONAL(UUID(parent_revision_id)) ||
  U64(revision_number) || TIME(deleted_at)`.
- `tima/recovery-response/signature/v1`: `UUID(request_id) ||
  UUID(responder_device_id) || U32(decision)`.
- `tima/recovery-transfer/signature/v1`: `UUID(request_id) || UUID(chat_id) ||
  UUID(requester_device_id) || UUID(responder_device_id) ||
  LIST(recovery_chunk) || B(requester_proof)`.
- `tima/phrase-backup/signature/v1`: `UUID(owner_id) ||
  U32(backup_version) || LIST(phrase_backup_key)`.
- `tima/private-shelf/signature/v1`: `UUID(owner_id) || U32(key_version) ||
  B(encrypted_payload) || B(escrow_blob) || FIXED32(key_commitment)`.
- `tima/shelf-key-wrap/signature/v1`: `UUID(owner_id) || UUID(grantee_id) ||
  UUID(grantee_device_id) || U32(key_version) || B(wrapped_shelf_key) ||
  FIXED32(key_commitment)`.

A recovery chunk is `B(signed_revision) || B(rewrapped_payload) ||
FIXED32(key_commitment)`. A phrase-backup key is `UUID(chat_id) ||
U64(message_id) || B(phrase_wrapped_key) || FIXED32(key_commitment)`.
`B(escrow_blob)` contains its complete domain-separated canonical bytes.

A nested `SignedRevision` is `U32(choice) || B(revision)`, where choice is
personal=1, group=2, tombstone=3. The nested revision contains its complete
domain-separated canonical signature input followed by `B(signature)`.

Wrapped delivery records are bound with:

- `tima/wrapped-message-key/v1`: `U64(message_id) || UUID(chat_id) ||
  UUID(device_id) || B(wrapped_key) || FIXED32(key_commitment)`.
- `tima/wrapped-group-key/v1`: `UUID(group_id) || U32(group_key_version) ||
  UUID(device_id) || B(wrapped_group_key) || FIXED32(key_commitment)`.
- `tima/ratchet-payload/v1`: `B(message_key) || FIXED32(key_commitment) ||
  OPTIONAL(B(encrypted_nodes_bundle)) ||
  OPTIONAL(B(encrypted_metadata)) || U32(presence_bitmap)`.

AEAD AAD starts with `tima/document-aad/v2` and contains
`U32(protocol_version) || FIXED32(key_commitment) || document headers`, where
document headers are the bitmap, optional canonical markup/null marker, and
canonical metadata. Node AAD appends `U32(node_index)`; encrypted-metadata AAD
appends ASCII `encrypted_metadata` with no terminator. A nonce MUST be unique
for each ciphertext under one key.

## Key commitment and verification

For every message key, group key, shelf key, recovery key, phrase-backup key,
participant wrap, ratchet payload, and escrow path:

```text
key_commitment = HKDF-SHA256(
  IKM = key,
  salt = 32 zero bytes,
  info = ASCII("tima/commit/v1"),
  L = 32
)
```

Absence is invalid. Every decrypt path recomputes and constant-time compares
the commitment before accepting plaintext. All paths for one object/version
must recover the same key. A mismatch is `commitment_mismatch`, never fallback
success. Ed25519 verification MUST reject non-canonical `S >= L`; signature
bytes are never identity or deduplication keys.

## WebSocket transport

The required WebSocket subprotocol is `tima.pb.v1`. Each data frame is binary
and contains exactly one `ClientFrame` or `ServerFrame`. Event identity comes
only from the protobuf `oneof`; there is no string event discriminator and no
JSON fallback. Text frames are closed with WebSocket code 1003. Native
WebSocket ping/pong is sent every 30 seconds and is not a protobuf event.

## Fixtures

`fixtures/golden-simple.*` exercises domain separation, integer widths,
length-prefixing, absent/present markers, bitmap ordering, and HKDF commitment
with values simple enough to recompute independently.

`fixtures/tamper-simple.*` changes only the final bitmap byte from `05` to
`04` and separately changes byte 0 of the test key from `00` to `ff`. Both
must fail comparison against the golden signature input/commitment.
