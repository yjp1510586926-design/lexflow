-- Existing rows remain protocol 1: missing AI journals cannot prove that no paid call occurred.
ALTER TABLE task_delivery_recovery ADD COLUMN registered_state_version INT NULL, ADD COLUMN source_state_version INT NULL,
  ADD CONSTRAINT ck_delivery_registered_version CHECK (registered_state_version IS NULL OR registered_state_version > 0);
ALTER TABLE contract_review ADD COLUMN execution_protocol INT NOT NULL DEFAULT 1;
ALTER TABLE document_generation ADD COLUMN execution_protocol INT NOT NULL DEFAULT 1;
ALTER TABLE case_analysis ADD COLUMN execution_protocol INT NOT NULL DEFAULT 1;
-- Original unnamed checks receive these deterministic MySQL names in V13/V22.
ALTER TABLE contract_review DROP CHECK contract_review_chk_2,
  ADD CONSTRAINT ck_contract_waiting_reason CHECK (waiting_reason IS NULL OR waiting_reason IN ('REVIEW','UNCERTAIN','DELIVERY'));
ALTER TABLE case_analysis DROP CHECK case_analysis_chk_1,
  ADD CONSTRAINT ck_case_analysis_waiting_reason CHECK (waiting_reason IS NULL OR waiting_reason IN ('REVIEW','UNCERTAIN','DELIVERY'));
