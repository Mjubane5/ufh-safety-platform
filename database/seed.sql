-- Synthetic seed data for local development only.
--
-- Every name, student number, email and phone number in this file is made up.
-- Nothing here belongs to a real person. Never replace these rows with real
-- student data — see the project brief and CLAUDE.md.
--
-- Do not run this against anything but your own local MySQL. Every account
-- below shares one publicly known password, so a seeded database must never
-- be exposed on a network.
--
-- Prerequisites:
--   1. Run 01-create-database.sql first.
--   1a. The staff rows below have no student number, which needs the nullable
--       student_number change. On an existing database also run
--       02-alter-users-student-number-nullable.sql.
--   2. Start the backend once (./mvnw spring-boot:run) so JPA creates the
--      `users` table, then stop it. This file inserts rows, it does not
--      create tables.
--
-- Usage (MySQL Workbench, or):
--   mysql -u root -p ufh_safety_platform < seed.sql

USE ufh_safety_platform;

-- Password for every seeded account below: DevPassword123!
--
-- The hashes are real BCrypt hashes of that password at strength 10, which is
-- what BCryptPasswordEncoder uses by default, so login works against these
-- rows exactly as it would against an account created through /api/auth/register.
-- Each row has a different hash because BCrypt salts every hash separately —
-- identical passwords are meant to produce different hashes.
--
-- INSERT IGNORE lets this file be re-run without error: email and
-- student_number are unique, so a second run skips rows that already exist
-- instead of aborting half way through.

INSERT IGNORE INTO users
    (student_number, full_name, email, password_hash, phone, role, created_at)
VALUES
    ('201900001', 'Thandiwe Mokoena', 'thandiwe.mokoena@example.ac.za',
     '$2a$10$TFSZbzf0TJicKhblf2nJoOxtAxXnY5VmOY4hYMRAHljCsvP4I0FhO',
     '+27710000001', 'student', '2026-02-10 08:15:00'),

    ('201900002', 'Sipho Dlamini', 'sipho.dlamini@example.ac.za',
     '$2a$10$nbN7uzEjnfg2T.IxCq.kQuOQhhArbLBj6G4YeVJKGqJSQJYR6fLkG',
     '+27710000002', 'student', '2026-02-11 09:40:00'),

    ('201900003', 'Aphiwe Ngcobo', 'aphiwe.ngcobo@example.ac.za',
     '$2a$10$Ezl58jBrPHWIubIXTl5d4erGXblaHIOtIo31eH3JCBGs7s2PG1f.a',
     NULL, 'student', '2026-02-11 14:05:00'),

    (NULL, 'Nomsa Khumalo', 'nomsa.khumalo@example.ac.za',
     '$2a$10$x/0Xu.YLL5kIUye4kYaaBOpUpg3b4Kn1ozbgSXl4PoLnz.C8dzYuW',
     '+27720000001', 'responder', '2026-01-20 07:00:00'),

    (NULL, 'Johan van Wyk', 'johan.vanwyk@example.ac.za',
     '$2a$10$F8YSrdOwH97oqjTi9fVDPOL03uPJypG7FZuAa1azneVSO9GD93bbi',
     '+27720000002', 'campus_control', '2026-01-20 07:05:00'),

    (NULL, 'Lerato Mahlangu', 'lerato.mahlangu@example.ac.za',
     '$2a$10$P/RgpD27UsV.Mb2MlOvxkeLP7rjJjGM1e1FGc5bYlNc1OsdjV9.uG',
     '+27720000003', 'gbv_officer', '2026-01-20 07:10:00');

-- Note: `phone` is nullable, so Aphiwe Ngcobo above has none. That row is
-- deliberate — the frontend must render a missing phone as absent rather than
-- printing "null", and this gives you a row to test that against.

-- The staff rows have no student number on purpose. A responder or a GBV
-- officer is not a student, so inventing a number for them would put false
-- data in the table. MySQL allows many NULLs in a unique index, so all three
-- can be null while real student numbers stay unique.

-- Incident seed rows are not here yet. The `incidents` table does not exist
-- on this branch; it arrives with the Incident entity in PR #10. Add them in a
-- follow-up once that is merged.

SELECT role, COUNT(*) AS seeded FROM users GROUP BY role;
