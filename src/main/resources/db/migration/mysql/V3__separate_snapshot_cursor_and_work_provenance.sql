ALTER TABLE account ADD COLUMN snapshot_cursor_at DATETIME(6) NULL;
ALTER TABLE transactional_outbox ADD COLUMN delivery_excluded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE account ADD COLUMN last_event_applied_at DATETIME(6) NULL;
ALTER TABLE transaction_history ADD COLUMN external_occurred_at DATETIME(6) NULL;
ALTER TABLE transaction_history ADD COLUMN external_completed_at DATETIME(6) NULL;
ALTER TABLE transaction_history ADD COLUMN recovered_at DATETIME(6) NULL;
ALTER TABLE transaction_history ADD COLUMN external_type VARCHAR(30) NULL;
ALTER TABLE transaction_history ADD COLUMN work_event_recorded BOOLEAN NOT NULL DEFAULT FALSE;
-- Already queued events must not be emitted again during the first full snapshot audit.
UPDATE transaction_history SET work_event_recorded = TRUE WHERE id IN
 (SELECT aggregate_id FROM transactional_outbox WHERE aggregate_type = 'TRANSACTION_SUCCEEDED' AND is_published = TRUE);
