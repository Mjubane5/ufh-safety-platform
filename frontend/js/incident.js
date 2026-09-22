/**
 * One incident, in full. Reads the id from ?id= in the query string.
 */

import { getIncident, cancelIncident, isLoggedIn, logout, getCurrentUser, getIncidentSignals, appendIncidentSignal, ApiError } from './api.js';
import { createTrackingMap, startLiveLocationWatch, stopLiveLocationWatch } from './tracking.js';
import { startDistressMonitoring, stopDistressMonitoring, isDistressMonitoring } from './distress-detection.js';

// Shared by students, responders and campus_control (an incident is
// reachable from any of their dashboards) - a single hardcoded login page
// sent staff to the student-only two-step form, which cannot sign a staff
// account back in at all. redirectToLogin() below picks the right one from
// logout()'s own return value instead (the role it read just before
// clearing storage) - same fix, same pattern, as map.js.
const LOGIN_URL = './login.html';
const STAFF_LOGIN_URL = './staff-login.html';
const DASHBOARD_URL = './dashboard.html';

function redirectToLogin() {
  const lastRole = logout();
  window.location.replace(lastRole && lastRole !== 'student' ? STAFF_LOGIN_URL : LOGIN_URL);
}

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

const DISTRESS_POLL_MS = 5000; // matches the interval used everywhere else in this app
const SIGNAL_LABELS = {
  transcript: 'Heard',
  sound: 'Sound detected',
  facial: 'Facial signal',
};

// ---------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------

const detail = document.getElementById('incident-detail');
const heading = document.getElementById('incident-heading');
const bannerSlot = document.getElementById('banner-slot');
const cancelSlot = document.getElementById('cancel-slot');
const signOutButton = document.getElementById('signout-button');

