// role-dashboard.js — Interactive role switcher for capstone demonstrations.

import { getCurrentUser, setActiveMockRole, MOCK_PERSONAS } from './api.js';

const ROLE_URLS = {
  student: './dashboard.html',
  campus_control: './control-dashboard.html',
  responder: './responder-dashboard.html',
  gbv_officer: './gbv-officer.html',
  scu_officer: './scu-officer.html',
  health_officer: './health-officer.html',
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
    btn.addEventListener('click', () => {
      const targetRole = btn.getAttribute('data-switch-role');
      if (targetRole && ROLE_URLS[targetRole]) {
        setActiveMockRole(targetRole);
        window.location.href = ROLE_URLS[targetRole];
      }
    });
  });
}

initRoleHub();
