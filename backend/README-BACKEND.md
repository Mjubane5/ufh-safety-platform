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
   the free tier at the time of writing) and verify a sender identity under
   Settings → Sender Authentication → Single Sender Verification - the
   address you'll set as `MAIL_FROM` below. This is an account only you can
   create; ask in the group chat if you want to share one rather than each
   making your own.

   **Known limitation, not a bug:** Single Sender Verification only proves
   you control that one address - it does not set up SPF/DKIM alignment for
   its domain. On Railway right now `MAIL_FROM` is a `@gmail.com` address
   sent *through* SendGrid rather than Gmail's own servers, which several
   receiving mail systems (Microsoft 365 in particular, which UFH's student
   mail runs on) treat as a spam/spoofing signal - the code email lands in
   spam, not the inbox, even though it sends successfully. Confirmed live
   via SendGrid's own Email Activity Feed (`app.sendgrid.com/email_logs`),
   which is the authoritative source for delivery status - the app has no
   visibility into what happens after SendGrid accepts a send.

   The real fix is SendGrid's **Domain Authentication** (Settings → Sender
   Authentication → Authenticate Your Domain), which needs a domain we
   actually control the DNS for - not `gmail.com`, and not `ufh.ac.za`
   since UFH's own IT controls that domain's DNS, not this team. That's a
   cost/ownership decision for the group, not something fixable in code.
   Until then, tell first-time testers to check spam and mark the message
   "Not spam" - most providers then deliver that sender to the inbox for
   that recipient going forward.

   **A step further than spam, seen against a real `@ufh.ac.za` account:**
   SendGrid's Activity Feed can show a send as **Delivered** (the receiving
   server accepted it, SMTP `250 OK`) while the recipient still never sees
   it anywhere, not even in Junk. `Delivered` only means SendGrid's job is
   done - what a Microsoft 365 tenant (which UFH's student mail is) does
   with it next is invisible to SendGrid and to this app. Microsoft 365's
   anti-phishing filtering has a separate **Quarantine**, distinct from the
   Junk folder: a normal user often cannot see or release it themselves
   (that needs a Microsoft 365 admin, at `security.microsoft.com/quarantine`,
   or a quarantine digest email if the tenant has one enabled) - and an
   email whose entire content is "your sign-in code is XXXXXX" is exactly
   the shape of a real credential-phishing lure, which is likely why an
   unfamiliar external sender's OTP email gets caught here particularly
   often. If a tester sees `Delivered` in SendGrid but nothing in their
   inbox *or* Junk, the next question is for UFH's own IT (or the tenant's
   Microsoft 365 admin) about quarantine - not something to keep debugging
   from this codebase's side.
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

## Safe Walk / dispatch routing (optional, C++)

Safe Walk routes and the responder route shown after `POST /incidents/{id}/assign`
follow real campus footways and roads via a small C++ shortest-path engine
(`algorithms/shortest_path`) — nobody needs a C++ toolchain to develop or run
the backend, though: without a compiled binary, routing automatically falls
back to a straight line (`GeometricRouteEngine`), same as before this module
existed. See `algorithms/shortest_path/README.md` if you want to build and
try the real one locally, or just to understand how production gets it (the
root `Dockerfile` compiles it automatically on deploy).

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
