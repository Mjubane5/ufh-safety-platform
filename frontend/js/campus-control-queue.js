import {
  getCurrentUser,
  getCampusControlQueue,
  getCampusControlMessagesWithStudent,
  sendCampusControlMessageToStudent,
  isLoggedIn,
  logout,
  ApiError,
} from './api.js';

const LOGIN_URL = './staff-login.html';
const POLL_MS = 5000; // matches the interval used everywhere else in this app

const studentList = document.getElementById('student-list');
const queueMessage = document.getElementById('queue-message');
const threadPanel = document.getElementById('thread-panel');
const emptyPanel = document.getElementById('empty-panel');
const threadStudentName = document.getElementById('thread-student-name');
const chatThread = document.getElementById('chat-thread');

let selectedStudent = null; // { studentUserId, studentName }
let queuePollTimer = null;
let threadPollTimer = null;
let renderedMessageCount = 0;

function redirectToLogin() {
  logout();
  window.location.replace(LOGIN_URL);
}

function showBanner(text) {
  const slot = document.getElementById('banner-slot');
  if (!slot) return;
  const banner = document.createElement('div');
  banner.className = 'banner banner-error';
  banner.setAttribute('role', 'alert');
  banner.textContent = text;
  slot.replaceChildren(banner);
}

function renderQueue(items) {
  studentList.setAttribute('aria-busy', 'false');
  studentList.replaceChildren();
  if (items.length === 0) {
    studentList.appendChild(Object.assign(document.createElement('p'), {
      className: 'empty-state',
      textContent: 'No students have messaged campus control yet.',
    }));
    return;
  }

  items.forEach((item) => {
    const card = document.createElement('button');
    card.type = 'button';
    card.className = 'card card-clickable';
    if (selectedStudent?.studentUserId === item.studentUserId) {
      card.setAttribute('aria-current', 'true');
    }

    const header = document.createElement('div');
    header.className = 'card-header';
    const title = document.createElement('span');
    title.className = 'card-title';
    title.textContent = item.studentName ?? `Student ${item.studentUserId}`;
    header.appendChild(title);
    if (item.hasUnread) {
      const unread = document.createElement('span');
      unread.className = 'pill pill-active';
      unread.textContent = 'New';
      header.appendChild(unread);
    }
    card.appendChild(header);

    card.addEventListener('click', () => selectStudent(item));
    studentList.appendChild(card);
  });
}

async function pollQueue() {
  try {
    const result = await getCampusControlQueue();
    const items = Array.isArray(result?.items) ? result.items : [];
    queueMessage.textContent = `${items.length} student${items.length === 1 ? '' : 's'} in the queue.`;
    renderQueue(items);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      stopQueuePolling();
      redirectToLogin();
      return;
    }
    studentList.setAttribute('aria-busy', 'false');
    queueMessage.textContent = 'The queue could not be loaded.';
  }
}

function startQueuePolling() {
  pollQueue();
  queuePollTimer = setInterval(pollQueue, POLL_MS);
}

function stopQueuePolling() {
  if (queuePollTimer) {
    clearInterval(queuePollTimer);
    queuePollTimer = null;
  }
}

function renderMessages(items) {
  chatThread.replaceChildren();
  if (items.length === 0) {
    chatThread.appendChild(Object.assign(document.createElement('p'), {
      className: 'empty-state',
      textContent: 'No messages with this student yet.',
    }));
    return;
  }
  items.forEach((item) => {
    const bubble = document.createElement('div');
    bubble.className = item.sender === 'campus_control' ? 'chat-bubble chat-bubble-mine' : 'chat-bubble chat-bubble-theirs';
    const text = document.createElement('span');
    text.textContent = item.text;
    bubble.appendChild(text);
    const meta = document.createElement('span');
    meta.className = 'chat-bubble-meta';
    meta.textContent = item.sender === 'campus_control' ? 'You' : 'Student';
    bubble.appendChild(meta);
    chatThread.appendChild(bubble);
  });
  chatThread.scrollTop = chatThread.scrollHeight;
}

async function pollThread() {
  if (!selectedStudent) return;
  try {
    const result = await getCampusControlMessagesWithStudent(selectedStudent.studentUserId);
    const items = Array.isArray(result?.items) ? result.items : [];
    if (items.length !== renderedMessageCount) {
      renderedMessageCount = items.length;
      renderMessages(items);
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      stopThreadPolling();
      redirectToLogin();
    }
  }
}

async function loadThread(student) {
  renderedMessageCount = -1; // force a render even if the count matches the previous student
  try {
    const result = await getCampusControlMessagesWithStudent(student.studentUserId);
    const items = Array.isArray(result?.items) ? result.items : [];
    renderedMessageCount = items.length;
    renderMessages(items);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner('Could not load this student’s messages.');
  }
}

function startThreadPolling() {
  stopThreadPolling();
  threadPollTimer = setInterval(pollThread, POLL_MS);
}

function stopThreadPolling() {
  if (threadPollTimer) {
    clearInterval(threadPollTimer);
    threadPollTimer = null;
  }
}

async function selectStudent(item) {
  selectedStudent = { studentUserId: item.studentUserId, studentName: item.studentName };
  emptyPanel.hidden = true;
  threadPanel.hidden = false;
  threadStudentName.textContent = item.studentName ?? `Student ${item.studentUserId}`;
  renderQueue((await getCampusControlQueue().catch(() => ({ items: [] })))?.items ?? []);
  await loadThread(selectedStudent);
  startThreadPolling();
}

document.getElementById('chat-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!selectedStudent) return;
  const input = document.getElementById('chat-input');
  const button = document.getElementById('chat-send-button');
  const text = input.value.trim();
  if (!text) return;

  button.disabled = true;
  try {
    await sendCampusControlMessageToStudent(selectedStudent.studentUserId, selectedStudent.studentName, text);
    input.value = '';
    await pollThread();
    await pollQueue();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner(error instanceof ApiError ? error.message : 'Could not send that reply.');
  } finally {
    button.disabled = false;
    input.focus();
  }
});

async function start() {
  if (!isLoggedIn()) {
    redirectToLogin();
    return;
  }
  try {
    const user = await getCurrentUser();
    if (!['campus_control', 'admin'].includes(user?.role)) {
      queueMessage.textContent = 'This queue is restricted to campus control and administrators.';
      studentList.setAttribute('aria-busy', 'false');
      return;
    }
    startQueuePolling();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) redirectToLogin();
    else queueMessage.textContent = 'Your role could not be verified.';
  }
}

document.getElementById('signout-button').addEventListener('click', redirectToLogin);
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    stopQueuePolling();
    stopThreadPolling();
  } else {
    startQueuePolling();
    if (selectedStudent) startThreadPolling();
  }
});

start();
