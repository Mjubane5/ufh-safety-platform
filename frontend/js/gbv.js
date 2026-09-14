import { getGbvReportStatus, logout, submitGbvReport, ApiError } from './api.js';

const LOGIN_URL = './login.html';
const form = document.getElementById('gbv-form');
const anonymous = document.getElementById('anonymous');
const contactPreference = document.getElementById('contact-preference');
const submitButton = document.getElementById('submit-button');

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

document.getElementById('status-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const result = document.getElementById('status-result');
  const referenceCode = document.getElementById('reference-code').value.trim();
  if (!referenceCode) {
    result.textContent = 'Enter your reference code.';
    return;
  }
  result.textContent = 'Checking status...';
  try {
    const status = await getGbvReportStatus(referenceCode);
    result.textContent = `${status.referenceCode}: ${status.status}. Last updated ${status.lastUpdatedAt ?? 'unknown'}.`;
  } catch (error) {
    result.textContent = error instanceof ApiError ? error.message : 'Could not check status.';
  }
});