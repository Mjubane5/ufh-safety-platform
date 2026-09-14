import {
  getIncident,
  getIncidents,
  getCurrentUser,
  isLoggedIn,
  logout,
  updateIncidentStatus,
  ApiError,
} from './api.js';

const LOGIN_URL = './login.html';
const TYPE_LABELS = {
  sos: 'SOS', medical: 'Medical emergency', fire: 'Fire', theft: 'Theft',
  assault: 'Assault', accident: 'Accident', suspicious: 'Suspicious behaviour',
  unsafe: 'Unsafe condition', other: 'Other',
};
const STATUS_LABELS = {
  reported: 'Reported', triaged: 'Triaged', assigned: 'Assigned',
  en_route: 'En route', on_scene: 'On scene', resolved: 'Resolved', cancelled: 'Cancelled',
};
const NEXT_STATUS = {
  assigned: 'en_route',
  en_route: 'on_scene',
  on_scene: 'resolved',
};

const list = document.getElementById('assignment-list');
const selected = document.getElementById('selected-incident');
const message = document.getElementById('responder-message');
const signOutButton = document.getElementById('signout-button');
let assignments = [];

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function labelFor(map, value) {
  return typeof value === 'string' ? (map[value] ?? value) : 'Unknown';
}

function showBanner(title, text) {
  const slot = document.getElementById('banner-slot');
  if (!slot) return;
  const banner = el('div', 'banner banner-error');
  banner.setAttribute('role', 'alert');
  const body = el('div');
  body.appendChild(el('span', 'banner-title', title));
  body.appendChild(el('span', null, text));
  banner.appendChild(body);
  slot.replaceChildren(banner);
}

function redirectToLogin() {
  logout();
  window.location.replace(LOGIN_URL);
}

function renderList() {
  list.setAttribute('aria-busy', 'false');
  list.replaceChildren();
  if (assignments.length === 0) {
    list.appendChild(el('p', 'empty-state', 'No incidents are assigned to you.'));
    return;
  }

  const cards = el('div', 'card-list');
  assignments.forEach((incident) => {
    const card = el('button', 'card card-clickable');
    card.type = 'button';
    card.setAttribute('aria-label', `Open ${labelFor(TYPE_LABELS, incident.type)} incident ${incident.incidentId}`);
    card.appendChild(el('span', 'incident-type', labelFor(TYPE_LABELS, incident.type)));
    card.appendChild(el('span', 'incident-ref', `Reference ${incident.incidentId}`));
    const badges = el('span', 'incident-badges');
    badges.appendChild(el('span', 'pill', labelFor(STATUS_LABELS, incident.status)));
    if (incident.priority >= 1 && incident.priority <= 5) {
      badges.appendChild(el('span', `priority priority-${incident.priority}`, `P${incident.priority}`));
    }
    card.appendChild(badges);
    card.addEventListener('click', () => loadSelected(incident.incidentId));
    cards.appendChild(card);
  });
  list.appendChild(cards);
}

function renderSelected(incident) {
  selected.setAttribute('aria-busy', 'false');
  selected.replaceChildren();
  const card = el('article', 'card');
  card.appendChild(el('h3', null, `${labelFor(TYPE_LABELS, incident.type)} · Reference ${incident.incidentId}`));
  card.appendChild(el('p', 'detail-description', incident.description || 'No description was provided.'));

  const details = el('dl', 'detail-list');
  const addRow = (label, value) => {
    const row = el('div', 'detail-row');
    row.appendChild(el('dt', null, label));
    row.appendChild(el('dd', null, value));
    details.appendChild(row);
  };
  addRow('Status', labelFor(STATUS_LABELS, incident.status));
  addRow('Priority', Number.isInteger(incident.priority) ? `P${incident.priority}` : 'Unknown');
  addRow('Location', incident.locationSource === 'device' ? `${incident.latitude}, ${incident.longitude}` : 'No location shared');
  card.appendChild(details);

  const nextStatus = NEXT_STATUS[incident.status];
  if (nextStatus) {
    const button = el('button', 'btn btn-primary', `Mark ${labelFor(STATUS_LABELS, nextStatus).toLowerCase()}`);
    button.type = 'button';
    button.addEventListener('click', () => advanceIncident(incident, nextStatus, button));
    card.appendChild(button);
  } else {
    card.appendChild(el('p', 'text-meta', 'No further responder action is available.'));
  }
  selected.appendChild(card);
}

async function loadSelected(incidentId) {
  selected.setAttribute('aria-busy', 'true');
  selected.replaceChildren(el('p', 'empty-state', 'Loading incident details...'));
  try {
    renderSelected(await getIncident(incidentId));
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    selected.setAttribute('aria-busy', 'false');
    selected.replaceChildren(el('p', 'empty-state', error instanceof ApiError ? error.message : 'Could not load this incident.'));
  }
}

async function advanceIncident(incident, nextStatus, button) {
  button.disabled = true;
  try {
    const updated = await updateIncidentStatus(incident.incidentId, nextStatus, null);
    assignments = assignments.map((item) => item.incidentId === updated.incidentId
      ? { ...item, status: updated.status }
      : item);
    renderList();
    renderSelected(updated);
  } catch (error) {
    button.disabled = false;
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner('Could not update incident', error instanceof ApiError ? error.message : 'Please try again.');
  }
}

async function loadWorkspace() {
  if (!isLoggedIn()) {
    redirectToLogin();
    return;
  }

  try {
    const [user, result] = await Promise.all([getCurrentUser(), getIncidents()]);
    const name = typeof user?.fullName === 'string' ? user.fullName.split(' ')[0] : '';
    message.textContent = name ? `Signed in as ${name}.` : 'Your assigned incidents, most recent first.';
    assignments = Array.isArray(result?.items) ? result.items : [];
    renderList();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    message.textContent = 'Assignments could not be loaded.';
    list.setAttribute('aria-busy', 'false');
    list.replaceChildren(el('p', 'empty-state', error instanceof ApiError ? error.message : 'Please refresh and try again.'));
  }
}

signOutButton.addEventListener('click', redirectToLogin);
loadWorkspace();