
CREATE TABLE IF NOT EXISTS currencies (
    code             CHAR(3)    PRIMARY KEY,  -- 'USD'
    numeric_code     SMALLINT   UNIQUE,       -- 840
    fraction_digits  SMALLINT   NOT NULL,     -- 0..3 (ISO default, not cash-specific)
    name             TEXT       NOT NULL,
    active           BOOLEAN    NOT NULL DEFAULT TRUE
    );

-- minimal seed (extend as needed)
INSERT INTO currencies (code, numeric_code, fraction_digits, name)
VALUES
    ('USD', 840, 2, 'US Dollar'),
    ('EUR', 978, 2, 'Euro'),
    ('PLN', 985, 2, 'Polish Złoty'),
    ('JPY', 392, 0, 'Japanese Yen')
    ON CONFLICT DO NOTHING;

-- V20250821_02__orders_money_minor.sql
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS currency_code CHAR(3) NOT NULL DEFAULT 'USD',
    ADD COLUMN IF NOT EXISTS total_amount_minor BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_orders_currency FOREIGN KEY (currency_code) REFERENCES currencies(code),
    ADD CONSTRAINT chk_orders_amount_minor_nonneg CHECK (total_amount_minor >= 0);

-- extend orders with currency + minor units
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS currency_code       CHAR(3) NOT NULL DEFAULT 'USD',
    ADD COLUMN IF NOT EXISTS total_amount_minor  BIGINT  NOT NULL DEFAULT 0;

-- FK & check constraints (create separately so ALTER above is always valid)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_orders_currency'
    ) THEN
ALTER TABLE orders
    ADD CONSTRAINT fk_orders_currency
        FOREIGN KEY (currency_code) REFERENCES currencies(code);
END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_orders_amount_minor_nonneg'
    ) THEN
ALTER TABLE orders
    ADD CONSTRAINT chk_orders_amount_minor_nonneg
        CHECK (total_amount_minor >= 0);
END IF;
END $$;

-- backfill minor amounts using ISO fraction digits
UPDATE orders o
SET total_amount_minor =
        ROUND(
                (o.total_amount::numeric)
                    * power(10::numeric, c.fraction_digits::numeric)
        )::bigint
FROM currencies c
WHERE c.code = o.currency_code
  AND o.total_amount IS NOT NULL;