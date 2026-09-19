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
// ---------------------------------------------------------------------------

// Storage keys
const TOKEN_KEY = 'ufh.authToken';
const USER_KEY = 'ufh.authUser';
const ACTIVE_ROLE_KEY = 'ufh.activeRole';

/** Sleep, so mock responses are not instant and loading states are visible. */
function delay(ms = MOCK_DELAY_MS) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ---------------------------------------------------------------------------
// Persona and Role definitions for Mock Mode & Session Storage
// ---------------------------------------------------------------------------

export const MOCK_PERSONAS = {
  student: {
    userId: 17,
    studentNumber: '202512345',
    fullName: 'Sipho Ndlovu',
    email: '202512345@ufh.ac.za',
    phone: '0821234567',
    role: 'student',
  },
  campus_control: {
    userId: 101,
    fullName: 'Sgt. Mthembu',
    email: 'campus.control@ufh.ac.za',
    phone: '0406082222',
    role: 'campus_control',
  },
  responder: {
    userId: 5,
    fullName: 'Nomsa Khumalo',
    email: 'responder.khumalo@ufh.ac.za',
    phone: '0834567890',
    role: 'responder',
    team: 'campus_security',
  },
  gbv_officer: {
    userId: 201,
    fullName: 'Dr. N. Dlamini',
    email: 'gbv.unit@ufh.ac.za',
    phone: '0406082999',
    role: 'gbv_officer',
  },
  admin: {
    userId: 1,
    fullName: 'System Administrator',
    email: 'admin@ufh.ac.za',
    phone: '0406082000',
    role: 'admin',
  },
};

/** Retrieves the currently active role in mock mode. */
export function getActiveMockRole() {
  return localStorage.getItem(ACTIVE_ROLE_KEY) || MOCK_ROLE || 'student';
}

/** Sets the active role for mock demonstration. */
export function setActiveMockRole(role) {
  if (role && MOCK_PERSONAS[role]) {
    localStorage.setItem(ACTIVE_ROLE_KEY, role);
    setStoredUser(MOCK_PERSONAS[role]);
    setToken(`mock.jwt.${role}`);
  }
}

/** Retrieves cached user profile from localStorage. */
export function getStoredUser() {
  const raw = localStorage.getItem(USER_KEY);
  if (raw) {
    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  }
  return null;
}

