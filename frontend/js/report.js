// report.js — behaviour for report.html, the incident submission form.
//
// Never calls fetch(). The one network call goes through submitIncident() in
// api.js, which owns the request shape and the auth header.
//
// ===========================================================================
// SECURITY — read this before editing
// ===========================================================================
//
// This page is where a stored XSS attack would START. The description field is
// free text typed by whoever is holding the phone, it is saved to MySQL, and
// it is later rendered on the campus control dashboard — a screen belonging to
// an account that can see every incident on campus.
//
// So the attack is: a student submits a description containing a <script> tag,
// an officer opens the dashboard, and if any page renders that description
// with innerHTML the script runs with the officer's session and can read their
// token out of localStorage.
//
// The rule that prevents it: NEVER assign innerHTML. Not here, not on the
// dashboard, not anywhere. Every string in this file reaches the page through
// textContent, which writes text as text — a description containing <script>
// is displayed to the officer, not executed by their browser.
//
// This file also never echoes the description back onto the page at all. The
// character counter reports a length, not the content.
// ===========================================================================

import { submitIncident, isLoggedIn, logout, ApiError } from './api.js';

const LOGIN_URL = './login.html';

/** The nine values from docs/api-contract.md. Must match report.html. */
const INCIDENT_TYPES = [
  'sos', 'medical', 'fire', 'theft', 'assault',
  'accident', 'suspicious', 'unsafe', 'other',
];

const MAX_DESCRIPTION_LENGTH = 1000;

// ===========================================================================
// GEOLOCATION — the permission flow, for the documentation
// ===========================================================================
//
// WHY IT ONLY WORKS ON HTTPS OR LOCALHOST
//
// navigator.geolocation is gated behind what the browser calls a "secure
// context". If the page is served over plain http:// from any host other than
// localhost, the API is either missing entirely or every call fails
// immediately. This is not a setting we can switch off.
//
// The reason is that http:// traffic is readable and modifiable by anyone on
// the same network — the campus wifi, for instance. Without TLS, an attacker
// could watch a student's exact coordinates go past in the clear, or inject
// script into the page and request the location themselves. A browser will not
// hand out someone's physical position over a channel like that.
//
// localhost is exempt because the traffic never leaves the machine, which is
// what makes local development possible.
//
// WHAT THIS MEANS FOR US
//   - Development: open the page through http://localhost:5500 (or whatever
//     port). Opening the file directly as file:///... will NOT work — ES
//     modules are blocked there too.
//   - Testing on a real phone against your laptop's IP (http://10.0.0.5:5500)
//     WILL fail. That is the secure-context rule, not a bug in this code.
//   - Demo and deployment: the site must be served over https://.
//
// THE PERMISSION FLOW
//
// 1. We call getCurrentPosition(). The browser shows ITS OWN permission
//    prompt — we cannot style it, move it, or read whether it is open.
// 2. The student answers, and exactly one of our two callbacks fires. There is
//    no third "they ignored it" callback, which is why the timeout option
//    below matters.
// 3. The browser remembers the answer for this origin. A student who pressed
//    "Block" is never prompted again; they must clear it in site settings.
//    That is why the denial message tells them where to look instead of just
//    saying "denied" — from our side the retry button is indistinguishable
//    from the first attempt, but for them nothing will appear to happen.
//
// THE THREE FAILURE CODES (GeolocationPositionError)
//   1 PERMISSION_DENIED    — they said no, or the browser said no for them.
//                            Retrying will not help until they change it.
//   2 POSITION_UNAVAILABLE — permission was fine, the fix failed. Indoors,
//                            in a basement, GPS hardware busy. Retry may work.
//   3 TIMEOUT              — no answer within our timeout. Common on a first
//                            fix with a cold GPS. Retry usually works.
//
// We handle all three separately because the advice differs. Collapsing them
// into "could not get location" tells a student nothing they can act on.
// ===========================================================================

const GEO_OPTIONS = {
  // Ask for the GPS chip rather than a coarse wifi/cell estimate. Costs
  // battery and time, but a responder needs to find a person, not a suburb.
  enableHighAccuracy: true,

  // Give up after 15 seconds. A first GPS fix outdoors is often 5-10 seconds;
  // less than this and we would report a timeout on a fix that was coming.
  timeout: 15000,

  // Accept a cached position up to 30 seconds old. Someone reporting an
  // incident has not moved far in that time, and it makes the common case
  // instant.
  maximumAge: 30000,
};

/**
 * Current location state. Kept in one object so the submit handler has a
 * single thing to read.
 *   status: 'pending' | 'found' | 'denied' | 'unavailable' | 'timeout' | 'unsupported'
 */
