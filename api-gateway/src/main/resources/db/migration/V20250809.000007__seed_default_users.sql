-- Insert admin user
INSERT INTO users (email, password_hash, status, username, created_at, updated_at)
VALUES (
           'admin@example.com',
           '$2a$10$aoxiGHlH0yJDqbT2C8DLW.RN2V0VJNxkhKwLd9zvd5c9WZcCRaX52', -- BCrypt("adminpass")
           0, -- ACTIVE
           'admin',
           NOW(),
           NOW()
       );

-- Insert regular user
INSERT INTO users (email, password_hash, status, username, created_at, updated_at)
VALUES (
           'user@example.com',
           '$2a$10$.lOsTK14qr.2SB0S7.Wt.uB0V22KvNhtwtzrptuPq8xcF0WOSd5bq', -- BCrypt("userpass")
           0, -- ACTIVE
           'user',
           NOW(),
           NOW()
       );
