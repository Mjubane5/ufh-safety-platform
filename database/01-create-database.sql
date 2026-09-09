CREATE DATABASE IF NOT EXISTS ufh_safety_platform
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE ufh_safety_platform;

-- Spring Boot/JPA will create the users table when the backend starts.
-- The expected table columns are: user_id, student_number, full_name,
-- email, password_hash, phone, role, created_at.
