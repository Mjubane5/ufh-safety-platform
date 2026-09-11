# UFH Safety Platform - Agent Deployment Instructions

## Task: Deploy to Railway (Fully Operational with Auto-Updates)

This document is written for an agent (or a person scripting the same steps).
It replaces an earlier version that described a three-service dashboard
click-through and a `git push origin main` at the end — both wrong for this
repo: the [Dockerfile](Dockerfile) bundles frontend and backend into one
service, and [CLAUDE.md](Claude.md)'s git rules require a branch and PR, never
a direct push to `main`.

**Already deployed** at the time of writing:
https://ufh-safety-platform-production.up.railway.app

Use this document to redeploy from scratch (a new Railway project) or to
understand/modify the existing one.

---

## What an agent cannot do here

- Create the Railway account, or complete the browser-based login/OAuth flow.
  `railway login` opens a browser; a human has to finish it.
- Authorize Railway's GitHub App to access the repo, if it isn't already.

Everything after authentication is CLI-scriptable and non-interactive.

---

## Step 1: Authenticate

```bash
railway login
railway whoami   # confirm
```

## Step 2: Link the project

```bash
railway link -p <project-name>   # existing project
# or, to create a new one:
railway up --new -y              # only if starting completely fresh
```

## Step 3: Add MySQL

```bash
railway add --database mysql
```

Do not hand-copy the resulting host/user/password anywhere — step 5 uses
Railway's live variable-reference syntax instead, so the app always has
current credentials even if they rotate.

## Step 4: Connect the app service to GitHub, on `main`

```bash
railway service source connect --repo Mjubane5/ufh-safety-platform --branch main --service ufh-safety-platform
```

`main` is what should be tracked — it's the branch with reviewed, merged code.
Never point this at a feature branch as a shortcut.

## Step 5: Set environment variables

```bash
railway variable set 'DB_URL=jdbc:mysql://${{MySQL.RAILWAY_PRIVATE_DOMAIN}}:3306/ufh_safety_platform?useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true&serverTimezone=Africa/Johannesburg' --service ufh-safety-platform
railway variable set 'DB_USERNAME=${{MySQL.MYSQLUSER}}' --service ufh-safety-platform
railway variable set 'DB_PASSWORD=${{MySQL.MYSQLPASSWORD}}' --service ufh-safety-platform
```

Generate `JWT_SECRET` locally so it's never typed into a shell command or
logged, and pipe it straight into `--stdin`:

```bash
node -e "process.stdout.write(require('crypto').randomBytes(48).toString('base64'))" | railway variable set JWT_SECRET --stdin --service ufh-safety-platform
```

This must be a fresh value, not the JWT secret from `ufh-local/env.cmd` —
that one is for local development only.

`RAILWAY_PRIVATE_DOMAIN` keeps the database on Railway's internal network.
**Do not** add a public TCP proxy to the MySQL service as a matter of course —
only do it for a specific one-off need (e.g. inspecting data from a machine
outside Railway), and remove the proxy again immediately after. If you're
trying to run a SQL script against the database and the network you're on
blocks outbound TCP to arbitrary ports (common on restrictive/campus
networks), don't fight it: `createDatabaseIfNotExist=true` on `DB_URL` (above)
makes manual schema creation unnecessary in the first place.

**Never set or run `database/seed.sql` against this database.** It seeds every
account with the same publicly-known password (`DevPassword123!`); the file's
own comments say local-dev-only. A production/demo database should start
empty — real users register through `/api/auth/register`.

## Step 6: Generate a domain and deploy

```bash
railway domain --service ufh-safety-platform --port 8080
railway redeploy --service ufh-safety-platform --from-source --yes
```

## Step 7: Verify

```bash
railway logs --service ufh-safety-platform --deployment --lines 60
```

Look for:
```
Started UfhSafetyApplication in <N> seconds
HikariPool-1 - Start completed.
Tomcat started on port 8080
```

Then hit the live endpoints:
```bash
curl -s -o /dev/null -w '%{http_code}\n' https://<your-domain>/
curl -s -o /dev/null -w '%{http_code}\n' https://<your-domain>/login.html
curl -s -X POST https://<your-domain>/api/auth/register -H 'Content-Type: application/json' -d '{}'
```

A `200` on the first two and a `400` with the standard error shape
(`{"error":"...","message":"...","field":"email"}`) on the third confirms the
frontend, backend, and database are all wired up correctly.

---

## Maintenance: making code changes

This is the one place the earlier version of this doc was wrong. The repo's
own [CLAUDE.md](Claude.md) rule stands above any convenience shortcut:

1. Branch off `main`, make the change, open a PR.
2. Once **merged** to `main`, Railway's GitHub integration redeploys
   automatically (2-5 minutes) — nothing further needed.

Do not push directly to `main` to trigger a faster deploy.

## Rollback

```bash
railway deployment list --service ufh-safety-platform --json
railway redeploy --service ufh-safety-platform   # redeploys the current latest deployment; to go further back, use the dashboard's Deployments tab and "Redeploy" on a specific prior one
```

## Troubleshooting

**Backend won't start / crashes immediately**: check `JWT_SECRET` is actually
set — the app refuses to start without it.

**Database connection errors**: confirm `DB_URL` references
`${{MySQL.RAILWAY_PRIVATE_DOMAIN}}` and that the MySQL service shows as
running (`railway status`).

**"Unknown database" in logs**: `DB_URL` is missing
`createDatabaseIfNotExist=true`.

**Can't reach a database service directly from your machine to run a manual
query**: try `railway connect <service> --ssh --tunnel-only`. If that times
out too, the network you're on likely blocks outbound SSH/arbitrary TCP —
this doesn't block the deployed app itself, since Railway's own network
traffic between services doesn't go through your machine at all.
