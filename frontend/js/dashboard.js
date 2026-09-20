// dashboard.js - behaviour for dashboard.html.
//
// Lists the incidents the signed-in student has reported. The server decides
// which rows those are; GET /api/incidents is scoped by role, so a student
// only ever gets their own back. We do not filter by user on the client.
//
// No fetch() here. Everything goes through api.js.
// No innerHTML here. Every server value reaches the page via textContent.

import { getCurrentUser, getIncidents, isLoggedIn, logout, ApiError, submitIncident, getHealthProfile } from './api.js';
import { createTrackingMap } from './tracking.js';

const LOGIN_URL = './login.html';
const REPORT_URL = './report.html';
const INCIDENT_URL = './incident.html';

// The seven status values from docs/api-contract.md, plus an "everything"
// option that sends no status parameter at all.
const STATUS_FILTERS = [
  { value: null, label: 'All' },
  { value: 'reported', label: 'Reported' },
  { value: 'triaged', label: 'Triaged' },
  { value: 'assigned', label: 'Assigned' },
  { value: 'en_route', label: 'En route' },
  { value: 'on_scene', label: 'On scene' },
  { value: 'resolved', label: 'Resolved' },
  { value: 'cancelled', label: 'Cancelled' },
];

const STATUS_LABELS = {
  reported: 'Reported',
  triaged: 'Triaged',
  assigned: 'Assigned',
  en_route: 'En route',
  on_scene: 'On scene',
  resolved: 'Resolved',
  cancelled: 'Cancelled',
};

const TYPE_LABELS = {
  sos: 'SOS',
  medical: 'Medical emergency',
  fire: 'Fire',
  theft: 'Theft',
  assault: 'Assault',
  accident: 'Accident',
  suspicious: 'Suspicious behaviour',
  unsafe: 'Unsafe condition',
  other: 'Other',
};

// Statuses that mean someone is on their way. They share a pill style.
const ACTIVE_STATUSES = ['assigned', 'en_route', 'on_scene'];

// What the page is currently showing. One object so the render path has a
// single thing to read and the retry button can just re-run the last query.
const view = {
  status: null,
  page: 1,
};

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

/**
 * Sends a signed-out student to the login page.
 *
 * replace() rather than href: the dashboard should not stay in the history,
 * or pressing Back from the login page lands them straight back here and
 * bounces them out again.
 */
function redirectToLogin() {
  logout();
  window.location.replace(LOGIN_URL);
}

/**
 * True if the failure means "your session is over".
 *
 * Holding a token is not the same as holding a valid one. The token could
 * have expired since the page loaded, or the backend could have restarted and
 * lost its signing key. Either way the fix is the same: sign in again.
 */
function isSessionExpired(err) {
  return err instanceof ApiError && err.status === 401;
}

// ---------------------------------------------------------------------------
// DOM helpers
// ---------------------------------------------------------------------------

function showBanner(title, message) {
  const slot = document.getElementById('banner-slot');
  if (!slot) return;

  slot.replaceChildren();

  const banner = document.createElement('div');
  banner.className = 'banner banner-error';
  banner.setAttribute('role', 'alert');

  const body = document.createElement('div');

  const heading = document.createElement('span');
  heading.className = 'banner-title';
  heading.textContent = title;

  const text = document.createElement('span');
  text.textContent = message;

  body.appendChild(heading);
  body.appendChild(text);
  banner.appendChild(body);
  slot.appendChild(banner);
}

function clearBanner() {
  const slot = document.getElementById('banner-slot');
  if (slot) slot.replaceChildren();
}

/** Creates an element with a class and, optionally, its text. */
function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

/**
 * Falls back to the raw value rather than showing nothing. If the backend
 * ever sends a status we have not mapped, we would rather see "in_transit" on
 * screen than an empty pill nobody can debug.
 */
function labelFor(map, value) {
  if (typeof value !== 'string' || value === '') return 'Unknown';
  return map[value] ?? value;
}

