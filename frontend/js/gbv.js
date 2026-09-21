import { getGbvReportStatus, logout, submitGbvReport, getGbvMessages, sendGbvMessage, ApiError } from './api.js';

const LOGIN_URL = './login.html';
const CHAT_POLL_MS = 5000; // matches the interval used everywhere else in this app
const form = document.getElementById('gbv-form');
const anonymous = document.getElementById('anonymous');
const contactPreference = document.getElementById('contact-preference');
const submitButton = document.getElementById('submit-button');
const gbvChatSection = document.getElementById('gbv-chat-section');
const gbvChatThread = document.getElementById('gbv-chat-thread');
let currentChatCode = null;
let chatPollTimer = null;
let renderedMessageCount = 0;

function showBanner(title, message, kind = 'error') {
  const slot = document.getElementById('banner-slot');
  if (!slot) return;
  const banner = document.createElement('div');
  banner.className = kind === 'success' ? 'banner banner-success' : 'banner banner-error';
  banner.setAttribute('role', kind === 'success' ? 'status' : 'alert');
  const body = document.createElement('div');
  const heading = document.createElement('span');
  heading.className = 'banner-title';
  heading.textContent = title;
  body.appendChild(heading);
  const text = document.createElement('span');
  text.textContent = message;
  body.appendChild(text);
  banner.appendChild(body);
  slot.replaceChildren(banner);
}

function redirectToLogin() {
  logout();
  window.location.replace(LOGIN_URL);
}

function syncAnonymousFields() {
  contactPreference.disabled = anonymous.checked;
  if (anonymous.checked) contactPreference.value = 'none';
}

document.getElementById('signout-button').addEventListener('click', redirectToLogin);
anonymous.addEventListener('change', syncAnonymousFields);
syncAnonymousFields();

// Polling a page nobody is looking at wastes requests for no benefit - pause
// while the tab is hidden, catch up immediately when it's visible again.
document.addEventListener('visibilitychange', () => {
  if (!currentChatCode) return;
  if (document.hidden) {
    stopGbvChatPolling();
  } else {
    startGbvChat(currentChatCode);
  }
});

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const description = document.getElementById('gbv-description').value.trim();
  if (!description) {
    showBanner('Description required', 'Describe what happened before submitting.');
    return;
  }
  submitButton.disabled = true;
  const occurredValue = document.getElementById('occurred-at').value;
  const report = {
    anonymous: anonymous.checked,
    description,
    occurredAt: occurredValue ? new Date(occurredValue).toISOString() : null,
    latitude: null,
    longitude: null,
    evidenceIds: [],
    contactPreference: anonymous.checked ? 'none' : contactPreference.value,
  };
  try {
    const result = await submitGbvReport(report);
    showBanner('Report submitted', `Keep this reference code: ${result.referenceCode}`, 'success');
    form.reset();
    anonymous.checked = true;
    syncAnonymousFields();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner('Could not submit report', error instanceof ApiError ? error.message : 'Please try again.');
  } finally {
    submitButton.disabled = false;
  }
});

// ---------------------------------------------------------------------------
// GBV chat - only ever shown for a report checked above that was submitted
// with anonymous: false. Knowing the reference code is the credential;
// there is no separate login for this.
// ---------------------------------------------------------------------------

function renderGbvMessages(items) {
  gbvChatThread.replaceChildren();
  if (items.length === 0) {
    gbvChatThread.appendChild(Object.assign(document.createElement('p'), {
      className: 'empty-state',
      textContent: 'No messages yet.',
    }));
    return;
  }
  items.forEach((item) => {
    const bubble = document.createElement('div');
    bubble.className = item.sender === 'reporter' ? 'chat-bubble chat-bubble-mine' : 'chat-bubble chat-bubble-theirs';
    const text = document.createElement('span');
    text.textContent = item.text;
    bubble.appendChild(text);
    const meta = document.createElement('span');
    meta.className = 'chat-bubble-meta';
    meta.textContent = item.sender === 'reporter' ? 'You' : 'GBV unit';
    bubble.appendChild(meta);
    gbvChatThread.appendChild(bubble);
  });
  gbvChatThread.scrollTop = gbvChatThread.scrollHeight;
}

async function pollGbvMessages() {
  if (!currentChatCode) return;
  try {
    const result = await getGbvMessages(currentChatCode);
    const items = Array.isArray(result?.items) ? result.items : [];
    if (items.length !== renderedMessageCount) {
      renderedMessageCount = items.length;
      renderGbvMessages(items);
    }
  } catch {
    // Any failure: leave the existing thread showing, try again next tick.
  }
}

function stopGbvChatPolling() {
  if (chatPollTimer) {
    clearInterval(chatPollTimer);
    chatPollTimer = null;
  }
}

function startGbvChat(referenceCode) {
  currentChatCode = referenceCode;
  renderedMessageCount = -1;
  gbvChatSection.hidden = false;
  stopGbvChatPolling();
  pollGbvMessages();
  chatPollTimer = setInterval(pollGbvMessages, CHAT_POLL_MS);
}

document.getElementById('gbv-chat-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!currentChatCode) return;
  const input = document.getElementById('gbv-chat-input');
  const button = document.getElementById('gbv-chat-send-button');
  const text = input.value.trim();
  if (!text) return;

  button.disabled = true;
  try {
    await sendGbvMessage(currentChatCode, text);
    input.value = '';
    await pollGbvMessages();
  } catch (error) {
    showBanner('Could not send that message', error instanceof ApiError ? error.message : 'Please try again.');
  } finally {
    button.disabled = false;
    input.focus();
  }
});

document.getElementById('status-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const result = document.getElementById('status-result');
  const referenceCode = document.getElementById('reference-code').value.trim();
  if (!referenceCode) {
    result.textContent = 'Enter your reference code.';
    return;
  }
  result.textContent = 'Checking status...';
  stopGbvChatPolling();
  gbvChatSection.hidden = true;
  currentChatCode = null;
  try {
    const status = await getGbvReportStatus(referenceCode);
    result.textContent = `${status.referenceCode}: ${status.status}. Last updated ${status.lastUpdatedAt ?? 'unknown'}.`;
    if (status.anonymous === false) {
      startGbvChat(status.referenceCode);
    }
  } catch (error) {
    result.textContent = error instanceof ApiError ? error.message : 'Could not check status.';
  }
});