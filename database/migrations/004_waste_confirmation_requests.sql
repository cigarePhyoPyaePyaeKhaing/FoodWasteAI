-- Additive migration: preserves all existing records. Run once before deploying the confirmation workflow.
-- Nullable keys preserve compatibility with old/manual clients that omit a request token.
ALTER TABLE waste_records ADD COLUMN request_key CHAR(64) NULL;
ALTER TABLE waste_records ADD UNIQUE KEY uq_waste_request (food_item_id, request_key);
