import {
  getCurrentUser,
  getWellnessQueue,
  getWellnessMessagesWithStudent,
  sendWellnessMessageToStudent,
  updateWellnessBookingStatus,
  isLoggedIn,
  logout,
  ApiError,
} from './api.js';

const LOGIN_URL = './login.html';
const POLL_MS = 5000; // matches the interval used everywhere else in this app

const studentList = document.getElementById('student-list');
const queueMessage = document.getElementById('queue-message');
const threadPanel = document.getElementById('thread-panel');
const emptyPanel = document.getElementById('empty-panel');
const threadStudentName = document.getElementById('thread-student-name');
const bookingsList = document.getElementById('bookings-list');
const chatThread = document.getElementById('chat-thread');

let selectedStudent = null; // { studentUserId, studentName }
let queuePollTimer = null;
let threadPollTimer = null;
let renderedMessageCount = 0;

function redirectToLogin() {
  logout();
  window.location.replace(LOGIN_URL);
}

function showBanner(text, kind = 'error') {
  const slot = document.getElementById('banner-slot');
  if (!slot) return;
  const banner = document.createElement('div');
  banner.className = kind === 'success' ? 'banner banner-success' : 'banner banner-error';
  banner.setAttribute('role', kind === 'success' ? 'status' : 'alert');
  banner.textContent = text;
  slot.replaceChildren(banner);
}

// ---------------------------------------------------------------------------
// Student queue (left panel)
// ---------------------------------------------------------------------------

function renderQueue(items) {
  studentList.setAttribute('aria-busy', 'false');
  studentList.replaceChildren();
  if (items.length === 0) {
    studentList.appendChild(Object.assign(document.createElement('p'), {
      className: 'empty-state',
      textContent: 'No students have booked or messaged yet.',
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

    const meta = document.createElement('span');
    meta.className = 'card-meta';
    const bookingCount = item.bookings?.length ?? 0;
    meta.textContent = `${bookingCount} booking${bookingCount === 1 ? '' : 's'}`;
    card.appendChild(meta);

    card.addEventListener('click', () => selectStudent(item));
    studentList.appendChild(card);
  });
}

async function pollQueue() {
  try {
    const result = await getWellnessQueue();
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

// ---------------------------------------------------------------------------
// Thread panel (right side) - bookings + messages for the selected student
// ---------------------------------------------------------------------------

const BOOKING_STATUS_LABELS = {
  requested: 'Requested',
  confirmed: 'Confirmed',
  declined: 'Declined',
  cancelled: 'Cancelled',
};

function bookingStatusPillClass(status) {
  if (status === 'confirmed') return 'pill pill-active';
  if (status === 'declined' || status === 'cancelled') return 'pill pill-cancelled';
  return 'pill';
}

function renderBookings(bookings) {
  bookingsList.replaceChildren();
  if (!bookings || bookings.length === 0) {
    bookingsList.appendChild(Object.assign(document.createElement('p'), {
      className: 'empty-state',
      textContent: 'No bookings from this student yet.',
    }));
    return;
  }

  bookings
    .slice()
    .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
    .forEach((booking) => {
      const row = document.createElement('div');
      row.className = 'detail-row';

      const term = document.createElement('dt');
      term.textContent = `${booking.preferredDate ?? ''} (${booking.preferredSlot ?? 'unspecified'})`;
      row.appendChild(term);

      const definition = document.createElement('dd');
      const status = document.createElement('span');
      status.className = bookingStatusPillClass(booking.status);
      status.textContent = BOOKING_STATUS_LABELS[booking.status] ?? booking.status ?? 'Unknown';
      definition.appendChild(status);

      if (booking.note) {
        const note = document.createElement('span');
        note.className = 'text-meta';
        note.textContent = ` ${booking.note}`;
        definition.appendChild(note);
      }

      if (booking.status === 'requested') {
        const actions = document.createElement('span');
        actions.className = 'form-actions';
        const confirmButton = document.createElement('button');
        confirmButton.type = 'button';
        confirmButton.className = 'btn btn-sm btn-primary';
        confirmButton.textContent = 'Confirm';
        confirmButton.addEventListener('click', () => changeBookingStatus(booking.bookingId, 'confirmed'));
        const declineButton = document.createElement('button');
        declineButton.type = 'button';
        declineButton.className = 'btn btn-sm btn-secondary';
        declineButton.textContent = 'Decline';
        declineButton.addEventListener('click', () => changeBookingStatus(booking.bookingId, 'declined'));
        actions.append(confirmButton, declineButton);
        definition.appendChild(actions);
      } else if (booking.status === 'confirmed') {
        const actions = document.createElement('span');
        actions.className = 'form-actions';
        const cancelButton = document.createElement('button');
        cancelButton.type = 'button';
        cancelButton.className = 'btn btn-sm btn-secondary';
        cancelButton.textContent = 'Cancel';
        cancelButton.addEventListener('click', () => changeBookingStatus(booking.bookingId, 'cancelled'));
        actions.appendChild(cancelButton);
        definition.appendChild(actions);
      }

      row.appendChild(definition);
      bookingsList.appendChild(row);
    });
}

async function changeBookingStatus(bookingId, status) {
  try {
    await updateWellnessBookingStatus(bookingId, status);
    showBanner(`Booking ${BOOKING_STATUS_LABELS[status].toLowerCase()}.`, 'success');
    await pollQueue();
    if (selectedStudent) await loadThread(selectedStudent);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner(error instanceof ApiError ? error.message : 'Could not update that booking.');
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
    bubble.className = item.sender === 'scu' ? 'chat-bubble chat-bubble-mine' : 'chat-bubble chat-bubble-theirs';
    const text = document.createElement('span');
    text.textContent = item.text;
    bubble.appendChild(text);
    const meta = document.createElement('span');
    meta.className = 'chat-bubble-meta';
    meta.textContent = item.sender === 'scu' ? 'You' : 'Student';
    bubble.appendChild(meta);
    chatThread.appendChild(bubble);
  });
  chatThread.scrollTop = chatThread.scrollHeight;
}

async function pollThread() {
  if (!selectedStudent) return;
  try {
    const result = await getWellnessMessagesWithStudent(selectedStudent.studentUserId);
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
    const [queueResult, messagesResult] = await Promise.all([
      getWellnessQueue(),
      getWellnessMessagesWithStudent(student.studentUserId),
    ]);
    const queueItems = Array.isArray(queueResult?.items) ? queueResult.items : [];
    const entry = queueItems.find((item) => item.studentUserId === student.studentUserId);
    renderBookings(entry?.bookings ?? []);
    const items = Array.isArray(messagesResult?.items) ? messagesResult.items : [];
    renderedMessageCount = items.length;
    renderMessages(items);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner('Could not load this student’s bookings and messages.');
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
  renderQueue((await getWellnessQueue().catch(() => ({ items: [] })))?.items ?? []);
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
    await sendWellnessMessageToStudent(selectedStudent.studentUserId, selectedStudent.studentName, text);
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
    if (!['scu_officer', 'admin'].includes(user?.role)) {
      queueMessage.textContent = 'This queue is restricted to SCU staff and administrators.';
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
