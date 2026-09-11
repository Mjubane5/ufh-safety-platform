# UFH Safety Platform - Deployment Guide

This guide reflects how the platform is actually deployed. It replaces an
earlier draft written before the [Dockerfile](Dockerfile) was changed to bundle the
frontend into the backend jar (see "Architecture" below) — that draft described
a three-service setup and a manual `frontend/js/config.js` edit that no longer
apply.

**Currently live at:** https://ufh-safety-platform-production.up.railway.app

## Architecture

The app is **one deployable unit**, not three. The [Dockerfile](Dockerfile) copies
`frontend/` into the Spring Boot jar's `static/` folder at build time, so the
same container answers both `https://host/login.html` and
`https://host/api/incidents`. One origin means no CORS to configure, and one
push updates both halves at once.

That leaves two things to run:
- **App**: the Docker image built from the repo root `Dockerfile`
- **Database**: MySQL

`frontend/js/config.js` needs **no manual edit for deployment**. It detects
`window.location.hostname` at load time: on `localhost`/`127.0.0.1` it talks to
`http://localhost:8080/api` with `MOCK` on; anywhere else it uses the relative
path `/api` (same origin) with `MOCK` off automatically. See the comments in
that file for the full reasoning.

---

## Option 1: Railway (what's actually deployed)