const capturedLocation = {
  status: 'pending',
  latitude: null,
  longitude: null,
  accuracy: null,
};

/** True when the student has ticked "send without my location". */
let submitWithoutLocation = false;

// ---------------------------------------------------------------------------
// DOM helpers — all textContent, never innerHTML
// ---------------------------------------------------------------------------

/**
 * Shows a page-level banner. Built fresh each time rather than toggled,
 * because .banner sets display:flex and would override [hidden].
 * @param {'error'|'success'} kind
 */
function showBanner(kind, title, message) {
  const slot = document.getElementById('banner-slot');
  if (!slot) return;

  slot.replaceChildren();

  const banner = document.createElement('div');
  banner.className = kind === 'error' ? 'banner banner-error' : 'banner banner-success';
  banner.setAttribute('role', 'alert');

  const body = document.createElement('div');

  const heading = document.createElement('span');
  heading.className = 'banner-title';
  heading.textContent = title;

  const text = document.createElement('span');
  text.textContent = message;   // server string — textContent, never innerHTML

  body.appendChild(heading);
  body.appendChild(text);
  banner.appendChild(body);
  slot.appendChild(banner);

  banner.scrollIntoView({ block: 'nearest' });
}

function clearBanner() {
  const slot = document.getElementById('banner-slot');
  if (slot) slot.replaceChildren();
}

/** Reads aria-describedby as a list of ids. Empty when the attribute is absent. */
function describedByIds(input) {
  return (input.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean);
}

function writeDescribedBy(input, ids) {
  if (ids.length === 0) {
    input.removeAttribute('aria-describedby');
  } else {
    input.setAttribute('aria-describedby', ids.join(' '));
  }
}

/** Marks one input invalid and writes the message below it. */
function setFieldError(inputId, message) {
  const input = document.getElementById(inputId);
  if (!input) return;

  const field = input.closest('.field');
  if (!field) return;

  clearFieldError(inputId);

  input.classList.add('input-invalid');
  input.setAttribute('aria-invalid', 'true');

  const error = document.createElement('p');
  error.className = 'field-error';
  error.id = `${inputId}-error`;
  error.textContent = message;

  // aria-describedby takes a LIST of ids, and the textarea already points at
  // its hint and its character counter. Add to that list rather than
  // replacing it, or fixing an error would silence the other two.
  const ids = describedByIds(input);
  if (!ids.includes(error.id)) ids.push(error.id);
  writeDescribedBy(input, ids);

  field.appendChild(error);
}

function clearFieldError(inputId) {
  const input = document.getElementById(inputId);
  if (!input) return;

  input.classList.remove('input-invalid');
  input.removeAttribute('aria-invalid');

  const errorId = `${inputId}-error`;
  writeDescribedBy(input, describedByIds(input).filter((id) => id !== errorId));

  const existing = document.getElementById(errorId);
  if (existing) existing.remove();
}

function clearAllFieldErrors() {
  ['type', 'description'].forEach(clearFieldError);
}

/** Loading state on the submit button. Disabling it stops a double-tap. */
function setLoading(button, isLoading) {
  button.disabled = isLoading;

  if (!isLoading) {
    button.textContent = 'Send report';
    return;
  }

  button.replaceChildren();

  const spinner = document.createElement('span');
  spinner.className = 'spinner';

  const label = document.createElement('span');
  label.textContent = 'Sending…';

  button.appendChild(spinner);
  button.appendChild(label);
}

// ---------------------------------------------------------------------------
// Description counter
// ---------------------------------------------------------------------------

/**
 * Live character count.
 *
 * Note this reports the LENGTH of the description, never the description
 * itself. maxlength on the textarea is the real limit; this just makes it
 * visible so nobody types 300 words and loses the end of them.
 *
 * No aria-live here on purpose — announcing a new number on every keystroke
 * makes the field unusable with a screen reader. The limit is stated in the
 * static hint text instead, which is linked via aria-describedby.
 */
function initDescriptionCounter() {
  const textarea = document.getElementById('description');
  const counter = document.getElementById('description-counter');
  if (!textarea || !counter) return;

  const update = () => {
    const used = textarea.value.length;
    counter.textContent = `${used} / ${MAX_DESCRIPTION_LENGTH} characters`;
  };

  textarea.addEventListener('input', update);
  update();
}

// ---------------------------------------------------------------------------
// "Other" requires a description
// ---------------------------------------------------------------------------

