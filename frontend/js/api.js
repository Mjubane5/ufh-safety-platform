// api.js - the single door between this frontend and the backend.
//
// RULE: this is the only file in the project allowed to call fetch().
// Every page imports the functions it needs from here:
//
//     import { login, getIncidents } from './api.js';
//
// Why one file? Three reasons the panel will ask about:
//   1. The auth token is attached in exactly one place, so we cannot forget it.
//   2. Error handling is written once, so every page shows errors the same way.
//   3. When the contract changes, we change one file, not fifteen pages.
//
// All shapes here follow docs/api-contract.md. If this file and the contract
// ever disagree, the contract is right - raise an issue, do not patch quietly.

// config.js sits next to this file inside frontend/js/, so the path is './'.
import { BASE_URL, MOCK, MOCK_DELAY_MS, MOCK_ENFORCE_AUTH, MOCK_ROLE } from './config.js';

// ---------------------------------------------------------------------------
// The MOCK pattern - read this before you change anything
// ---------------------------------------------------------------------------
//
// The backend team is building endpoints while the frontend team is building
// pages. We cannot wait for each other, so api.js can run in two modes.
//
// Every exported function below has the same skeleton:
//
//     export async function doSomething(args) {
//       if (MOCK) {
//         await delay();               // pretend the network took a moment
//         return { ...fake data... };  // shaped exactly like the contract says
//       }
//       return request('/real/path', { ... });   // the real call
//     }
//
// The important part: the fake object and the real response have IDENTICAL
// field names and types. A page that reads `incident.incidentId` works the
// same in both modes, so switching modes never breaks a page.
//
// HOW TO SWITCH IT OFF when the backend is ready:
//   1. Start the Spring Boot backend on http://localhost:8080
//   2. Open frontend/config.js and set  MOCK = false
//   3. Reload the page. That is the whole change.
//
// If a page breaks after the switch, the backend's response does not match the
// contract, or our mock was wrong. Compare both against docs/api-contract.md
// and fix whichever one drifted. Do NOT "fix" it by changing the page.
//
// When adding a new endpoint, write the mock branch FIRST, straight from the
// contract. It forces you to read the contract before you write the code.
// ---------------------------------------------------------------------------

// Key used for the JWT in localStorage.
// The project rules allow the auth token in localStorage and nothing else
// sensitive - no names, no student numbers, no incident content.
//
// SECURITY TRADE-OFF - know this one, a panel will ask.
// localStorage is readable by ANY JavaScript running on the page. If an
// attacker gets script onto one of our pages (an XSS bug), they can read this
// token and impersonate the student until it expires. The safer alternative is
// an httpOnly cookie, which JavaScript cannot read at all - but that needs
// extra CORS and CSRF work on the Spring Boot side. We chose localStorage for
// the prototype and documented the trade-off rather than hiding it.
//
// That choice puts a rule on every other frontend file:
// NEVER put user-supplied text on the page with innerHTML. Not an incident
// description, not a full name, not an error message from the server. Use
// element.textContent instead - it writes text as text, so a description
// containing <script> is displayed, not executed. One innerHTML slip is all it
// takes to turn a stored incident report into a token thief.
const TOKEN_KEY = 'ufh.authToken';

/** Sleep, so mock responses are not instant and loading states are visible. */
function delay(ms = MOCK_DELAY_MS) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ---------------------------------------------------------------------------
// Token handling
// ---------------------------------------------------------------------------

/** Returns the stored JWT, or null if nobody is logged in. */
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || null;
}

/** Stores the JWT. Called by login(); pages should not call this directly. */
export function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

/** Clears the session. Use this for the logout button. */
export function logout() {
  localStorage.removeItem(TOKEN_KEY);
}

/** True if we are holding a token. Does not prove the token is still valid -
 *  only the server can say that, which is what getCurrentUser() is for. */
export function isLoggedIn() {
  return getToken() !== null;
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/**
 * An error carrying the server's standard error shape:
 *   { "error": "VALIDATION_FAILED", "message": "...", "field": "description" }
 *
 * `err.message` is the human-readable sentence - show that to the student.
 * `err.code` is the machine string, for branching in code.
 * `err.field` names the form input to highlight (null when not a validation error).
 * `err.status` is the HTTP status code.
 *
 * Usage in a page:
 *
 *     try {
 *       await submitIncident(data);
 *     } catch (err) {
 *       showError(err.message);
 *       if (err.field) highlightInput(err.field);
 *     }
 */
export class ApiError extends Error {
  constructor({ message, code = null, field = null, status = 0 }) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.field = field;
    this.status = status;
  }
}

