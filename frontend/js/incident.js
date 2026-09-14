/**
 * One incident, in full. Reads the id from ?id= in the query string.
 *
 * SCAFFOLD - the rendering is not written yet. Owner: Awethu.
 *
 * ---------------------------------------------------------------------------
 * What this page is for
 * ---------------------------------------------------------------------------
 *
 * dashboard.js lists incidents in the summary shape - no description, no
 * coordinates. This page fetches the detail shape for one of them, and is the
 * only place a student can cancel a false alarm.
 *
 * It uses three functions that PR #20 added to api.js and that nothing has
 * called until now:
 *
 *   getIncident(incidentId)            the detail shape
 *   cancelIncident(incidentId, reason) the false-alarm path
 *   updateIncidentStatus(...)          responder and campus control only,
 *                                      so NOT used on this student-facing page
 *
 * ---------------------------------------------------------------------------
 * The rules that matter here
 * ---------------------------------------------------------------------------
 *
 * 1. Every field can be null. The contract says so and the backend enforces
 *    it. Specifically:
 *
 *      reporter           null when the report was anonymous
 *      assignedResponder  null until somebody is assigned
 *      description        null when the student did not type one
 *      latitude/longitude/accuracy   null when there was no GPS fix
 *
 *    Never write incident.reporter.fullName without checking reporter first.
 *    dashboard.js already does this properly - copy its habits.
 *
 * 2. A loading state on every request and a readable error when one fails.
 *    Not a spinner that hides what went wrong.
 *
 * 3. Mobile first. A student reads this on a phone, probably in a hurry.
 *
 * 4. Cancelling is destructive from the student's point of view, so confirm
 *    before calling the API. It is not destructive in the database - the row
 *    is kept and the status set to cancelled - but the student does not know
 *    that, and an accidental cancel on a real emergency is the worst bug this
 *    page could have.
 *
 * ---------------------------------------------------------------------------
 * When cancel should be offered
 * ---------------------------------------------------------------------------
 *
 * The backend allows cancel from any live status and refuses it once the
 * incident is resolved or cancelled. Mirror that here so the button is not
 * offered when the server is going to refuse it:
 *
 *   reported, triaged, assigned, en_route, on_scene  -> show the button
 *   resolved, cancelled                              -> hide it
 *
 * Mirror, do not trust. The server check is the real one; this is only so the
 * student is not shown a control that cannot work.
 *
 * ---------------------------------------------------------------------------
 * Errors worth handling by name
 * ---------------------------------------------------------------------------
 *
 *   401  session expired      -> redirect to login, same as dashboard.js
 *   403  not your incident    -> readable message, not a raw code
 *   404  no such incident     -> the backend also returns this when the
 *                                incident exists but belongs to somebody
 *                                else, so word the message carefully:
 *                                "We could not find that report" is honest,
 *                                "it was deleted" is not
 *   409  already resolved     -> the cancel was refused; re-fetch and
 *                                re-render so the page shows the real state
 *
 * ---------------------------------------------------------------------------
 * A decision to make before writing much
 * ---------------------------------------------------------------------------
 *
 * dashboard.js has el(), labelFor(), relativeTime(), exactTime(),
 * pillClassFor() and the STATUS_LABELS / TYPE_LABELS maps. This page needs
 * most of them.
 *
 * Copying them means two copies drifting apart - the day somebody adds a
 * status, one page renders it and the other shows a blank pill.
 *
 * The alternative is pulling them into a shared module, say js/ui.js, and
 * importing from both. That is the better answer but it edits dashboard.js,
 * so decide it deliberately rather than by accident. Your call.
 *
 * ---------------------------------------------------------------------------
 * Suggested order
 * ---------------------------------------------------------------------------
 *
 *   1. Read the id from the query string, reject a missing or non-numeric one
 *      before making any request
 *   2. Loading state
 *   3. Happy path render with every field present
 *   4. Null cases - anonymous reporter, no responder, no coordinates
 *   5. Error states, 401 / 403 / 404
 *   6. Cancel, with confirmation
 *   7. 409 on cancel
 *
 * Each of those is a commit.
 */

import { getIncident, cancelIncident, isLoggedIn, logout, ApiError } from './api.js';

const LOGIN_URL = './login.html';
const DASHBOARD_URL = './dashboard.html';

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

const CANCELLABLE_STATUSES = ['reported', 'triaged', 'assigned', 'en_route', 'on_scene'];

// ---------------------------------------------------------------------------
// Wiring that is already done
// ---------------------------------------------------------------------------

const detail = document.getElementById('incident-detail');
const heading = document.getElementById('incident-heading');
const bannerSlot = document.getElementById('banner-slot');
const cancelSlot = document.getElementById('cancel-slot');
const signOutButton = document.getElementById('signout-button');

if (signOutButton) {
  signOutButton.addEventListener('click', () => {
    logout();
    window.location.href = './login.html';
  });
}

/**
 * The incident id from ?id= in the address bar, or null when it is missing or
 * not a number.
 *
 * Returns null rather than NaN so the caller has one thing to check. A NaN id
 * would sail into the URL and produce a confusing 404 from the server instead
 * of a clear message here.
 */
export function incidentIdFromUrl(search = window.location.search) {
  const raw = new URLSearchParams(search).get('id');
  if (raw === null || raw.trim() === '') return null;

  // Number() rather than parseInt(): parseInt('42abc') is 42, which would
  // accept a malformed url as if it were fine.
  const id = Number(raw);
  return Number.isInteger(id) && id > 0 ? id : null;
}