/**
 * Updates the description hint when the type changes.
 *
 * Why "other" is special, and why we say so to the student: every other type
 * carries meaning on its own. "Fire" tells campus control what to send before
 * anyone reads a word of description. "Other" tells them nothing — so the
 * description is the ONLY information available to decide what kind of help to
 * send and how fast. A blank "other" report is a dispatcher guessing.
 *
 * The contract enforces this server-side too (400 when description is absent
 * on an `other` report). We check here so the student is told before they lose
 * the round trip, not instead of the server checking.
 */
function initTypeBehaviour() {
  const typeSelect = document.getElementById('type');
  const label = document.querySelector('label[for="description"]');
  const hint = document.getElementById('description-hint');
  if (!typeSelect || !label || !hint) return;

  const update = () => {
    const isOther = typeSelect.value === 'other';

    // .label-required appends " (required)" in text, so the requirement is
    // not signalled by colour or an asterisk alone.
    label.classList.toggle('label-required', isOther);

    hint.textContent = isOther
      ? 'Required. "Other" does not tell responders what kind of help to send, '
        + 'so your description is all they have to go on. Say what is '
        + 'happening and where.'
      : 'Optional for most types, but include anything that helps responders '
        + 'find you — a building name, a landmark, what people are wearing.';

    if (!isOther) clearFieldError('description');
  };

  typeSelect.addEventListener('change', update);
  update();
}

// ---------------------------------------------------------------------------
// Location capture
// ---------------------------------------------------------------------------

/** Writes the plain status line under the "Your location" label. */
function setLocationStatus(text) {
  const status = document.getElementById('location-status');
  if (!status) return;

  status.className = 'field-hint';
  status.replaceChildren();

  const span = document.createElement('span');
  span.textContent = text;
  status.appendChild(span);
}

/** Status line with a spinner, for while the fix is in progress. */
function setLocationSearching(text) {
  const status = document.getElementById('location-status');
  if (!status) return;

  status.className = 'field-hint';
  status.replaceChildren();

  const spinner = document.createElement('span');
  spinner.className = 'spinner';

  const span = document.createElement('span');
  span.textContent = ` ${text}`;

  status.appendChild(spinner);
  status.appendChild(span);
}

/** Status line styled as an error, with the ⚠ glyph from .field-error. */
function setLocationProblem(text) {
  const status = document.getElementById('location-status');
  if (!status) return;

  status.className = 'field-error';
  status.replaceChildren();

  const span = document.createElement('span');
  span.textContent = text;
  status.appendChild(span);
}

/** Removes whatever buttons/checkboxes are under the location status. */
function clearLocationActions() {
  const actions = document.getElementById('location-actions');
  if (actions) actions.replaceChildren();
}

/**
 * Offers a retry button and a "send without location" checkbox.
 *
 * The form is never blocked. A student reporting an assault in a basement
 * with no GPS fix must still be able to send the report — refusing it because
 * we could not get coordinates would be the worst possible failure for a
 * safety app.
 *
 * @param {boolean} canRetry  False after a denial: the browser will not
 *                            re-prompt, so a retry button would do nothing
 *                            visible and just look broken.
 */
function showLocationFallback(canRetry) {
  const actions = document.getElementById('location-actions');
  if (!actions) return;

  actions.replaceChildren();

  if (canRetry) {
    const retry = document.createElement('button');
    retry.type = 'button';           // not "submit" — this must not send the form
    retry.className = 'btn btn-secondary';
    retry.textContent = 'Try again';
    retry.addEventListener('click', requestLocation);
    actions.appendChild(retry);
  }

  const label = document.createElement('label');
  label.className = 'checkbox';
  label.setAttribute('for', 'without-location');

  const checkbox = document.createElement('input');
  checkbox.type = 'checkbox';
  checkbox.id = 'without-location';
  checkbox.addEventListener('change', (event) => {
    submitWithoutLocation = event.target.checked;
    clearBanner();
  });

  const text = document.createElement('span');
  text.textContent = 'Send this report without my location';

  label.appendChild(checkbox);
  label.appendChild(text);
  actions.appendChild(label);

  const hint = document.createElement('p');
  hint.className = 'field-hint';
  hint.textContent =
    'Without coordinates, responders cannot be sent to you automatically. '
    + 'Describe where you are in the description box above.';
  actions.appendChild(hint);
}

/**
 * Asks the browser for the student's position.
 *
 * Called on page load so the permission prompt appears while they are still
 * choosing an incident type, rather than adding a delay at the moment they
 * press send.
 */
