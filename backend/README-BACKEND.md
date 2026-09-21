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

## Two-step student login (email code)

Student sign-in is now two steps: password, then a 6-digit code emailed to
that student, entered on `login.html` before a session is issued. Staff
logins (`staff-login.html`) are unaffected - they still call
`POST /api/auth/login` directly.

Sending the email uses [SendGrid](https://sendgrid.com)'s API over plain
HTTP (no SDK dependency - see `SendGridEmailService.java`). To send real
emails:

1. Create a free SendGrid account (100 emails/day free, no card required for
   the free tier at the time of writing) and verify a sender identity - the
   address you'll set as `MAIL_FROM` below. This is an account only you can
   create; ask in the group chat if you want to share one rather than each
   making your own.
2. Create an API key with **Mail Send** access under Settings → API Keys.
3. Set these before running:

```powershell
$env:MAIL_API_KEY="SG.your-api-key-here"
$env:MAIL_FROM="your-verified-sender@example.com"
```

**Nobody needs to do this to keep developing locally.** If `MAIL_API_KEY` is
unset, the backend still starts and two-step login still works end to end -
`SendGridEmailService` writes the code to the server's own console/log
instead of emailing it (clearly labelled, and never sent over HTTP to the
browser either way). Check the terminal running `spring-boot:run` for a line
starting `MAIL_API_KEY or MAIL_FROM is not set...` and read the code from
there. Only the real, deployed backend needs the two environment variables
actually set.

If you're running the packaged jar detached from a terminal (background
process, a script, anything other than watching `spring-boot:run` live),
that console output goes nowhere unless you redirect it yourself - there is
no file it falls back to. Start it with stdout/stderr sent to a log file, e.g.
`java -jar target\ufh-safety-backend-0.0.1-SNAPSHOT.jar > backend.log 2>&1`,
and read the code from there instead. Otherwise the code is generated and
correct, but genuinely unrecoverable, which looks identical to "no code was
ever sent."

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
