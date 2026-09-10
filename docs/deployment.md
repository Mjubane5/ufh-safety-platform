# Deployment

How to run this system so it stays up, and what would have to be true before it
could be exposed to anyone outside the team.

Three targets are described. They are not equivalent, and the third one is
gated on work that has not been done yet.

| Target | Status | Who can reach it |
| --- | --- | --- |
| 1. Local, always-on | **Working now** | Only this machine |
| 2. Campus LAN demo | Ready when needed | Anyone on the same network |
| 3. Public internet | **Not permitted yet** — see section 5 | Anyone |

---

## 0. Read this before deploying anything

This is a **student safety, emergency and GBV reporting prototype**. Nothing it
receives reaches a control room, a responder, or a support unit. A report filed
here is written to a database and read by nobody.

That makes public deployment a different question to it being for most student
projects. If a page that says "report an incident" and "SOS" is reachable from
a search engine, someone in genuine distress can find it, use it, and wait for
help that is never coming. The failure is silent — they get a confirmation
screen with an incident number.

So the rule for this repository:

> **Do not put this on a public address while it presents itself as a working
> safety service.** Targets 1 and 2 are fine. Target 3 requires either a real
> operational backing, or the interstitial described in section 5.1 that makes
> the demo status unmissable before anyone can file anything.

This is not a hypothetical objection. It is the reason section 5 has a
checklist rather than a set of instructions.

---

## 1. What gets deployed

Two artifacts and a database.

| Artifact | Built by | Output |
| --- | --- | --- |
| Backend | `mvnw clean package` | `backend/target/ufh-safety-backend-0.0.1-SNAPSHOT.jar` (~59 MB, embedded Tomcat) |
| Frontend | nothing to build | `frontend/` served as static files |
| Database | SQL scripts in `database/` | MySQL 8 schema |

The frontend is plain HTML, CSS and JavaScript with no build step, so
"deploying" it means copying a directory behind any static file server.

---

## 2. Configuration

Nothing is hardcoded. Every environment-specific value is an environment
variable, and the backend refuses to start without a signing key.

| Variable | Required | Notes |
| --- | --- | --- |
| `DB_URL` | yes in practice | Defaults to `localhost:3306`, which is rarely right |
| `DB_USERNAME` | yes | |
| `DB_PASSWORD` | yes | |
| `JWT_SECRET` | **yes, no default** | Minimum 32 characters for HS256 |

`JWT_SECRET` deliberately has no fallback. A key committed to this repository
would let anyone who can read the repository mint a token for any registered
account, so the application fails fast instead of starting insecurely.

Tokens are signed, not encrypted, and a token signed with one key is rejected
by a backend running another. Rotating the key signs everyone out. That is the
intended behaviour, and it is the fastest way to revoke every session at once.

---

## 3. Database setup

Order matters. Run these once against a new database:

```sql
-- 1. Create the database with the right character set.
SOURCE database/01-create-database.sql;

-- 2. Only needed for a database created before the staff-accounts change.
--    ddl-auto=update adds columns but will NOT relax an existing NOT NULL,
--    so JPA cannot do this for you. Skipping it makes staff inserts fail at
--    runtime with what looks like a data bug.
SOURCE database/02-alter-users-student-number-nullable.sql;

-- 3. Synthetic accounts for development ONLY. See the warning below.
SOURCE database/seed.sql;
```

JPA creates the `users` and `incidents` tables on first start.

**`seed.sql` must never run anywhere real.** It creates six accounts that all
share the password `DevPassword123!`. That is acceptable for a local prototype
holding synthetic data and unacceptable anywhere else.

---

## 4. Target 1 — local, always-on

This is running now. The full stack lives outside the repository at
`C:\Users\Admin\ufh-local\`, deliberately, because it holds the signing key and
a 900 MB MySQL install.

```
ufh-local\
  env.cmd            paths, ports, DB settings, JWT signing key
  start-all.cmd      starts MySQL, backend, frontend
  stop-all.cmd       clean shutdown
  rebuild.cmd        rebuild the jar after changing Java code
  mysql\             MySQL 8.0.41 portable, no installer, no admin rights
  mysqldata\         the database itself. Back this up, not the binaries.
```

| Port | Service |
| --- | --- |
| 3399 | MySQL — not 3306, so it cannot collide with an existing install |
| 8080 | Spring Boot |
| 5500 | Static frontend — the CORS allow-list only contains 5500 |

All three bind to localhost only.

**Start it:** run `start-all.cmd`, then open
`http://127.0.0.1:5500/frontend/login.html`

### 4.1 Surviving a reboot

Not enabled by default. To turn it on:

1. Press `Win+R`, type `shell:startup`, press Enter.
2. Put a shortcut to `C:\Users\Admin\ufh-local\start-all.cmd` in that folder.

Delete the shortcut to undo it. No administrator rights are needed either way,
which matters because we do not have them on lab machines.

`start-backend.cmd` polls `mysqladmin ping` before launching, so the backend
cannot lose the start-up race and die on a connection refused.

### 4.2 The two things that catch people out

**The backend runs a built jar, not your source.** Change Java code and nothing
happens until `rebuild.cmd`, then `stop-all.cmd` and `start-all.cmd`. This is
the price of starting in seconds instead of recompiling on every boot.