function requestLocation() {
  clearLocationActions();
  submitWithoutLocation = false;
  capturedLocation.status = 'pending';

  // Feature check first. Older browsers, and any page served over plain http
  // from a non-localhost host, have no geolocation object at all.
  if (!('geolocation' in navigator)) {
    capturedLocation.status = 'unsupported';
    setLocationProblem(
      'This browser cannot share your location, or the page is not being '
      + 'served securely. Describe where you are in the description box.',
    );
    showLocationFallback(false);
    return;
  }

  setLocationSearching('Finding your location…');

  navigator.geolocation.getCurrentPosition(onLocationFound, onLocationError, GEO_OPTIONS);
}

/** Success callback. */
function onLocationFound(position) {
  capturedLocation.status = 'found';
  capturedLocation.latitude = position.coords.latitude;
  capturedLocation.longitude = position.coords.longitude;

  // accuracy is a radius in metres: the true position is somewhere within a
  // circle this wide. The contract allows null when it is unavailable.
  capturedLocation.accuracy = Number.isFinite(position.coords.accuracy)
    ? Math.round(position.coords.accuracy)
    : null;

  clearLocationActions();

  // Show the accuracy so the student can judge it themselves. 8 m is a person
  // on a path; 2000 m is a wifi guess at the wrong end of campus, and they may
  // want to add a landmark to the description.
  setLocationStatus(
    capturedLocation.accuracy === null
      ? 'Location found.'
      : `Location found — accurate to about ${capturedLocation.accuracy} m.`,
  );
}

/**
 * Failure callback. The three codes get three different messages because they
 * need three different actions from the student.
 */
