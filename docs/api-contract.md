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

**Request**
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
  "lastUpdatedAt": "2026-08-23T08:00:00Z"
}
```

Returns status only — never the report content, never officer names. Apply rate
limiting so codes cannot be brute-forced, and record the limit in the report.

GBV status values: `submitted`, `under_review`, `referred`, `closed`.

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
  "status": "requested",
  "createdAt": "2026-08-23T02:00:00Z"
}
```

Booking status: `requested`, `confirmed`, `declined`, `cancelled`.

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
