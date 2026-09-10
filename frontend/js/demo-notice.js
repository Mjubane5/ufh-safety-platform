// Demonstration notice.
//
// This prototype accepts incident and SOS reports that reach nobody. Hosted
// publicly, someone in genuine distress can find it, file a report, and get a
// confirmation screen with an incident number while no one is on the other
// end. The failure is silent, which makes it worse than the site being down.
//
// So before anyone can file anything, we say so, and we give them numbers that
// do work.
//
// The trigger is MOCK. When MOCK is true there is no backend at all and
// nothing is stored anywhere, so that flag is exactly the condition this
// notice describes. Nothing extra to configure and nothing to forget: the
// public build is a mock build, and a mock build always says so.

import { MOCK } from './config.js';

// Real services, for anyone who arrived here by mistake. Verify these before
// each submission - a wrong number here is worse than no number.
const EMERGENCY_CONTACTS = [
  { label: 'Police / ambulance (SAPS)', number: '10111' },
  { label: 'GBV Command Centre', number: '0800 428 428' },
  { label: 'Childline South Africa', number: '116' },
];

// Once per browser tab. Dismissing it on every page load would train people to
// click it away without reading, which defeats the point.
const SEEN_KEY = 'ufh.demoNoticeAcknowledged';

function contactList() {
  const list = document.createElement('ul');
  list.className = 'demo-contacts';

  EMERGENCY_CONTACTS.forEach((contact) => {
    const item = document.createElement('li');

    const label = document.createElement('span');
    label.textContent = contact.label;

    // tel: so it dials on a phone, which is what someone in trouble is holding.
    const link = document.createElement('a');
    link.href = `tel:${contact.number.replace(/\s/g, '')}`;
    link.textContent = contact.number;

    item.append(label, link);
    list.appendChild(item);
  });

  return list;
}

function buildDialog() {
  const overlay = document.createElement('div');
  overlay.className = 'demo-overlay';

  const dialog = document.createElement('div');
  dialog.className = 'demo-dialog';
  dialog.setAttribute('role', 'alertdialog');
  dialog.setAttribute('aria-modal', 'true');
  dialog.setAttribute('aria-labelledby', 'demo-title');
  dialog.setAttribute('aria-describedby', 'demo-body');

  const title = document.createElement('h2');
  title.id = 'demo-title';
  title.textContent = 'This is a demonstration, not a real safety service';

  const body = document.createElement('div');
  body.id = 'demo-body';

  const lead = document.createElement('p');
  lead.innerHTML =
    '<strong>No report made here reaches anyone.</strong> This is a student ' +
    'project at the University of Fort Hare. It is not connected to campus ' +
    'control, to the police, or to any GBV support unit. Nothing you type is ' +
    'saved, and nobody is monitoring it.';

  const help = document.createElement('p');
  help.textContent = 'If you need help right now, call one of these instead:';

  const button = document.createElement('button');
  button.className = 'btn btn-primary btn-block';
  button.type = 'button';
  button.textContent = 'I understand this is a demonstration';

  body.append(lead, help, contactList());
  dialog.append(title, body, button);
  overlay.appendChild(dialog);

  const dismiss = () => {
    try {
      sessionStorage.setItem(SEEN_KEY, 'true');
    } catch {
      // Private browsing can refuse storage. Showing the notice again on the
      // next page is the acceptable failure here.
    }
    overlay.remove();
    document.body.classList.remove('demo-locked');
  };

  button.addEventListener('click', dismiss);

  // Deliberately no click-outside-to-close and no Escape handler. This has to
  // be read, and it is the one dialog in the app where making it awkward to
  // dismiss is the correct choice.

  return { overlay, button };
}

function buildBanner() {
  const banner = document.createElement('div');
  banner.className = 'demo-banner';
  banner.setAttribute('role', 'note');
  banner.innerHTML =
    '<strong>Demonstration only.</strong> Reports filed here reach nobody. ' +
    'In an emergency call <a href="tel:10111">10111</a>, or the GBV Command ' +
    'Centre on <a href="tel:0800428428">0800&nbsp;428&nbsp;428</a>.';
  return banner;
}

/**
 * Corrects any page copy that promises a response. report.html tells the
 * student "Campus control receives this immediately", which is true of the
 * finished system and false of this one. Leaving it would undo the notice.
 */
function correctMisleadingCopy() {
  document.querySelectorAll('[data-live-claim]').forEach((element) => {
    element.textContent = element.dataset.liveClaim;
  });
}

function init() {
  if (!MOCK) return;

  document.body.classList.add('is-demo');
  correctMisleadingCopy();

  // Persistent strip under the header, on every page, so the status is visible
  // after the dialog has been dismissed.
  const header = document.querySelector('.app-header');
  const banner = buildBanner();
  if (header) header.insertAdjacentElement('afterend', banner);
  else document.body.prepend(banner);

  // A second copy immediately above the submit button, because that is the
  // moment someone commits to filing a report.
  const submitButton = document.getElementById('submit-button');
  if (submitButton) {
    submitButton.insertAdjacentElement('beforebegin', buildBanner());
  }

  let alreadySeen = false;
  try {
    alreadySeen = sessionStorage.getItem(SEEN_KEY) === 'true';
  } catch {
    alreadySeen = false;
  }
  if (alreadySeen) return;

  const { overlay, button } = buildDialog();
  document.body.appendChild(overlay);
  document.body.classList.add('demo-locked');
  button.focus();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
