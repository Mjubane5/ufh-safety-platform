# UFH Safety Platform - Agent Deployment Instructions

## Task: Deploy to Railway (Fully Operational with Auto-Updates)

This document contains everything an agent needs to deploy the UFH Safety Platform to production.

---

## Prerequisites (Agent must verify)

- [ ] Railway account created (https://railway.app) - if not, create one
- [ ] GitHub account has admin access to Mjubane5/ufh-safety-platform
- [ ] Railway personal access token available (or create one during setup)

---

## Step 1: Initialize Railway Project

**Action**: Create a new Railway project connected to this GitHub repository.

1. Go to https://railway.app/dashboard
2. Click **"New Project"**
3. Select **"Deploy from GitHub repo"**
4. Search for and select: `Mjubane5/ufh-safety-platform`
5. Click **"Deploy Now"**

**Wait for**: The initial project to be created (1-2 minutes)

**Result**: You should see a project dashboard with an empty service list.

---

## Step 2: Add MySQL Database Service

**Action**: Create a MySQL database instance in Railway.

1. In Railway project dashboard, click **"Add Service"**
2. Select **"MySQL"**
3. Wait for deployment (2-3 minutes)
4. Once running, click the MySQL service to view details

**Record these values** from MySQL service → "Variables" tab:
```
MYSQL_URL=
MYSQL_USER=
MYSQL_PASSWORD=
MYSQL_HOST=
MYSQL_PORT=
```

**Critical**: Keep these values secure - you'll use them in Step 4.

---

## Step 3: Add Backend Service (Docker)

**Action**: Deploy the Java Spring Boot backend using the Dockerfile.

1. In Railway project, click **"Add Service"**
2. Select **"GitHub Repo"** (not Docker)
3. Confirm the repo is `Mjubane5/ufh-safety-platform`
4. Click **"Deploy"**

Railway auto-detects the Dockerfile and builds it.

**Wait for**: Build to complete (3-5 minutes). You'll see logs in the "Build Logs" tab.

**After build completes**:
- Click the backend service
- Go to **"Settings"** tab
- Find **"Internal Port"** - set it to `8080`
- Go to **"Deploy"** tab - verify it shows the Dockerfile

---

## Step 4: Configure Backend Environment Variables

**Action**: Set environment variables for the backend to connect to MySQL and run securely.

In Railway, with the backend service selected:

1. Click **"Variables"** tab
2. Add these variables by clicking **"New Variable"** for each:

| Key | Value | Notes |
|-----|-------|-------|
| `DB_URL` | `jdbc:mysql://[MYSQL_HOST]:[MYSQL_PORT]/ufh_safety_platform?useSSL=false&serverTimezone=UTC` | Replace [MYSQL_HOST] and [MYSQL_PORT] with values from Step 2 |
| `DB_USERNAME` | `root` | Standard MySQL default |
| `DB_PASSWORD` | [MYSQL_PASSWORD] | Copy from MySQL service variables (Step 2) |
| `JWT_SECRET` | `your-random-secret-key-at-least-32-characters-long-change-this-value` | Generate a random string, min 32 chars |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://[MYSQL_HOST]:[MYSQL_PORT]/ufh_safety_platform?useSSL=false&serverTimezone=UTC` | Same as DB_URL |
| `SPRING_DATASOURCE_USERNAME` | `root` | Same as DB_USERNAME |
| `SPRING_DATASOURCE_PASSWORD` | [MYSQL_PASSWORD] | Same as DB_PASSWORD |

**Click "Deploy"** to apply changes.

**Wait for**: Backend to restart (1-2 minutes). Check logs to confirm it starts successfully.

**Verify**: In logs, you should see:
```
UfhSafetyApplication : Started UfhSafetyApplication
```

---

## Step 5: Get Backend Public URL

**Action**: Find the public URL where the backend is running.

1. In Railway, click the backend service
2. Go to **"Settings"** tab
3. Look for **"Domains"** section
4. Copy the domain (looks like: `https://ufh-safety-backend-production.up.railway.app`)

**Record this URL** - you'll need it in Step 7.

---

## Step 6: Add Frontend Service (Static Site)

**Action**: Deploy the frontend HTML/CSS/JavaScript as a static website.

1. In Railway project, click **"Add Service"**
2. Select **"GitHub Repo"**
3. Confirm repo: `Mjubane5/ufh-safety-platform`
4. Click **"Deploy"**

Railway detects it's not a standard framework and treats it as static.

**Configure**:
- Root directory (if prompted): `/frontend`
- Build command: (leave blank - no build needed)
- Start command: (leave blank - static files)

---

## Step 7: Update Frontend Configuration

**Action**: Connect the frontend to the backend URL so it knows where to make API requests.

1. Edit `frontend/js/config.js` in the repository
2. Find these lines:
   ```javascript
   export const BASE_URL = 'http://localhost:8080/api';
   export const MOCK = true;
   ```
3. Replace with:
   ```javascript
   export const BASE_URL = 'https://[BACKEND_URL]/api';
   export const MOCK = false;
   ```
   where `[BACKEND_URL]` is the domain from Step 5 (without `/api`)

4. **Commit and push**:
   ```bash
   git add frontend/js/config.js
   git commit -m "Configure frontend for production deployment"
   git push origin main
   ```

**Wait for**: Railway to automatically redeploy frontend (1-2 minutes)

---

## Step 8: Verify All Services Are Running

**Action**: Confirm all services are operational.

In Railway project dashboard:

1. **MySQL service**: Status should be green "Running"
2. **Backend service**: Status should be green "Running"
3. **Frontend service**: Status should be green "Running"

Click each service and check the **"Logs"** tab - there should be no error messages.

---

## Step 9: Test the Live Application

**Action**: Access the deployed application and verify it works.

1. Click the **Frontend service** in Railway
2. Go to **"Settings"** → **"Domains"**
3. Click the public domain (looks like: `https://ufh-safety-platform-production.up.railway.app`)
4. You should see the login page

**Test workflow**:
1. Click **"Register"**
2. Create a test account (any email/password)
3. Confirm registration succeeds
4. Login with those credentials
5. Confirm dashboard loads
6. Try creating an incident report

**If any step fails**: Check logs in Railway for error messages.

---

## Step 10: Enable Auto-Redeploy on GitHub Push

**Action**: Ensure the platform auto-updates whenever code is pushed.

This should be automatic, but verify:

1. In Railway project settings, check **"GitHub Integration"**
2. Confirm the repo `Mjubane5/ufh-safety-platform` is connected
3. Confirm **"Auto-deploy"** is enabled

**From now on**:
- Any push to `main` branch = automatic rebuild and deploy
- No manual intervention needed
- Changes live within 2-5 minutes

---

## Summary: What Agent Accomplished

✅ MySQL database running and persistent  
✅ Java backend running at public URL with secure JWT configuration  
✅ Frontend running and connected to backend  
✅ Application fully operational and accessible online  
✅ Automatic redeploy on every GitHub push  

---

## Troubleshooting Checklist

**Backend won't start**:
- Check logs in Railway for "JWT_SECRET" error
- Verify all environment variables are set in Step 4
- Confirm MySQL service is running

**Frontend shows blank page**:
- Verify `MOCK = false` in `frontend/js/config.js`
- Check that `BASE_URL` points to correct backend URL (no `/api` at the end)
- Commit and push changes
- Wait for Railway to redeploy

**Registration/Login fails**:
- Check backend logs for database connection errors
- Verify MySQL variables are correct
- Confirm database schema was created (check MySQL logs)

**Services keep restarting**:
- Check logs for error stack traces
- Verify environment variables don't have typos
- Ensure MySQL is fully started before backend starts

---

## Maintenance: Making Code Changes

**When team makes changes and pushes**:

1. Changes are pushed to GitHub: `git push origin main`
2. Railway automatically detects the push
3. Services rebuild and redeploy (2-5 minutes)
4. No action needed from agent or team

**To redeploy manually**: Click "Deploy" button on any service in Railway dashboard.

---

## Emergency: Rollback to Previous Version

If deployment breaks:

1. Go to Railway service
2. Click **"Deployments"** tab
3. Select the previous working deployment
4. Click **"Redeploy"**

Application reverts to that version immediately.

---

## End of Agent Instructions

Once this process is complete, the platform is fully operational and ready for team use. All infrastructure is managed by Railway - no servers to maintain.
