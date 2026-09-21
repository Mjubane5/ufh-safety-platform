import { getCampusControlMessages, sendCampusControlMessage, isLoggedIn, logout, ApiError } from './api.js';

const LOGIN_URL = './login.html';
const CHAT_POLL_MS = 5000; // matches the interval already used everywhere else in this app
const chatThread = document.getElementById('chat-thread');
let chatPollTimer = null;
let renderedMessageCount = 0;

function showBanner(title, text) {
  const slot = document.getElementById('banner-slot');
  if (!slot) return;
  const banner = document.createElement('div');
  banner.className = 'banner banner-error';
  banner.setAttribute('role', 'alert');
  const body = document.createElement('div');
  const heading = document.createElement('span');
  heading.className = 'banner-title';
  heading.textContent = title;
  body.appendChild(heading);
  const textNode = document.createElement('span');
  textNode.textContent = text;
  body.appendChild(textNode);
  banner.appendChild(body);
  slot.replaceChildren(banner);
}

function redirectToLogin() {
  logout();
  window.location.replace(LOGIN_URL);
}

function renderMessages(items) {
  chatThread.replaceChildren();
  if (items.length === 0) {
    chatThread.appendChild(Object.assign(document.createElement('p'), {
      className: 'empty-state',
      textContent: 'No messages yet. Tell campus control what you need help with.',
    }));
    return;
  }
  items.forEach((item) => {
    const bubble = document.createElement('div');
    bubble.className = item.sender === 'student' ? 'chat-bubble chat-bubble-mine' : 'chat-bubble chat-bubble-theirs';
    const text = document.createElement('span');
    text.textContent = item.text;
    bubble.appendChild(text);
    const meta = document.createElement('span');
    meta.className = 'chat-bubble-meta';
    meta.textContent = item.sender === 'student' ? 'You' : 'Campus control';
    bubble.appendChild(meta);
    chatThread.appendChild(bubble);
  });
  chatThread.scrollTop = chatThread.scrollHeight;
}

async function pollMessages() {
  try {
    const result = await getCampusControlMessages();
    const items = Array.isArray(result?.items) ? result.items : [];
    if (items.length !== renderedMessageCount) {
      renderedMessageCount = items.length;
      renderMessages(items);
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      stopChatPolling();
      redirectToLogin();
    }
    // Any other failure: leave the existing thread showing, try again next tick.
  }
}

function startChatPolling() {
  pollMessages();
  chatPollTimer = setInterval(pollMessages, CHAT_POLL_MS);
}

function stopChatPolling() {
  if (chatPollTimer) {
    clearInterval(chatPollTimer);
    chatPollTimer = null;
  }
}

// Polling a page nobody is looking at wastes requests for no benefit - pause
// while the tab is hidden, catch up immediately when it's visible again.
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    stopChatPolling();
  } else {
    startChatPolling();
  }
});

function load() {
  if (!isLoggedIn()) {
    redirectToLogin();
    return;
  }
  startChatPolling();
}

document.getElementById('signout-button').addEventListener('click', redirectToLogin);
document.getElementById('chat-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const input = document.getElementById('chat-input');
  const button = document.getElementById('chat-send-button');
  const text = input.value.trim();
  if (!text) return;

  button.disabled = true;
  try {
    await sendCampusControlMessage(text);
    input.value = '';
    await pollMessages();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner('Could not send that message', error instanceof ApiError ? error.message : 'Please try again.');
  } finally {
    button.disabled = false;
    input.focus();
  }
});

load();
