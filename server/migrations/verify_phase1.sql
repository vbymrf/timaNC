\set ON_ERROR_STOP on
BEGIN;

DO $verify$
DECLARE missing TEXT[];
BEGIN
  SELECT array_agg(name) INTO missing
  FROM (VALUES
    ('auth_credentials'), ('otp_challenges'), ('idempotency_requests'),
    ('chat_message_counters'), ('message_id_reservations'),
    ('device_attestation_tokens'), ('device_link_sessions'),
    ('device_push_registrations'), ('chat_read_state')
  ) required(name)
  WHERE to_regclass('public.' || name) IS NULL;
  IF missing IS NOT NULL THEN
    RAISE EXCEPTION 'Phase 1 tables missing: %', array_to_string(missing, ', ');
  END IF;

  IF to_regclass('public.chats_canonical_pair_unique') IS NULL THEN
    RAISE EXCEPTION 'canonical chat pair index missing';
  END IF;
  IF to_regclass('public.sessions_access_hash_unique') IS NULL OR
     to_regclass('public.sessions_refresh_hash_unique') IS NULL THEN
    RAISE EXCEPTION 'session token indexes missing';
  END IF;
  IF to_regclass('public.idx_device_attestation_expiry') IS NULL OR
     to_regclass('public.idx_device_link_claim_token') IS NULL OR
     to_regclass('public.idx_device_link_expiry') IS NULL THEN
    RAISE EXCEPTION 'device trust indexes missing';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid = 'device_attestation_tokens'::regclass
      AND conname IN ('device_attestation_platform_check',
                      'device_attestation_verdict_check',
                      'device_attestation_hashes_check')
    GROUP BY conrelid HAVING count(*) = 3
  ) THEN
    RAISE EXCEPTION 'device attestation constraints missing';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid = 'device_link_sessions'::regclass
      AND conname IN ('device_link_name_check', 'device_link_keys_check',
                      'device_link_secret_hashes_check',
                      'device_link_confirmation_check')
    GROUP BY conrelid HAVING count(*) = 4
  ) THEN
    RAISE EXCEPTION 'device link constraints missing';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid = 'device_push_registrations'::regclass
      AND conname IN ('device_push_provider_check', 'device_push_token_check',
                      'device_push_registrations_pkey',
                      'device_push_registrations_token_hash_key')
    GROUP BY conrelid HAVING count(*) = 4
  ) THEN
    RAISE EXCEPTION 'device push constraints missing';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid = 'chat_read_state'::regclass
      AND conname IN ('chat_read_state_pkey', 'chat_read_message_id_check')
    GROUP BY conrelid HAVING count(*) = 2
  ) THEN
    RAISE EXCEPTION 'chat read-state constraints missing';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='devices'
      AND column_name='attested_at' AND data_type='timestamp with time zone'
  ) THEN
    RAISE EXCEPTION 'devices.attested_at column missing or has wrong type';
  END IF;
  IF EXISTS (
    SELECT 1 FROM devices WHERE attestation_ok = true AND attested_at IS NULL
  ) THEN
    RAISE EXCEPTION 'attested devices were not backfilled with attested_at';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='chat_read_state'
      AND column_name='last_read_message_id' AND data_type='bigint' AND is_nullable='NO'
  ) OR NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='chat_read_state'
      AND column_name='read_at' AND data_type='timestamp with time zone' AND is_nullable='NO'
  ) THEN
    RAISE EXCEPTION 'chat read-state columns missing or invalid';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='message_id_reservations'
      AND column_name='revision_id' AND is_nullable='NO'
  ) THEN
    RAISE EXCEPTION 'reservation revision_id column missing';
  END IF;
END
$verify$;

DO $smoke$
DECLARE
  a UUID := gen_random_uuid();
  b UUID := gen_random_uuid();
  d UUID := gen_random_uuid();
  c UUID := gen_random_uuid();
  mid BIGINT;
  stored BIGINT;
  stored_at TIMESTAMPTZ;
