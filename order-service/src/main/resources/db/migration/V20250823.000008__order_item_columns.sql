ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS currency CHAR(3) NOT NULL DEFAULT 'USD';

COMMENT ON COLUMN order_items.attributes_json IS 'Free-form attributes (color, size, etc.)';
COMMENT ON COLUMN order_items.currency IS 'ISO 4217 alpha-3 currency code';