-- Phase 1 authentication and private 1:1 messaging vertical slice.
-- Forward-only and atomic.
BEGIN;

CREATE TABLE auth_credentials (
  user_id       UUID PRIMARY KEY REFERENCES users(user_id),
  phone_hash    BYTEA NOT NULL UNIQUE,
  password_hash BYTEA NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (octet_length(phone_hash) = 32),
  CHECK (octet_length(password_hash) > 0)
);

CREATE TABLE otp_challenges (
  challenge_id UUID PRIMARY KEY,
  phone_hash   BYTEA NOT NULL,
  otp_hash     BYTEA NOT NULL,
  locale       TEXT NOT NULL,
  attempts     SMALLINT NOT NULL DEFAULT 0,
  expires_at   TIMESTAMPTZ NOT NULL,
  consumed_at  TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (octet_length(phone_hash) = 32),
  CHECK (octet_length(otp_hash) = 32),
  CHECK (attempts BETWEEN 0 AND 5),
  CHECK (expires_at > created_at)
);
CREATE INDEX idx_otp_phone_active ON otp_challenges(phone_hash, created_at DESC)
  WHERE consumed_at IS NULL;

ALTER TABLE devices
  ADD COLUMN name TEXT NOT NULL DEFAULT 'device',
  ADD COLUMN app_version TEXT,
  ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE devices
  ADD CONSTRAINT devices_name_check CHECK (btrim(name) <> ''),
  ADD CONSTRAINT devices_identity_key_length CHECK (octet_length(identity_pubkey) = 32),
  ADD CONSTRAINT devices_signing_key_length CHECK (octet_length(signing_pubkey) = 32);

ALTER TABLE sessions
  ADD COLUMN access_hash BYTEA,
  ADD COLUMN access_expires_at TIMESTAMPTZ,
  ADD COLUMN revoked_at TIMESTAMPTZ,
  ADD COLUMN rotated_from UUID REFERENCES sessions(session_id),
  ADD COLUMN last_used_at TIMESTAMPTZ;
UPDATE sessions SET access_hash = digest(session_id::TEXT, 'sha256') WHERE access_hash IS NULL;
UPDATE sessions SET access_expires_at = least(expires_at, now() + interval '15 minutes')
  WHERE access_expires_at IS NULL;
ALTER TABLE sessions ALTER COLUMN access_hash SET NOT NULL;
ALTER TABLE sessions ALTER COLUMN access_expires_at SET NOT NULL;
ALTER TABLE sessions
  ADD CONSTRAINT sessions_access_hash_length CHECK (octet_length(access_hash) = 32),
  ADD CONSTRAINT sessions_refresh_hash_length CHECK (octet_length(refresh_hash) = 32),
  ADD CONSTRAINT sessions_access_expiry_check CHECK (access_expires_at <= expires_at);
CREATE UNIQUE INDEX sessions_access_hash_unique ON sessions(access_hash);
CREATE UNIQUE INDEX sessions_refresh_hash_unique ON sessions(refresh_hash);
CREATE INDEX idx_sessions_access_active ON sessions(access_hash, expires_at)
  WHERE revoked_at IS NULL;

CREATE TABLE idempotency_requests (
  device_id     UUID NOT NULL REFERENCES devices(device_id),
  idempotency_key UUID NOT NULL,
  operation     TEXT NOT NULL,
  request_hash  BYTEA NOT NULL,
  response_status INT,
  response_body JSONB,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '24 hours'),
  PRIMARY KEY (device_id, idempotency_key),
  CHECK (btrim(operation) <> ''),
  CHECK (octet_length(request_hash) = 32),
  CHECK (expires_at > created_at),
  CHECK ((response_status IS NULL) = (response_body IS NULL))
);
CREATE INDEX idx_idempotency_expiry ON idempotency_requests(expires_at);

-- Normalize existing rows before enforcing canonical pair order.
UPDATE chats SET user_a = user_b, user_b = user_a WHERE user_a::TEXT > user_b::TEXT;
ALTER TABLE chats ADD CONSTRAINT chats_canonical_pair_check CHECK (user_a::TEXT < user_b::TEXT);
CREATE UNIQUE INDEX chats_canonical_pair_unique ON chats(user_a, user_b);

CREATE TABLE chat_message_counters (
  chat_id         UUID PRIMARY KEY REFERENCES chats(chat_id) ON DELETE CASCADE,
  next_message_id BIGINT NOT NULL DEFAULT 1,
  CHECK (next_message_id > 0)
);

CREATE TABLE message_id_reservations (
  chat_id     UUID NOT NULL REFERENCES chats(chat_id) ON DELETE CASCADE,
  message_id  BIGINT NOT NULL,
  revision_id UUID NOT NULL UNIQUE,
  device_id   UUID NOT NULL REFERENCES devices(device_id),
  expires_at  TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (chat_id, message_id),
  CHECK (message_id > 0),
  CHECK (expires_at > created_at)
);
CREATE INDEX idx_message_reservation_owner
  ON message_id_reservations(device_id, expires_at) WHERE consumed_at IS NULL;

ALTER TABLE personal_messages
  ADD COLUMN message_key_id INT NOT NULL DEFAULT 0,
  ADD CONSTRAINT personal_messages_key_id_check CHECK (message_key_id >= 0);
ALTER TABLE personal_message_revisions
  ADD COLUMN message_key_id INT NOT NULL DEFAULT 0,
  ADD CONSTRAINT personal_message_revisions_key_id_check CHECK (message_key_id >= 0);

ALTER TABLE prekeys
  ADD COLUMN expires_at TIMESTAMPTZ,
  ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE prekeys ADD CONSTRAINT prekeys_signed_expiry_check
  CHECK ((kind = 'signed' AND expires_at IS NOT NULL) OR
         (kind = 'onetime' AND expires_at IS NULL));

COMMIT;