/** Recent times read better as an age. Older ones read better as a date. */
function relativeTime(iso) {
  if (typeof iso !== 'string') return 'Time unknown';

  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return 'Time unknown';

  const seconds = Math.floor((Date.now() - then.getTime()) / 1000);
  if (seconds < 0) return 'Just now';      // clock skew between phone and server
  if (seconds < 60) return 'Just now';

  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;

  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hr ago`;

  const days = Math.floor(hours / 24);
  if (days < 7) return `${days} ${days === 1 ? 'day' : 'days'} ago`;

  return then.toLocaleDateString(undefined, {
    day: 'numeric', month: 'short', year: 'numeric',
  });
}

/** Full timestamp for the tooltip, so the exact time is still reachable. */
function exactTime(iso) {
  if (typeof iso !== 'string') return '';
  const then = new Date(iso);
  return Number.isNaN(then.getTime()) ? '' : then.toLocaleString();
}

function pillClassFor(status) {
  if (status === 'resolved') return 'pill pill-resolved';
  if (status === 'cancelled') return 'pill pill-cancelled';
  if (ACTIVE_STATUSES.includes(status)) return 'pill pill-active';
  return 'pill';
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

/**
 * Builds one incident card.
 *
 * The list endpoint returns a summary shape: incidentId, type, status,
 * priority, createdAt. No description and no coordinates, so nothing here may
 * reach for them.
 *
 * These cards are not links yet. The incident detail page does not exist, and
 * a card that navigates to a 404 is worse than one that does not move.
 */
function incidentCard(incident) {
  const card = el('article', 'card');

  const priority = incident.priority;
  const isUrgent = priority === 1;
  if (isUrgent) card.classList.add('card-emergency');

  const head = el('div', 'incident-head');
  const titles = el('div');

  titles.appendChild(el('span', 'incident-type', labelFor(TYPE_LABELS, incident.type)));

  const ref = incident.incidentId;
  titles.appendChild(el(
    'p',
    'incident-ref',
    ref === null || ref === undefined ? 'No reference number' : `Reference ${ref}`,
  ));

  head.appendChild(titles);

  // <time> gives the machine-readable value; the visible text stays friendly.
  const when = el('time', 'card-meta', relativeTime(incident.createdAt));
  if (typeof incident.createdAt === 'string') {
    when.setAttribute('datetime', incident.createdAt);
    const exact = exactTime(incident.createdAt);
    if (exact) when.title = exact;
  }
  head.appendChild(when);

  card.appendChild(head);

  const badges = el('div', 'incident-badges');
  badges.appendChild(el('span', pillClassFor(incident.status), labelFor(STATUS_LABELS, incident.status)));

  // Priority is computed server-side. Only render a badge for a value we
  // actually have a colour for, rather than inventing a priority-0 class.
  if (Number.isInteger(priority) && priority >= 1 && priority <= 5) {
    const badge = el('span', `priority priority-${priority}`, `P${priority}`);
    // The badge reads as "P2" on its own, which means nothing out loud.
    badge.setAttribute('aria-label', `Priority ${priority} of 5, 1 is most urgent`);
    badges.appendChild(badge);
  }

  if (incident.status === 'cancelled') {
    badges.appendChild(el('span', 'card-meta', 'Marked as a false alarm. The record is kept.'));
  }

  card.appendChild(badges);

  // A real link rather than a click handler on the whole card: it opens in a
  // new tab on a long press, it is reachable by keyboard, and a screen reader
  // announces it as a link. Only rendered when there is an id to link to,
  // because a card with no reference number has nothing to open.
  if (ref !== null && ref !== undefined) {
    const open = el('a', 'btn btn-ghost', 'View details');
    open.href = `${INCIDENT_URL}?id=${encodeURIComponent(ref)}`;
    // "View details" repeated down a list says nothing about which incident.
    open.setAttribute('aria-label', `View details for incident ${ref}`);
    card.appendChild(open);
  }

  return card;
}

function renderSkeletons(container) {
  container.setAttribute('aria-busy', 'true');
  container.replaceChildren();

  // No role="status" on this. The container is already an aria-live region,
  // and a live region inside a live region gets announced twice.
  container.appendChild(el('p', 'visually-hidden', 'Loading your reports'));

  const list = el('div', 'card-list');
  for (let i = 0; i < 3; i += 1) {
    const card = el('div', 'card');
    card.appendChild(el('div', 'skeleton skeleton-title'));
    card.appendChild(el('div', 'skeleton skeleton-text'));
    card.appendChild(el('div', 'skeleton skeleton-text'));
    list.appendChild(card);
  }
  container.appendChild(list);
}

function renderEmpty(container) {
  container.replaceChildren();

  const empty = el('div', 'empty-state');

  if (view.status === null) {
    empty.appendChild(el('h2', null, 'No reports yet'));
    empty.appendChild(el('p', 'text-meta', 'Anything you report will appear here with its current status.'));

    const link = el('a', 'btn btn-primary', 'Report an incident');
    link.href = REPORT_URL;
    empty.appendChild(link);
  } else {
    const label = labelFor(STATUS_LABELS, view.status).toLowerCase();
    empty.appendChild(el('h2', null, 'Nothing to show'));
    empty.appendChild(el('p', 'text-meta', `You have no reports with the status "${label}".`));

    const reset = el('button', 'btn btn-secondary', 'Show all reports');
    reset.type = 'button';
    reset.addEventListener('click', () => applyFilter(null));
    empty.appendChild(reset);
  }

  container.appendChild(empty);
}

function renderError(container, message) {
  container.replaceChildren();

  const wrap = el('div', 'empty-state');
  wrap.appendChild(el('h2', null, 'Could not load your reports'));
  wrap.appendChild(el('p', 'text-meta', message));

  const retry = el('button', 'btn btn-secondary', 'Try again');
  retry.type = 'button';
  retry.addEventListener('click', loadIncidents);
  wrap.appendChild(retry);

  container.appendChild(wrap);
}

/**
 * Previous/next controls.
 *
 * The contract returns totalItems and pageSize rather than a page count, so
 * we work it out here. Guarded because a pageSize of 0 or a missing field
 * would otherwise give Infinity pages.
 */
function renderPagination(meta) {
  const container = document.getElementById('pagination');
  if (!container) return;

  container.replaceChildren();

  const pageSize = Number.isFinite(meta.pageSize) && meta.pageSize > 0 ? meta.pageSize : 20;
  const totalItems = Number.isFinite(meta.totalItems) && meta.totalItems >= 0 ? meta.totalItems : 0;
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));

  if (totalPages <= 1) return;

  const nav = el('nav', 'pagination');
  nav.setAttribute('aria-label', 'Report pages');

  const previous = el('button', 'btn btn-secondary', 'Previous');
  previous.type = 'button';
  previous.disabled = view.page <= 1;
  previous.addEventListener('click', () => goToPage(view.page - 1));

  const next = el('button', 'btn btn-secondary', 'Next');
  next.type = 'button';
  next.disabled = view.page >= totalPages;
  next.addEventListener('click', () => goToPage(view.page + 1));

  nav.appendChild(previous);
  nav.appendChild(el('span', 'pagination-status', `Page ${view.page} of ${totalPages}`));
  nav.appendChild(next);

  container.appendChild(nav);
}

// Mounted once per page load, not once per loadIncidents() call - loadIncidents
// runs again on every filter/page change, and remounting the Leaflet map each
// time would leak the previous instance (createTrackingMap never gets a
// matching destroy() call from here) and restart its simulation from scratch.
let liveTrackingMounted = false;

/**
 * Shows the live tracking map when the student has an incident a responder
 * is actively en route to or on scene for. Was previously called here with
 * no matching definition anywhere in the file - a ReferenceError that broke
 * the entire incident list (caught by loadIncidents' try/catch) for any
 * student in that state. createTrackingMap already exists in tracking.js and
 * is imported above; this just actually calls it.
 */
function renderLiveTracking(incident) {
  const slot = document.getElementById('live-tracking-slot');
  if (!slot || liveTrackingMounted) return;

  createTrackingMap(slot, { role: 'student', incidentId: incident.incidentId });
  liveTrackingMounted = true;
}

// ---------------------------------------------------------------------------
// Loading
// ---------------------------------------------------------------------------

async function loadIncidents() {
  const container = document.getElementById('incident-list');
  if (!container) return;

  clearBanner();
  renderSkeletons(container);

  try {
    const result = await getIncidents(view.status, view.page);

    // Never assume the shape. A backend mid-build can return 200 with
    // something unexpected, and an empty list is a better failure than a
    // thrown TypeError on an undefined .length.
    const items = Array.isArray(result?.items) ? result.items : [];

    // If an incident has an active responder dispatched (en_route or on_scene), mount live map
    const activeDispatched = items.find((i) => i.status === 'en_route' || i.status === 'on_scene');
    if (activeDispatched) {
      renderLiveTracking(activeDispatched);
    }

    container.setAttribute('aria-busy', 'false');

    if (items.length === 0) {
      renderEmpty(container);
      renderPagination({});
      return;
    }

    container.replaceChildren();
    const list = el('div', 'card-list');
    items.forEach((incident) => list.appendChild(incidentCard(incident)));
    container.appendChild(list);

    renderPagination({
      pageSize: result?.pageSize,
      totalItems: result?.totalItems,
    });
  } catch (err) {
    container.setAttribute('aria-busy', 'false');

    if (isSessionExpired(err)) {
      redirectToLogin();
      return;
    }

    if (err instanceof ApiError) {
      showBanner('Could not load your reports', err.message);
      renderError(container, err.message);
      return;
    }

    console.error('Unexpected error loading incidents:', err);
    showBanner('Could not load your reports', 'Something went wrong on this page. Please try again.');
    renderError(container, 'Something went wrong on this page.');
  }
}

/**
 * Greets the student by name.
 *
 * Deliberately not awaited before the list loads. The name is decoration; the
 * list is the page. Holding the incidents back for it would add a whole round
 * trip to a screen someone may have opened in a hurry.
 */
async function loadGreeting() {
  try {
    const user = await getCurrentUser();
    const name = typeof user?.fullName === 'string' ? user.fullName.trim() : '';
    if (!name) return;

    // First name only. "Your reports, Mpilwenhle Jubane" reads like a letter
    // from the registrar.
    const heading = document.getElementById('greeting');
    if (heading) heading.textContent = `Your reports, ${name.split(' ')[0]}`;
  } catch (err) {
    if (isSessionExpired(err)) {
      redirectToLogin();
      return;
    }
    // Any other failure is not worth an error banner. The heading already
    // says "Your reports" and the list carries its own errors.
    console.error('Could not load the current user:', err);
  }
}

// ---------------------------------------------------------------------------
// Filters and paging
// ---------------------------------------------------------------------------

function applyFilter(status) {
  view.status = status;
  view.page = 1;   // page 3 of "all" is rarely page 3 of a filter
  syncFilterButtons();
  loadIncidents();
}

function goToPage(page) {
  if (page < 1) return;
  view.page = page;
  loadIncidents();

  // On a phone the buttons are below the fold of the new list, so without
  // this the page looks unchanged after a tap. Honour the same reduced-motion
  // setting the stylesheet checks: jump instead of gliding.
  const stillMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  window.scrollTo({ top: 0, behavior: stillMotion ? 'auto' : 'smooth' });
}

function syncFilterButtons() {
  document.querySelectorAll('#status-filter .chip').forEach((button) => {
    // dataset values are strings, so the "All" chip stores an empty one.
    const value = button.dataset.status === '' ? null : button.dataset.status;
    button.setAttribute('aria-pressed', String(value === view.status));
  });
}

function initFilters() {
  const container = document.getElementById('status-filter');
  if (!container) return;

  STATUS_FILTERS.forEach(({ value, label }) => {
    const button = el('button', 'chip', label);
    button.type = 'button';
    button.dataset.status = value ?? '';
    button.setAttribute('aria-pressed', String(value === view.status));
    button.addEventListener('click', () => applyFilter(value));
    container.appendChild(button);
  });
}

// ---------------------------------------------------------------------------
// SOS
// ---------------------------------------------------------------------------

// Same high-accuracy request report.js uses, on a shorter leash. An
// emergency report should never wait 15 seconds on a GPS fix that report.js
// can afford to wait for; if a fix isn't in by the time the student confirms,
// send without one rather than hold the SOS back.
const SOS_GEO_OPTIONS = { enableHighAccuracy: true, timeout: 8000, maximumAge: 30000 };
const SOS_CONFIRM_WINDOW_MS = 5000;

function capturePosition() {
  return new Promise((resolve) => {
    if (!navigator.geolocation) {
      resolve(null);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({
        latitude: pos.coords.latitude,
        longitude: pos.coords.longitude,
        accuracy: pos.coords.accuracy ?? null,
      }),
      () => resolve(null), // denied, unavailable, or timed out - all the same to an SOS: send anyway
      SOS_GEO_OPTIONS,
    );
  });
}

// Shared by the SOS button and the medical alert button below - same
// two-tap-confirm, GPS-in-parallel, send-regardless-of-fix behaviour either
// way, they only differ in incident type and what goes in the description.
function initEmergencyButton({ buttonId, labelId, hintId, idleLabel, confirmLabel, sendingLabel, incidentType, buildDescription }) {
  const button = document.getElementById(buttonId);
  const label = document.getElementById(labelId);
  const hint = document.getElementById(hintId);
  if (!button || !label || !hint) return;

  const idleHint = hint.textContent;
  let armed = false;
  let disarmTimer = null;
  let positionPromise = null;

  function disarm() {
    armed = false;
    clearTimeout(disarmTimer);
    button.classList.remove('sos-button-armed');
    label.textContent = idleLabel;
    hint.textContent = idleHint;
  }

  function arm() {
    armed = true;
    // Start the GPS fix now, in parallel with the confirm countdown, so it
    // has a head start by the time (if) the student taps again.
    positionPromise = capturePosition();
    label.textContent = confirmLabel;
    hint.textContent = `Sending in ${SOS_CONFIRM_WINDOW_MS / 1000}s if you don't tap again. Tap anywhere else to cancel.`;
    button.classList.add('sos-button-armed');
    disarmTimer = setTimeout(disarm, SOS_CONFIRM_WINDOW_MS);
  }

  async function send() {
    clearTimeout(disarmTimer);
    button.disabled = true;
    label.textContent = sendingLabel;
    hint.textContent = 'Do not close this page.';

    // Give a fix already in flight a little more time, but this must not
    // hang indefinitely on GPS - three more seconds, then send regardless.
    const position = await Promise.race([
      positionPromise,
      new Promise((resolve) => setTimeout(() => resolve(null), 3000)),
    ]);

    try {
      const incident = await submitIncident({
        type: incidentType,
        description: buildDescription(),
        anonymous: false,
        latitude: position?.latitude ?? null,
        longitude: position?.longitude ?? null,
        accuracy: position?.accuracy ?? null,
      });
      window.location.href = `${INCIDENT_URL}?id=${incident.incidentId}`;
    } catch (err) {
      disarm();
      button.disabled = false;
      if (isSessionExpired(err)) {
        redirectToLogin();
        return;
      }
      showBanner('Could not send your alert',
        err instanceof ApiError ? err.message : 'Something went wrong. Please try again, or call 112 directly.');
    }
  }

  button.addEventListener('click', () => {
    if (armed) {
      send();
    } else {
      arm();
    }
  });

  // Tapping anywhere else cancels an armed alert rather than leaving it
  // primed to fire on whatever gets tapped next.
  document.addEventListener('click', (event) => {
    if (armed && event.target !== button && !button.contains(event.target)) {
      disarm();
    }
  });
}

