-- Ensure we never process the same PSP event twice
CREATE UNIQUE INDEX IF NOT EXISTS uq_webhook_dedup
    ON webhook_inbox (provider, event_id);

-- Fast poll of unprocessed webhooks
CREATE INDEX IF NOT EXISTS idx_webhook_unprocessed
    ON webhook_inbox (processed_at)
    WHERE processed_at IS NULL;

-- Optional: filter by provider while unprocessed
CREATE INDEX IF NOT EXISTS idx_webhook_provider_unprocessed
    ON webhook_inbox (provider, processed_at)
    WHERE processed_at IS NULL;