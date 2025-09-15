-- Add idem_key for idempotent inserts per day partition
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS idem_key VARCHAR(64);

-- Keep it short/cheap (client-calculated MD5 or nameUUID string)
CREATE UNIQUE INDEX IF NOT EXISTS uq_outbox_idem_per_day
    ON outbox (created_on, idem_key);
