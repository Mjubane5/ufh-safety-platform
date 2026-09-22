// role-dashboard.js — Interactive role switcher for capstone demonstrations.
//
// Two very different environments use this same page:
//   MOCK = true  (local dev, no backend) - setActiveMockRole() fakes a
//                 token; MOCK mode never actually checks it, so that's fine.
//   MOCK = false (the hosted demo, a real backend) - a fake token is not
//                 fine. The switched-to page's very first real API call
//                 sends it as a Bearer token, the backend rejects it, and
//                 that page's own session guard bounces straight back to
//                 sign-in - "enter a page, land back on the login screen"
//                 with no clue why. Production must sign in for real, with
//                 one of the seeded demo accounts (see
//                 backend/.../DemoDataSeeder.java) - synthetic accounts
//                 that exist precisely so a page like this can do that.

import { getCurrentUser, setActiveMockRole, login, ApiError, MOCK_PERSONAS } from './api.js';
import { MOCK } from './config.js';

const ROLE_URLS = {
  student: './dashboard.html',
  campus_control: './control-dashboard.html',
  responder: './responder-dashboard.html',
  gbv_officer: './gbv-officer.html',
  scu_officer: './scu-officer.html',
  health_officer: './health-officer.html',
};

// Matches DemoDataSeeder's actual seeded accounts and its one shared
// DEFAULT_PASSWORD exactly. Synthetic, already-public demo credentials
// (documented in backend/README-BACKEND.md) - not a secret, safe to ship in
// a client-side file whose entire purpose is a public demonstration.
const ROLE_DEMO_CREDENTIALS = {
  student: { email: 'thandiwe.mokoena@example.ac.za', password: 'DevPassword123!' },
  campus_control: { email: 'johan.vanwyk@example.ac.za', password: 'DevPassword123!' },
  responder: { email: 'nomsa.khumalo@example.ac.za', password: 'DevPassword123!' },
  gbv_officer: { email: 'lerato.mahlangu@example.ac.za', password: 'DevPassword123!' },
  scu_officer: { email: 'thandeka.radebe@example.ac.za', password: 'DevPassword123!' },
  health_officer: { email: 'bongiwe.nqcobo@example.ac.za', password: 'DevPassword123!' },
};

const ROLE_LABELS = {
  student: 'Student',
  campus_control: 'Campus Control',
  responder: 'Responder',
  gbv_officer: 'GBV Support Officer',
  scu_officer: 'SCU Officer',
  health_officer: 'Health Centre Officer',
  admin: 'Administrator',
};

// Same textContent-only, no-innerHTML banner pattern used throughout the
// frontend (see e.g. map.js) - server messages are attacker-influenced text.
function showBanner(title, message) {
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
  const text = document.createElement('span');
  text.textContent = message;
  body.appendChild(text);
  banner.appendChild(body);
  slot.replaceChildren(banner);
}

async function initRoleHub() {
  const nameEl = document.getElementById('active-persona-name');
  const roleEl = document.getElementById('active-persona-role');

  try {
    const user = await getCurrentUser();
    if (nameEl && user) {
      nameEl.textContent = user.fullName || 'Authenticated User';
    }
    if (roleEl && user) {
      roleEl.textContent = ROLE_LABELS[user.role] || user.role;
      roleEl.className = `role-tag role-${user.role}`;
    }
  } catch (err) {
    console.warn('Could not load current user profile:', err);
    if (nameEl) nameEl.textContent = 'Demo Mode';
  }

  // Wire up switcher buttons
  const buttons = document.querySelectorAll('button[data-switch-role]');
  buttons.forEach((btn) => {
    btn.addEventListener('click', async () => {
      const targetRole = btn.getAttribute('data-switch-role');
      if (!targetRole || !ROLE_URLS[targetRole]) return;

      if (MOCK) {
        // No real backend to authenticate against - fake it, same as always.
        setActiveMockRole(targetRole);
        window.location.href = ROLE_URLS[targetRole];
        return;
      }

      // A real backend is live. Sign in for real with the matching seeded
      // demo account, so the page we're about to land on actually works -
      // see the file header comment for why a fake token breaks this here.
      const credentials = ROLE_DEMO_CREDENTIALS[targetRole];
      if (!credentials) return;

      const originalLabel = btn.textContent;
      btn.disabled = true;
      btn.textContent = 'Switching…';
      try {
        await login(credentials.email, credentials.password);
        window.location.href = ROLE_URLS[targetRole];
      } catch (err) {
        btn.disabled = false;
        btn.textContent = originalLabel;
        const message = err instanceof ApiError
          ? err.message
          : 'Could not switch roles right now. Please try again.';
        showBanner('Could not switch personas', message);
      }
    });
  });
}

initRoleHub();
