# API Contract — Campus Safety, Emergency & Wellness Platform

**Status:** Draft v0.3 — to be reviewed and agreed by the full group before
frontend or backend implementation begins.

**Owner:** Project Group Leader
**Location in repo:** `docs/api-contract.md`

> This document is the single source of truth for how the frontend and backend
> communicate. If the code disagrees with this document, one of them is wrong —
> raise it as an issue rather than silently changing your side.

---

## 1. Conventions

These apply to every endpoint. Agree them once, follow them everywhere.

| Rule | Decision |
| --- | --- |
| Base URL (dev) | `http://localhost:8080/api` |
| Transport | HTTP/1.1, JSON request and response bodies |
| Content type | `application/json; charset=utf-8` |
| Field naming | `camelCase` — never `snake_case` |
| Timestamps | ISO 8601, UTC, with `Z` suffix: `2026-08-23T01:50:00Z` |
| IDs | Integers, server-generated. Clients never invent an ID. |
| Coordinates | Decimal degrees. `latitude` and `longitude` spelled in full. |
| Booleans | `true` / `false` — never `"true"`, never `0` / `1` |
| Empty values | Use `null`, not `""` or `-1` |
| Auth | `Authorization: Bearer <token>` header on all protected endpoints |

### Roles

Every user has exactly one role. Endpoints state which roles may call them.

| Role | Description |
| --- | --- |
| `student` | Reports incidents, triggers SOS, views own cases, uses maps and wellness |
| `responder` | Receives assignments, updates incident status |
| `campus_control` | Logs patrols, views all incidents, oversees dispatch |
| `gbv_officer` | Sole role able to read GBV case content |
| `scu_officer` | Sole role able to manage wellness bookings and student wellness messages |
| `health_officer` | Sole role able to manage Campus Health Centre bookings and student health messages |
| `admin` | User management, audit log access |

### Standard error response

Every non-2xx response uses this shape. No exceptions — the frontend has one
error handler.

```json
{
  "error": "VALIDATION_FAILED",
  "message": "Description is required for incidents of type 'other'.",
  "field": "description"
}
```

`field` is present only for validation errors, `null` otherwise.

### Status codes in use

| Code | Meaning |
| --- | --- |
| 200 | OK — request succeeded |
| 201 | Created — new resource made; body contains it |
| 204 | No content — succeeded, nothing to return |
| 400 | Validation failed — client sent bad data |
| 401 | Not authenticated — missing or expired token |
| 403 | Authenticated but not permitted for this role |
| 404 | Resource does not exist |
| 409 | Conflict — e.g. incident already assigned |
| 500 | Server error — backend bug, not the client's fault |

---

## 2. Authentication

### POST /api/auth/register

Public. Student self-registration only; other roles are created by an admin.

**Request**
```json
{
  "studentNumber": "202512345",
  "fullName": "A Student",
  "email": "202512345@ufh.ac.za",
  "password": "plaintext-over-https",
  "phone": "0821234567"
}
```

**Response 201**
```json
{
  "userId": 17,
  "fullName": "A Student",
  "role": "student"
}
```

**Errors:** 400 if email already registered, password too short, or student
number malformed.

---

### POST /api/auth/login

Public.

**Request** — `email` accepts a real email address, or (student accounts
only) a bare student number, e.g. `"202512345"`. `AuthService.verifyCredentials`
decides which lookup to use from the value's shape (digits only = student
number); the field keeps the name `email` rather than being renamed, to avoid
touching every other endpoint that shares this request shape.
```json
{
  "email": "202512345@ufh.ac.za",
  "password": "plaintext-over-https"
}
```

