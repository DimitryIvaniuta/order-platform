-- Dynamically bump CACHE on identity-backed sequences (users/roles/user_roles).
-- Works whether the sequences have default or custom names.

DO $$
DECLARE
seq_name text;
BEGIN
  -- users.id
SELECT pg_get_serial_sequence('users','id') INTO seq_name;
IF seq_name IS NOT NULL THEN
    EXECUTE format('ALTER SEQUENCE %s CACHE 1000;', seq_name);
END IF;

  -- roles.id
SELECT pg_get_serial_sequence('roles','id') INTO seq_name;
IF seq_name IS NOT NULL THEN
    EXECUTE format('ALTER SEQUENCE %s CACHE 1000;', seq_name);
END IF;

  -- user_roles.id
SELECT pg_get_serial_sequence('user_roles','id') INTO seq_name;
IF seq_name IS NOT NULL THEN
    EXECUTE format('ALTER SEQUENCE %s CACHE 1000;', seq_name);
END IF;
END $$;