// ---------------------------------------------------------------------------
// Rendering helpers
// ---------------------------------------------------------------------------

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function labelFor(map, value) {
  if (typeof value !== 'string' || value === '') return 'Unknown';
  return map[value] ?? value;
}

function formatTime(value) {
  if (typeof value !== 'string') return 'Time unavailable';
  const time = new Date(value);
  return Number.isNaN(time.getTime()) ? 'Time unavailable' : time.toLocaleString();
}

function showBanner(title, message) {
  if (!bannerSlot) return;
  const banner = el('div', 'banner banner-error');
  banner.setAttribute('role', 'alert');
  const body = el('div');
  body.appendChild(el('span', 'banner-title', title));
  body.appendChild(el('span', null, message));
  banner.appendChild(body);
  bannerSlot.replaceChildren(banner);
}

function clearBanner() {
  if (bannerSlot) bannerSlot.replaceChildren();
}

function renderLoading() {
  detail.setAttribute('aria-busy', 'true');
  detail.replaceChildren(el('p', 'visually-hidden', 'Loading incident details'));
  const card = el('div', 'card');
  card.appendChild(el('div', 'skeleton skeleton-title'));
  card.appendChild(el('div', 'skeleton skeleton-text'));
  card.appendChild(el('div', 'skeleton skeleton-text'));
  detail.appendChild(card);
}

function addDetailRow(container, label, value) {
  const row = el('div', 'detail-row');
  row.appendChild(el('dt', null, label));
  row.appendChild(el('dd', null, value));
  container.appendChild(row);
}

function renderIncident(incident) {
  detail.setAttribute('aria-busy', 'false');
  detail.replaceChildren();

  const type = labelFor(TYPE_LABELS, incident?.type);
  const status = labelFor(STATUS_LABELS, incident?.status);
  const reference = incident?.incidentId ?? 'Unknown';
  heading.textContent = `${type} report`;

  const card = el('article', 'card');
  const badges = el('div', 'incident-badges');
  badges.appendChild(el('span', 'pill', status));
  if (Number.isInteger(incident?.priority) && incident.priority >= 1 && incident.priority <= 5) {
    badges.appendChild(el('span', `priority priority-${incident.priority}`, `P${incident.priority}`));
  }
  card.appendChild(badges);

  const title = el('h2', null, `Reference ${reference}`);
  card.appendChild(title);

  const description = incident?.description;
  card.appendChild(el('p', 'detail-description', description || 'No description was provided.'));

  const details = el('dl', 'detail-list');
  addDetailRow(details, 'Reported', formatTime(incident?.createdAt));
  addDetailRow(details, 'Last updated', formatTime(incident?.updatedAt));
  addDetailRow(details, 'Location', incident?.locationSource === 'device' ? 'Device location shared' : 'No location shared');
  if (incident?.latitude !== null && incident?.latitude !== undefined
      && incident?.longitude !== null && incident?.longitude !== undefined) {
    addDetailRow(details, 'Coordinates', `${incident.latitude}, ${incident.longitude}`);
  }
  addDetailRow(details, 'Reporter', incident?.reporter?.fullName ?? (incident?.anonymous ? 'Anonymous report' : 'Not available'));
  addDetailRow(details, 'Responder', incident?.assignedResponder?.fullName ?? 'Not assigned');
  card.appendChild(details);
  detail.appendChild(card);

  renderCancelControl(incident);
}

function renderCancelControl(incident) {
  cancelSlot.replaceChildren();
  cancelSlot.hidden = !CANCELLABLE_STATUSES.includes(incident?.status);
  if (cancelSlot.hidden) return;

  const button = el('button', 'btn btn-secondary', 'Cancel this report');
  button.type = 'button';
  button.addEventListener('click', async () => {
    if (!window.confirm('Cancel this report because it was submitted by mistake?')) return;

    button.disabled = true;
    clearBanner();
    try {
      const updated = await cancelIncident(incident.incidentId, null);
      renderIncident(updated);
    } catch (error) {
      button.disabled = false;
      if (error instanceof ApiError && error.status === 401) {
        logout();
        window.location.replace(LOGIN_URL);
        return;
      }
      if (error instanceof ApiError && error.status === 409) {
        await load();
        return;
      }
      showBanner('Could not cancel report', error instanceof ApiError ? error.message : 'Something went wrong.');
    }
  });

  cancelSlot.appendChild(button);
}

function renderFailure(title, message) {
  detail.setAttribute('aria-busy', 'false');
  detail.replaceChildren();
  const empty = el('div', 'empty-state');
  empty.appendChild(el('h2', null, title));
  empty.appendChild(el('p', 'text-meta', message));
  const back = el('a', 'btn btn-secondary', 'Back to your reports');
  back.href = DASHBOARD_URL;
  empty.appendChild(back);
  detail.appendChild(empty);
}

async function load() {
  clearBanner();
  renderLoading();

  if (!isLoggedIn()) {
    window.location.replace(LOGIN_URL);
    return;
  }

  const incidentId = incidentIdFromUrl();
  if (incidentId === null) {
    renderFailure('Report not selected', 'We could not find that report.');
    return;
  }

  try {
    const incident = await getIncident(incidentId);
    if (!incident || typeof incident !== 'object') {
      renderFailure('Could not load report', 'The report data was not available.');
      return;
    }
    renderIncident(incident);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      logout();
      window.location.replace(LOGIN_URL);
      return;
    }

    if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
      renderFailure('Report not found', 'We could not find that report.');
      return;
    }

    renderFailure('Could not load report', error instanceof ApiError ? error.message : 'Something went wrong on this page.');
  }
}

load();