**Response 200**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "expiresAt": "2026-08-23T09:50:00Z",
  "user": {
    "userId": 17,
    "fullName": "A Student",
    "role": "student"
  }
}
```

**Errors:** 401 for wrong credentials. Return the same message for "unknown
email" and "wrong password" — revealing which one is a security weakness worth
noting in the report.

Still used as-is by `staff-login.html` for every staff role. `login.html`
(students) no longer calls this endpoint directly — see "Two-step student
login" below, which sits in front of it and calls it internally once the
emailed code is verified. Extending the two-step flow to staff roles too is
a decision for the group, not assumed here.

---

## 2a. Two-step student login

> Added so a student is notified — and must act — every time their account
> is used to sign in, not just warned after the fact. `login.html` calls
> `request-code` where it used to call `POST /api/auth/login` directly; the
> token is only issued once `verify-code` succeeds.

**Implemented in `backend/src/main/java/za/ac/ufh/safety/auth/`**
(`AuthService`, `LoginVerificationCode`, `SendGridEmailService`):
- The code is 6 digits, expires in 5 minutes, and is single-use.
- `verify-code` locks out after 5 wrong attempts for that `pendingLoginId`
  and requires a fresh code.
- **Known gap:** `request-code`/`resend-code` have no rate limit per
  account yet (unlike the GBV status lookup, which does - see section 7).
  A caller who already knows a valid email/password could currently spam
  that student's inbox by calling either endpoint repeatedly. Worth the
  same per-caller sliding-window treatment before this goes anywhere near
  real students.
- Email is sent via SendGrid (`SendGridEmailService`) when `MAIL_API_KEY`/
  `MAIL_FROM` are set; without them the backend still starts and the code is
  written to the server's own log instead (dev-only fallback, clearly
  labelled, never sent over HTTP to the browser either way) - see
  `README-BACKEND.md`. Production (Railway) has both set and sends real
  email; `README-BACKEND.md` also documents a known SendGrid deliverability
  limitation (mail landing in spam without full Domain Authentication).

### POST /api/auth/login/request-code

Public, student only. Validates the password the same way
`POST /api/auth/login` does, then emails a 6-digit code instead of
returning a token.

**Request:** same shape as `POST /api/auth/login`.

**Response 200**
```json
{
  "pendingLoginId": "a1b2c3d4",
  "maskedEmail": "jo***@ufh.ac.za"
}
```

**Errors:** 401 for wrong credentials, same generic message as
`POST /api/auth/login` for the same reason.

---

### POST /api/auth/login/resend-code

Public. Issues a fresh code for an existing `pendingLoginId`, invalidating
the previous one and resetting the attempt counter.

**Request**
```json
{ "pendingLoginId": "a1b2c3d4" }
```

**Response 200:** same shape as `request-code`.

**Errors:** 400 if `pendingLoginId` is unknown or already completed.

---

### POST /api/auth/login/verify-code

Public. On success, returns exactly what `POST /api/auth/login` returns.

**Request**
```json
{ "pendingLoginId": "a1b2c3d4", "code": "483920" }
```

**Response 200:** same shape as `POST /api/auth/login`.

**Errors:** 400 `INVALID_CODE` (wrong code) or `CODE_EXPIRED`, 429
`TOO_MANY_ATTEMPTS` after 5 wrong tries for this `pendingLoginId`.

---

### GET /api/auth/me

Any authenticated role. Lets the frontend restore session state on page reload.

**Response 200**
```json
{
  "userId": 17,
  "fullName": "A Student",
  "role": "student"
}
```

---

### GET /api/students/me/health

Roles: `student`. Implemented in
`backend/src/main/java/za/ac/ufh/safety/healthprofile/`.

Declared once at registration, editable any time after. Backs the medical
alert button on the dashboard, which only appears once a student has
declared at least one condition or a note, and copies this into the incident
description when pressed so a responder doesn't need to ask.

**Response 200**
```json
{
  "conditions": ["asthma", "severe_allergy"],
  "note": "Carries an EpiPen"
}
```

`conditions` is a fixed list, not free text: `asthma`, `diabetes`,
`epilepsy`, `severe_allergy`, `heart_condition`. `note` is capped at 200
characters - context for a responder in an emergency, not a medical file.
Both empty (`{"conditions": [], "note": null}`) when nothing's been
declared, not a 404 - "no profile yet" is a normal state, not an error.

**Data protection, same POPIA reasoning as the registration field:** this is
special personal information. It must never appear in `GET /api/incidents`
or `GET /api/incidents/{id}` for anyone except the incident's own reporter,
the responder actually assigned to it, and `campus_control`/`admin` - the
same visibility rule incidents already have, not a wider one just because
health data is involved.

---

### PUT /api/students/me/health

Roles: `student`.

**Request:** same shape as the response above.

**Response 200:** the saved profile, same shape.

**Errors:** 400 `VALIDATION_FAILED` for a `conditions` entry outside the
fixed list, or a `note` over 200 characters.

---

### POST /api/auth/forgot-password

Public. Implemented in
`backend/src/main/java/za/ac/ufh/safety/passwordreset/`.

**Request**
```json
{ "email": "202512345@ufh.ac.za" }
```

**Response 200**, unconditionally, whether or not the email is registered:
```json
{ "message": "If that email is registered, a reset link has been sent." }
```

Never returns 404 for an unknown email and never returns a different shape
for a known vs. unknown one - that difference is exactly how an attacker
enumerates which addresses have accounts. A missing/malformed `email` is
still a normal 400 `VALIDATION_FAILED`, since that's a client mistake, not
information about the target account.

Sends a link like `https://<host>/reset-password.html?token=<opaque-token>`.
The token is a 256-bit `SecureRandom` value, stored as a SHA-256 digest
(not BCrypt - see `PasswordResetToken`'s class comment for why a fast,
deterministic hash is the correct choice here, unlike password/OTP hashing),
expires in 1 hour, and is marked used on redemption so it cannot be replayed.

---

### POST /api/auth/reset-password

Public. Implemented alongside `forgot-password` above.

**Request**
```json
{ "token": "the opaque token from the emailed link", "newPassword": "..." }
```

**Response 200**
```json
{ "message": "Password updated. Sign in with your new password." }
```

**Errors:** 400 `VALIDATION_FAILED` for a weak password (same 8-character
minimum as registration), 400 `INVALID_TOKEN` for a token that's wrong,
already used, or expired - one generic message either way, not "expired" vs
"already used", since distinguishing them tells an attacker something about
timing they shouldn't get.

---

## 3. Incidents

The single pipeline. SOS is an incident with `type: "sos"` and forced high
priority — it is not a separate subsystem.

### POST /api/incidents

Roles: `student`