if (signOutButton) {
  signOutButton.addEventListener('click', redirectToLogin);
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


let trackingController = null;
let currentUser = null;
let distressPollTimer = null;
let renderedSignalCount = 0;

// ---------------------------------------------------------------------------
// Live listening (distress-detection.js) - reporter-only controls, plus a
// read-only log of whatever it has sent, visible to anyone who can see this
// incident (reporter, assigned responder, campus control/admin).
// ---------------------------------------------------------------------------

function formatSignalEntry(item) {
  if (item.type === 'transcript') return `"${item.text}"`;
  const confidence = typeof item.confidence === 'number' ? ` (${Math.round(item.confidence * 100)}%)` : '';
  return `${item.label}${confidence}`;
}

function renderDistressLog(items) {
  const log = document.getElementById('distress-log');
  if (!log) return;
  log.replaceChildren();
  if (items.length === 0) {
    log.appendChild(el('p', 'empty-state', 'No live signals yet.'));
    return;
  }
  items.forEach((item) => {
    const bubble = el('div', 'chat-bubble chat-bubble-theirs');
    const text = el('span', null, formatSignalEntry(item));
    bubble.appendChild(text);
    const meta = el('span', 'chat-bubble-meta', `${SIGNAL_LABELS[item.type] ?? item.type} · ${formatTime(item.createdAt)}`);
    bubble.appendChild(meta);
    log.appendChild(bubble);
  });
  log.scrollTop = log.scrollHeight;
}

async function pollDistressLog(incidentId) {
  try {
    const result = await getIncidentSignals(incidentId);
    const items = Array.isArray(result?.items) ? result.items : [];
    document.getElementById('distress-log-section').hidden = items.length === 0 && !isDistressMonitoring();
    if (items.length !== renderedSignalCount) {
      renderedSignalCount = items.length;
      renderDistressLog(items);
    }
  } catch {
    // Leave the existing log showing, try again next tick.
  }
}

function startDistressPolling(incidentId) {
  stopDistressPolling();
  pollDistressLog(incidentId);
  distressPollTimer = setInterval(() => pollDistressLog(incidentId), DISTRESS_POLL_MS);
}

function stopDistressPolling() {
  if (distressPollTimer) {
    clearInterval(distressPollTimer);
    distressPollTimer = null;
  }
}

function initDistressControls(incident, user) {
  const controls = document.getElementById('distress-monitor-controls');
  if (!controls) return;

  const isReporter = user?.role === 'student' && incident?.reporter?.userId === user?.userId;
  const isActive = CANCELLABLE_STATUSES.includes(incident?.status);
  controls.hidden = !(isReporter && isActive);
  if (controls.hidden) return;

  const startButton = document.getElementById('distress-monitor-start');
  const stopButton = document.getElementById('distress-monitor-stop');
  const preview = document.getElementById('distress-monitor-preview');
  const status = document.getElementById('distress-monitor-status');

  async function sendSignal(type, payload) {
    try {
      await appendIncidentSignal(incident.incidentId, { type, ...payload });
      await pollDistressLog(incident.incidentId);
    } catch {
      // A dropped signal is not worth interrupting monitoring for - the next
      // one will go through, or the student can see the miss and speak up.
    }
  }

  async function begin() {
    startButton.disabled = true;
    startButton.hidden = true;
    status.textContent = 'Starting…';
    const result = await startDistressMonitoring({
      withCamera: true,
      videoEl: preview,
      onTranscript: (text) => sendSignal('transcript', { text }),
      onSoundEvent: ({ label, confidence }) => sendSignal('sound', { label, confidence }),
      onFacialSignal: ({ label, confidence }) => sendSignal('facial', { label, confidence }),
      onStatus: (message) => { status.textContent = message; },
    });

    if (!result.ok) {
      // Mic permission was denied or unavailable - leave a way to retry
      // (e.g. after the student grants it in the browser's site settings)
      // rather than silently giving up for the rest of the page's life.
      startButton.hidden = false;
      startButton.disabled = false;
      return;
    }
    preview.hidden = !result.camera;
    startButton.hidden = true;
    stopButton.hidden = false;
    document.getElementById('distress-log-section').hidden = false;
    startLiveLocationWatch(incident.incidentId);
  }

  stopButton.addEventListener('click', () => {
    stopDistressMonitoring();
    stopLiveLocationWatch();
    preview.hidden = true;
    startButton.hidden = false;
    startButton.disabled = false;
    stopButton.hidden = true;
    status.textContent = 'Stopped.';
  });
  startButton.addEventListener('click', begin);

  // Releasing the mic/camera on navigation is not optional - leaving them
  // held open after the student has moved off this page would be exactly
  // the silent, always-on listening this feature is deliberately not.
  window.addEventListener('beforeunload', () => {
    if (isDistressMonitoring()) stopDistressMonitoring();
    stopLiveLocationWatch();
  });

  // Starts as soon as this incident's page loads for its reporter - the
  // browser's own microphone/camera permission prompts are still the real
  // consent gate, this just skips the extra tap before reaching them.
  begin();
}

function renderIncidentTracking(incident) {
  const slot = document.getElementById('incident-live-tracking');
  if (!slot) return;
  if (!['en_route', 'on_scene', 'assigned'].includes(incident?.status)) {
    if (trackingController) {
      trackingController.destroy();
      trackingController = null;
    }
    slot.replaceChildren();
    return;
  }
  if (!trackingController) {
    getCurrentUser().then((user) => {
      const userRole = user?.role === 'campus_control' ? 'campus_control' : (user?.role === 'responder' ? 'responder' : 'student');
      trackingController = createTrackingMap(slot, {
        role: userRole,
        incidentId: incident.incidentId,
        showControls: true,
      });
    }).catch(() => {
      trackingController = createTrackingMap(slot, {
        role: 'student',
        incidentId: incident.incidentId,
        showControls: true,
      });
    });
  }
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
  renderIncidentTracking(incident);
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
      if (isDistressMonitoring()) stopDistressMonitoring();
      renderIncident(updated);
      initDistressControls(updated, currentUser);
    } catch (error) {
      button.disabled = false;
      if (error instanceof ApiError && error.status === 401) {
        redirectToLogin();
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
    redirectToLogin();
    return;
  }

  const incidentId = incidentIdFromUrl();
  if (incidentId === null) {
    renderFailure('Report not selected', 'We could not find that report.');
    return;
  }

  try {
    const [incident, user] = await Promise.all([
      getIncident(incidentId),
      getCurrentUser().catch(() => null),
    ]);
    if (!incident || typeof incident !== 'object') {
      renderFailure('Could not load report', 'The report data was not available.');
      return;
    }
    currentUser = user;
    renderIncident(incident);
    initDistressControls(incident, user);
    startDistressPolling(incident.incidentId);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
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
