-- Run once against a database that was created before student_number became
-- nullable. A fresh database does not need this: JPA creates the column from
-- the User entity, which already allows null.
--
-- Hibernate runs with ddl-auto=update. That adds missing tables and columns
-- but does not relax an existing NOT NULL, so the change has to be applied by
-- hand here.
--
-- Why: responders, campus control, GBV officers and admins are not students
-- and have no student number. Requiring one meant inventing fake numbers for
-- real staff rows.

USE ufh_safety_platform;

ALTER TABLE users
  MODIFY COLUMN student_number VARCHAR(20) NULL;

-- The unique index stays. MySQL allows any number of NULLs in a unique index,
-- so every staff row can have no student number while student numbers that
-- are present are still unique.
