
-- add a default partition so inserts always succeed
CREATE TABLE IF NOT EXISTS outbox_default
    PARTITION OF outbox DEFAULT;

-- helpful indexes for publisher
CREATE INDEX IF NOT EXISTS idx_outbox_lease
    ON outbox (tenant_id, lease_until);

CREATE INDEX IF NOT EXISTS idx_outbox_created
    ON outbox (created_on, created_at);