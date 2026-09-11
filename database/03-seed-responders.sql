-- Synthetic responder duty data for local development only.
--
-- Every name, email and position below is made up. Nothing here belongs to a
-- real person and no position is a real person's whereabouts.
--
-- Prerequisites:
--   1. Run 01-create-database.sql and seed.sql first.
--   2. Start the backend once so JPA creates the `responders` table from the
--      Responder entity, then stop it. This file inserts rows only.
--
-- Usage:
--   mysql -u root -p ufh_safety_platform < 03-seed-responders.sql

USE ufh_safety_platform;

-- Three more responders so there is something to choose between. Password is
-- DevPassword123!, the same as every other seeded account.
--
-- These reuse BCrypt hashes already in seed.sql rather than carrying fresh
-- ones. A BCrypt hash contains its own salt, so a copied hash still verifies
-- that same password correctly — this is synthetic data and nobody signs in
-- as these accounts except to demonstrate the dispatcher screen.
INSERT IGNORE INTO users
    (student_number, full_name, email, password_hash, phone, role, created_at)
VALUES
    (NULL, 'Zanele Mthembu', 'zanele.mthembu@example.ac.za',
     '$2a$10$x/0Xu.YLL5kIUye4kYaaBOpUpg3b4Kn1ozbgSXl4PoLnz.C8dzYuW',
     '+27720000004', 'responder', '2026-01-20 07:15:00'),

    (NULL, 'Pieter Botha', 'pieter.botha@example.ac.za',
     '$2a$10$x/0Xu.YLL5kIUye4kYaaBOpUpg3b4Kn1ozbgSXl4PoLnz.C8dzYuW',
     '+27720000005', 'responder', '2026-01-20 07:20:00'),

    (NULL, 'Ayanda Sithole', 'ayanda.sithole@example.ac.za',
     '$2a$10$x/0Xu.YLL5kIUye4kYaaBOpUpg3b4Kn1ozbgSXl4PoLnz.C8dzYuW',
     '+27720000006', 'responder', '2026-01-20 07:25:00');

-- Duty rows. responder_id is looked up from the email rather than typed as a
-- number, because AUTO_INCREMENT ids differ between machines depending on
-- what each person has already inserted.
--
-- The four rows are chosen to cover every branch of the selection rule:
--
--   Nomsa   available, near the library  -> the one auto-assignment should pick
--   Zanele  available, far side of campus -> available but should lose on distance
--   Pieter  available, no position        -> available but not locatable, skip
--   Ayanda  off_duty, very close          -> closest of all, must still be skipped
--
-- Pieter and Ayanda exist so that a selector which forgets to filter shows up
-- immediately as picking the wrong person, rather than looking correct
-- because every seeded responder happened to be eligible.
INSERT IGNORE INTO responders
    (responder_id, team, status, latitude, longitude, last_seen_at, updated_at)
SELECT user_id, 'campus_security', 'available', -32.78210, 26.84800,
       '2026-08-23 01:49:30', '2026-08-23 01:49:30'
FROM users WHERE email = 'nomsa.khumalo@example.ac.za';

INSERT IGNORE INTO responders
    (responder_id, team, status, latitude, longitude, last_seen_at, updated_at)
SELECT user_id, 'medical', 'available', -32.79500, 26.86000,
       '2026-08-23 01:45:00', '2026-08-23 01:45:00'
FROM users WHERE email = 'zanele.mthembu@example.ac.za';

-- Never checked in, so no position and no last_seen_at. Both null on purpose:
-- the frontend must render this as unknown rather than printing "null" or
-- plotting the responder at zero, which is in the Gulf of Guinea.
INSERT IGNORE INTO responders
    (responder_id, team, status, latitude, longitude, last_seen_at, updated_at)
SELECT user_id, 'campus_security', 'available', NULL, NULL, NULL, '2026-08-23 01:40:00'
FROM users WHERE email = 'pieter.botha@example.ac.za';

INSERT IGNORE INTO responders
    (responder_id, team, status, latitude, longitude, last_seen_at, updated_at)
SELECT user_id, 'campus_security', 'off_duty', -32.78335, 26.84975,
       '2026-08-22 18:00:00', '2026-08-22 18:00:00'
FROM users WHERE email = 'ayanda.sithole@example.ac.za';

SELECT status, COUNT(*) AS seeded FROM responders GROUP BY status;
