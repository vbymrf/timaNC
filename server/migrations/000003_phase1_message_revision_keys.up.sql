BEGIN;

ALTER TABLE personal_message_keys
  DROP CONSTRAINT personal_message_keys_pkey;

ALTER TABLE personal_message_keys
  ADD COLUMN revision_id UUID;

UPDATE personal_message_keys k
SET revision_id = m.current_revision_id
FROM personal_messages m
WHERE m.chat_id = k.chat_id
  AND m.message_id = k.message_id;

ALTER TABLE personal_message_keys
  ALTER COLUMN revision_id SET NOT NULL,
  ADD CONSTRAINT personal_message_keys_revision_fk
    FOREIGN KEY (chat_id, message_id, revision_id)
    REFERENCES personal_message_revisions(chat_id, message_id, revision_id),
  ADD PRIMARY KEY (chat_id, message_id, revision_id, recipient_key);

CREATE INDEX idx_wrapped_revision_recipient
  ON personal_message_keys(recipient_key, chat_id, message_id, revision_id);

COMMIT;
