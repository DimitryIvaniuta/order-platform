-- Ensure at least today's partition exists (safe even if previous DO loop ran)
DO $$
DECLARE d DATE := CURRENT_DATE;
BEGIN
EXECUTE format($f$
    CREATE TABLE IF NOT EXISTS outbox_%s
    PARTITION OF outbox
    FOR VALUES FROM (DATE %L) TO (DATE %L)
  $f$, to_char(d, 'YYYYMMDD'), d, d + 1);
END $$;