import {
  getCurrentUser,
  getGbvReports,
  getGbvChatQueue,
  getGbvMessagesForReport,
  sendGbvMessageToReport,
  isLoggedIn,
  logout,
  ApiError,
} from './api.js';

const LOGIN_URL = './staff-login.html';
const POLL_MS = 5000; // matches the interval used everywhere else in this app
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

// ---------------------------------------------------------------------------
// Chat with reporters - keyed by referenceCode, never a name. Only reports
// submitted with anonymous: false ever appear here - see getGbvChatQueue().
// ---------------------------------------------------------------------------

const chatQueueMessage = document.getElementById('chat-queue-message');
const chatCodeList = document.getElementById('chat-code-list');
const chatThreadPanel = document.getElementById('chat-thread-panel');
const chatEmptyPanel = document.getElementById('chat-empty-panel');
const chatThreadCode = document.getElementById('chat-thread-code');
const officerChatThread = document.getElementById('officer-chat-thread');

let selectedCode = null;
let chatQueuePollTimer = null;
let chatThreadPollTimer = null;
let renderedChatMessageCount = 0;

function renderChatQueue(items) {
  chatCodeList.setAttribute('aria-busy', 'false');
  chatCodeList.replaceChildren();
  if (items.length === 0) {
    chatCodeList.appendChild(Object.assign(document.createElement('p'), {
      className: 'empty-state',
      textContent: 'No reporters have started a conversation yet.',
    }));
    return;
  }

  items.forEach((item) => {
    const card = document.createElement('button');
    card.type = 'button';
    card.className = 'card card-clickable';
    if (selectedCode === item.referenceCode) card.setAttribute('aria-current', 'true');

    const header = document.createElement('div');
    header.className = 'card-header';
    const title = document.createElement('span');
    title.className = 'card-title';
    title.textContent = item.referenceCode;
    header.appendChild(title);
    if (item.hasUnread) {
      const unread = document.createElement('span');
      unread.className = 'pill pill-active';
      unread.textContent = 'New';
      header.appendChild(unread);
    }
    card.appendChild(header);
    card.addEventListener('click', () => selectChatCode(item.referenceCode));
    chatCodeList.appendChild(card);
  });
}

async function pollChatQueue() {
  try {
    const result = await getGbvChatQueue();
    const items = Array.isArray(result?.items) ? result.items : [];
    chatQueueMessage.textContent = `${items.length} conversation${items.length === 1 ? '' : 's'}.`;
    renderChatQueue(items);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      stopChatQueuePolling();
      redirectToLogin();
      return;
    }
    chatCodeList.setAttribute('aria-busy', 'false');
    chatQueueMessage.textContent = 'Conversations could not be loaded.';
  }
}

function startChatQueuePolling() {
  pollChatQueue();
  chatQueuePollTimer = setInterval(pollChatQueue, POLL_MS);
}

function stopChatQueuePolling() {
  if (chatQueuePollTimer) {
    clearInterval(chatQueuePollTimer);
    chatQueuePollTimer = null;
  }
}

function renderOfficerChatMessages(items) {
  officerChatThread.replaceChildren();
  if (items.length === 0) {
    officerChatThread.appendChild(Object.assign(document.createElement('p'), {
      className: 'empty-state',
      textContent: 'No messages in this conversation yet.',
    }));
    return;
  }
  items.forEach((item) => {
    const bubble = document.createElement('div');
    bubble.className = item.sender === 'gbv_officer' ? 'chat-bubble chat-bubble-mine' : 'chat-bubble chat-bubble-theirs';
    const text = document.createElement('span');
    text.textContent = item.text;
    bubble.appendChild(text);
    const meta = document.createElement('span');
    meta.className = 'chat-bubble-meta';
    meta.textContent = item.sender === 'gbv_officer' ? 'You' : 'Reporter';
    bubble.appendChild(meta);
    officerChatThread.appendChild(bubble);
  });
  officerChatThread.scrollTop = officerChatThread.scrollHeight;
}

async function pollChatThread() {
  if (!selectedCode) return;
  try {
    const result = await getGbvMessagesForReport(selectedCode);
    const items = Array.isArray(result?.items) ? result.items : [];
    if (items.length !== renderedChatMessageCount) {
      renderedChatMessageCount = items.length;
      renderOfficerChatMessages(items);
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      stopChatThreadPolling();
      redirectToLogin();
    }
  }
}

function startChatThreadPolling() {
  stopChatThreadPolling();
  chatThreadPollTimer = setInterval(pollChatThread, POLL_MS);
}

function stopChatThreadPolling() {
  if (chatThreadPollTimer) {
    clearInterval(chatThreadPollTimer);
    chatThreadPollTimer = null;
  }
}

async function selectChatCode(referenceCode) {
  selectedCode = referenceCode;
  renderedChatMessageCount = -1;
  chatEmptyPanel.hidden = true;
  chatThreadPanel.hidden = false;
  chatThreadCode.textContent = referenceCode;
  renderChatQueue((await getGbvChatQueue().catch(() => ({ items: [] })))?.items ?? []);
  await pollChatThread();
  startChatThreadPolling();
}

document.getElementById('officer-chat-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!selectedCode) return;
  const input = document.getElementById('officer-chat-input');
  const button = document.getElementById('officer-chat-send-button');
  const text = input.value.trim();
  if (!text) return;

  button.disabled = true;
  try {
    await sendGbvMessageToReport(selectedCode, text);
    input.value = '';
    await pollChatThread();
    await pollChatQueue();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showError(error instanceof ApiError ? error.message : 'Could not send that reply.');
  } finally {
    button.disabled = false;
    input.focus();
  }
});

document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    stopChatQueuePolling();
    stopChatThreadPolling();
  } else {
    startChatQueuePolling();
    if (selectedCode) startChatThreadPolling();
  }
});

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
    startChatQueuePolling();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) redirectToLogin();
    else showError('Your role could not be verified.');
  }
}

document.getElementById('signout-button').addEventListener('click', redirectToLogin);
start();