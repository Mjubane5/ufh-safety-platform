import {
  assignIncident,
  getAvailableResponders,
  getIncidents,
  isLoggedIn,
  logout,
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

const incidentContainer = document.getElementById('control-incidents');
const responderContainer = document.getElementById('responder-roster');
const message = document.getElementById('control-message');
let incidents = [];
let responders = [];

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

function renderResponders() {
  responderContainer.setAttribute('aria-busy', 'false');
  responderContainer.replaceChildren();
  if (responders.length === 0) {
    responderContainer.appendChild(el('p', 'empty-state', 'No responders are currently available.'));
    return;
  }

  const list = el('div', 'card-list');
  responders.forEach((responder) => {
    const card = el('article', 'card');
    card.appendChild(el('h3', null, responder.fullName ?? 'Unnamed responder'));
    card.appendChild(el('p', 'text-meta', responder.team ?? 'Team unavailable'));
    const position = responder.latitude !== null && responder.latitude !== undefined
      && responder.longitude !== null && responder.longitude !== undefined
      ? `Position: ${responder.latitude}, ${responder.longitude}`
      : 'Position unavailable';
    card.appendChild(el('p', 'text-meta', position));
    list.appendChild(card);
  });
  responderContainer.appendChild(list);
}

function renderIncidents() {
  incidentContainer.setAttribute('aria-busy', 'false');
  incidentContainer.replaceChildren();
  if (incidents.length === 0) {
    incidentContainer.appendChild(el('p', 'empty-state', 'No incidents are in the queue.'));
    return;
  }

  const list = el('div', 'card-list');
  incidents.forEach((incident) => {
    const card = el('article', 'card');
    card.appendChild(el('h3', null, `${labelFor(TYPE_LABELS, incident.type)} · Reference ${incident.incidentId}`));
    const badges = el('div', 'incident-badges');
    badges.appendChild(el('span', 'pill', labelFor(STATUS_LABELS, incident.status)));
    if (incident.priority >= 1 && incident.priority <= 5) {
      badges.appendChild(el('span', `priority priority-${incident.priority}`, `P${incident.priority}`));
    }
    card.appendChild(badges);

    if (incident.status === 'reported' || incident.status === 'triaged') {
      const select = document.createElement('select');
      select.className = 'input';
      select.setAttribute('aria-label', `Responder for incident ${incident.incidentId}`);
      const emptyOption = el('option', null, 'Choose a responder');
      emptyOption.value = '';
      select.appendChild(emptyOption);
      responders.forEach((responder) => {
        const option = el('option', null, responder.fullName ?? `Responder ${responder.responderId}`);
        option.value = responder.responderId;
        select.appendChild(option);
      });
      card.appendChild(select);

      const assign = el('button', 'btn btn-primary', 'Assign incident');
      assign.type = 'button';
      assign.addEventListener('click', () => assignSelected(incident, select, assign));
      card.appendChild(assign);
    }
    list.appendChild(card);
  });
  incidentContainer.appendChild(list);
}

async function assignSelected(incident, select, button) {
  const responderId = select.value ? Number(select.value) : null;
  if (responderId === null) {
    showBanner('Choose a responder', 'Select an available responder before assigning this incident.');
    select.focus();
    return;
  }

  button.disabled = true;
  try {
    await assignIncident(incident.incidentId, responderId);
    incidents = incidents.map((item) => item.incidentId === incident.incidentId
      ? { ...item, status: 'assigned' }
      : item);
    renderIncidents();
  } catch (error) {
    button.disabled = false;
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner('Could not assign incident', error instanceof ApiError ? error.message : 'Please try again.');
  }
}

async function loadDashboard() {
  if (!isLoggedIn()) {
    redirectToLogin();
    return;
  }

  try {
    const [incidentResult, responderResult] = await Promise.all([
      getIncidents(),
      getAvailableResponders(),
    ]);
    incidents = Array.isArray(incidentResult?.items) ? incidentResult.items : [];
    responders = Array.isArray(responderResult?.items) ? responderResult.items : [];
    message.textContent = `${incidents.length} incident${incidents.length === 1 ? '' : 's'} in the queue.`;
    renderIncidents();
    renderResponders();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    message.textContent = 'The dispatch queue could not be loaded.';
    incidentContainer.setAttribute('aria-busy', 'false');
    responderContainer.setAttribute('aria-busy', 'false');
    incidentContainer.replaceChildren(el('p', 'empty-state', error instanceof ApiError ? error.message : 'Please refresh and try again.'));
    responderContainer.replaceChildren();
  }
}

document.getElementById('signout-button').addEventListener('click', redirectToLogin);
loadDashboard();