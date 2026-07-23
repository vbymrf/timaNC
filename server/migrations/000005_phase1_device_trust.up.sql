CREATE TABLE device_attestation_tokens (
  token_hash      BYTEA PRIMARY KEY,
  platform        TEXT NOT NULL,
  challenge_hash  BYTEA NOT NULL,
  verdict         TEXT NOT NULL,
  expires_at      TIMESTAMPTZ NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT device_attestation_platform_check CHECK (platform IN ('ios', 'android')),
  CONSTRAINT device_attestation_verdict_check CHECK (verdict IN ('trusted', 'limited')),
  CONSTRAINT device_attestation_hashes_check
    CHECK (octet_length(token_hash) = 32 AND octet_length(challenge_hash) = 32)
);

CREATE INDEX idx_device_attestation_expiry
  ON device_attestation_tokens(expires_at);

CREATE TABLE device_link_sessions (
  session_id             UUID PRIMARY KEY,
  desktop_identity_key   BYTEA NOT NULL,
  desktop_signing_key    BYTEA NOT NULL,
  desktop_name           TEXT NOT NULL,
  qr_secret_hash         BYTEA NOT NULL,
  claim_token_hash       BYTEA NOT NULL,
  wrapped_device_secret  BYTEA,
  user_id                UUID REFERENCES users(user_id),
  confirmed_by_device    UUID REFERENCES devices(device_id),
  linked_device_id       UUID REFERENCES devices(device_id),
  expires_at             TIMESTAMPTZ NOT NULL,
  confirmed_at           TIMESTAMPTZ,
  claimed_at             TIMESTAMPTZ,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT device_link_name_check CHECK (char_length(desktop_name) BETWEEN 1 AND 100),
  CONSTRAINT device_link_keys_check CHECK (
    octet_length(desktop_identity_key) = 32 AND octet_length(desktop_signing_key) = 32
  ),
  CONSTRAINT device_link_secret_hashes_check CHECK (
    octet_length(qr_secret_hash) = 32 AND octet_length(claim_token_hash) = 32
  ),
  CONSTRAINT device_link_confirmation_check CHECK (
    (confirmed_at IS NULL AND user_id IS NULL AND confirmed_by_device IS NULL AND linked_device_id IS NULL)
    OR
    (confirmed_at IS NOT NULL AND user_id IS NOT NULL AND confirmed_by_device IS NOT NULL AND linked_device_id IS NOT NULL)
  )
);

CREATE UNIQUE INDEX idx_device_link_claim_token
  ON device_link_sessions(claim_token_hash);
CREATE INDEX idx_device_link_expiry
  ON device_link_sessions(expires_at);

CREATE TABLE device_push_registrations (
  device_id       UUID PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
  provider        TEXT NOT NULL,
  token_ciphertext BYTEA NOT NULL,
  token_hash      BYTEA NOT NULL UNIQUE,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT device_push_provider_check CHECK (provider IN ('fcm', 'apns', 'wns')),
  CONSTRAINT device_push_token_check CHECK (
    octet_length(token_ciphertext) > 0 AND octet_length(token_hash) = 32
  )
);
