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
- MySQL 8+

Maven is not in the list on purpose. Use the `./mvnw` wrapper committed here
and it downloads the right version itself, so we are all building with Maven
3.9.9 rather than whatever each of us happened to install.

## Database

Run `../database/01-create-database.sql` in MySQL Workbench. It sets the
`utf8mb4` character set, so prefer it over typing `CREATE DATABASE` by hand.
JPA creates the `users` table on first start.

The application uses `root` with a blank password by default. If your MySQL root account has a password, set it before running:

PowerShell:

```powershell
$env:DB_PASSWORD="your_mysql_password"
```

## Signing key

The backend will not start without `JWT_SECRET`. There is deliberately no
default: a key committed to this repository would let anyone who can read the
repository mint a token for any registered account.

Set one of at least 32 characters before running:

```powershell
$env:JWT_SECRET="pick-any-long-random-string-at-least-32-chars"
```

Each of us can use a different value locally. A token signed with one key is
rejected by a backend running another, so sign in again after changing it.

## Run backend

From the `backend` directory:

```powershell
.\mvnw spring-boot:run
```

The API will run at `http://localhost:8080/api`.

## Tests

```powershell
.\mvnw test
```

These cover the auth slice only and need no database: passwords are stored as
BCrypt hashes, the role is set by the server rather than the request body,
login gives the same answer for an unknown email as for a wrong password, and
a token signed with one key is rejected by another.

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

Open it through the server, not by double-clicking the file. A page opened from
disk sends `Origin: null`, which is not in the CORS allow-list, and every
request fails with an error the browser will not explain.

A successful registration inserts a user into MySQL. Login verifies the BCrypt password and returns a JWT. `api.js` stores that JWT and sends it to `/api/auth/me` on protected calls.

Leave `MOCK = true` when you commit. Flipping it is a local change for testing
against the backend, not something the rest of the team should inherit.
