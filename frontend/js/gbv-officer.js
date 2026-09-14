import { getCurrentUser, getGbvReports, isLoggedIn, logout, ApiError } from './api.js';

const LOGIN_URL = './login.html';
const STATUS_FILTERS = [
  { value: null, label: 'All' },
  { value: 'submitted', label: 'Submitted' },
  { value: 'under_review', label: 'Under review' },
  { value: 'referred', label: 'Referred' },
  { value: 'closed', label: 'Closed' },
];
const list = document.getElementById('case-list');
const message = document.getElementById('queue-message');
let selectedStatus = null;

function redirectToLogin() {
  logout();
  window.location.replace(LOGIN_URL);
}

function showError(text) {
  const slot = document.getElementById('banner-slot');
  const banner = document.createElement('div');
  banner.className = 'banner banner-error';
  banner.setAttribute('role', 'alert');
  banner.textContent = text;
  slot.replaceChildren(banner);
}

function renderFilters() {
  const container = document.getElementById('status-filter');
  container.replaceChildren();
  STATUS_FILTERS.forEach(({ value, label }) => {
    const button = document.createElement('button');
    button.className = 'chip';
    button.type = 'button';
    button.textContent = label;
    button.setAttribute('aria-pressed', String(value === selectedStatus));
    button.addEventListener('click', () => {
      selectedStatus = value;
      renderFilters();
      loadCases();
    });
    container.appendChild(button);
  });
}

function row(label, value) {
  const wrapper = document.createElement('div');
  wrapper.className = 'detail-row';
  const term = document.createElement('dt');
  term.textContent = label;
  const definition = document.createElement('dd');
  definition.textContent = value ?? 'Not available';
  wrapper.append(term, definition);
  return wrapper;
}

function renderCases(items) {
  list.setAttribute('aria-busy', 'false');
  list.replaceChildren();
  if (items.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'empty-state';
    empty.textContent = 'No cases match this filter.';
    list.appendChild(empty);
    return;
  }
  items.forEach((item) => {
    const card = document.createElement('article');
    card.className = 'card';
    const heading = document.createElement('h2');
    heading.textContent = item.referenceCode ?? 'Unknown reference';
    card.appendChild(heading);
    const status = document.createElement('span');
    status.className = 'pill';
    status.textContent = item.status ?? 'Unknown status';
    card.appendChild(status);
    const description = document.createElement('p');
    description.className = 'detail-description';
    description.textContent = item.description ?? 'No description provided.';
    card.appendChild(description);
    const details = document.createElement('dl');
    details.className = 'detail-list';
    details.appendChild(row('Occurred', item.occurredAt));
    details.appendChild(row('Submitted', item.submittedAt));
    details.appendChild(row('Contact', item.contactPreference));
    details.appendChild(row('Anonymous', item.anonymous === true ? 'Yes' : 'No'));
    card.appendChild(details);
    list.appendChild(card);
  });
}

async function loadCases() {
  list.setAttribute('aria-busy', 'true');
  list.replaceChildren();
  try {
    const result = await getGbvReports(selectedStatus);
    const items = Array.isArray(result?.items) ? result.items : [];
    message.textContent = `${result?.totalItems ?? items.length} confidential case${items.length === 1 ? '' : 's'}.`;
    renderCases(items);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    list.setAttribute('aria-busy', 'false');
    showError(error instanceof ApiError ? error.message : 'Could not load confidential cases.');
  }
}

async function start() {
  if (!isLoggedIn()) {
    redirectToLogin();
    return;
  }
  try {
    const user = await getCurrentUser();
    if (!['gbv_officer', 'admin'].includes(user?.role)) {
      showError('This queue is restricted to GBV officers and administrators.');
      return;
    }
    renderFilters();
    await loadCases();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) redirectToLogin();
    else showError('Your role could not be verified.');
  }
}

document.getElementById('signout-button').addEventListener('click', redirectToLogin);
start();