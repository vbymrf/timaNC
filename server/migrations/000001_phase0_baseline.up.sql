-- Phase 0 core schema baseline for PostgreSQL 16.
-- This migration is intentionally forward-only and atomic.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE FUNCTION document_metadata_valid(value JSONB, expected_mode TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
  SELECT coalesce(
    value IS NOT NULL
    AND value->>'format_version' = '2'
    AND value->>'revision_number' ~ '^[1-9][0-9]*$'
    AND expected_mode IN ('private', 'public')
    AND value->>'content_mode' = expected_mode,
    FALSE
  )
$$;

CREATE FUNCTION document_markup_has_content(value JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
  SELECT value IS NOT NULL AND (
    jsonb_path_exists(value, '$.entities[*] ? (@.type == "media" && exists(@.media_id))')
    OR jsonb_path_exists(value, '$.layout.** ? (@.type == "media" && exists(@.media_id))')
  )
$$;

CREATE FUNCTION document_markup_has_secret_refs(value JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
  SELECT value IS NOT NULL
    AND jsonb_path_exists(value, '$.** ? (exists(@.secret_ref))')
$$;

CREATE FUNCTION document_cipher_nodes_valid(value BYTEA[])
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
  SELECT value IS NULL OR (
    cardinality(value) > 0
    AND NOT EXISTS (
      SELECT 1
      FROM unnest(value) AS t(node)
      WHERE node IS NULL OR octet_length(node) = 0
    )
  )
$$;

CREATE FUNCTION reject_row_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION '% is append-only: % is forbidden', TG_TABLE_NAME, TG_OP
    USING ERRCODE = '55000';
END
$$;

CREATE TABLE users (
  user_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_type        TEXT NOT NULL DEFAULT 'human',
  owner_user_id       UUID REFERENCES users(user_id),
  account_home_region TEXT NOT NULL,
  phone_hash          BYTEA,
  email               TEXT UNIQUE,
  username            TEXT UNIQUE,
  display_name        TEXT NOT NULL,
  avatar_media_id     UUID,
  bio                  TEXT,
  invisible_mode      BOOLEAN NOT NULL DEFAULT FALSE,
  last_active_at      TIMESTAMPTZ,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at          TIMESTAMPTZ,
  blocked_at          TIMESTAMPTZ,
  CONSTRAINT users_account_type_check
    CHECK (account_type IN ('human', 'temporary', 'virtual')),
  CONSTRAINT users_home_region_check
    CHECK (account_home_region IN ('RU', 'EU')),
  CONSTRAINT users_virtual_no_credentials CHECK (
    account_type = 'human'
    OR (
      phone_hash IS NULL
      AND (email IS NULL OR account_type = 'temporary')
      AND owner_user_id IS NOT NULL
    )
  )
);

CREATE TABLE devices (
  device_id       UUID PRIMARY KEY,
  user_id         UUID NOT NULL REFERENCES users(user_id),
  platform        TEXT NOT NULL,
  identity_pubkey BYTEA NOT NULL,
  signing_pubkey  BYTEA NOT NULL,
  is_trust_anchor BOOLEAN NOT NULL DEFAULT FALSE,
  attestation_ok  BOOLEAN NOT NULL DEFAULT FALSE,
  trust_via_phone BOOLEAN NOT NULL DEFAULT FALSE,
  push_token      TEXT,
  last_seen       TIMESTAMPTZ,
  revoked_at      TIMESTAMPTZ,
  CONSTRAINT devices_platform_check
    CHECK (platform IN ('android', 'ios', 'windows')),
  CONSTRAINT devices_identity_pubkey_nonempty
    CHECK (octet_length(identity_pubkey) > 0),
  CONSTRAINT devices_signing_pubkey_nonempty
    CHECK (octet_length(signing_pubkey) > 0),
  UNIQUE (device_id, user_id)
);

CREATE TABLE prekeys (
  device_id  UUID NOT NULL REFERENCES devices(device_id),
  key_id     INT NOT NULL,
  kind       TEXT NOT NULL,
  public_key BYTEA NOT NULL,
  signature  BYTEA,
  consumed_at TIMESTAMPTZ,
  PRIMARY KEY (device_id, kind, key_id),
  CONSTRAINT prekeys_kind_check CHECK (kind IN ('signed', 'onetime')),
  CONSTRAINT prekeys_key_id_check CHECK (key_id >= 0),
  CONSTRAINT prekeys_public_key_nonempty CHECK (octet_length(public_key) > 0),
  CONSTRAINT prekeys_signed_signature_check
    CHECK (kind <> 'signed' OR signature IS NOT NULL)
);

CREATE TABLE sessions (
  session_id   UUID PRIMARY KEY,
  device_id    UUID NOT NULL REFERENCES devices(device_id),
  refresh_hash BYTEA NOT NULL,
  expires_at   TIMESTAMPTZ NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT sessions_refresh_hash_nonempty CHECK (octet_length(refresh_hash) > 0),
  CONSTRAINT sessions_expiry_check CHECK (expires_at > created_at)
);

CREATE TABLE chats (
  chat_id                  UUID PRIMARY KEY,
  user_a                   UUID NOT NULL REFERENCES users(user_id),
  user_b                   UUID NOT NULL REFERENCES users(user_id),
  conversation_home_region TEXT NOT NULL,
  ttl_seconds              INT,
  created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chats_home_region_check
    CHECK (conversation_home_region IN ('RU', 'EU')),
  CONSTRAINT chats_distinct_users_check CHECK (user_a <> user_b),
  CONSTRAINT chats_ttl_check CHECK (ttl_seconds IS NULL OR ttl_seconds > 0),
  UNIQUE (user_a, user_b)
);

CREATE TABLE escrow_periods (
  scope_type       TEXT NOT NULL,
  scope_id         UUID NOT NULL,
  period_id        INT NOT NULL,
  escrow_blob      BYTEA NOT NULL,
  protocol_version INT NOT NULL DEFAULT 2,
  key_commitment   BYTEA NOT NULL,
  started_at       TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (scope_type, scope_id, period_id),
  CONSTRAINT escrow_periods_scope_type_check
    CHECK (scope_type IN ('user', 'chat', 'media')),
  CONSTRAINT escrow_periods_period_check CHECK (period_id >= 0),
  CONSTRAINT escrow_periods_blob_nonempty CHECK (octet_length(escrow_blob) > 0),
  CONSTRAINT escrow_periods_protocol_check CHECK (protocol_version = 2),
  CONSTRAINT escrow_periods_commitment_check CHECK (octet_length(key_commitment) = 32)
);

CREATE TABLE personal_messages (
  message_id         BIGINT NOT NULL,
  chat_id            UUID NOT NULL REFERENCES chats(chat_id),
  sender_id          UUID NOT NULL REFERENCES users(user_id),
  sender_device      UUID NOT NULL,
  shard_id           INT NOT NULL DEFAULT 0,
  encrypted_nodes    BYTEA[],
  markup             JSONB,
  encrypted_metadata BYTEA,
  metadata           JSONB NOT NULL,
  format_version     INT NOT NULL DEFAULT 2,
  presence_bitmap    BIGINT NOT NULL,
  key_commitment     BYTEA NOT NULL,
  current_revision_id UUID NOT NULL,
  escrow_blob        BYTEA NOT NULL,
  ratchet_envelope   BYTEA,
  protocol_version   INT NOT NULL DEFAULT 2,
  reply_to           BIGINT,
  signature          BYTEA NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted            BOOLEAN NOT NULL DEFAULT FALSE,
  deleted_at         TIMESTAMPTZ,
  PRIMARY KEY (chat_id, message_id),
  CONSTRAINT personal_messages_sender_device_fk
    FOREIGN KEY (sender_device, sender_id)
    REFERENCES devices(device_id, user_id),
  CONSTRAINT personal_messages_id_check CHECK (message_id >= 0),
  CONSTRAINT personal_messages_phase0_shard_check CHECK (shard_id = 0),
  CONSTRAINT personal_messages_optional_canonical CHECK (
    document_cipher_nodes_valid(encrypted_nodes)
    AND (markup IS NULL OR markup <> '{}'::jsonb)
    AND (encrypted_metadata IS NULL OR octet_length(encrypted_metadata) > 0)
  ),
  CONSTRAINT personal_messages_metadata_check CHECK (
    protocol_version = 2
    AND format_version = 2
    AND metadata->>'format_version' = '2'
    AND document_metadata_valid(metadata, 'private')
  ),
  CONSTRAINT personal_messages_presence_check
    CHECK (presence_bitmap BETWEEN 0 AND 4294967295),
  CONSTRAINT personal_messages_commitment_check
    CHECK (octet_length(key_commitment) = 32),
  CONSTRAINT personal_messages_escrow_check
    CHECK (octet_length(escrow_blob) > 0),
  CONSTRAINT personal_messages_signature_check
    CHECK (octet_length(signature) > 0),
  CONSTRAINT personal_messages_has_content_check CHECK (
    coalesce(cardinality(encrypted_nodes), 0) > 0
    OR document_markup_has_content(markup)
  ),
  CONSTRAINT personal_messages_secrets_check CHECK (
    document_markup_has_secret_refs(markup) = (encrypted_metadata IS NOT NULL)
  ),
  CONSTRAINT personal_messages_deleted_check CHECK (
    (deleted AND deleted_at IS NOT NULL)
    OR (NOT deleted AND deleted_at IS NULL)
  )
) PARTITION BY HASH (chat_id);

CREATE TABLE personal_messages_p0
  PARTITION OF personal_messages
  FOR VALUES WITH (MODULUS 1, REMAINDER 0);

-- Add the self-reference only after the partition exists. PostgreSQL 16 releases
-- before 16.9 had catalog/trigger bugs when a partition was attached after a
-- self-referencing FK had already been defined.
ALTER TABLE personal_messages
  ADD CONSTRAINT personal_messages_reply_fk
  FOREIGN KEY (chat_id, reply_to)
  REFERENCES personal_messages(chat_id, message_id)
  DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE personal_message_revisions (
  chat_id             UUID NOT NULL,
  message_id          BIGINT NOT NULL,
  revision_id         UUID NOT NULL,
  parent_revision_id  UUID,
  revision_number     INT NOT NULL,
  encrypted_nodes     BYTEA[],
  markup              JSONB,
  encrypted_metadata  BYTEA,
  metadata             JSONB NOT NULL,
  protocol_version     INT NOT NULL DEFAULT 2,
  presence_bitmap     BIGINT NOT NULL,
  key_commitment      BYTEA NOT NULL,
  escrow_blob          BYTEA NOT NULL,
  signature            BYTEA NOT NULL,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (chat_id, message_id, revision_id),
  UNIQUE (chat_id, message_id, revision_number),
  CONSTRAINT personal_message_revisions_message_fk
    FOREIGN KEY (chat_id, message_id)
    REFERENCES personal_messages(chat_id, message_id)
    DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT personal_message_revisions_parent_fk
    FOREIGN KEY (chat_id, message_id, parent_revision_id)
    REFERENCES personal_message_revisions(chat_id, message_id, revision_id)
    DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT personal_message_revisions_number_check CHECK (revision_number > 0),
  CONSTRAINT personal_message_revisions_parent_check CHECK (
    (revision_number = 1 AND parent_revision_id IS NULL)
    OR (revision_number > 1 AND parent_revision_id IS NOT NULL)
  ),
  CONSTRAINT personal_message_revisions_optional_canonical CHECK (
    document_cipher_nodes_valid(encrypted_nodes)
    AND (markup IS NULL OR markup <> '{}'::jsonb)
    AND (encrypted_metadata IS NULL OR octet_length(encrypted_metadata) > 0)
  ),
  CONSTRAINT personal_message_revisions_metadata_check CHECK (
    protocol_version = 2
    AND metadata->>'format_version' = '2'
    AND document_metadata_valid(metadata, 'private')
    AND metadata->>'revision_number' = revision_number::TEXT
  ),
  CONSTRAINT personal_message_revisions_presence_check
    CHECK (presence_bitmap BETWEEN 0 AND 4294967295),
  CONSTRAINT personal_message_revisions_commitment_check
    CHECK (octet_length(key_commitment) = 32),
  CONSTRAINT personal_message_revisions_escrow_check
    CHECK (octet_length(escrow_blob) > 0),
  CONSTRAINT personal_message_revisions_signature_check
    CHECK (octet_length(signature) > 0),
  CONSTRAINT personal_message_revisions_has_content_check CHECK (
    coalesce(cardinality(encrypted_nodes), 0) > 0
    OR document_markup_has_content(markup)
  ),
  CONSTRAINT personal_message_revisions_secrets_check CHECK (
    document_markup_has_secret_refs(markup) = (encrypted_metadata IS NOT NULL)
  )
);

ALTER TABLE personal_messages
  ADD CONSTRAINT personal_messages_current_revision_fk
  FOREIGN KEY (chat_id, message_id, current_revision_id)
  REFERENCES personal_message_revisions(chat_id, message_id, revision_id)
  DEFERRABLE INITIALLY DEFERRED;

CREATE TRIGGER personal_message_revisions_append_only
BEFORE UPDATE OR DELETE ON personal_message_revisions
FOR EACH ROW EXECUTE FUNCTION reject_row_mutation();

CREATE TABLE personal_message_keys (
  message_id       BIGINT NOT NULL,
  chat_id          UUID NOT NULL,
  recipient_key    UUID NOT NULL,
  wrapped_key      BYTEA NOT NULL,
  protocol_version INT NOT NULL DEFAULT 2,
  key_commitment   BYTEA NOT NULL,
  PRIMARY KEY (chat_id, message_id, recipient_key),
  CONSTRAINT personal_message_keys_message_fk
    FOREIGN KEY (chat_id, message_id)
    REFERENCES personal_messages(chat_id, message_id),
  CONSTRAINT personal_message_keys_protocol_check CHECK (protocol_version = 2),
  CONSTRAINT personal_message_keys_wrapped_check CHECK (octet_length(wrapped_key) > 0),
  CONSTRAINT personal_message_keys_commitment_check
    CHECK (octet_length(key_commitment) = 32)
);

CREATE TABLE media_objects (
  media_id       UUID PRIMARY KEY,
  owner_id       UUID NOT NULL REFERENCES users(user_id),
  content_hash   BYTEA,
  is_encrypted   BOOLEAN NOT NULL DEFAULT TRUE,
  period_id      INT,
  size_bytes     BIGINT NOT NULL,
  mime_type      TEXT,
  chunk_count    INT NOT NULL DEFAULT 1,
  width          INT,
  height         INT,
  duration_ms    INT,
  status         TEXT NOT NULL DEFAULT 'uploading',
  threat_scan    JSONB,
  tier           TEXT NOT NULL DEFAULT 'hot',
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT media_objects_size_check CHECK (size_bytes >= 0),
  CONSTRAINT media_objects_chunk_count_check CHECK (chunk_count > 0),
  CONSTRAINT media_objects_dimensions_check CHECK (
    (width IS NULL OR width > 0)
    AND (height IS NULL OR height > 0)
    AND (duration_ms IS NULL OR duration_ms >= 0)
  ),
  CONSTRAINT media_objects_status_check
    CHECK (status IN ('uploading', 'processing', 'ready', 'blocked')),
  CONSTRAINT media_objects_tier_check CHECK (tier IN ('hot', 'cold')),
  CONSTRAINT media_objects_private_hash_check
    CHECK (NOT is_encrypted OR content_hash IS NULL),
  CONSTRAINT media_objects_period_check
    CHECK (period_id IS NULL OR period_id >= 0)
);

CREATE TABLE media_variants (
  media_id    UUID NOT NULL REFERENCES media_objects(media_id),
  variant     TEXT NOT NULL,
  storage_key TEXT NOT NULL,
  size_bytes  BIGINT NOT NULL,
  width       INT,
  height      INT,
  checksum    BYTEA NOT NULL,
  PRIMARY KEY (media_id, variant),
  CONSTRAINT media_variants_variant_check
    CHECK (variant IN ('thumbnail', 'preview', 'full')),
  CONSTRAINT media_variants_storage_key_check CHECK (btrim(storage_key) <> ''),
  CONSTRAINT media_variants_size_check CHECK (size_bytes >= 0),
  CONSTRAINT media_variants_dimensions_check CHECK (
    (width IS NULL OR width > 0) AND (height IS NULL OR height > 0)
  ),
  CONSTRAINT media_variants_checksum_check CHECK (octet_length(checksum) > 0)
);

ALTER TABLE users
  ADD CONSTRAINT users_avatar_media_fk
  FOREIGN KEY (avatar_media_id) REFERENCES media_objects(media_id)
  DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE legal_holds (
  hold_id       UUID PRIMARY KEY,
  authority_ref TEXT NOT NULL,
  reason        TEXT NOT NULL,
  selector      JSONB NOT NULL,
  status        TEXT NOT NULL DEFAULT 'active',
  created_by    UUID NOT NULL REFERENCES users(user_id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  released_by   UUID REFERENCES users(user_id),
  released_at   TIMESTAMPTZ,
  CONSTRAINT legal_holds_status_check CHECK (status IN ('active', 'released')),
  CONSTRAINT legal_holds_release_check CHECK (
    (status = 'active' AND released_by IS NULL AND released_at IS NULL)
    OR (status = 'released' AND released_by IS NOT NULL AND released_at IS NOT NULL)
  )
);

CREATE TABLE legal_hold_targets (
  hold_id         UUID NOT NULL REFERENCES legal_holds(hold_id),
  target_type     TEXT NOT NULL,
  target_id       TEXT NOT NULL,
  materialized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (hold_id, target_type, target_id),
  CONSTRAINT legal_hold_targets_id_check CHECK (btrim(target_id) <> '')
);

CREATE TABLE escrow_audit_events (
  audit_seq     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id      UUID NOT NULL UNIQUE,
  event_type    TEXT NOT NULL,
  actor_ref     TEXT NOT NULL,
  target_type   TEXT NOT NULL,
  target_id     TEXT NOT NULL,
  legal_hold_id UUID REFERENCES legal_holds(hold_id),
  request_ref   TEXT,
  payload_hash  BYTEA NOT NULL,
  previous_hash BYTEA,
  event_hash    BYTEA NOT NULL UNIQUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT escrow_audit_events_type_check
    CHECK (event_type IN ('hold', 'release', 'access', 'decapsulate', 'export', 'purge')),
  CONSTRAINT escrow_audit_events_payload_hash_check CHECK (octet_length(payload_hash) = 32),
  CONSTRAINT escrow_audit_events_previous_hash_check
    CHECK (previous_hash IS NULL OR octet_length(previous_hash) = 32),
  CONSTRAINT escrow_audit_events_event_hash_check CHECK (octet_length(event_hash) = 32)
);

CREATE TRIGGER escrow_audit_events_append_only
BEFORE UPDATE OR DELETE ON escrow_audit_events
FOR EACH ROW EXECUTE FUNCTION reject_row_mutation();

CREATE TABLE escrow_merkle_batches (
  batch_id        UUID PRIMARY KEY,
  first_audit_seq BIGINT NOT NULL REFERENCES escrow_audit_events(audit_seq),
  last_audit_seq  BIGINT NOT NULL REFERENCES escrow_audit_events(audit_seq),
  leaf_count      INT NOT NULL,
  merkle_root     BYTEA NOT NULL,
  signer_key_id   TEXT NOT NULL,
  root_signature  BYTEA NOT NULL,
  anchored_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT escrow_merkle_batches_range_check
    CHECK (last_audit_seq >= first_audit_seq),
  CONSTRAINT escrow_merkle_batches_leaf_count_check CHECK (leaf_count > 0),
  CONSTRAINT escrow_merkle_batches_root_check CHECK (octet_length(merkle_root) = 32),
  CONSTRAINT escrow_merkle_batches_signature_check CHECK (octet_length(root_signature) > 0)
);

CREATE TABLE outbox_events (
  event_id     UUID PRIMARY KEY,
  aggregate_id TEXT NOT NULL,
  topic        TEXT NOT NULL,
  payload      JSONB NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ,
  CONSTRAINT outbox_events_aggregate_check CHECK (btrim(aggregate_id) <> ''),
  CONSTRAINT outbox_events_topic_check CHECK (btrim(topic) <> ''),
  CONSTRAINT outbox_events_publish_time_check
    CHECK (published_at IS NULL OR published_at >= created_at)
);

CREATE INDEX idx_devices_user_active
  ON devices(user_id, device_id)
  WHERE revoked_at IS NULL;

CREATE INDEX idx_prekeys_available
  ON prekeys(device_id, kind, key_id)
  WHERE consumed_at IS NULL;

CREATE INDEX idx_sessions_device_expires
  ON sessions(device_id, expires_at);

CREATE INDEX idx_chats_user_b
  ON chats(user_b, created_at DESC);

CREATE INDEX idx_messages_chat
  ON personal_messages(chat_id, message_id DESC);

CREATE INDEX idx_messages_sender
  ON personal_messages(sender_id, created_at DESC);

CREATE INDEX idx_message_revisions_created
  ON personal_message_revisions(chat_id, message_id, created_at DESC);

CREATE INDEX idx_wrapped_recipient
  ON personal_message_keys(recipient_key, chat_id);

CREATE INDEX idx_media_owner_created
  ON media_objects(owner_id, created_at DESC);

CREATE INDEX idx_media_processing
  ON media_objects(status, created_at)
  WHERE status IN ('uploading', 'processing');

CREATE INDEX idx_escrow_periods_started
  ON escrow_periods(scope_type, scope_id, started_at DESC);

CREATE INDEX idx_legal_hold_targets_target
  ON legal_hold_targets(target_type, target_id);

CREATE INDEX idx_escrow_audit_target
  ON escrow_audit_events(target_type, target_id, audit_seq);

CREATE INDEX idx_outbox_unpublished
  ON outbox_events(created_at)
  WHERE published_at IS NULL;

COMMIT;