/** Caches user profile in localStorage. */
export function setStoredUser(user) {
  if (user) {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    if (user.role) {
      localStorage.setItem(ACTIVE_ROLE_KEY, user.role);
    }
  } else {
    localStorage.removeItem(USER_KEY);
  }
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

/** Clears the session. Returns the previous role so redirects can route accurately. */
export function logout() {
  const user = getStoredUser();
  const lastRole = user?.role || getActiveMockRole();
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  localStorage.removeItem(ACTIVE_ROLE_KEY);
  return lastRole;
}

/** True if we are holding a token. */
export function isLoggedIn() {
  return getToken() !== null;
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/**
 * An error carrying the server's standard error shape:
 *   { "error": "VALIDATION_FAILED", "message": "...", "field": "description" }
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

  if (auth) {
    const token = getToken();
    if (!token) {
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
    throw apiError(
      0,
      'NETWORK_ERROR',
      'Could not reach the server. Check your connection and try again.',
    );
  }

  if (response.status === 204) return null;

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

const MOCK_INCIDENT_SUMMARIES = [
  { incidentId: 42, type: 'medical',    status: 'assigned', priority: 2, createdAt: '2026-08-23T01:50:00Z' },
  { incidentId: 41, type: 'sos',        status: 'en_route', priority: 1, createdAt: '2026-08-23T01:35:12Z' },
  { incidentId: 40, type: 'suspicious', status: 'reported', priority: 4, createdAt: '2026-08-22T22:10:45Z' },
  { incidentId: 39, type: 'theft',      status: 'resolved', priority: 3, createdAt: '2026-08-22T18:02:00Z' },
  { incidentId: 38, type: 'other',      status: 'cancelled', priority: 5, createdAt: '2026-08-22T16:44:30Z' },
];

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
 */
/**
 * POST /api/auth/register - public.
 *
 * healthInfo is optional: { conditions: string[], note: string|null }. Not
 * yet part of the backend contract - the real endpoint currently ignores
 * unknown JSON fields rather than rejecting the request (verified directly
 * against a running backend), so sending it is safe, but nothing is stored
 * or shown to a responder until the backend adds a column for it.
 */
export async function register(studentNumber, fullName, email, password, phone, healthInfo = null) {
  const body = {
    studentNumber,
    fullName,
    email,
    password,
    phone,
    healthConditions: healthInfo?.conditions?.length ? healthInfo.conditions : null,
    healthNote: healthInfo?.note ?? null,
  };

  if (MOCK) {
    await delay();
    const newUser = {
      userId: 17,
      studentNumber,
      fullName,
      email,
      phone,
      role: 'student',
    };
    setStoredUser(newUser);
    return newUser;
  }

  return request('/auth/register', { method: 'POST', body });
}

/**
 * POST /api/auth/login - public.
 * Supports optional requestedRole in mock mode for instant persona testing.
 */
export async function login(email, password, requestedRole = null) {
  let result;

  if (MOCK) {
    await delay();

    let targetRole = requestedRole;
    if (!targetRole) {
      const lowerEmail = (email || '').toLowerCase();
      if (lowerEmail.includes('control') || lowerEmail.includes('dispatch')) {
        targetRole = 'campus_control';
      } else if (lowerEmail.includes('gbv')) {
        targetRole = 'gbv_officer';
      } else if (lowerEmail.includes('responder')) {
        targetRole = 'responder';
      } else if (lowerEmail.includes('admin')) {
        targetRole = 'admin';
      } else {
        targetRole = getActiveMockRole() || 'student';
      }
    }

    const persona = MOCK_PERSONAS[targetRole] || MOCK_PERSONAS.student;
    result = {
      token: `mock.jwt.${persona.role}`,
      expiresAt: '2026-08-23T09:50:00Z',
      user: {
        userId: persona.userId,
        fullName: persona.fullName,
        role: persona.role,
        email: email || persona.email,
      },
    };
  } else {
    result = await request('/auth/login', {
      method: 'POST',
      body: { email, password },
    });
  }

  setToken(result.token);
  if (result.user) {
    setStoredUser(result.user);
  }
  return result;
}

/**
 * GET /api/auth/me - any authenticated role.
 */
export async function getCurrentUser() {
  if (MOCK) {
    await delay();
    requireMockToken();
    const cached = getStoredUser();
    if (cached) return cached;

    const activeRole = getActiveMockRole();
    const persona = MOCK_PERSONAS[activeRole] || MOCK_PERSONAS.student;
    setStoredUser(persona);
    return persona;
  }

  const user = await request('/auth/me', { auth: true });
  if (user) {
    setStoredUser(user);
  }
  return user;
}

// ---------------------------------------------------------------------------
// Incidents - contract section 3
// ---------------------------------------------------------------------------

export async function submitIncident(incidentData) {
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

    const priority = body.type === 'sos' ? 1 : (body.type === 'medical' || body.type === 'fire' ? 2 : 3);
    const newId = Math.floor(Math.random() * 900) + 100;
    const created = {
      incidentId: newId,
      type: body.type,
      status: 'reported',
      priority,
      createdAt: new Date().toISOString(),
    };

    MOCK_INCIDENT_SUMMARIES.unshift({ ...created });
    return created;
  }

  return request('/incidents', { method: 'POST', body, auth: true });
}

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

  const params = new URLSearchParams();
  if (status) params.set('status', status);
  if (page) params.set('page', String(page));

  const query = params.toString();
  return request(`/incidents${query ? `?${query}` : ''}`, { auth: true });
}

export async function getIncident(incidentId) {
  if (MOCK) {
    await delay();
    requireMockToken();
    return mockIncidentDetail(Number(incidentId));
  }

  return request(`/incidents/${encodeURIComponent(incidentId)}`, { auth: true });
}

// ---------------------------------------------------------------------------
// Incident lifecycle
// ---------------------------------------------------------------------------

function mockIncidentDetail(incidentId, overrides = {}) {
  const found = MOCK_INCIDENT_SUMMARIES.find((i) => i.incidentId === incidentId);
  const type = found?.type ?? 'medical';
  const status = overrides.status ?? found?.status ?? 'assigned';
  const priority = found?.priority ?? 2;

  return {
    incidentId,
    type,
    description: 'Reported incident requiring security response near campus grounds.',
    status,
    priority,
    latitude: -32.78331,
    longitude: 26.84971,
    accuracy: 18.5,
    locationSource: 'device',
    anonymous: false,
    createdAt: found?.createdAt ?? '2026-08-23T01:50:00Z',
    updatedAt: new Date().toISOString(),
    reporter: {
      userId: 17,
      fullName: 'Sipho Ndlovu',
    },
    assignedResponder: {
      responderId: 5,
      fullName: 'Nomsa Khumalo',
      latitude: -32.78210,
      longitude: 26.84800,
    },
    ...overrides,
  };
}

export async function updateIncidentStatus(incidentId, status, note = null) {
  if (MOCK) {
    await delay();
    requireMockToken();

    const listed = MOCK_INCIDENT_SUMMARIES.find((i) => i.incidentId === Number(incidentId));
    if (listed) listed.status = status;

    return mockIncidentDetail(Number(incidentId), { status });
  }

  return request(`/incidents/${encodeURIComponent(incidentId)}/status`, {
    method: 'PATCH',
    body: { status, note },
    auth: true,
  });
}

export async function cancelIncident(incidentId, reason = null) {
  if (MOCK) {
    await delay();
    requireMockToken();

    const listed = MOCK_INCIDENT_SUMMARIES.find((i) => i.incidentId === Number(incidentId));
    if (listed) listed.status = 'cancelled';

    return mockIncidentDetail(Number(incidentId), { status: 'cancelled' });
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

export async function getAvailableResponders() {
  if (MOCK) {
    await delay();
    requireMockToken();

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
          latitude: -32.78420,
          longitude: 26.85100,
          lastSeenAt: '2026-08-23T01:40:00Z',
        },
      ],
    };
  }

  return request('/responders/available', { auth: true });
}

