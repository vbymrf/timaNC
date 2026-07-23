BEGIN;

ALTER TABLE media_objects
  ADD COLUMN scope_type TEXT,
  ADD COLUMN scope_id UUID,
  ADD COLUMN content_mode TEXT,
  ADD COLUMN kind TEXT,
  ADD CONSTRAINT media_objects_scope_check
    CHECK (scope_type IN ('chat', 'user')),
  ADD CONSTRAINT media_objects_content_mode_check
    CHECK (content_mode IN ('private', 'public')),
  ADD CONSTRAINT media_objects_kind_check
    CHECK (kind IN ('image', 'video', 'audio', 'file'));

CREATE TABLE media_upload_variants (
  media_id     UUID NOT NULL REFERENCES media_objects(media_id) ON DELETE CASCADE,
  variant      TEXT NOT NULL,
  content_type TEXT NOT NULL,
  size_bytes   BIGINT NOT NULL,
  checksum     BYTEA NOT NULL,
  storage_key  TEXT NOT NULL,
  PRIMARY KEY (media_id, variant),
  CONSTRAINT media_upload_variants_variant_check
    CHECK (variant IN ('thumbnail', 'preview', 'full')),
  CONSTRAINT media_upload_variants_size_check CHECK (size_bytes > 0),
  CONSTRAINT media_upload_variants_checksum_check CHECK (octet_length(checksum) = 32),
  CONSTRAINT media_upload_variants_storage_key_check CHECK (btrim(storage_key) <> '')
);

CREATE INDEX idx_media_scope
  ON media_objects(scope_type, scope_id, status);

COMMIT;
