ALTER TABLE captures
    ADD COLUMN IF NOT EXISTS idempotency_key varchar(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_captures_idem
    ON captures(tenant_id, payment_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;