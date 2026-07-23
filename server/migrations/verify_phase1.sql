\set ON_ERROR_STOP on
BEGIN;

DO $verify$
DECLARE missing TEXT[];
BEGIN
  SELECT array_agg(name) INTO missing
  FROM (VALUES
    ('auth_credentials'), ('otp_challenges'), ('idempotency_requests'),
    ('chat_message_counters'), ('message_id_reservations')
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
