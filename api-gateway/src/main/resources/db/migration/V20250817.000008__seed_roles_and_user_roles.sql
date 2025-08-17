-- Ensure extra roles exist
INSERT INTO roles(name) VALUES ('ORDER_READ')  ON CONFLICT (name) DO NOTHING;
INSERT INTO roles(name) VALUES ('ORDER_WRITE') ON CONFLICT (name) DO NOTHING;
-- (ADMIN and USER are already inserted in V20250809.000002__roles.sql)

-- Helper: assign role to user by username (idempotent)
-- ADMIN → admin user
INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id
FROM users u, roles r
WHERE lower(u.username) = 'admin'
  AND r.name = 'ADMIN'
    ON CONFLICT (user_id, role_id) DO NOTHING;

-- ORDER_WRITE → admin
INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id
FROM users u, roles r
WHERE lower(u.username) = 'admin'
  AND r.name = 'ORDER_WRITE'
    ON CONFLICT (user_id, role_id) DO NOTHING;

-- ORDER_READ → admin
INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id
FROM users u, roles r
WHERE lower(u.username) = 'admin'
  AND r.name = 'ORDER_READ'
    ON CONFLICT (user_id, role_id) DO NOTHING;

-- USER → user
INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id
FROM users u, roles r
WHERE lower(u.username) = 'user'
  AND r.name = 'USER'
    ON CONFLICT (user_id, role_id) DO NOTHING;

-- ORDER_READ → user
INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id
FROM users u, roles r
WHERE lower(u.username) = 'user'
  AND r.name = 'ORDER_READ'
    ON CONFLICT (user_id, role_id) DO NOTHING;
