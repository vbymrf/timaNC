ALTER TABLE devices
  ADD COLUMN attested_at TIMESTAMPTZ;

UPDATE devices
SET attested_at = created_at
WHERE attestation_ok = true;

CREATE TABLE chat_read_state (
  chat_id               UUID NOT NULL REFERENCES chats(chat_id) ON DELETE CASCADE,
  user_id               UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
  last_read_message_id  BIGINT NOT NULL,
  read_at               TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (chat_id, user_id),
  CONSTRAINT chat_read_message_id_check CHECK (last_read_message_id >= 0)
);
