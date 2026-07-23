\set ON_ERROR_STOP on

BEGIN;

DO $verify$
DECLARE
  missing TEXT[];
  partition_bound TEXT;
BEGIN
  SELECT array_agg(required.name ORDER BY required.name)
  INTO missing
  FROM (
    VALUES
      ('users'),
      ('devices'),
      ('prekeys'),
      ('sessions'),
      ('chats'),
      ('personal_messages'),
      ('personal_messages_p0'),
      ('personal_message_revisions'),
      ('personal_message_keys'),
      ('media_objects'),
      ('media_variants'),
      ('escrow_periods'),
      ('legal_holds'),
      ('legal_hold_targets'),
      ('escrow_audit_events'),
      ('escrow_merkle_batches'),
      ('outbox_events')
  ) AS required(name)
  WHERE to_regclass('public.' || required.name) IS NULL;

  IF missing IS NOT NULL THEN
    RAISE EXCEPTION 'Phase 0 tables are missing: %', array_to_string(missing, ', ');
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_partitioned_table pt
    JOIN pg_class c ON c.oid = pt.partrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relname = 'personal_messages'
      AND pt.partstrat = 'h'
  ) THEN
    RAISE EXCEPTION 'personal_messages is not hash-partitioned';
  END IF;

  SELECT pg_get_expr(c.relpartbound, c.oid)
  INTO partition_bound
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public'
    AND c.relname = 'personal_messages_p0';

  IF partition_bound IS DISTINCT FROM 'FOR VALUES WITH (modulus 1, remainder 0)' THEN
    RAISE EXCEPTION 'unexpected Phase 0 partition bound: %', partition_bound;
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conrelid = 'public.personal_messages'::regclass
      AND conname = 'personal_messages_current_revision_fk'
      AND contype = 'f'
      AND condeferrable
      AND condeferred
  ) THEN
    RAISE EXCEPTION 'deferred current revision FK is missing';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_trigger
    WHERE tgrelid = 'public.personal_message_revisions'::regclass
      AND tgname = 'personal_message_revisions_append_only'
      AND NOT tgisinternal
  ) THEN
    RAISE EXCEPTION 'revision append-only trigger is missing';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_trigger
    WHERE tgrelid = 'public.escrow_audit_events'::regclass
      AND tgname = 'escrow_audit_events_append_only'
      AND NOT tgisinternal
  ) THEN
    RAISE EXCEPTION 'escrow audit append-only trigger is missing';
  END IF;

  IF to_regclass('public.idx_outbox_unpublished') IS NULL THEN
    RAISE EXCEPTION 'outbox unpublished index is missing';
  END IF;
END
$verify$;

DO $smoke$
DECLARE
  v_user_a_id UUID := gen_random_uuid();
  v_user_b_id UUID := gen_random_uuid();
  v_device_id UUID := gen_random_uuid();
  v_chat_id UUID := gen_random_uuid();
  v_revision_id UUID := gen_random_uuid();
  v_metadata JSONB := '{"format_version":2,"revision_number":1,"content_mode":"private"}';
BEGIN
  INSERT INTO users (
    user_id, account_home_region, email, display_name
  ) VALUES
    (v_user_a_id, 'RU', v_user_a_id::TEXT || '@verify.invalid', 'verify-a'),
    (v_user_b_id, 'RU', v_user_b_id::TEXT || '@verify.invalid', 'verify-b');

  INSERT INTO devices (
    device_id, user_id, platform, identity_pubkey, signing_pubkey
  ) VALUES (
    v_device_id, v_user_a_id, 'android', decode('01', 'hex'), decode('02', 'hex')
  );

  INSERT INTO chats (
    chat_id, user_a, user_b, conversation_home_region
  ) VALUES (
    v_chat_id, v_user_a_id, v_user_b_id, 'RU'
  );

  INSERT INTO personal_messages (
    message_id,
    chat_id,
    sender_id,
    sender_device,
    encrypted_nodes,
    metadata,
    presence_bitmap,
    key_commitment,
    current_revision_id,
    escrow_blob,
    signature
  ) VALUES (
    1,
    v_chat_id,
    v_user_a_id,
    v_device_id,
    ARRAY[decode('03', 'hex')],
    v_metadata,
    1,
    decode(repeat('00', 32), 'hex'),
    v_revision_id,
    decode('04', 'hex'),
    decode('05', 'hex')
  );

  INSERT INTO personal_message_revisions (
    chat_id,
    message_id,
    revision_id,
    revision_number,
    encrypted_nodes,
    metadata,
    presence_bitmap,
    key_commitment,
    escrow_blob,
    signature
  ) VALUES (
    v_chat_id,
    1,
    v_revision_id,
    1,
    ARRAY[decode('03', 'hex')],
    v_metadata,
    1,
    decode(repeat('00', 32), 'hex'),
    decode('04', 'hex'),
    decode('05', 'hex')
  );

  SET CONSTRAINTS ALL IMMEDIATE;

  BEGIN
    UPDATE personal_message_revisions
    SET created_at = created_at
    WHERE personal_message_revisions.revision_id = v_revision_id;
    RAISE EXCEPTION 'revision append-only trigger accepted UPDATE';
  EXCEPTION
    WHEN SQLSTATE '55000' THEN
      NULL;
  END;
END
$smoke$;

SELECT 'Phase 0 schema verification passed' AS result;

ROLLBACK;
