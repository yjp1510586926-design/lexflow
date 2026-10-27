-- Daily late-write reviews must not reset the age used for compacting detailed records.
ALTER TABLE document_export_artifact ADD COLUMN first_cleaned_at TIMESTAMP(6) NULL;
UPDATE document_export_artifact SET first_cleaned_at=last_cleaned_at WHERE last_cleaned_at IS NOT NULL;
