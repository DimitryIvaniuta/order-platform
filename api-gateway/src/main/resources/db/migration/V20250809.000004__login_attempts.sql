CREATE TABLE IF NOT EXISTS login_attempts (
    id          BIGINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    username_ci TEXT NOT NULL,       -- already-lowered username
    success     BOOLEAN NOT NULL,
    ip          INET,
    user_agent  TEXT,
    CONSTRAINT pk_login_attempts PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE SEQUENCE IF NOT EXISTS login_attempts_id_seq START WITH 1 INCREMENT BY 1 CACHE 1000;
ALTER TABLE login_attempts ALTER COLUMN id SET DEFAULT nextval('login_attempts_id_seq');

-- Create partitions: last 7 days to next 14 days.
DO $$
DECLARE
d_start DATE := (now()::date - INTERVAL '7 days')::date;
  i INT;
  part_name TEXT;
BEGIN
FOR i IN 0..21 LOOP
    part_name := to_char(d_start + i, 'YYYY_MM_DD');
EXECUTE format($f$
                   CREATE TABLE IF NOT EXISTS login_attempts_%1$s PARTITION OF login_attempts
      FOR VALUES FROM (timestamp with time zone '%2$s 00:00:00+00')
                   TO   (timestamp with time zone '%2$s 00:00:00+00' + INTERVAL '1 day')
    $f$, part_name, to_char(d_start + i, 'YYYY-MM-DD'));

EXECUTE format($f$ ALTER TABLE login_attempts_%1$s ALTER COLUMN id SET DEFAULT nextval('login_attempts_id_seq') $f$, part_name);
EXECUTE format($f$ CREATE INDEX IF NOT EXISTS idx_login_attempts_%1$s_user_time ON login_attempts_%1$s (username_ci, created_at) $f$, part_name);
END LOOP;
END $$;