export async function assignIncident(incidentId, responderId = null) {
  if (MOCK) {
    await delay();
    requireMockToken();

    const incident = MOCK_INCIDENT_SUMMARIES.find((item) => item.incidentId === Number(incidentId));
    if (incident) incident.status = 'assigned';

    return {
      incidentId: Number(incidentId),
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
        {
          patrolId: 89,
          zoneName: 'Sports Grounds Gate',
          latitude: -32.78620,
          longitude: 26.85310,
          recordedAt: '2026-08-23T01:30:00Z',
          minutesAgo: 20,
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
      patrolId: Math.floor(Math.random() * 900) + 100,
      zoneId,
      recordedAt: new Date().toISOString(),
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
        {
          hotspotId: 8,
          name: 'East Gate Perimeter',
          latitude: -32.78200,
          longitude: 26.85400,
          radiusMetres: 80,
          riskLevel: 'moderate',
          incidentCount: 6,
          computedAt: '2026-08-22T21:00:00Z',
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

// ---------------------------------------------------------------------------
// Wellness - contract section 8
// ---------------------------------------------------------------------------

export async function getWellnessResources() {
  if (MOCK) {
    await delay();
    requireMockToken();
    return {
      items: [
        {
          resourceId: 1,
          title: 'Student Counselling Unit',
          category: 'counselling',
          description: 'On-campus professional psychological support and crisis debriefing.',
          contactPhone: '0406082210',
          availability: 'Mon-Fri 08:00-16:30',
        },
        {
          resourceId: 2,
          title: 'Peer Wellness & Support Desk',
          category: 'wellness',
          description: 'Confidential peer counselling and student wellbeing conversations.',
          contactPhone: '0406082215',
          availability: 'Tuesday and Thursday 12:00-15:00',
        },
        {
          resourceId: 3,
          title: 'Campus Health Centre',
          category: 'health',
          description: 'Primary healthcare clinic and immediate medical assistance.',
          contactPhone: '0406082333',
          availability: 'Mon-Fri 08:00-17:00 (24h On-Call Nurse)',
        },
      ],
    };
  }

  return request('/wellness/resources', { auth: true });
}

export async function createWellnessBooking(resourceId, preferredDate, preferredSlot, note = null) {
  if (MOCK) {
    await delay();
    requireMockToken();
    return {
      bookingId: Math.floor(Math.random() * 900) + 10,
      status: 'requested',
      createdAt: new Date().toISOString(),
    };
  }

  return request('/wellness/bookings', {
    method: 'POST',
    body: { resourceId, preferredDate, preferredSlot, note },
    auth: true,
  });
}

// ---------------------------------------------------------------------------
// Confidential GBV reporting - contract section 7
// ---------------------------------------------------------------------------

export async function submitGbvReport(report) {
  if (MOCK) {
    await delay();
    return {
      referenceCode: 'GBV-4K7P-22XQ',
      status: 'submitted',
      submittedAt: new Date().toISOString(),
    };
  }

  return request('/gbv/reports', { method: 'POST', body: report, auth: !report.anonymous });
}

export async function getGbvReportStatus(referenceCode) {
  if (MOCK) {
    await delay();
    return {
      referenceCode,
      status: 'under_review',
      lastUpdatedAt: new Date().toISOString(),
    };
  }

  return request(`/gbv/reports/${encodeURIComponent(referenceCode)}/status`);
}

export async function getGbvReports(status = null, page = 1) {
  if (MOCK) {
    await delay();
    requireMockToken();
    const items = [
      {
        referenceCode: 'GBV-4K7P-22XQ',
        status: 'under_review',
        description: 'Confidential report submitted for safety guidance.',
        occurredAt: '2026-08-20T19:30:00Z',
        latitude: null,
        longitude: null,
        anonymous: true,
        contactPreference: 'email',
        submittedAt: '2026-08-23T01:55:00Z',
        lastUpdatedAt: '2026-08-23T08:00:00Z',
      },
      {
        referenceCode: 'GBV-9M2R-55YT',
        status: 'submitted',
        description: 'Incident occurred near residence corridor.',
        occurredAt: '2026-08-22T14:10:00Z',
        latitude: -32.7842,
        longitude: 26.8503,
        anonymous: false,
        contactPreference: 'phone',
        submittedAt: '2026-08-22T15:00:00Z',
        lastUpdatedAt: '2026-08-22T15:00:00Z',
      },
    ].filter((report) => !status || report.status === status);
    return { items, page, pageSize: 20, totalItems: items.length };
  }

  const params = new URLSearchParams({ page: String(page) });
  if (status) params.set('status', status);
  return request(`/gbv/reports?${params.toString()}`, { auth: true });
}