**Request**
```json
{
  "type": "medical",
  "description": "Someone collapsed outside the library.",
  "latitude": -32.78331,
  "longitude": 26.84971,
  "accuracy": 18.5,
  "anonymous": false
}
```

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `type` | enum | yes | `sos`, `medical`, `fire`, `theft`, `assault`, `accident`, `suspicious`, `unsafe`, `other` |
| `description` | string | yes if `type` is `other` | Max 1000 chars |
| `latitude` | number | no | Decimal degrees. `null` if unavailable. |
| `longitude` | number | no | Decimal degrees. `null` if unavailable. |
| `accuracy` | number | no | Metres, from the browser. `null` if unavailable. |
| `anonymous` | boolean | yes | If true, reporter identity is withheld from responders |

**Response 201**
```json
{
  "incidentId": 42,
  "type": "medical",
  "status": "reported",
  "priority": 2,
  "locationSource": "device",
  "createdAt": "2026-08-23T01:50:00Z"
}
```

`locationSource` is `"device"` when the report carried coordinates, `"none"`
when it did not. It is set by the server from the coordinates it received, not
by the client. A dispatcher reads this to tell a real fix from an absent one
without having to test the coordinates for `null` themselves.

**Errors:** 400 if coordinates are present but out of range, or if
`description` is absent on an `other` report. Missing coordinates are allowed;
out-of-range values are not.

> **Why coordinates are optional.** A student whose browser has location
> blocked, or who is indoors with no GPS fix, must still be able to file a
> report. Refusing the submission would mean the system fails exactly when
> someone needs it. For a safety system we prefer availability over data
> completeness: take the report, mark it `locationSource: "none"`, and let a
> human work out the location from the description. An incomplete report that
> reaches campus control is worth more than a complete one that was never sent.

---

### Incident status values

A fixed lifecycle. Both sides use these exact strings.

```
reported -> triaged -> assigned -> en_route -> on_scene -> resolved
                                                        -> cancelled
```

`cancelled` is the false-alarm path. The record is retained, never deleted —
this is the false-alarm workflow required by the project brief.

### Priority values

Integer `1` to `5`, where **1 is most urgent**. Computed by the backend
(C++ module or Java equivalent). The client never sets it and never assumes a
formula.

---

### GET /api/incidents/{incidentId}

Roles: `student` (own incidents only), `responder` (assigned only),
`campus_control`, `admin`

**Response 200**
```json
{
  "incidentId": 42,
  "type": "medical",
  "description": "Someone collapsed outside the library.",
  "status": "assigned",
  "priority": 2,
  "latitude": -32.78331,
  "longitude": 26.84971,
  "accuracy": 18.5,
  "locationSource": "device",
  "anonymous": false,
  "createdAt": "2026-08-23T01:50:00Z",
  "updatedAt": "2026-08-23T01:52:14Z",
  "reporter": {
    "userId": 17,
    "fullName": "A Student"
  },
  "assignedResponder": {
    "responderId": 5,
    "fullName": "A Responder",
    "latitude": -32.78210,
    "longitude": 26.84800
  }
}
```

`reporter` is `null` when `anonymous` is true.
`assignedResponder` is `null` until assignment.
`latitude`, `longitude` and `accuracy` are `null` when the reporter could not
share a location. In that case `locationSource` is `"none"` and the incident
must be triaged manually — see section 4.

---

### GET /api/incidents

Roles: `student` (own only), `responder` (assigned only), `campus_control`,
`admin`

**Query parameters**

| Param | Type | Default | Notes |
| --- | --- | --- | --- |
| `status` | string | all | Filter by one status value |
| `page` | integer | 1 | |
| `pageSize` | integer | 20 | Max 100 |

**Response 200**
```json
{
  "items": [ { "incidentId": 42, "type": "medical", "status": "assigned", "priority": 2, "createdAt": "2026-08-23T01:50:00Z" } ],
  "page": 1,
  "pageSize": 20,
  "totalItems": 1
}
```

List items are a **summary** shape — no description, no coordinates. Fetch the
single-incident endpoint for detail. Keeps the dashboard fast.

---

### PATCH /api/incidents/{incidentId}/status

Roles: `responder` (own assignment), `campus_control`, `admin`

**Request**
```json
{
  "status": "en_route",
  "note": "Departing from residence gate."
}
```

**Response 200** — returns the full incident object as above.

**Errors:** 409 if the transition is not legal (e.g. `resolved` back to
`reported`).

---

### POST /api/incidents/{incidentId}/cancel

Roles: `student` (own incident only)

The false-alarm path. Sets status to `cancelled` and retains the record.

**Request**
```json
{ "reason": "Pressed by accident." }
```

**Response 200** — full incident object.

---

### GET /api/incidents/{incidentId}/signals

Roles: same visibility as `GET /api/incidents/{incidentId}` — `student`
(own incident only), `responder` (assigned only), `campus_control`, `admin`.