/** Builds an ApiError the same way in mock mode and real mode. */
function apiError(status, code, message, field = null) {
  return new ApiError({ status, code, message, field });
}

// ---------------------------------------------------------------------------
// The one and only fetch()
// ---------------------------------------------------------------------------

/**
 * Performs a request against the backend.
 *
 * @param {string} path      Path after BASE_URL, e.g. '/auth/login'.
 * @param {object} options
 * @param {string} options.method   HTTP method. Defaults to 'GET'.
 * @param {object} options.body     Object to send as JSON. Omit for GET.
 * @param {boolean} options.auth    True to attach the Bearer token.
 * @returns {Promise<any>} Parsed JSON body, or null for a 204.
 * @throws {ApiError} On any non-2xx response, or if the network is unreachable.
 */
async function request(path, { method = 'GET', body = undefined, auth = false } = {}) {
  const headers = { Accept: 'application/json' };

  if (body !== undefined) {
    headers['Content-Type'] = 'application/json; charset=utf-8';
  }

  // The single place the token is attached. Contract section 1:
  // Authorization: Bearer <token> on all protected endpoints.
  if (auth) {
    const token = getToken();
    if (!token) {
      // Fail here rather than sending a request we know will be rejected.
      throw apiError(401, 'NOT_AUTHENTICATED', 'You are not signed in. Please log in again.');
    }
    headers.Authorization = `Bearer ${token}`;
  }

  let response;
  try {
    response = await fetch(BASE_URL + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (networkFailure) {
    // fetch() only rejects when the request never reached the server:
    // server down, no signal, CORS blocked. A 400 or 500 is a successful
    // round trip and lands below, not here.
    throw apiError(
      0,
      'NETWORK_ERROR',
      'Could not reach the server. Check your connection and try again.',
    );
  }

  // 204 No Content - succeeded, nothing to parse.
  if (response.status === 204) return null;

  // Read the body defensively. A 500 from a crashed backend may return HTML
  // or nothing at all, and JSON.parse would throw over the real problem.
  let payload = null;
  const text = await response.text();
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch (notJson) {
      payload = null;
    }
  }

  if (!response.ok) {
    // Contract section 1: every non-2xx uses { error, message, field }.
    // We still guard for a malformed body so the student sees something useful.
    throw apiError(
      response.status,
      payload?.error ?? 'UNKNOWN_ERROR',
      payload?.message ?? `Request failed (${response.status}).`,
      payload?.field ?? null,
    );
  }

  return payload;
}

// ---------------------------------------------------------------------------
// Mock data
// ---------------------------------------------------------------------------
//
// Synthetic only. Project rule: never put a real student name, real student
// number, or real incident in this repository.

const MOCK_USER = {
  userId: 17,
  fullName: MOCK_ROLE === 'responder' ? 'A Responder' : 'A Student',
  role: MOCK_ROLE,
};

// A small in-memory list so the dashboard has something to render and paginate.
// Summary shape only - contract section 3 says list items carry no description
// and no coordinates. Keeping the mock honest here stops us writing a page that
// silently depends on a field the real endpoint will not send.
const MOCK_INCIDENT_SUMMARIES = [
  { incidentId: 42, type: 'medical',    status: 'assigned', priority: 2, createdAt: '2026-08-23T01:50:00Z' },
  { incidentId: 41, type: 'sos',        status: 'en_route', priority: 1, createdAt: '2026-08-23T01:35:12Z' },
  { incidentId: 40, type: 'suspicious', status: 'reported', priority: 4, createdAt: '2026-08-22T22:10:45Z' },
  { incidentId: 39, type: 'theft',      status: 'resolved', priority: 3, createdAt: '2026-08-22T18:02:00Z' },
  { incidentId: 38, type: 'other',      status: 'cancelled', priority: 5, createdAt: '2026-08-22T16:44:30Z' },
];

/**
 * The mock stand-in for the backend's token check.
 *
 * Controlled by MOCK_ENFORCE_AUTH in config.js:
 *   false (current) - does nothing. Protected calls work with no token, so
 *                     pages can be built before the login page exists.
 *   true            - throws the same 401 the real backend would, so we find
 *                     out early which pages forgot to handle a signed-out user.
 *
 * Switch it on in config.js as soon as login works - that is a one-line change
 * in one file, not a code edit here.
 */
function requireMockToken() {
  if (!MOCK_ENFORCE_AUTH) return;

  if (!getToken()) {
    throw apiError(401, 'NOT_AUTHENTICATED', 'You are not signed in. Please log in again.');
  }
}

// ---------------------------------------------------------------------------
// Authentication - contract section 2
// ---------------------------------------------------------------------------

/**
 * POST /api/auth/register - public, student self-registration.
 *
 * @returns {Promise<{userId: number, fullName: string, role: string}>}
 */
export async function register(studentNumber, fullName, email, password, phone) {
  const body = { studentNumber, fullName, email, password, phone };

  if (MOCK) {
    await delay();
    return {
      userId: 17,
      fullName,
      role: 'student',
    };
  }

  return request('/auth/register', { method: 'POST', body });
}

/**
 * POST /api/auth/login - public.
 *
 * On success the JWT is stored here, so every later protected call is
 * authenticated automatically. Pages never touch the token themselves.
 *
 * @returns {Promise<{token: string, expiresAt: string, user: object}>}
 */
export async function login(email, password) {
  let result;

  if (MOCK) {
    await delay();
    // A fake token that is obviously fake. It is never sent anywhere in mock
    // mode - it exists so the logged-in / logged-out flow is testable.
    result = {
      token: 'mock.jwt.token',
      expiresAt: '2026-08-23T09:50:00Z',
      user: { ...MOCK_USER },
    };
  } else {
    result = await request('/auth/login', {
      method: 'POST',
      body: { email, password },
    });
  }

  setToken(result.token);
  return result;
}

/**
 * GET /api/auth/me - any authenticated role.
 * Used on page load to restore the session after a refresh.
 *
 * @returns {Promise<{userId: number, fullName: string, role: string}>}
 */
export async function getCurrentUser() {
  if (MOCK) {
    await delay();
    requireMockToken();
    return { ...MOCK_USER };
  }

  return request('/auth/me', { auth: true });
}

// ---------------------------------------------------------------------------
// Incidents - contract section 3
// ---------------------------------------------------------------------------

/**
 * POST /api/incidents - role: student.
 *
 * @param {object} incidentData
 * @param {string} incidentData.type        sos | medical | fire | theft |
 *                                          assault | accident | suspicious |
 *                                          unsafe | other
 * @param {string|null} incidentData.description  Required when type is 'other'.
 * @param {number} incidentData.latitude
 * @param {number} incidentData.longitude
 * @param {number|null} incidentData.accuracy    Metres, or null if unavailable.
 * @param {boolean} incidentData.anonymous
 * @returns {Promise<{incidentId, type, status, priority, createdAt}>}
 */
export async function submitIncident(incidentData) {
  // Send exactly the six contract fields, nothing extra. Anything else a page
  // happens to have on its object is dropped here rather than confusing the
  // backend validator.
  const body = {
    type: incidentData.type,
    description: incidentData.description ?? null,
    latitude: incidentData.latitude,
    longitude: incidentData.longitude,
    accuracy: incidentData.accuracy ?? null,
    anonymous: incidentData.anonymous,
  };

  if (MOCK) {
    await delay();
    requireMockToken();

    // Priority is computed by the BACKEND (C++ module or Java equivalent).
    // The client never sets it and must never assume the formula. The line
    // below is a placeholder so the confirmation screen has a number to show -
    // the real values will differ, and that is correct.
    const priority = body.type === 'sos' ? 1 : 2;

    const created = {
      incidentId: 42,
      type: body.type,
      status: 'reported',
      priority,
      createdAt: '2026-08-23T01:50:00Z',
    };

    // Keep the mock list in step so the dashboard shows what we just reported.
    MOCK_INCIDENT_SUMMARIES.unshift({ ...created });
    return created;
  }

  return request('/incidents', { method: 'POST', body, auth: true });
}

/**
 * GET /api/incidents - list, scoped by the caller's role on the server.
 *
 * @param {string|null} status  One status value to filter by, or null for all.
 * @param {number} page         1-based. Contract default is 1.
 * @returns {Promise<{items: object[], page: number, pageSize: number, totalItems: number}>}
 *
 * `pageSize` is left to the server default of 20 - we send only the two
 * parameters the contract's default listing needs. Items come back in the
 * SUMMARY shape: incidentId, type, status, priority, createdAt. For detail,
 * call getIncident().
 */
export async function getIncidents(status = null, page = 1) {
  if (MOCK) {
    await delay();
    requireMockToken();

    const pageSize = 20;
    const filtered = status
      ? MOCK_INCIDENT_SUMMARIES.filter((incident) => incident.status === status)
      : MOCK_INCIDENT_SUMMARIES;

    const start = (page - 1) * pageSize;
    return {
      items: filtered.slice(start, start + pageSize).map((incident) => ({ ...incident })),
      page,
      pageSize,
      totalItems: filtered.length,
    };
  }

  // URLSearchParams handles the encoding and the '?' for us. We only append a
  // parameter when we actually have one, so the server's own defaults apply.
  const params = new URLSearchParams();
  if (status) params.set('status', status);
  if (page) params.set('page', String(page));

  const query = params.toString();
  return request(`/incidents${query ? `?${query}` : ''}`, { auth: true });
}

/**
 * GET /api/incidents/{incidentId} - the full detail shape.
 *
 * Remember when rendering: `reporter` is null when the report was anonymous,
 * and `assignedResponder` is null until someone is assigned. Never read
 * `incident.reporter.fullName` without checking for null first.
 *
 * @param {number} incidentId
 * @returns {Promise<object>}
 */
export async function getIncident(incidentId) {
  if (MOCK) {
    await delay();
    requireMockToken();

    return mockIncidentDetail(incidentId);
  }

  return request(`/incidents/${encodeURIComponent(incidentId)}`, { auth: true });
}

// ---------------------------------------------------------------------------
// Incident lifecycle
// ---------------------------------------------------------------------------

/**
 * One realistic detail record for mock mode.
 *
 * GET /incidents/{id}, PATCH .../status and POST .../cancel all return this
 * same shape, so they share one builder. If they each had their own copy they
 * would drift, and a page that works after a status change would break after
 * a plain reload.
 *
 * The two nullable objects are populated here on purpose - set either to null
 * to test your null handling.
 */
function mockIncidentDetail(incidentId, overrides = {}) {
  return {
    incidentId,
    type: 'medical',
    description: 'Someone collapsed outside the library.',
    status: 'assigned',
    priority: 2,
    latitude: -32.78331,
    longitude: 26.84971,
    accuracy: 18.5,
    locationSource: 'device',
    anonymous: false,
    createdAt: '2026-08-23T01:50:00Z',
    updatedAt: '2026-08-23T01:52:14Z',
    reporter: {
      userId: 17,
      fullName: 'A Student',
    },
    assignedResponder: {
      responderId: 5,
      fullName: 'A Responder',
      latitude: -32.78210,
      longitude: 26.84800,
    },
    ...overrides,
  };
}

/**
 * Moves an incident along the lifecycle.
 *
 *   reported -> triaged -> assigned -> en_route -> on_scene -> resolved
 *
 * Roles: responder (own assignment only), campus_control, admin.
 *
 * The server rejects an illegal jump with 409 - for example resolved back to
 * reported. Catch it and show `err.message`; it names both statuses.
 *
 * @param {number} incidentId
 * @param {string} status   one of the wire values above
 * @param {string|null} note  optional, max 500 characters
 * @returns {Promise<object>} the full incident
 */
export async function updateIncidentStatus(incidentId, status, note = null) {
  if (MOCK) {
    await delay();
    requireMockToken();

    // Keep the list in step so a dashboard behind this call does not show a
    // stale status after the detail page has moved on.
    const listed = MOCK_INCIDENT_SUMMARIES.find((i) => i.incidentId === incidentId);
    if (listed) listed.status = status;

    return mockIncidentDetail(incidentId, { status });
  }

  return request(`/incidents/${encodeURIComponent(incidentId)}/status`, {
    method: 'PATCH',
    body: { status, note },
    auth: true,
  });
}

/**
 * The false-alarm path. Sets status to `cancelled` and keeps the record -
 * incidents are never deleted.
 *
 * Roles: student, and only on an incident they reported themselves.
 *
 * `reason` is optional. Do not make a student who pressed SOS by accident
 * write an explanation before the alarm stops.
 *
 * @param {number} incidentId
 * @param {string|null} reason  optional, max 500 characters
 * @returns {Promise<object>} the full incident, now cancelled
 */
export async function cancelIncident(incidentId, reason = null) {
  if (MOCK) {
    await delay();
    requireMockToken();

    const listed = MOCK_INCIDENT_SUMMARIES.find((i) => i.incidentId === incidentId);
    if (listed) listed.status = 'cancelled';

    return mockIncidentDetail(incidentId, { status: 'cancelled' });
  }

  return request(`/incidents/${encodeURIComponent(incidentId)}/cancel`, {
    method: 'POST',
    body: { reason },
    auth: true,
  });
}

// ---------------------------------------------------------------------------
// Responders
// ---------------------------------------------------------------------------

/**
 * The responders a dispatcher can currently send.
 *
 * Roles: campus_control and admin only. A student calling this gets 403 —
 * where every responder on campus is standing is not student-facing data.
 *
 * Every field except `responderId`, `team` and `status` can be null:
 *
 *   fullName    null if the duty row outlived the account
 *   latitude    null if the responder has never checked in
 *   longitude   same
 *   lastSeenAt  same
 *
 * So do not plot a marker without checking the coordinates first. Plotting
 * null as 0 puts the responder in the Gulf of Guinea.
 *
 * @returns {Promise<{items: object[]}>}
 */
export async function getAvailableResponders() {
  if (MOCK) {
    await delay();
    requireMockToken();

    // Deliberately includes one responder with no position, so the dispatcher
    // screen gets tested against the null case from the first render rather
    // than the first time a real responder forgets to check in.
    return {
      items: [
        {
          responderId: 5,
          fullName: 'Nomsa Khumalo',
          team: 'campus_security',
          status: 'available',
          latitude: -32.78210,
          longitude: 26.84800,
          lastSeenAt: '2026-08-23T01:49:30Z',
        },
        {
          responderId: 8,
          fullName: 'Pieter Botha',
          team: 'campus_security',
          status: 'available',
          latitude: null,
          longitude: null,
          lastSeenAt: null,
        },
      ],
    };
  }

  return request('/responders/available', { auth: true });
}

/**
 * POST /api/incidents/{incidentId}/assign - campus control and admin only.
 *
 * The dispatcher must choose an explicit responder when an incident has no
 * coordinates. The backend remains the authority and returns 409 or 404 when
 * the assignment cannot be made.
 */
export async function assignIncident(incidentId, responderId = null) {
  if (MOCK) {
    await delay();
    requireMockToken();

    const incident = MOCK_INCIDENT_SUMMARIES.find((item) => item.incidentId === incidentId);
    if (incident) incident.status = 'assigned';

    return {
      incidentId,
      status: 'assigned',
      responder: {
        responderId: responderId ?? 5,
        fullName: responderId === 8 ? 'Pieter Botha' : 'Nomsa Khumalo',
      },
      route: null,
    };
  }

  const body = responderId === null ? {} : { responderId };
  return request(`/incidents/${encodeURIComponent(incidentId)}/assign`, {
    method: 'POST',
    body,
    auth: true,
  });
}

// ---------------------------------------------------------------------------
// Safety map - contract sections 5 and 6
// ---------------------------------------------------------------------------

export async function getRecentPatrols(latitude, longitude, radiusMetres = 500) {
  if (MOCK) {
    await delay();
    requireMockToken();
    return {
      items: [
        {
          patrolId: 88,
          zoneName: 'Library Precinct',
          latitude: -32.78400,
          longitude: 26.85010,
          recordedAt: '2026-08-23T01:48:00Z',
          minutesAgo: 2,
        },
      ],
    };
  }

  const params = new URLSearchParams({
    latitude: String(latitude),
    longitude: String(longitude),
    radiusMetres: String(radiusMetres),
  });
  return request(`/patrols/recent?${params.toString()}`, { auth: true });
}

export async function createPatrol(zoneId, latitude, longitude, note = null) {
  if (MOCK) {
    await delay();
    requireMockToken();
    return {
      patrolId: 88,
      zoneId,
      recordedAt: '2026-08-23T01:48:00Z',
    };
  }

  return request('/patrols', {
    method: 'POST',
    body: { zoneId, latitude, longitude, note },
    auth: true,
  });
}

export async function getHotspots() {
  if (MOCK) {
    await delay();
    requireMockToken();
    return {
      items: [
        {
          hotspotId: 7,
          name: 'Lower Campus Footpath',
          latitude: -32.78550,
          longitude: 26.85200,
          radiusMetres: 120,
          riskLevel: 'elevated',
          incidentCount: 14,
          computedAt: '2026-08-22T20:00:00Z',
        },
      ],
    };
  }

  return request('/hotspots', { auth: true });
}

export async function getSafeRoute(from, to) {
  if (MOCK) {
    await delay();
    requireMockToken();
    return {
      distanceMetres: 720,
      estimatedSeconds: 540,
      safetyScore: 0.78,
      avoidedHotspots: [7],
      points: [
        { ...from },
        { latitude: -32.78330, longitude: 26.84950 },
        { ...to },
      ],
    };
  }

  return request('/routes/safe', {
    method: 'POST',
    body: { from, to },
    auth: true,
  });
}
