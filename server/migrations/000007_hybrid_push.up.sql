ALTER TABLE device_push_registrations
  DROP CONSTRAINT device_push_registrations_pkey,
  DROP CONSTRAINT device_push_provider_check;

ALTER TABLE device_push_registrations
  ADD COLUMN priority SMALLINT NOT NULL DEFAULT 100,
  ADD CONSTRAINT device_push_registrations_pkey PRIMARY KEY (device_id, provider),
  ADD CONSTRAINT device_push_provider_check
    CHECK (provider IN ('fcm', 'apns', 'wns', 'unifiedpush')),
  ADD CONSTRAINT device_push_priority_check CHECK (priority BETWEEN 0 AND 100);

CREATE INDEX idx_device_push_routing
  ON device_push_registrations(device_id, priority, provider);

CREATE FUNCTION validate_device_push_provider() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE device_platform TEXT;
BEGIN
  SELECT platform INTO device_platform FROM devices WHERE device_id = NEW.device_id;
  IF device_platform IS NULL THEN
    RAISE EXCEPTION 'push registration references unknown device';
  END IF;
  IF NOT (
    (device_platform = 'android' AND NEW.provider IN ('fcm', 'unifiedpush'))
    OR (device_platform = 'ios' AND NEW.provider = 'apns')
    OR (device_platform = 'windows' AND NEW.provider = 'wns')
  ) THEN
    RAISE EXCEPTION 'provider % is not valid for platform %', NEW.provider, device_platform;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER device_push_platform_provider
  BEFORE INSERT OR UPDATE OF device_id, provider
  ON device_push_registrations
  FOR EACH ROW EXECUTE FUNCTION validate_device_push_provider();
