// health-profile.js — behaviour for health-profile.html.
//
// Lets a student view and edit the health conditions they declared at
// registration (or add them for the first time if they skipped it). Same
// POPIA-scoped fixed checklist as register.html, not free text.

import { getHealthProfile, updateHealthProfile, isLoggedIn, logout, ApiError } from './api.js';

const LOGIN_URL = './login.html';
const HEALTH_NOTE_MAX_LENGTH = 200;

const form = document.getElementById('health-profile-form');
const noteInput = document.getElementById('healthNote');
const noteCount = document.getElementById('health-note-count');
const submitButton = document.getElementById('submit-button');

function showBanner(kind, title, message) {
  const slot = document.getElementById('banner-slot');
  if (!slot) return;
  slot.replaceChildren();
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
  slot.appendChild(banner);
}

function redirectToLogin() {
  logout();
  window.location.replace(LOGIN_URL);
}

function updateNoteCount() {
  noteCount.textContent = `${noteInput.value.length} / ${HEALTH_NOTE_MAX_LENGTH} characters`;
}

async function load() {
  if (!isLoggedIn()) {
    redirectToLogin();
    return;
  }

  try {
    const profile = await getHealthProfile();
    const conditions = Array.isArray(profile?.conditions) ? profile.conditions : [];
    conditions.forEach((value) => {
      const input = form.querySelector(`input[name="healthCondition"][value="${value}"]`);
      if (input) input.checked = true;
    });
    noteInput.value = profile?.note ?? '';
    updateNoteCount();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    // Not fatal - an empty form is still usable, this is a "nothing declared
    // yet" state as far as the student can tell either way.
    console.error('Could not load health profile:', error);
  } finally {
    form.setAttribute('aria-busy', 'false');
  }
}

noteInput.addEventListener('input', updateNoteCount);

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  submitButton.disabled = true;

  const conditions = Array.from(form.querySelectorAll('input[name="healthCondition"]:checked'))
    .map((input) => input.value);
  const note = noteInput.value.trim();

  try {
    await updateHealthProfile(conditions, note || null);
    showBanner('success', 'Saved', 'Your health conditions have been updated.');
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner('error', 'Could not save', error instanceof ApiError ? error.message : 'Please try again.');
  } finally {
    submitButton.disabled = false;
  }
});

document.getElementById('signout-button').addEventListener('click', redirectToLogin);
load();
