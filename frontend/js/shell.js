import { logout } from './api.js';

const HOME_BY_PAGE = {
  'dashboard.html': './dashboard.html',
  'report.html': './dashboard.html',
  'incident.html': './dashboard.html',
  'map.html': './dashboard.html',
  'wellness.html': './dashboard.html',
  'gbv.html': './dashboard.html',
  'responder-dashboard.html': './responder-dashboard.html',
  'control-dashboard.html': './control-dashboard.html',
  'patrol.html': './control-dashboard.html',
  'gbv-officer.html': './gbv-officer.html',
  'role-dashboard.html': './role-dashboard.html',
};

function createButton(label, className, onClick) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = className;
  button.textContent = label;
  button.addEventListener('click', onClick);
  return button;
}

function addNavigation() {
  const header = document.querySelector('.app-header-inner');
  if (!header || header.querySelector('.app-nav')) return;

  const currentPage = window.location.pathname.split('/').pop() || 'dashboard.html';
  const homeUrl = HOME_BY_PAGE[currentPage] ?? './dashboard.html';
  const actions = document.createElement('div');
  actions.className = 'app-header-actions';

  const back = createButton('Back', 'btn btn-ghost app-back', () => {
    if (window.history.length > 1) {
      window.history.back();
    } else {
      window.location.replace(homeUrl);
    }
  });
  back.setAttribute('aria-label', 'Go back to the previous page');
  actions.appendChild(back);

  const nav = document.createElement('nav');
  nav.className = 'app-nav';
  nav.setAttribute('aria-label', 'Main navigation');
  const links = [
    ['Dashboard', homeUrl],
    ['Safety map', './map.html'],
    ['Wellness', './wellness.html'],
  ];
  links.forEach(([label, href]) => {
    const link = document.createElement('a');
    link.className = 'app-nav-link';
    link.href = href;
    link.textContent = label;
    nav.appendChild(link);
  });
  actions.appendChild(nav);

  const signOut = header.querySelector('#signout-button');
  if (signOut) {
    header.insertBefore(actions, signOut);
  } else {
    header.appendChild(actions);
  }
}

addNavigation();
