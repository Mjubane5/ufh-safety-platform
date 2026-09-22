import { createPatrol, isLoggedIn, logout, ApiError } from './api.js';

// Staff-only page (campus_control) - unlike map.html/incident.html this one
// has no student use, so there is no role to detect: always the staff form.
const LOGIN_URL = './staff-login.html';
const form = document.getElementById('patrol-form');
const zoneInput = document.getElementById('zone-id');
const noteInput = document.getElementById('patrol-note');
const locationButton = document.getElementById('location-button');
const submitButton = document.getElementById('submit-button');
const locationStatus = document.getElementById('location-status');
let coordinates = null;

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

function updateSubmitState() {
  submitButton.disabled = !coordinates || !zoneInput.value;
}

if (!isLoggedIn()) redirectToLogin();

document.getElementById('signout-button').addEventListener('click', redirectToLogin);
zoneInput.addEventListener('input', updateSubmitState);
locationButton.addEventListener('click', () => {
  if (!navigator.geolocation) {
    locationStatus.textContent = 'Location is not available in this browser.';
    return;
  }
  locationButton.disabled = true;
  locationStatus.textContent = 'Requesting location...';
  navigator.geolocation.getCurrentPosition(
    ({ coords }) => {
      coordinates = { latitude: coords.latitude, longitude: coords.longitude };
      locationStatus.textContent = `Location captured: ${coords.latitude.toFixed(5)}, ${coords.longitude.toFixed(5)}`;
      locationButton.disabled = false;
      updateSubmitState();
    },
    () => {
      locationButton.disabled = false;
      locationStatus.textContent = 'Location was unavailable. Try again before recording the patrol.';
    },
    { enableHighAccuracy: true, timeout: 10000 },
  );
});

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!coordinates || !zoneInput.value) {
    showBanner('More information needed', 'Choose a zone and capture the patrol location.');
    return;
  }

  submitButton.disabled = true;
  try {
    const patrol = await createPatrol(Number(zoneInput.value), coordinates.latitude, coordinates.longitude, noteInput.value.trim() || null);
    showBanner('Patrol recorded', `Patrol ${patrol?.patrolId ?? ''} was recorded for the safety map.`, 'success');
    form.reset();
    coordinates = null;
    locationStatus.textContent = 'Location has not been captured.';
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner('Could not record patrol', error instanceof ApiError ? error.message : 'Please try again.');
  } finally {
    updateSubmitState();
  }
});