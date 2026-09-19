# Hosting the prototype on Railway

How to put this project on the internet so it runs continuously and redeploys
itself whenever we push to `main`.

Read `docs/development-challenges.md` for why the decisions below were made.

## Why Railway

We need three things at once: somewhere to run a Spring Boot container,
a **MySQL** database, and automatic redeploy from GitHub.

| Option | Verdict |
| --- | --- |
| GitHub Pages | Serves static files only. Cannot run Spring Boot or MySQL, and Pages on a private repository needs a paid plan. |
| Render | Free tier exists, but its managed database is **PostgreSQL only** — there is no managed MySQL. Free services also sleep after 15 minutes and take 30–60 seconds to wake, which is a bad way to start a demonstration. |
| Railway | Runs the Dockerfile, offers managed MySQL, and redeploys on push. This is the one. |

Cost: Railway gives new accounts a one-off **$5 trial credit, valid 30 days**.
That covers a small container plus a MySQL instance through the 18 September
submission. After the trial the Hobby plan is **$5/month**. There is no
permanent free tier — plan for one person to pay $5 or to deploy inside the
trial window and take the demonstration recording as the backup.

## What gets deployed

One service, not two. The Dockerfile copies `frontend/` into
`src/main/resources/static` before the Maven build, so the pages travel inside
the jar and Spring Boot serves them. That means:

- One URL. `https://<app>.up.railway.app/login.html` and
  `https://<app>.up.railway.app/api/incidents` are the same application.
- **No CORS.** The pages and the API share an origin, so there is no
  cross-origin request for a browser to block. The allow-list in
  `SecurityConfig` only matters on our own machines.
- One push updates both halves, and there is one thing to pay for.

`frontend/js/config.js` works out which mode it is in from the hostname, so
nothing has to be edited before deploying: on `localhost` it uses mock data and
`http://localhost:8080/api`, and anywhere else it uses the real backend at
`/api`. This is why `MOCK` can stay committed as it is.

## Before you deploy

`IS_PROTOTYPE` in `frontend/js/config.js` must be `true`. It drives the
demonstration notice, and this app accepts GBV and SOS reports that reach
nobody. Publishing it without that notice is the one mistake here with a
real-world cost. See `frontend/js/demo-notice.js` for the reasoning.

## Steps

1. Sign in to <https://railway.com> **with GitHub** and grant access to this
   repository. It is private; Railway needs explicit permission.
2. **New Project → Deploy from GitHub repo →** pick this repository.
   Railway finds the `Dockerfile` at the root and uses it. No build command
   to configure.
3. In the same project, **New → Database → Add MySQL**. It provisions and
   exposes its connection details as variables.
4. Open the **app service → Variables** and set:

   | Variable | Value |
   | --- | --- |
   | `DB_URL` | `jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Africa/Johannesburg` |
   | `DB_USERNAME` | `${{MySQL.MYSQLUSER}}` |
   | `DB_PASSWORD` | `${{MySQL.MYSQLPASSWORD}}` |
   | `JWT_SECRET` | a fresh 64-character random string |

   The `${{MySQL.*}}` form is Railway's variable reference: it fills in the
   database's real values at deploy time, so no credential is ever typed into
   a file or into the repository.

   **Generate a new `JWT_SECRET`. Do not reuse the one in `ufh-local/env.cmd`.**
   That key is on a laptop and in a folder we hand around; a hosted app needs
   its own. `application.properties` gives it no default, so the container will
   refuse to start rather than run with a guessable key.

5. **Settings → Networking → Generate Domain.** Railway assigns the port
   through `PORT`, which `application.properties` already reads.
6. Wait for the deploy. Hibernate creates the tables on first start
   (`ddl-auto=update`).
7. **Automatic Demo Account Seeding.** With `DemoDataSeeder` in place, Spring Boot
   automatically provisions synthetic student, campus control, GBV officer, and
   responder duty accounts on initial startup if they are absent. Manual MySQL CLI
   execution (`database/seed.sql`) is no longer required on Railway or local development.
   
   Check the startup logs: `DemoDataSeeder: Demonstration accounts check completed successfully.`

8. Open `https://<app>.up.railway.app/`. You should get the landing page, then
   the login page, with the demonstration notice on top.

## Automatic updates

Railway watches the repository once connected. Every merge into `main` triggers
a rebuild and redeploy, roughly two to four minutes. Nothing to configure and
no GitHub Actions workflow needed.

To limit it to `main`, set **Settings → Source → Branch** to `main`. Worth
doing: without it a push to any branch can redeploy the live site.

## Known limitations of the hosted build

- **Anyone can register.** `POST /api/auth/register` is public, so a stranger
  who finds the URL can create an account and file incidents into our database.
  Acceptable for a prototype carrying a demonstration notice; it would not be
  acceptable for anything real.
- **No backups.** Railway's MySQL on the trial has none configured. Everything
  in it is synthetic and re-seedable from `database/seed.sql` and `DemoDataSeeder`, so treat the
  hosted database as disposable.
- **`ddl-auto=update` never drops anything.** Rename a field and the old column
  stays behind holding its data. Fine for us, wrong for production.
- **The trial expires.** Services pause when the credit runs out or 30 days
  pass. Data is preserved and resumes on upgrade, but a paused service on
  demonstration day is a failed demonstration. Record a video walkthrough as a
  fallback and keep the local stack in `ufh-local/` working.
