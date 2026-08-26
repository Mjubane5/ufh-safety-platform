# Team Working Agreement & Workload Split

**Project:** Campus Student Safety, Emergency & Wellness Platform
**Repository:** `ufh-safety-platform`
**Module:** Collaborative Capstone Project — CSC 200 / CSC 300
**Institution:** Department of Computational Sciences, University of Fort Hare

**Next progress report:** 31 August 2026
**Final submission:** 18 September 2026
**Presentations:** 21–23 September 2026

> Read this before you write any code. Everyone follows the same rules, or the
> repository turns into eight different projects.

---

## 1. Team and pairing

We work in **pairs**, not as eight individuals. Fewer branches, fewer conflicts,
and each pair reviews the other pair in their half. Pairing is also the peer
mentoring the project brief asks for.

### Backend (Java + MySQL)

| Pair | Members                           | Owns |
| --- |-----------------------------------| --- |
| **A — Foundation** | Sinesipho Malgas, Nondumiso Mbuli | Database schema, authentication endpoints |
| **B — Incidents** | Brains Nkosi, Nkosinathi Mbewana  | Incident endpoints, status lifecycle |

### Frontend (HTML / CSS / JavaScript)

| Pair | Members                            | Owns |
| --- |------------------------------------| --- |
| **C — Shell & Auth** | Scott, Nondumiso Mkhonto | Page layout, shared CSS, `api.js`, login/register |
| **D — Incidents UI** | Mpilwenhle Jubane, Awethu Dyani    | Incident report form, student dashboard |



---

## 2. Decisions made — do not re-open without the group

These were agreed before coding started. If you disagree, raise a GitHub issue.
Do not quietly build it differently.

| Decision | Choice | Reason |
| --- | --- | --- |
| Backend framework | Spring Boot | REST, JSON and JDBC handled for us |
| Real-time updates | Polling every 5 seconds | No socket reconnection logic to debug |
| Map provider | Leaflet + OpenStreetMap | No API key, no quota, no billing |
| Token lifetime | Fixed 8 hours, no refresh | Simpler, adequate for a prototype |
| Evidence storage | File path stored in MySQL | Simpler than BLOBs |
| Field naming | `camelCase` everywhere | Frontend and backend must match exactly |

**The API contract (`docs/api-contract.md`) is the single source of truth.**
If your code and the contract disagree, the contract is right.

---

## 3. Rules — everyone, every day

1. **`git pull` before you start.** Every session, no exceptions.
2. **Never commit directly to `main`.** Always a branch, always a pull request.
3. **One branch per pair per feature.** Name it for the feature, not the person:
   `feature/auth-endpoints`, `feature/incident-form`.
4. **Merge the same day.** Nothing lives longer than 48 hours. Long-lived
   branches are how the two halves stop fitting together.
5. **Stay in your area.** Do not edit files owned by another pair without
   posting in the group chat first.
6. **Small pull requests.** One feature, not a week of work.
7. **Review promptly.** If you are tagged on a PR, review it within a few hours.
   A blocked teammate blocks the whole team.
8. **Never commit secrets.** No passwords, no API keys, no `.env` files. If one
   is committed by accident, tell the group immediately — it must be rotated,
   not just deleted.
9. **Synthetic data only.** No real student names, student numbers, or real
   incidents anywhere in the repository.
10. **Ask early.** Two hours stuck alone is two hours the team lost.

### Commit messages

Short, present tense, describing what changed:

```
Add incident submission endpoint
Fix null location on anonymous reports
Update API contract with cancel endpoint
```

Not: `update`, `fix`, `stuff`, `asdf`.




---

## 5. Backend — steps in order

### Pair A: Foundation

1. Create the Spring Boot project (Spring Web, Spring Data JPA, MySQL Driver)
2. Write `database/schema.sql` — tables for `users`, `incidents`, `responders`,
   `patrols`, `hotspots`, `gbv_reports`, `wellness_resources`, `audit_log`
3. Write `database/seed.sql` with synthetic test data
4. Configure the database connection using **environment variables**, never
   hardcoded credentials
5. Implement `POST /api/auth/register`
6. Implement `POST /api/auth/login`, returning a JWT
7. Implement `GET /api/auth/me`
8. Hash passwords with BCrypt — never store plaintext
9. Add CORS configuration allowing the frontend origin

### Pair B: Incidents

1. Write the schema jointly with Pair A first — should take under an hour
2. Implement `POST /api/incidents` exactly as specified in the contract
3. Enforce validation: coordinates present and in range, `description` required
   when `type` is `other`
4. Implement `GET /api/incidents` with the summary shape and pagination
5. Implement `GET /api/incidents/{id}` with the full shape
6. Implement the status lifecycle and `PATCH /api/incidents/{id}/status`,
   rejecting illegal transitions with 409
7. Implement `POST /api/incidents/{id}/cancel` — the false-alarm path. Sets
   status to `cancelled`, never deletes the record
8. Basic priority calculation in Java for now. The C++ module replaces it later

### Both backend pairs

- Test every endpoint with IntelliJ's HTTP Client before saying it works
- Save your `.http` files into `docs/` — they are evidence of testing for the
  report
- Return the standard error shape from the contract on every failure

---

## 6. Frontend — steps in order

### Pair C: Shell and auth

1. Create `frontend/api.js` **first** and push it early — Pair D imports from it
2. Include the `MOCK` flag pattern so pages work before the backend is live
3. Every network call goes through `api.js`. No `fetch()` anywhere else
4. Build shared CSS — mobile-first, then desktop breakpoints
5. Build the login and register pages
6. Store the JWT and attach it as `Authorization: Bearer <token>` on requests
7. Redirect to login when a request returns 401

### Pair D: Incidents UI

1. Build the incident report form — all nine types from the contract
2. Make `description` required when `type` is `other`
3. Capture location with `navigator.geolocation`, handling refusal gracefully
4. Build the student dashboard listing the user's incidents with status
5. Build the incident detail view
6. Design the SOS button: large, high contrast, thumb-reachable, with a
   confirmation step so it is not triggered by accident

### Both frontend pairs

- Build mobile-first. Students use phones; campus control uses a desktop
- Show a loading state on every request, and a readable error when one fails
- Never assume a response field exists — check for `null`

---

## 7. Definition of done

A feature is not done until all of these are true:

- [ ] It matches the API contract exactly, field names included
- [ ] It has been tested by hand and works
- [ ] Errors are handled, not ignored
- [ ] No secrets, no real data, no commented-out experiments
- [ ] The pull request has been reviewed and approved by another pair
- [ ] It is merged into `main`

---

## 8. Deadlines

| Date | What |
| --- | --- |
| **31 August** | Progress report 3 — whatever exists on this date is what gets reported |
| **11 September** | Progress report 4 |
| **18 September** | Final submission — code, report, everything |
| **21–23 September** | Presentations |

Work backwards from 18 September, not forwards from today.

Target: **feature-complete by 11 September.** The final week is documentation,
testing and demo rehearsal — not writing features.

---

## 9. Important notes on this project

**This is a prototype.** It is not connected to any live emergency service or
GBV support unit. All data is synthetic. This must be stated in the README, the
report, and the presentation.

**GBV data is handled separately from everything else.** Different tables,
stricter access control, its own audit trail. Only the GBV officer role may read
case content. Do not shortcut this — it carries a significant part of the
Security mark.

**Everyone must be able to explain their own code.** The panel will ask. Use
tools to help you write and debug, but never merge something you could not
explain out loud.
