-- Common lookups: by saga, by status, by created
CREATE INDEX IF NOT EXISTS idx_payments_tenant_saga
    ON payments (tenant_id, saga_id);

CREATE INDEX IF NOT EXISTS idx_payments_status
    ON payments (status);

CREATE INDEX IF NOT EXISTS idx_payments_created
    ON payments (created_at);

-- Quick joins / filters across sub-entities
CREATE INDEX IF NOT EXISTS idx_captures_payment
    ON captures (payment_id);

CREATE INDEX IF NOT EXISTS idx_refunds_payment
    ON refunds (payment_id);

CREATE INDEX IF NOT EXISTS idx_ledger_payment
    ON ledger_entries (payment_id);