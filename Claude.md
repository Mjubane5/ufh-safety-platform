# Project context for Claude Code

## What this is

A university student safety, emergency and wellness platform. Academic capstone
project for CSC 200 / CSC 300 at the University of Fort Hare. Team of eight
students, four on frontend and four on backend.

**This is a prototype.** It is not connected to any live emergency service or
GBV support unit. All data in this repository is synthetic. Never add real
student names, student numbers, or real incident data.

Final submission: 18 September 2026.

## Read these first

- `docs/api-contract.md` — the single source of truth for all frontend/backend
  communication. If code and contract disagree, the contract is right.
- `docs/team-working-agreement.md` — team rules, workload split, deadlines.

## Stack

| Layer | Technology |
| --- | --- |
| Frontend | Plain HTML, CSS, JavaScript. No framework. |
| Backend | Java with Spring Boot |
| Database | MySQL |
| Maps | Leaflet with OpenStreetMap |
| Algorithms | C++ (standalone module, not in the request path) |
| Analysis | Python (offline, results seeded into MySQL) |

## Conventions — follow these exactly

- Field names are `camelCase` in all JSON. Never `snake_case`.
- Timestamps are ISO 8601 UTC with a `Z` suffix.
- Coordinates use `latitude` and `longitude` spelled in full.
- Empty values are `null`, never `""` or `-1`.
- All errors return the standard shape: `{ "error": "...", "message": "...", "field": null }`
- Auth uses `Authorization: Bearer <token>`.
- Incident priority is an integer 1 to 5, where **1 is most urgent**.

## Frontend rules

- Every network call goes through `frontend/api.js`. No `fetch()` anywhere else.
- `api.js` has a `MOCK` flag so pages work before the backend is ready.
- Build mobile-first, then add desktop breakpoints. Students use phones.
- Show a loading state on every request and a readable error when one fails.
- Never assume a response field exists. Check for `null`.
- No localStorage for sensitive data beyond the auth token.

## Backend rules

- Passwords hashed with BCrypt. Never plaintext.
- Database credentials come from environment variables. Never hardcoded.
- Validate all input server-side, even if the frontend already validated it.
- GBV data lives in separate tables with stricter access. Only the
  `gbv_officer` and `admin` roles may read case content.
- Incidents are never hard-deleted. False alarms set status to `cancelled`.

## Git rules

- Never commit directly to `main`. Always a branch and a pull request.
- Branch names describe the feature: `feature/incident-form`, not the person.
- Never commit secrets, `.env` files, or API keys.
- Commit messages are short and in present tense: "Add incident submission
  endpoint".

## How to help this team

We are students being graded on our own understanding. A panel will ask us to
explain our code out loud.

- Explain what you are doing and why, not just the finished code.
- Prefer simple, readable solutions over clever ones.
- When there is a choice, say what the trade-off is so we can decide.
- Do not introduce new libraries or frameworks without flagging it first.
- If something we ask for contradicts `docs/api-contract.md`, say so rather
  than silently doing it a different way.