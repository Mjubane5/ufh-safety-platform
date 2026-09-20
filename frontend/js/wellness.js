import { createWellnessBooking, getCurrentUser, getWellnessResources, getWellnessMessages, sendWellnessMessage, isLoggedIn, logout, ApiError } from './api.js';

const LOGIN_URL = './login.html';
const CHAT_POLL_MS = 5000; // matches the interval already used elsewhere in this app
const list = document.getElementById('resource-list');
const message = document.getElementById('wellness-message');
const bookingSection = document.getElementById('booking-section');
const resourceSelect = document.getElementById('resource-id');
const chatSection = document.getElementById('chat-section');
const chatThread = document.getElementById('chat-thread');
let currentUserId = null;
let chatPollTimer = null;
let renderedMessageCount = 0;

function showBanner(title, text, kind = 'error') {
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

function renderResources(resources) {
  list.setAttribute('aria-busy', 'false');
  list.replaceChildren();
  resourceSelect.replaceChildren();
  if (resources.length === 0) {
    list.appendChild(Object.assign(document.createElement('p'), { className: 'empty-state', textContent: 'No wellness resources are available.' }));
    return;
  }

  resources.forEach((resource) => {
    const card = document.createElement('article');
    card.className = 'card';
    const title = document.createElement('h3');
    title.textContent = resource.title ?? 'Unnamed resource';
    card.appendChild(title);
    const description = document.createElement('p');
    description.className = 'text-meta';
    description.textContent = resource.description ?? 'Description unavailable.';
    card.appendChild(description);
    const availability = document.createElement('p');
    availability.textContent = resource.availability ?? 'Availability unavailable.';
    card.appendChild(availability);
    if (resource.contactPhone) {
      const phone = document.createElement('a');
      phone.href = `tel:${encodeURIComponent(resource.contactPhone)}`;
      phone.textContent = resource.contactPhone;
      card.appendChild(phone);
    }
    list.appendChild(card);

    const option = document.createElement('option');
    option.value = resource.resourceId;
    option.textContent = resource.title ?? `Resource ${resource.resourceId}`;
    resourceSelect.appendChild(option);
  });
}

// ---------------------------------------------------------------------------
// Chat ("talk to SCU") - polling, not sockets, matching the team's own
// decision log for every other real-time feature in this app.
// ---------------------------------------------------------------------------

function renderMessages(items) {
  chatThread.replaceChildren();
  if (items.length === 0) {
    chatThread.appendChild(Object.assign(document.createElement('p'), {
      className: 'empty-state',
      textContent: 'No messages yet. Say hello, or ask about booking a session.',
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
    meta.textContent = item.sender === 'student' ? 'You' : 'SCU';
    bubble.appendChild(meta);
    chatThread.appendChild(bubble);
  });
  chatThread.scrollTop = chatThread.scrollHeight;
}

async function pollMessages() {
  try {
    const result = await getWellnessMessages();
    const items = Array.isArray(result?.items) ? result.items : [];
    // Cheap way to avoid rebuilding (and losing scroll position on) an
    // unchanged thread every 5 seconds - only re-render when the count moves.
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
  if (!chatSection || chatSection.hidden) return;
  if (document.hidden) {
    stopChatPolling();
  } else {
    startChatPolling();
  }
});

async function load() {
  if (!isLoggedIn()) {
    redirectToLogin();
    return;
  }
  try {
    const [user, result] = await Promise.all([getCurrentUser(), getWellnessResources()]);
    const resources = Array.isArray(result?.items) ? result.items : [];
    renderResources(resources);
    message.textContent = `${resources.length} support resource${resources.length === 1 ? '' : 's'} available.`;
    if (user?.role === 'student') {
      currentUserId = user.userId;
      if (resources.length > 0) bookingSection.hidden = false;
      chatSection.hidden = false;
      startChatPolling();
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    list.setAttribute('aria-busy', 'false');
    message.textContent = 'Wellness resources could not be loaded.';
    list.replaceChildren(Object.assign(document.createElement('p'), { className: 'empty-state', textContent: error instanceof ApiError ? error.message : 'Please refresh and try again.' }));
  }
}

document.getElementById('signout-button').addEventListener('click', redirectToLogin);
document.getElementById('booking-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = document.getElementById('booking-button');
  const date = document.getElementById('preferred-date').value;
  const slot = document.getElementById('preferred-slot').value;
  if (!resourceSelect.value || !date || !slot) {
    showBanner('More information needed', 'Choose a resource, date, and time slot.');
    return;
  }
  button.disabled = true;
  try {
    const result = await createWellnessBooking(Number(resourceSelect.value), date, slot, document.getElementById('booking-note').value.trim() || null);
    showBanner('Booking requested', `Booking ${result?.bookingId ?? ''} is ${result?.status ?? 'requested'}.`, 'success');
    event.target.reset();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner('Could not request booking', error instanceof ApiError ? error.message : 'Please try again.');
  } finally {
    button.disabled = false;
  }
});

document.getElementById('chat-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const input = document.getElementById('chat-input');
  const button = document.getElementById('chat-send-button');
  const text = input.value.trim();
  if (!text) return;

  button.disabled = true;
  try {
    await sendWellnessMessage(text);
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