function initSosButton() {
  initEmergencyButton({
    buttonId: 'sos-button',
    labelId: 'sos-button-label',
    hintId: 'sos-hint',
    idleLabel: 'SOS — Tap for emergency help',
    confirmLabel: 'Tap again to confirm SOS',
    sendingLabel: 'Sending SOS…',
    incidentType: 'sos',
    buildDescription: () => null,
  });
}

const CONDITION_LABELS = {
  asthma: 'Asthma',
  diabetes: 'Diabetes',
  epilepsy: 'Epilepsy',
  severe_allergy: 'Severe allergy (anaphylaxis risk)',
  heart_condition: 'Heart condition',
};

/**
 * Shows the medical alert button only for a student who has declared at
 * least one condition or a note (see health-profile.html) - someone with
 * nothing declared has nothing for the alert to usefully attach, and
 * showing an empty-condition "medical alert" next to a real SOS button
 * would just be a second, confusing way to do the same thing.
 */
async function initMedicalAlertButton() {
  const section = document.getElementById('medical-alert-section');
  if (!section) return;

  let profile;
  try {
    profile = await getHealthProfile();
  } catch (err) {
    if (isSessionExpired(err)) redirectToLogin();
    return; // any other failure: just don't show it, the SOS button still covers an emergency
  }

  const conditions = Array.isArray(profile?.conditions) ? profile.conditions : [];
  const note = profile?.note ?? null;
  if (conditions.length === 0 && !note) return;

  section.hidden = false;

  initEmergencyButton({
    buttonId: 'medical-alert-button',
    labelId: 'medical-alert-button-label',
    hintId: 'medical-alert-hint',
    idleLabel: 'Medical alert — Tap for help',
    confirmLabel: 'Tap again to confirm medical alert',
    sendingLabel: 'Sending medical alert…',
    incidentType: 'medical',
    buildDescription: () => {
      const parts = [];
      if (conditions.length > 0) {
        parts.push(`Known conditions: ${conditions.map((c) => CONDITION_LABELS[c] ?? c).join(', ')}.`);
      }
      if (note) parts.push(note);
      return parts.length > 0 ? parts.join(' ') : null;
    },
  });
}

function initSignOut() {
  const button = document.getElementById('signout-button');
  if (!button) return;

  button.addEventListener('click', () => {
    logout();
    window.location.replace(LOGIN_URL);
  });
}

// ---------------------------------------------------------------------------
// Start up
// ---------------------------------------------------------------------------

// Guard before anything renders. This only checks that a token exists, which
// is not proof it is valid - the getCurrentUser and getIncidents calls below
// are what actually test it with the server. The point of checking here is to
// avoid drawing a dashboard for someone who was never signed in.
if (!isLoggedIn()) {
  redirectToLogin();
} else {
  initSignOut();
  initSosButton();
  initMedicalAlertButton();
  initFilters();
  loadGreeting();
  loadIncidents();
}