Polled every 5 seconds by `incident.html` while viewing an active incident,
same interval as every other live view in this app (see the working
agreement's decision log — polling, not sockets).

**Response 200**
```json
{
  "items": [
    { "signalId": 1, "incidentId": 42, "type": "transcript", "label": null, "confidence": null, "text": "Someone help me", "createdAt": "2026-08-23T01:51:03Z" },
    { "signalId": 2, "incidentId": 42, "type": "sound", "label": "Screaming", "confidence": 0.41, "text": null, "createdAt": "2026-08-23T01:51:05Z" },
    { "signalId": 3, "incidentId": 42, "type": "facial", "label": "fearful", "confidence": 0.72, "text": null, "createdAt": "2026-08-23T01:51:07Z" }
  ]
}
```

`type` is one of `transcript`, `sound`, `facial`. Ordered oldest first.

---

### POST /api/incidents/{incidentId}/signals

Roles: `student` (own incident only) — only the reporter's own device can
add to their own incident's log.

Produced by `frontend/js/distress-detection.js`, which starts automatically
on `incident.html` as soon as the reporter's SOS or medical alert has been
sent (never as part of sending the alert itself — only once the incident
already exists). It turns on the microphone and camera — the browser's own
permission prompts are still the real consent gate — and sends only what it
derives from them:

- `transcript` — a line of speech-to-text from the browser's own Web Speech
  API.
- `sound` — a label from a short distress-relevant allowlist (screaming,
  shouting, crying, and a few others), from a client-side pass of a
  pretrained sound classifier (Google's YAMNet, run in the browser with
  TensorFlow.js).
- `facial` — an expression label, from a client-side pass of a pretrained
  facial-expression model (`face-api.js`) over the camera feed, only when
  the student opted in to the camera.

**The raw audio and video never leave the student's device and are never
recorded anywhere.** Only these derived, short text/label values are sent.
Confidence thresholds for `sound` and `facial` are first-pass estimates —
see the comment at the top of `distress-detection.js` — and should be
retuned once the team can test with real recordings.

**Request**
```json
{ "type": "sound", "label": "Screaming", "confidence": 0.41 }
```
or
```json
{ "type": "transcript", "text": "Someone help me" }
```

**Response 201** — the created signal, same shape as one item above.

**Errors:** 403 if the caller is not the reporter, 404 if the incident does
not exist or is not visible to the caller, 409 if the incident is already
`resolved` or `cancelled`.

---

## 4. Dispatch and routing

### POST /api/incidents/{incidentId}/assign

Roles: `campus_control`, `admin`

Triggers responder selection and route calculation.

**Request**
```json
{ "responderId": 5 }
```

Omit `responderId` to let the backend choose the nearest available responder.

**Incidents with no coordinates cannot be auto-assigned.** When
`locationSource` is `"none"` there is no position to measure distance from, so
proximity selection is meaningless. Such an incident routes to campus control
for manual triage instead:

- `POST /api/incidents/{incidentId}/assign` **with no `responderId` must return
  409**, not pick a responder. Choosing one without a location would be
  choosing at random and presenting it as a nearest-responder result, which is
  worse than returning nothing — it sends help to the wrong place while
  looking correct.
- The same call **with an explicit `responderId` succeeds**. A dispatcher who
  has read the description and worked out where the student is may assign by
  hand. The `route` object is `null` in that response, because a route cannot
  be calculated to an unknown destination.

**Response 200**
```json
{
  "incidentId": 42,
  "status": "assigned",
  "responder": { "responderId": 5, "fullName": "A Responder" },
  "route": {
    "distanceMetres": 640,
    "estimatedSeconds": 460,
    "points": [
      { "latitude": -32.78210, "longitude": 26.84800 },
      { "latitude": -32.78290, "longitude": 26.84900 },
      { "latitude": -32.78331, "longitude": 26.84971 }
    ]
  }
}
```

`points` is an ordered polyline the frontend draws on the map. This is where
the C++ shortest-path work surfaces in the product.

**Errors:** 409 if already assigned, 409 if auto-assignment was requested for
an incident with `locationSource: "none"`, 404 if no responder available.

---

### GET /api/responders/available

Roles: `campus_control`, `admin`

**Response 200**
```json
{
  "items": [
    {
      "responderId": 5,
      "fullName": "A Responder",
      "team": "campus_security",
      "status": "available",
      "latitude": -32.78210,
      "longitude": 26.84800,
      "lastSeenAt": "2026-08-23T01:49:30Z"
    }
  ]
}
```

Responder `status`: `available`, `assigned`, `off_duty`.

---

## 5. Patrols

### POST /api/patrols

Roles: `campus_control`

**Request**
```json
{
  "zoneId": 3,
  "latitude": -32.78400,
  "longitude": 26.85010,
  "note": "Routine sweep, nothing to report."
}
```

**Response 201**
```json
{
  "patrolId": 88,
  "zoneId": 3,
  "recordedAt": "2026-08-23T01:48:00Z"
}
```

---

### GET /api/patrols/recent

Roles: any authenticated. This is the student-visible freshness feature.

**Query parameters:** `latitude`, `longitude`, `radiusMetres` (default 500)

**Response 200**
```json
{
  "items": [
    {
      "patrolId": 88,
      "zoneName": "Library Precinct",
      "latitude": -32.78400,
      "longitude": 26.85010,
      "recordedAt": "2026-08-23T01:48:00Z",
      "minutesAgo": 2
    }
  ]
}
```

`minutesAgo` is computed server-side so every client shows the same figure.
Never expose patrol officer identity on this endpoint.

---

## 6. Safety map

### GET /api/hotspots

Roles: any authenticated.

**Response 200**
```json
{
  "items": [
    {
      "hotspotId": 7,
      "name": "Lower Campus Footpath",
      "latitude": -32.78550,
      "longitude": 26.85200,
      "radiusMetres": 120,
      "riskLevel": "elevated",
      "incidentCount": 14,
      "computedAt": "2026-08-22T20:00:00Z"
    }
  ]
}
```

`riskLevel`: `low`, `moderate`, `elevated`, `high`.
Produced by the offline Python analysis and seeded into MySQL — this endpoint
reads stored results, it does not run analysis on request.

---

### POST /api/routes/safe

Roles: any authenticated.

**Request**
```json
{
  "from": { "latitude": -32.78210, "longitude": 26.84800 },
  "to": { "latitude": -32.78550, "longitude": 26.85200 }
}
```

**Response 200**
```json
{
  "distanceMetres": 720,
  "estimatedSeconds": 540,
  "safetyScore": 0.78,
  "avoidedHotspots": [7],
  "points": [
    { "latitude": -32.78210, "longitude": 26.84800 },
    { "latitude": -32.78330, "longitude": 26.84950 },
    { "latitude": -32.78550, "longitude": 26.85200 }
  ]
}
```

`safetyScore` is 0.0 to 1.0, higher is safer. Document the weighting formula in
the Assignment — a panel will ask how it is calculated.

---

### Safe Walk sessions

A Safe Walk is a student walking a route from `POST /api/routes/safe` with
live location sharing turned on, so campus control can see it in progress —
not just an SOS after something has already gone wrong. Implemented in
`backend/src/main/java/za/ac/ufh/safety/safetywalk/` (`SafeWalk`,
`SafeWalkService`, `SafeWalkController`) and consumed by
`frontend/js/map.js` (student side) and `frontend/js/control-dashboard.js`
(campus control's live list).

#### POST /api/safewalks

Roles: `student`.

**Request**
```json
{
  "origin": { "latitude": -32.78210, "longitude": 26.84800 },
  "destination": { "latitude": -32.78550, "longitude": 26.85200 },
  "route": { "distanceMetres": 720, "estimatedSeconds": 540, "safetyScore": 0.78 }
}
```

`route` is whatever `POST /api/routes/safe` returned for this origin/destination —
sent back so the session records the safety score it started with, rather
than the backend recomputing it.

**Response 201**
```json
{
  "walkId": 501,
  "status": "active",
  "origin": { "latitude": -32.78210, "longitude": 26.84800 },
  "destination": { "latitude": -32.78550, "longitude": 26.85200 },
  "currentLocation": { "latitude": -32.78210, "longitude": 26.84800 },
  "safetyScore": 0.78,
  "startedAt": "2026-09-21T08:00:00Z",
  "updatedAt": "2026-09-21T08:00:00Z"
}
```

#### PATCH /api/safewalks/{walkId}/location

Roles: `student` (own walk only).

Pushed roughly every 5 seconds while a walk is active — the same cadence
every other live view in this app polls at, just in the send direction here.

**Request**
```json
{ "latitude": -32.78400, "longitude": 26.85000 }
```

**Response 200** — the updated walk, same shape as above.
**Errors:** 409 if the walk is not `active` (already arrived or cancelled).

#### POST /api/safewalks/{walkId}/arrived

Roles: `student` (own walk only). Sets `status` to `arrived`. Always a
deliberate tap from the student, never inferred automatically just because
the live location came within range of the destination — see the frontend's
arrival-detection note.

**Response 200** — the updated walk.

#### POST /api/safewalks/{walkId}/cancel

Roles: `student` (own walk only). Sets `status` to `cancelled`. The false-alarm
equivalent for a walk — for example the student changed their route.

**Response 200** — the updated walk.

#### GET /api/safewalks/active

Roles: `campus_control`, `admin`. Polled every 5 seconds by
`control-dashboard.html`.

**Response 200**
```json
{
  "items": [
    {
      "walkId": 501,
      "studentUserId": 17,
      "studentName": "Sipho Ndlovu",
      "destination": { "latitude": -32.78550, "longitude": 26.85200 },
      "currentLocation": { "latitude": -32.78400, "longitude": 26.85000 },
      "safetyScore": 0.78,
      "startedAt": "2026-09-21T08:00:00Z",
      "updatedAt": "2026-09-21T08:04:12Z"
    }
  ]
}
```

Only `active` walks are listed — one already `arrived` or `cancelled` drops
off this endpoint (it is not a live safety concern any more).

---

## 7. Confidential GBV reporting

> **Handle separately from ordinary incidents.** Different table, different
> access rules, different audit trail. Only `gbv_officer` and `admin` may read
> case content. `campus_control` and `responder` must receive 403.
>
> All data used in this project is synthetic. The system is not connected to any
> live support service.

### POST /api/gbv/reports

Roles: any authenticated, **or** unauthenticated when `anonymous` is true.

**Request**
```json
{
  "anonymous": true,
  "description": "Free-text account of the incident.",
  "occurredAt": "2026-08-20T19:30:00Z",
  "latitude": -32.78400,
  "longitude": 26.85010,
  "evidenceIds": [12, 13],
  "contactPreference": "none"
}
```

`contactPreference`: `none`, `email`, `phone`. Must be `none` when `anonymous`
is true — reject with 400 otherwise.

**Response 201**
```json
{
  "referenceCode": "GBV-4K7P-22XQ",
  "status": "submitted",
  "submittedAt": "2026-08-23T01:55:00Z"
}
```

No numeric ID is returned. The `referenceCode` is the only handle an anonymous
reporter has — it is not guessable and not linked to a user account.

---

### GET /api/gbv/reports/{referenceCode}/status

Public, given a valid reference code.

**Response 200**
```json
{
  "referenceCode": "GBV-4K7P-22XQ",
  "status": "under_review",
  "lastUpdatedAt": "2026-08-23T08:00:00Z",
  "anonymous": false
}
```

Returns status only — never the report content, never officer names. Apply rate
limiting so codes cannot be brute-forced, and record the limit in the report.

GBV status values: `submitted`, `under_review`, `referred`, `closed`.

Rate-limited per caller IP (10 requests/minute) so codes cannot be
brute-forced by trying many at speed - a limit scoped to one code would not
stop that, since the attack is trying many different codes, not repeating
one. 429 `TOO_MANY_ATTEMPTS` once exceeded.

`anonymous` tells the frontend whether to offer the chat in section 7a below —
an anonymous report has no channel back to the reporter at all, by design, so
it's never offered one. This doesn't weaken anonymity: the reporter already
knows whether they checked that box.

---

### PATCH /api/gbv/reports/{referenceCode}/status

Roles: `gbv_officer`, `admin`.

**Request**
```json
{ "status": "under_review" }
```

**Response 200:** the same shape as the public status endpoint above
(`referenceCode`, `status`, `lastUpdatedAt`, `anonymous`) - never report
content, never officer names, even on the officer-only write path.

---

### GET /api/gbv/reports

Roles: `gbv_officer`, `admin`.

Returns the confidential case queue. This endpoint must never be exposed to
`student`, `responder`, or `campus_control`; those roles receive 403. Unlike
the public status lookup above, this response contains case content and must
be recorded in the GBV audit trail.

**Query parameters**

| Param | Type | Default | Notes |
| --- | --- | --- | --- |
| `status` | string | all | One GBV status value |
| `page` | integer | 1 | 1-based page number |
| `pageSize` | integer | 20 | Maximum 100 |

**Response 200**
```json
{
  "items": [
    {
      "referenceCode": "GBV-4K7P-22XQ",
      "status": "under_review",
      "description": "Free-text account of the incident.",
      "occurredAt": "2026-08-20T19:30:00Z",
      "latitude": -32.78400,
      "longitude": 26.85010,
      "anonymous": true,
      "contactPreference": "none",
      "submittedAt": "2026-08-23T01:55:00Z",
      "lastUpdatedAt": "2026-08-23T08:00:00Z"
    }
  ],
  "page": 1,
  "pageSize": 20,
  "totalItems": 1
}
```

The frontend must render `null` coordinates and descriptions safely. Evidence
is intentionally excluded from this first text-only officer view until the
multipart upload and EXIF-stripping design is implemented.

### POST /api/gbv/evidence

Roles: same as report submission.

Multipart upload, not JSON. Returns an ID to reference in the report body.

**Response 201**
```json
{ "evidenceId": 12, "sizeBytes": 148223, "mimeType": "image/jpeg" }
```

Strip EXIF metadata on upload. Photographs carry GPS coordinates and device
identifiers that can deanonymise a reporter — worth an explicit paragraph in the
Security section of the Assignment.

---

## 7a. GBV chat

> Deliberately a different shape from every other chat in this app (SCU,
> campus control): scoped to a report's `referenceCode`, not a student
> account, and never carries a reporter's name anywhere an officer can see
> it — the same rule `GET /api/gbv/reports` already follows. Only offered
> for a report submitted with `anonymous: false`; an anonymous report has no
> chat, ever, because there is no channel back to an anonymous reporter by
> design.
>
> Implemented in `backend/src/main/java/za/ac/ufh/safety/gbv/`
> (`GbvChatService`, `GbvController`) and consumed by `frontend/js/gbv.js`
> (reporter side) and `frontend/js/gbv-officer.js` (officer side).

### GET /api/gbv/reports/{referenceCode}/messages

Public, given the reference code — same "the code is the credential" model
as the status endpoint. **Errors:** 404 for an unknown code, 403 if the
report is anonymous.

**Response 200**
```json
{
  "items": [
    { "messageId": 1, "referenceCode": "GBV-4K7P-22XQ", "sender": "reporter", "text": "Has anyone reviewed this yet?", "sentAt": "2026-09-21T09:00:00Z" }
  ]
}
```

`sender` is `reporter` or `gbv_officer`. Poll every 5 seconds while open,
matching the interval used everywhere else in this app.

---

### POST /api/gbv/reports/{referenceCode}/messages

Public, same rules as above.

**Request**
```json
{ "text": "Has anyone reviewed this yet?" }
```

**Response 201:** the created message, `sender: "reporter"`.

---

### GET /api/gbv/chat-queue

Roles: `gbv_officer`, `admin`. Every non-anonymous report that has a
message, most recent activity first — reference codes only, **never** a
name, same rule as the case queue in section 7.

**Response 200**
```json
{
  "items": [
    { "referenceCode": "GBV-4K7P-22XQ", "lastActivityAt": "2026-09-21T09:00:00Z", "hasUnread": true }
  ]
}
```

---

### GET /api/gbv/reports/{referenceCode}/messages/officer

Roles: `gbv_officer`, `admin`. Same response shape as the public endpoint
above, for the one report named in the path. A separate path from the
public one so the two can carry different auth/rate-limit rules even though
the data returned is the same.

---

### POST /api/gbv/reports/{referenceCode}/messages/officer

Roles: `gbv_officer`, `admin`.

**Request:** same shape as the public `POST`.

**Response 201:** the created message, `sender: "gbv_officer"`.

---

## 8. Wellness

### GET /api/wellness/resources

Roles: any authenticated.

**Response 200**
```json
{
  "items": [
    {
      "resourceId": 1,
      "title": "Student Counselling Unit",
      "category": "counselling",
      "description": "On-campus counselling service.",
      "contactPhone": "0400000000",
      "availability": "Mon-Fri 08:00-16:30"
    }
  ]
}
```

---

### POST /api/wellness/bookings

Roles: `student`

**Request**
```json
{
  "resourceId": 1,
  "preferredDate": "2026-08-26",
  "preferredSlot": "morning",
  "note": null
}
```

**Response 201**
```json
{
  "bookingId": 31,
  "studentUserId": 17,
  "studentName": "A Student",
  "resourceId": 1,
  "preferredDate": "2026-08-26",
  "preferredSlot": "morning",
  "note": null,
  "status": "requested",
  "createdAt": "2026-08-23T02:00:00Z"
}
```

Booking status: `requested`, `confirmed`, `declined`, `cancelled`. The
response carries the full booking (not just `bookingId`/`status`/`createdAt`
as an earlier draft of this contract showed) so the same shape can be
reused for the queue below without a second, thinner DTO.

---

### PATCH /api/wellness/bookings/{bookingId}

Roles: `scu_officer` for `resourceId` 1/2, `health_officer` for `resourceId`
3 (the Health Centre - section 8b), `admin` for either. One endpoint shared
by both officer roles, split by which resource the booking is actually for.

**Request**
```json
{ "status": "confirmed" }
```

**Response 200:** the updated booking, same shape as the `POST` response.

---

### GET /api/wellness/queue

Roles: `scu_officer`, `admin`.

Every student with a booking (`resourceId` 1/2 only - the Health Centre has
its own queue, section 8b) or a message thread, most recent activity first -
the SCU equivalent of the GBV case queue. `hasUnread` is true from the
moment any message from the student has been seen, and stays true for the
rest of the thread - it is not reset by an officer reply.

**Response 200**
```json
{
  "items": [
    {
      "studentUserId": 17,
      "studentName": "A Student",
      "lastActivityAt": "2026-08-23T02:00:00Z",
      "hasUnread": true,
      "bookings": [
        { "bookingId": 31, "studentUserId": 17, "studentName": "A Student", "resourceId": 1, "preferredDate": "2026-08-26", "preferredSlot": "morning", "note": null, "status": "requested", "createdAt": "2026-08-23T02:00:00Z" }
      ]
    }
  ]
}
```

---

### GET /api/wellness/messages

Roles: `student`. Scoped to the caller automatically -
a student only ever sees their own thread, there is no student-facing way
to address a message to anyone else's.

One ongoing thread per student rather than one per booking: simpler for
both sides, and closer to how a real counselling unit actually works.

**Response 200**
```json
{
  "items": [
    {
      "messageId": 1,
      "sender": "student",
      "text": "Could I move Thursday's session?",
      "sentAt": "2026-08-23T02:00:00Z"
    }
  ]
}
```

`sender` is `student` or `scu`. Poll this every 5 seconds while a student
has the conversation open, matching the interval already used elsewhere in
this app rather than introducing a different one.

---

### POST /api/wellness/messages

Roles: `student`.

**Request**
```json
{ "text": "Could I move Thursday's session?" }
```

**Response 201:** the created message, same shape as one item above.

---

### GET /api/wellness/messages/{studentUserId}

Roles: `scu_officer`, `admin`. Same response shape as the student-facing
endpoint, for the one student named in the path.

---

### POST /api/wellness/messages/{studentUserId}

Roles: `scu_officer`, `admin`.

**Request:** same shape as the student-facing `POST`.

**Response 201:** the created message, `sender: "scu"`.

---

## 8a. Contact campus control

Same "one ongoing thread per student" shape as wellness messaging above, for
non-emergency questions and issues - lost property, access requests, that
kind of thing. SOS and incidents already have their own dedicated flow
(section 3) and stay separate from this; this is deliberately *not* a
substitute for reporting something urgent. Implemented in
`backend/src/main/java/za/ac/ufh/safety/campuscontrol/` and consumed by
`frontend/js/contact-campus-control.js` (student side) and
`frontend/js/campus-control-queue.js` (officer side).

### GET /api/campus-control/messages

Roles: `student`. Scoped to the caller automatically, same as wellness
messages - a student can only ever see their own thread.

**Response 200**
```json
{
  "items": [
    { "messageId": 1, "studentUserId": 17, "studentName": "A Student", "sender": "student", "text": "Where is the lost property office?", "sentAt": "2026-09-21T09:00:00Z" }
  ]
}
```

`sender` is `student` or `campus_control`. Poll every 5 seconds while the
conversation is open, matching the interval used everywhere else in this app.

---

### POST /api/campus-control/messages

Roles: `student`.

**Request**
```json
{ "text": "Where is the lost property office?" }
```

**Response 201:** the created message, same shape as one item above.

---

### GET /api/campus-control/queue

Roles: `campus_control`, `admin`. Every student who has sent a message, most
recent activity first - the campus control equivalent of the SCU booking
queue.

**Response 200**
```json
{
  "items": [
    { "studentUserId": 17, "studentName": "A Student", "lastActivityAt": "2026-09-21T09:00:00Z", "hasUnread": true }
  ]
}
```

---

### GET /api/campus-control/messages/{studentUserId}

Roles: `campus_control`, `admin`. Same response shape as the student-facing
endpoint, for the one student named in the path.

---

### POST /api/campus-control/messages/{studentUserId}

Roles: `campus_control`, `admin`.

**Request:** same shape as the student-facing `POST`.

**Response 201:** the created message, `sender: "campus_control"`.

---

## 8b. Campus Health Centre messaging

The Health Centre is one of the three `GET /api/wellness/resources` (section
8) and keeps using that same booking flow - `resourceId` 3, created via
`POST /api/wellness/bookings`, confirmed/declined/cancelled via
`PATCH /api/wellness/bookings/{bookingId}`. Only the **messaging** and the
**officer queue** are separate from SCU's, with their own `health_officer`
role and their own portal (`frontend/health-officer.html`), so a
physical-health question never lands in a counsellor's inbox or vice versa.
Same one-thread-per-student, 5-second-polling shape as SCU messaging.
Implemented in `backend/src/main/java/za/ac/ufh/safety/health/`
(bookings still read from `WellnessBookingRepository` - see section 8) and
consumed by `frontend/js/wellness.js` (student side) and
`frontend/js/health-officer.js` (officer side).

### GET /api/health/messages

Roles: `student`. Scoped to the caller automatically, same as wellness messages.

**Response 200**
```json
{
  "items": [
    { "messageId": 1, "studentUserId": 17, "studentName": "A Student", "sender": "student", "text": "Can I get a repeat prescription?", "sentAt": "2026-09-21T09:00:00Z" }
  ]
}
```

`sender` is `student` or `health_officer`.

---

### POST /api/health/messages

Roles: `student`.

**Request**
```json
{ "text": "Can I get a repeat prescription?" }
```

**Response 201:** the created message, same shape as one item above.

---

### GET /api/health/queue

Roles: `health_officer`, `admin`. Every student with a Health Centre message
or a `resourceId` 3 booking, most recent activity first - same shape as the
SCU queue in section 8, filtered to this one resource.

**Response 200**
```json
{
  "items": [
    { "studentUserId": 17, "studentName": "A Student", "lastActivityAt": "2026-09-21T09:00:00Z", "hasUnread": true, "bookings": [] }
  ]
}
```

---

### GET /api/health/messages/{studentUserId}

Roles: `health_officer`, `admin`. Same response shape as the student-facing
endpoint, for the one student named in the path.

---

### POST /api/health/messages/{studentUserId}

Roles: `health_officer`, `admin`.

**Request:** same shape as the student-facing `POST`.

**Response 201:** the created message, `sender: "health_officer"`.

---

## 9. Open questions for the group

Resolve these before implementation starts and record the decisions here.

1. **Token lifetime and refresh.** Fixed 8-hour token, or refresh tokens? Fixed
   is simpler and adequate for a prototype.
2. **Real-time updates.** Does the campus control dashboard poll
   `GET /api/incidents` every few seconds, or do we add WebSockets? Polling is
   safer for the timeline; WebSockets makes a stronger Networks discussion.
   Pick one and commit.
3. **Map provider.** Leaflet with OpenStreetMap (no API key, no billing) versus
   Google Maps (key required, usage limits). Affects the `points` format only
   if we change our minds late.
4. **Evidence storage.** Filesystem path recorded in MySQL, or BLOB in the
   database? Filesystem is simpler; document whichever is chosen.
5. **Anonymous incident reporting.** Does an anonymous incident still require a
   logged-in account behind the scenes for abuse prevention, or is it fully
   unauthenticated? Security trade-off worth arguing in the report.

---

## 10. Change process

The contract will change — that is fine, as long as it changes deliberately.

1. Open a GitHub issue describing the proposed change and why.
2. Get agreement from one frontend and one backend member.
3. Update this file in the same pull request as the code change.
4. Bump the version at the top and note the change below.

### Change log

| Version | Date | Change |
| --- | --- | --- |
| 0.1 | 2026-08-23 | Initial draft for group review |
| 0.2 | 2026-08-30 | Coordinates optional; `locationSource` added; manual triage for location-less incidents |
| 0.3 | 2026-09-14 | Added role-restricted GBV officer/admin queue for text-only case review |