function onLocationError(error) {
  switch (error.code) {
    case 1: // PERMISSION_DENIED
      capturedLocation.status = 'denied';
      setLocationProblem(
        'Location permission was blocked. The browser will not ask again, so '
        + 'to share it you would need to allow location for this site in your '
        + 'browser settings — usually the padlock or (i) icon in the address '
        + 'bar. You can send the report without it.',
      );
      // No retry button: the browser will not re-prompt after a block, so the
      // button would appear to do nothing.
      showLocationFallback(false);
      break;

    case 3: // TIMEOUT
      capturedLocation.status = 'timeout';
      setLocationProblem(
        'Finding your location is taking too long. This is common indoors or '
        + 'on a first attempt. Try again, or send the report without it.',
      );
      showLocationFallback(true);
      break;

    case 2: // POSITION_UNAVAILABLE
    default:
      capturedLocation.status = 'unavailable';
      setLocationProblem(
        'Your location could not be determined — this usually means no signal, '
        + 'indoors or underground. Try again, or send the report without it.',
      );
      showLocationFallback(true);
      break;
  }
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

/**
 * @returns {Array<{field: string, message: string}>} Empty when valid.
 */
function validate({ type, description }) {
  const errors = [];

  if (!type) {
    errors.push({ field: 'type', message: 'Choose what is happening.' });
  } else if (!INCIDENT_TYPES.includes(type)) {
    // Only reachable if report.html and INCIDENT_TYPES drift apart.
    errors.push({ field: 'type', message: 'That is not a valid incident type.' });
  }

  if (type === 'other' && !description) {
    errors.push({
      field: 'description',
      message: 'Describe what is happening. "Other" gives responders nothing to act on by itself.',
    });
  }

  if (description.length > MAX_DESCRIPTION_LENGTH) {
    // maxlength should prevent this; checked anyway in case it is bypassed.
    errors.push({
      field: 'description',
      message: `Description must be ${MAX_DESCRIPTION_LENGTH} characters or fewer.`,
    });
  }

  return errors;
}

// ---------------------------------------------------------------------------
// Submit
// ---------------------------------------------------------------------------

/**
 * Replaces the form with a confirmation.
 *
 * The incidentId is the student's reference number, so it is shown large and
 * plainly. It comes from the server, so like every other server value it goes
 * on the page with textContent.
 */
function showConfirmation(incident, button) {
  const form = document.getElementById('report-form');

  // Take the button out of its loading state before disabling it. Without
  // this the spinner keeps turning under a "Report sent" banner, which reads
  // as though the report is still in flight.
  if (button) {
    button.replaceChildren();
    button.textContent = 'Report sent';
  }

  const reference = incident?.incidentId;
  let message = reference === null || reference === undefined
    ? 'Campus control has received this report.'
    : `Your reference number is ${reference}. `
      + 'Campus control has received this report. Write the number down.';

  // Contract v0.2: the server reports whether it got coordinates. When it did
  // not, say so here rather than letting the student assume someone is
  // already walking towards them.
  if (incident?.locationSource === 'none') {
    message += ' This report has no location attached, so campus control will '
      + 'work out where you are from your description. If you can, call them '
      + 'as well.';
  }

  showBanner('success', 'Report sent', message);

  if (form) {
    // Disable everything so the same report cannot be sent twice.
    form.querySelectorAll('input, select, textarea, button')
      .forEach((control) => { control.disabled = true; });
  }
}

function initForm() {
  const form = document.getElementById('report-form');
  const button = document.getElementById('submit-button');
  if (!form || !button) return;

  let isSubmitting = false;

  form.addEventListener('submit', async (event) => {
    // Handle it here; never let the browser reload the page.
    event.preventDefault();

    // Second guard behind the disabled button — a fast double-tap can fire
    // twice before the attribute lands.
    if (isSubmitting) return;

    clearBanner();
    clearAllFieldErrors();

    const type = document.getElementById('type').value;
    const description = document.getElementById('description').value.trim();
    const anonymous = document.getElementById('anonymous').checked;

    const errors = validate({ type, description });
    if (errors.length > 0) {
      errors.forEach(({ field, message }) => setFieldError(field, message));
      const first = document.getElementById(errors[0].field);
      if (first) first.focus();
      return;
    }

    // Location is still being fetched, and they have not opted out.
    if (capturedLocation.status === 'pending' && !submitWithoutLocation) {
      showBanner(
        'error',
        'Still finding your location',
        'Give it a moment, then send again. If it does not arrive you can '
        + 'choose to send without it.',
      );
      return;
    }

    // Location failed and they have not chosen either option yet.
    if (capturedLocation.status !== 'found' && !submitWithoutLocation) {
      showBanner(
        'error',
        'No location yet',
        'Tick "Send this report without my location" to continue, or try '
        + 'finding it again.',
      );
      return;
    }

    isSubmitting = true;
    setLoading(button, true);

    try {
      // api.js sends exactly the six contract fields and adds the auth header.
      // Empty description is null, never "" — contract convention.
      const incident = await submitIncident({
        type,
        description: description === '' ? null : description,
        latitude: capturedLocation.latitude,
        longitude: capturedLocation.longitude,
        accuracy: capturedLocation.accuracy,
        anonymous,
      });

      showConfirmation(incident, button);
    } catch (err) {
      // An expired token is not something the student can fix on this form.
      // Send them to sign in again rather than showing an error they cannot
      // act on. The typed report is lost, which is bad, but a token good for
      // eight hours expiring mid-form is rare.
      if (err instanceof ApiError && err.status === 401) {
        logout();
        window.location.replace(LOGIN_URL);
        return;
      }

      if (err instanceof ApiError) {
        // `field` names the input at fault, so mark it as well as showing the
        // banner — otherwise the student has to guess which box is wrong.
        if (err.field) {
          setFieldError(err.field, err.message);
          const input = document.getElementById(err.field);
          if (input) input.focus();
        }
        showBanner('error', 'Could not send your report', err.message);
      } else {
        // A bug in our own code. Log it for us, show something honest to them.
        console.error('Unexpected error submitting incident:', err);
        showBanner(
          'error',
          'Could not send your report',
          'Something went wrong on this page. Please try again.',
        );
      }

      setLoading(button, false);
      isSubmitting = false;
    }
  });
}

// ---------------------------------------------------------------------------
// Start up
// ---------------------------------------------------------------------------
//
// Module scripts are deferred, so the DOM is parsed by the time this runs.

// Guard first. POST /api/incidents is a student-role endpoint, so a signed-out
// visitor cannot file anything. Better to send them to sign in now than to let
// them type a report and lose it at submit.
//
// Reporting anonymously still needs an account. `anonymous` withholds the
// reporter's identity from responders; it does not make the request itself
// unauthenticated. Contract section 9 has that as an open question.
if (!isLoggedIn()) {
  window.location.replace(LOGIN_URL);
} else {
  initDescriptionCounter();
  initTypeBehaviour();
  initForm();
  requestLocation();
}

// ---------------------------------------------------------------------------
// NOTES FOR THE TEAM
//
// 1. The coordinates question is settled. Contract v0.2 (PR #4) makes
//    `latitude` and `longitude` optional and adds `locationSource`, which the
//    server sets to "device" or "none" from the coordinates it received. A
//    report with no location is accepted and routed to campus control for
//    manual triage instead of being auto-assigned. showConfirmation() reads
//    that field and tells the student when no location went with the report.
//
// 2. Both checkboxes now use the .checkbox class from styles.css, which sets
//    accent-color and makes the whole row a 44px touch target. It is still
//    the browser's native control underneath, so focus and high-contrast mode
//    keep working.
// ---------------------------------------------------------------------------
