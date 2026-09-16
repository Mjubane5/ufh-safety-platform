/**
 * One incident, in full. Reads the id from ?id= in the query string.
 */

import { getIncident, cancelIncident, isLoggedIn, logout, getCurrentUser, ApiError } from './api.js';

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
// Wiring
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

// Adjust back link target based on viewer role
const backLink = document.getElementById('incident-back-link');
if (backLink) {
  getCurrentUser().then((user) => {
    if (user?.role === 'responder') {
      backLink.href = './responder-dashboard.html';
      backLink.textContent = '← Back to assignments';
    } else if (user?.role === 'campus_control') {
      backLink.href = './control-dashboard.html';
      backLink.textContent = '← Back to dispatch queue';
    }
  }).catch(() => {});
}

/**
 * The incident id from ?id= in the address bar, or null when it is missing or
 * not a number.
 */
export function incidentIdFromUrl(search = window.location.search) {
  const raw = new URLSearchParams(search).get('id');
  if (raw === null || raw.trim() === '') return null;

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
  const back = el('a', 'btn btn-secondary', 'Back to reports');
  back.href = DASHBOARD_URL;
  getCurrentUser().then((user) => {
    if (user?.role === 'responder') {
      back.href = './responder-dashboard.html';
      back.textContent = 'Back to assignments';
    } else if (user?.role === 'campus_control') {
      back.href = './control-dashboard.html';
      back.textContent = 'Back to dispatch queue';
    }
  }).catch(() => {});
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
      return;\n    }
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