**`MOCK` must be `false` locally and `true` in Git.** Committing `false` breaks
every teammate without a backend running, and it presents as a broken page
rather than a configuration problem. A `pre-commit` hook blocks it. Hooks are
not committed, so install your own:

```sh
# in .git/hooks/pre-commit, then chmod +x
git diff --cached --name-only | grep -q '^frontend/js/config\.js$' && \
  git show ":frontend/js/config.js" | grep -qE '^export const MOCK = false' && \
  { echo "commit blocked: MOCK = false is staged"; exit 1; }
exit 0
```

---

## 5. Target 3 — what public deployment would require

Not a to-do list to work through casually. Every item is a reason the system is
not ready, and the first one is not a technical control.

### 5.1 Make the demo status unmissable

Before anything else. A safety service that does not work must say so before it
takes a report, not in a footer.

- An interstitial on first load that must be dismissed explicitly, stating that
  this is a university prototype and that **no report reaches emergency
  services**.
- The same sentence on the report form itself, above the submit button.
- Real emergency numbers displayed on that interstitial — SAPS 10111, the GBV
  Command Centre 0800 428 428 — so someone who arrived here by mistake leaves
  with something useful.

### 5.2 Transport security

- TLS everywhere. Right now the JWT crosses the network in clear text, so
  anyone on the same Wi-Fi can read it and replay it.
- HSTS, and redirect HTTP to HTTPS.
- `BASE_URL` in `frontend/js/config.js` becomes an `https://` origin.

### 5.3 Database

- Root with a blank password must go. A dedicated application user with
  `SELECT`, `INSERT`, `UPDATE` on this schema only, and no `DROP`.
- `spring.jpa.hibernate.ddl-auto` must become `validate`, with real versioned
  migrations (Flyway or Liquibase). `update` is a development convenience: it
  adds columns, silently ignores anything it cannot do safely, and will not
  narrow or remove. Introducing a migration tool needs a team decision first —
  see the "no new libraries without flagging it" rule in `Claude.md`.
- Backups, and a restore that has actually been tested.

### 5.4 Authentication and abuse

- **Rate limiting on `/api/auth/login`.** There is none. Nothing currently
  slows down an attacker guessing passwords, and BCrypt only makes each guess
  expensive for us as well as them.
- Account lockout or exponential backoff after repeated failures.
- Rate limiting on `POST /api/incidents`, or one person can fill the dispatcher
  queue with noise.
- Shorten token lifetime from 120 minutes, and add refresh tokens.

### 5.5 CORS

`SecurityConfig` pins the allow-list to `localhost:5500` and `127.0.0.1:5500`,
hardcoded in a `List.of(...)`. That must move to configuration and name the
real frontend origin. Do not replace it with `*` — with credentials in play
that is the whole access control gone.

### 5.6 Data protection

- GBV case content lives in separate tables with stricter access. Only
  `gbv_officer` and `admin` may read it. Verify this holds at the query level
  before exposing anything, not just in the UI.
- POPIA applies to real student data. A prototype holding synthetic records has
  no obligations; the moment one real report is filed, it does.
- Check logs do not carry personal data. The 500 handler already keeps
  exception messages out of responses, and there is a test asserting
  `password_hash` never appears in a body, but log output has not been audited.

### 5.7 Testing

- No automated integration tests. Service logic is unit tested against a mocked
  repository, and endpoints were verified by hand against a live MySQL. A bad
  query would pass the suite. `@SpringBootTest` with Testcontainers, or at
  minimum a scripted end-to-end run, before anyone relies on this.

### 5.8 Operations

- Health endpoint and uptime monitoring. Spring Boot Actuator gives
  `/actuator/health` cheaply, but it is not currently a dependency.
- Somewhere to see logs that is not a minimised console window.
- A named person who notices when it is down.

---

## 6. Verifying a deployment

Same checks regardless of target. Substitute the base URL.

```sh
# 1. Backend is answering and rejecting anonymous access
curl -s -o /dev/null -w "%{http_code}\n" $API/incidents          # expect 401

# 2. Login works
TOKEN=$(curl -s -X POST $API/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"...","password":"..."}' | jq -r .token)

# 3. Authenticated read works
curl -s -o /dev/null -w "%{http_code}\n" $API/incidents \
  -H "Authorization: Bearer $TOKEN"                              # expect 200

# 4. Unknown paths are 404, not 500
curl -s -o /dev/null -w "%{http_code}\n" $API/nope               # expect 404

# 5. CORS preflight from the real frontend origin
curl -s -i -X OPTIONS $API/incidents \
  -H "Origin: $FRONTEND_ORIGIN" \
  -H 'Access-Control-Request-Method: POST' | grep Access-Control-Allow-Origin

# 6. Frontend is not stuck in mock mode
curl -s $FRONTEND/js/config.js | grep 'export const MOCK'        # expect false
```

Check 6 is the one people forget. A frontend deployed with `MOCK = true` looks
perfect and talks to nothing.

---

## 7. Rolling back

The jar is self-contained, so rollback is redeploying the previous one.

The database is not. `ddl-auto=update` never removes a column, so an older jar
usually starts fine against a newer schema — but that is a property of the
current settings, not a guarantee. Once real migrations exist, every migration
needs a tested down-path.