BEGIN
  INSERT INTO users(user_id, account_home_region, display_name) VALUES
    (a, 'RU', 'phase1-a'), (b, 'RU', 'phase1-b');
  INSERT INTO devices(device_id, user_id, platform, identity_pubkey, signing_pubkey)
    VALUES (d, a, 'android', decode(repeat('01', 32), 'hex'), decode(repeat('02', 32), 'hex'));
  INSERT INTO chats(chat_id, user_a, user_b, conversation_home_region)
    VALUES (c, least(a::TEXT, b::TEXT)::UUID, greatest(a::TEXT, b::TEXT)::UUID, 'RU');
  INSERT INTO chat_message_counters(chat_id) VALUES (c)
    ON CONFLICT DO NOTHING;
  UPDATE chat_message_counters SET next_message_id = next_message_id + 1
    WHERE chat_id = c RETURNING next_message_id - 1 INTO mid;
  INSERT INTO message_id_reservations(chat_id, message_id, revision_id, device_id, expires_at)
    VALUES (c, mid, gen_random_uuid(), d, now() + interval '5 minutes');
  IF mid <> 1 THEN RAISE EXCEPTION 'first reserved message id was %', mid; END IF;

  INSERT INTO chat_read_state(chat_id, user_id, last_read_message_id, read_at)
    VALUES (c, a, 9, TIMESTAMPTZ '2025-01-01 00:00:00+00')
    ON CONFLICT(chat_id, user_id) DO UPDATE SET
      last_read_message_id = greatest(chat_read_state.last_read_message_id,
                                      excluded.last_read_message_id),
      read_at = CASE
        WHEN excluded.last_read_message_id > chat_read_state.last_read_message_id
          THEN excluded.read_at
        ELSE chat_read_state.read_at
      END;
  INSERT INTO chat_read_state(chat_id, user_id, last_read_message_id, read_at)
    VALUES (c, a, 4, TIMESTAMPTZ '2025-01-02 00:00:00+00')
    ON CONFLICT(chat_id, user_id) DO UPDATE SET
      last_read_message_id = greatest(chat_read_state.last_read_message_id,
                                      excluded.last_read_message_id),
      read_at = CASE
        WHEN excluded.last_read_message_id > chat_read_state.last_read_message_id
          THEN excluded.read_at
        ELSE chat_read_state.read_at
      END
    RETURNING last_read_message_id, read_at INTO stored, stored_at;
  IF stored <> 9 OR stored_at <> TIMESTAMPTZ '2025-01-01 00:00:00+00' THEN
    RAISE EXCEPTION 'read state regressed to %, %', stored, stored_at;
  END IF;
  INSERT INTO chat_read_state(chat_id, user_id, last_read_message_id, read_at)
    VALUES (c, a, 12, TIMESTAMPTZ '2025-01-03 00:00:00+00')
    ON CONFLICT(chat_id, user_id) DO UPDATE SET
      last_read_message_id = greatest(chat_read_state.last_read_message_id,
                                      excluded.last_read_message_id),
      read_at = CASE
        WHEN excluded.last_read_message_id > chat_read_state.last_read_message_id
          THEN excluded.read_at
        ELSE chat_read_state.read_at
      END
    RETURNING last_read_message_id, read_at INTO stored, stored_at;
  IF stored <> 12 OR stored_at <> TIMESTAMPTZ '2025-01-03 00:00:00+00' THEN
    RAISE EXCEPTION 'read state did not advance to 12';
  END IF;

  BEGIN
    INSERT INTO chats(chat_id, user_a, user_b, conversation_home_region)
      VALUES (gen_random_uuid(), least(a::TEXT, b::TEXT)::UUID,
              greatest(a::TEXT, b::TEXT)::UUID, 'RU');
    RAISE EXCEPTION 'duplicate canonical pair accepted';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
END
$smoke$;

SELECT 'Phase 1 schema verification passed' AS result;
ROLLBACK;
