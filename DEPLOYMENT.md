# UFH Safety Platform - Deployment Guide

This guide walks you through deploying the UFH Safety Platform to the cloud for full production use.

## Architecture Overview

Your application has three components:
- **Frontend**: Static HTML/CSS/JavaScript
- **Backend**: Java Spring Boot REST API
- **Database**: MySQL

All three need to be running for the application to work.

---

## Option 1: Railway (Recommended - Easiest)

Railway is the simplest option for deploying both backend and database with automatic updates from GitHub.

### Prerequisites
- Railway account (create at https://railway.app)
- GitHub account with this repository

### Steps

#### 1. Connect Repository to Railway
1. Go to [https://railway.app/dashboard](https://railway.app/dashboard)
2. Click "New Project" → "Deploy from GitHub repo"
3. Select `Mjubane5/ufh-safety-platform`
4. Authorize Railway to access your GitHub account

#### 2. Create Backend Service
1. In Railway, click "Add Service" → "Docker"
2. Set Dockerfile path to: `Dockerfile`
3. Set port to: `8080`

#### 3. Add MySQL Database
1. Click "Add Service" → "MySQL"
2. Railway creates a MySQL instance automatically
3. Copy the connection details from Railway's UI

#### 4. Configure Environment Variables
In the Backend service settings, add these variables:

```
DB_URL=mysql://user:password@host:port/ufh_safety_platform
DB_USERNAME=root
DB_PASSWORD=<your-railway-mysql-password>
JWT_SECRET=<generate-a-random-string-32-chars-minimum>
SPRING_DATASOURCE_URL=jdbc:mysql://host:port/ufh_safety_platform?useSSL=false&serverTimezone=UTC
```

You can find these values in the MySQL service variables on Railway.

#### 5. Deploy Frontend
1. Click "Add Service" → "Static Site"
2. Point to `frontend` directory
3. Build command: (leave empty - it's static HTML)
4. Publish directory: `frontend`

#### 6. Update Frontend Configuration
Once deployed, edit `frontend/js/config.js`:

```javascript
// Change from:
export const BASE_URL = 'http://localhost:8080/api';
export const MOCK = true;

// To:
export const BASE_URL = 'https://<your-railway-backend-url>/api';
export const MOCK = false;
```

Then commit and push:
```bash
git add frontend/js/config.js
git commit -m "Update API endpoint for production"
git push
```

**Automatic redeploy**: Railway watches your GitHub repo. Every push to `main` automatically rebuilds and deploys.

---

## Option 2: Render (Alternative)

Render has a free tier and simple GitHub integration.

### Steps

#### 1. Deploy Backend
1. Go to [https://render.com](https://render.com)
2. Click "New +" → "Web Service"
3. Connect your GitHub repository
4. Configure:
   - **Name**: ufh-safety-backend
   - **Build command**: `mvn clean package -f backend/pom.xml -DskipTests`
   - **Start command**: `java -jar backend/target/*.jar`
   - **Plan**: Free
5. Add environment variables (same as Railway above)

#### 2. Deploy Database
1. Click "New +" → "MySQL"
2. Create a MySQL instance
3. Copy connection details

#### 3. Deploy Frontend
1. Click "New +" → "Static Site"
2. Connect your repository
3. **Build command**: (empty - static HTML)
4. **Publish directory**: `frontend`

Then update `frontend/js/config.js` with your Render backend URL.

---

## Option 3: Docker Compose (For Testing Locally)

Test the full stack locally before cloud deployment:

### Create `docker-compose.yml` in project root:

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

  backend:
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    environment:
      DB_URL: jdbc:mysql://mysql:3306/ufh_safety_platform?useSSL=false&serverTimezone=UTC
      DB_USERNAME: root
      DB_PASSWORD: root
      JWT_SECRET: your-secret-key-min-32-chars-change-this
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/ufh_safety_platform?useSSL=false&serverTimezone=UTC
    depends_on:
      mysql:
        condition: service_healthy
    networks:
      - ufh-network

  frontend:
    image: nginx:alpine
    ports:
      - "80:80"
    volumes:
      - ./frontend:/usr/share/nginx/html:ro
    depends_on:
      - backend
    networks:
      - ufh-network

networks:
  ufh-network:
    driver: bridge
```

Run locally:
```bash
docker-compose up -d
```

Access at `http://localhost`

---

## Critical Configuration Checklist

Before deploying to production:

- [ ] **JWT_SECRET**: At least 32 random characters (not committed to git)
- [ ] **CORS**: Backend must allow frontend's production domain
- [ ] **Database**: Created and accessible from backend service
- [ ] **Frontend config**: `MOCK = false` and correct `BASE_URL`
- [ ] **Database initialization**: SQL scripts in `database/` folder run on first start
- [ ] **SSL/TLS**: Use HTTPS (Railway and Render provide free SSL)

---

## Environment Variables Reference

| Variable | Example | Required |
|----------|---------|----------|
| `DB_URL` | `jdbc:mysql://localhost:3306/ufh_safety_platform` | Yes |
| `DB_USERNAME` | `root` | Yes |
| `DB_PASSWORD` | `password123` | Yes |
| `JWT_SECRET` | Random 32+ char string | Yes |
| `SPRING_DATASOURCE_URL` | Same as DB_URL | Yes |
| `MOCK` | `false` | No (frontend only) |
| `BASE_URL` | `https://your-backend.com/api` | No (frontend only) |

---

## Monitoring & Logs

**Railway**: Logs visible in Dashboard → select service → "Logs" tab
**Render**: Logs visible in Dashboard → select service → "Logs" tab

---

## Making Changes & Auto-Deployment

Once deployed:

1. Make code changes locally
2. Commit and push to GitHub
3. Watch your platform redeploy automatically (takes 2-5 minutes)

No manual intervention needed! This is the key to keeping it "fully operational" as you make changes.

---

## Troubleshooting

**Backend won't start**: Check that `JWT_SECRET` is set and has 32+ characters

**Database connection fails**: Verify `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` are correct

**Frontend can't reach backend**: Ensure `BASE_URL` in `frontend/js/config.js` matches your deployed backend URL

**CORS errors**: Backend is blocking requests from your frontend domain - add to `SecurityConfig.java`

---

For questions, refer to:
- Railway docs: https://docs.railway.app
- Render docs: https://render.com/docs
- Spring Boot: https://spring.io/projects/spring-boot
