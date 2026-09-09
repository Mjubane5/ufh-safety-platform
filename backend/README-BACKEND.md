# UFH Safety Backend - authentication slice

This backend currently implements only the first integration slice:

- POST /api/auth/register
- POST /api/auth/login
- GET /api/auth/me
- MySQL persistence
- BCrypt password hashing
- JWT bearer authentication
- CORS for the Python frontend server on ports localhost:5500 / 127.0.0.1:5500

## Prerequisites

- Java 17+
- Maven 3.9+
- MySQL 8+

## Database

Run `../database/01-create-database.sql` in MySQL Workbench, or manually run:

```sql
CREATE DATABASE ufh_safety_platform;
```

The application uses `root` with a blank password by default. If your MySQL root account has a password, set it before running:

PowerShell:

```powershell
$env:DB_PASSWORD="your_mysql_password"
```

## Run backend

From the `backend` directory:

```powershell
mvn spring-boot:run
```

The API will run at `http://localhost:8080/api`.

## Connect the existing frontend

In `frontend/js/config.js`, change:

```js
export const MOCK = true;
```

to:

```js
export const MOCK = false;
```

Keep the Python frontend server running from the repository root:

```powershell
python -m http.server 5500
```

Then open:

`http://localhost:5500/frontend/register.html`

A successful registration inserts a user into MySQL. Login verifies the BCrypt password and returns a JWT. `api.js` stores that JWT and sends it to `/api/auth/me` on protected calls.
