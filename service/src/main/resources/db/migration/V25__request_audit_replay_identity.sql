-- Nullable identity leaves transaction-bound business audits unchanged.
-- Offline request replay keeps the same UUID/hash and the original occurrence time.
ALTER TABLE audit_log
  ADD COLUMN event_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD UNIQUE INDEX uq_audit_event_uuid (event_uuid),
  ADD CONSTRAINT ck_audit_event_identity CHECK (
    (event_uuid IS NULL AND payload_sha256 IS NULL) OR
    (event_uuid IS NOT NULL AND payload_sha256 IS NOT NULL)
  );
