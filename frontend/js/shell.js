// shell.js — shared responsive header, role-aware navigation, user badge,
// and centralized session controls across all pages in the platform.

import { getCurrentUser, getStoredUser, logout } from './api.js';

const ROLE_HOME_URLS = {
  student: './dashboard.html',
  campus_control: './control-dashboard.html',
  responder: './responder-dashboard.html',
  gbv_officer: './gbv-officer.html',
  scu_officer: './scu-officer.html',
  health_officer: './health-officer.html',
  admin: './control-dashboard.html',
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

const NAV_BY_ROLE = {
  student: [
    ['Dashboard', './dashboard.html'],
    ['Report Incident', './report.html'],
    ['Safety Map', './map.html'],
    ['Wellness', './wellness.html'],
    ['Health Conditions', './health-profile.html'],
    ['Confidential GBV', './gbv.html'],
    ['Contact Campus Control', './contact-campus-control.html'],
  ],
  campus_control: [
    ['Dispatch Queue', './control-dashboard.html'],
    ['Contact Queue', './campus-control-queue.html'],
    ['Record Patrol', './patrol.html'],
    ['Safety Map', './map.html'],
    ['Demo Switcher', './role-dashboard.html'],
  ],
  responder: [
    ['Assignments', './responder-dashboard.html'],
    ['Safety Map', './map.html'],
    ['Demo Switcher', './role-dashboard.html'],
  ],
  gbv_officer: [
    ['Case Queue', './gbv-officer.html'],
    ['Confidential Report', './gbv.html'],
    ['Wellness Units', './wellness.html'],
    ['Demo Switcher', './role-dashboard.html'],
  ],
  scu_officer: [
    ['Booking Queue', './scu-officer.html'],
    ['Demo Switcher', './role-dashboard.html'],
  ],
  health_officer: [
    ['Booking Queue', './health-officer.html'],
    ['Demo Switcher', './role-dashboard.html'],
  ],
  admin: [
    ['Campus Control', './control-dashboard.html'],
    ['Contact Queue', './campus-control-queue.html'],
    ['Responder Desk', './responder-dashboard.html'],
    ['GBV Queue', './gbv-officer.html'],
    ['SCU Queue', './scu-officer.html'],
    ['Health Queue', './health-officer.html'],
    ['Demo Switcher', './role-dashboard.html'],
  ],
};

function createButton(label, className, onClick) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = className;
  button.textContent = label;
  button.addEventListener('click', onClick);
  return button;
}

export function handleSignOut() {
  const lastRole = logout();
  if (['campus_control', 'gbv_officer', 'scu_officer', 'health_officer', 'responder', 'admin'].includes(lastRole)) {
    window.location.replace('./staff-login.html');
  } else {
    window.location.replace('./login.html');
  }
}

async function renderShell() {
  const header = document.querySelector('.app-header-inner');
  if (!header) return;

  // Prevent duplicate rendering
  if (header.querySelector('.app-header-actions')) return;

  // Retrieve user or cached fallback
  let user = getStoredUser();
  if (!user) {
    try {
      user = await getCurrentUser();
    } catch {
      user = null;
    }
  }

  const role = user?.role || 'student';
  const homeUrl = ROLE_HOME_URLS[role] ?? './dashboard.html';
  const currentPage = window.location.pathname.split('/').pop() || 'dashboard.html';

  const actions = document.createElement('div');
  actions.className = 'app-header-actions';

  // Navigation Links
  const nav = document.createElement('nav');
  nav.className = 'app-nav';
  nav.setAttribute('aria-label', 'Main navigation');

  const links = NAV_BY_ROLE[role] || NAV_BY_ROLE.student;
  links.forEach(([label, href]) => {
    const link = document.createElement('a');
    link.className = 'app-nav-link';
    link.href = href;
    link.textContent = label;

    const linkPage = href.replace('./', '').split('?')[0];
    if (currentPage === linkPage) {
      link.classList.add('active');
      link.setAttribute('aria-current', 'page');
    }
    nav.appendChild(link);
  });
  actions.appendChild(nav);

  // User Badge (if logged in)
  if (user) {
    const userBadge = document.createElement('div');
    userBadge.className = 'user-badge';
    userBadge.title = `Signed in as ${user.fullName || 'User'} (${ROLE_LABELS[role] || role})`;

    const userName = document.createElement('span');
    userName.className = 'user-badge-name';
    userName.textContent = user.fullName || 'User';

    const userRole = document.createElement('span');
    userRole.className = `user-badge-role role-tag role-${role}`;
    userRole.textContent = ROLE_LABELS[role] || role;

    userBadge.appendChild(userName);
    userBadge.appendChild(userRole);
    actions.appendChild(userBadge);
  }

  // Hook or create Sign Out button
  let signOutBtn = header.querySelector('#signout-button');
  if (!signOutBtn) {
    signOutBtn = createButton('Sign out', 'btn btn-ghost', handleSignOut);
    signOutBtn.id = 'signout-button';
  } else {
    // Replace with clean event listener
    const freshBtn = signOutBtn.cloneNode(true);
    freshBtn.addEventListener('click', handleSignOut);
    signOutBtn.parentNode.replaceChild(freshBtn, signOutBtn);
    signOutBtn = freshBtn;
  }

  header.appendChild(actions);
  header.appendChild(signOutBtn);
}

// Automatically initialize header
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', renderShell);
} else {
  renderShell();
}
