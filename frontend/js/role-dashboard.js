import { getCurrentUser, isLoggedIn, logout, ApiError } from './api.js';

const ROLE_LABELS = {
  responder: 'Responder',
  campus_control: 'Campus control',
  gbv_officer: 'GBV officer',
  admin: 'Administrator',
};

const heading = document.getElementById('workspace-heading');
const message = document.getElementById('workspace-message');
const signOutButton = document.getElementById('signout-button');

function showError(text) {
  const slot = document.getElementById('banner-slot');
  if (!slot) return;

  const banner = document.createElement('div');
  banner.className = 'banner banner-error';
  banner.setAttribute('role', 'alert');
  banner.textContent = text;
  slot.replaceChildren(banner);
}

if (signOutButton) {
  signOutButton.addEventListener('click', () => {
    logout();
    window.location.replace('./login.html');
  });
}

async function loadWorkspace() {
  if (!isLoggedIn()) {
    window.location.replace('./login.html');
    return;
  }

  try {
    const user = await getCurrentUser();
    const roleLabel = ROLE_LABELS[user?.role] ?? 'Authenticated user';
    heading.textContent = `${roleLabel} workspace`;
    message.textContent = user?.fullName
      ? `Signed in as ${user.fullName}.`
      : 'You are signed in.';
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      logout();
      window.location.replace('./login.html');
      return;
    }

    console.error('Could not load role workspace:', error);
    message.textContent = 'Your workspace could not be loaded.';
    showError('Please refresh the page and try again.');
  }
}

loadWorkspace();