### Prerequisites
- A Railway account (https://railway.app) — sign-up/login happens in your own
  browser; an agent cannot create the account or authorize the GitHub
  connection for you.
- [Railway CLI](https://docs.railway.app/guides/cli): `npm install -g @railway/cli`

### Steps

#### 1. Authenticate and link

```bash
railway login              # opens a browser to sign in
railway link -p <project>  # or omit -p and pick interactively
```

#### 2. Add MySQL

```bash
railway add --database mysql
```

No manual connection-string copying needed — the app reads it via a Railway
variable reference (step 4).

#### 3. Connect the app service to GitHub, tracking `main`

```bash
railway service source connect --repo Mjubane5/ufh-safety-platform --branch main --service ufh-safety-platform
```

Railway auto-detects the root `Dockerfile` and builds with it. Every push to
`main` (i.e. every merged PR — see [CLAUDE.md](Claude.md)'s git rules) redeploys
automatically.

#### 4. Set environment variables on the app service

```bash
railway variable set 'DB_URL=jdbc:mysql://${{MySQL.RAILWAY_PRIVATE_DOMAIN}}:3306/ufh_safety_platform?useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true&serverTimezone=Africa/Johannesburg' --service ufh-safety-platform
railway variable set 'DB_USERNAME=${{MySQL.MYSQLUSER}}' --service ufh-safety-platform
railway variable set 'DB_PASSWORD=${{MySQL.MYSQLPASSWORD}}' --service ufh-safety-platform
railway variable set JWT_SECRET --stdin --service ufh-safety-platform   # paste/pipe a random 32+ char value
```

Two things worth calling out:
- The `${{MySQL.XYZ}}` syntax is a **live Railway variable reference**, not a
  value you copy-paste. It always points at the MySQL service's current
  credentials, even if they rotate.
- `RAILWAY_PRIVATE_DOMAIN` keeps the database connection on Railway's internal
  network — it is never reachable from the public internet. Don't add a public
  TCP proxy to the MySQL service unless you have a specific reason to reach it
  from outside Railway (e.g. a one-off manual query), and remove the proxy
  again afterward.
- `createDatabaseIfNotExist=true` means the `ufh_safety_platform` schema is
  created automatically on first connect — you do not need to run
  `database/01-create-database.sql` by hand. Hibernate (`ddl-auto=update`,
  see `application-prod.properties`) then creates the tables on first boot.

**Never run `database/seed.sql` against this database.** Every seeded account
shares one publicly known password (`DevPassword123!`) — the file's own header
says local dev only. Real accounts go through `/api/auth/register`.

#### 5. Generate a public domain and deploy

```bash
railway domain --service ufh-safety-platform --port 8080
railway redeploy --service ufh-safety-platform --from-source --yes
```

#### 6. Verify

```bash
railway logs --service ufh-safety-platform --deployment --lines 60
curl -s -o /dev/null -w '%{http_code}\n' https://<your-domain>/
```

Look for `Started UfhSafetyApplication` and a Hikari pool connection message
in the logs, and a `200` from curl.

---

## Option 2: Render (untested alternative)

Same single-Docker-image architecture applies — there is no separate frontend
service to configure.

1. **New Web Service** → connect the repo → Render detects the root
   `Dockerfile` and builds it directly (no build/start command needed).
2. **New MySQL** instance, then set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`,
   `JWT_SECRET` on the web service the same way as the Railway table below.
3. No frontend step — it ships inside the same image.

This repo has not actually been deployed to Render; treat this section as a
starting point, not a verified path.

---

## Option 3: Docker Compose (for testing locally)

```yaml
version: '3.8'

services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: ufh_safety_platform
    ports:
      - "3306:3306"
    volumes:
      - ./database:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      timeout: 5s
      retries: 10

  app:
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    environment:
      DB_URL: jdbc:mysql://mysql:3306/ufh_safety_platform?useSSL=false&serverTimezone=UTC
      DB_USERNAME: root
      DB_PASSWORD: root
      JWT_SECRET: local-only-secret-min-32-characters-change-this
    depends_on:
      mysql:
        condition: service_healthy

networks:
  default:
    driver: bridge
```

Run with `docker-compose up -d`, then open `http://localhost:8080`. There is
one service to reach, not a separate frontend origin — the container serves
both.

---

## Environment Variables Reference

| Variable | Purpose | Required |
|----------|---------|----------|
| `DB_URL` | JDBC connection string. Include `createDatabaseIfNotExist=true` if the schema doesn't exist yet | Yes |
| `DB_USERNAME` | MySQL user | Yes |
| `DB_PASSWORD` | MySQL password | Yes |
| `JWT_SECRET` | Random 32+ char string, distinct per environment (never reuse your local dev secret) | Yes |
| `PORT` | Defaults to `8080`; Railway/Render set this automatically | No |

`SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD` from the old guide are **not
needed** — `application.properties` already reads `DB_URL`/`DB_USERNAME`/
`DB_PASSWORD` directly (`spring.datasource.url=${DB_URL:...}` etc).

There is nothing to set on the frontend side — `MOCK` and `BASE_URL` are
derived automatically from the hostname at page load (see
`frontend/js/config.js`).

---

## Critical Configuration Checklist

- [ ] `JWT_SECRET` set, 32+ random characters, not committed to git, not reused from local dev
- [ ] Database created and reachable from the app service (private network preferred over a public proxy)
- [ ] `database/seed.sql` has **not** been run against this database
- [ ] HTTPS in place (Railway/Render provide this automatically)

---

## Monitoring & Logs

**Railway CLI**: `railway logs --service ufh-safety-platform --deployment --lines 100`
**Railway dashboard**: select the service → "Logs" tab

---

## Making Changes & Auto-Deployment

1. Branch, commit, open a PR against `main` (never commit directly to `main` — see [CLAUDE.md](Claude.md))
2. Once merged, Railway detects the push to `main` and rebuilds/redeploys automatically (2-5 minutes)

---

## Troubleshooting

**Backend won't start**: check `JWT_SECRET` is set (the app refuses to start without it — see `backend/README-BACKEND.md`)

**Database connection fails**: confirm `DB_URL` points at the MySQL service's `RAILWAY_PRIVATE_DOMAIN` (not a stale public proxy host) and that the MySQL service is running

**"Unknown database" error**: `DB_URL` is missing `createDatabaseIfNotExist=true` and the schema was never created

**Frontend shows blank page**: this only happens if `BASE_URL`/`MOCK` were hardcoded somewhere outside `config.js`'s auto-detection — check nothing overrides it

---

For questions, refer to:
- Railway docs: https://docs.railway.app
- Railway CLI reference: https://docs.railway.app/reference/cli-api
- Spring Boot: https://spring.io/projects/spring-boot
