// auth.js — behaviour for login.html and register.html.
//
// One module serves both pages. It looks for #login-form or #register-form
// and wires up whichever one is on the page, so both HTML files load the same
// script tag and neither needs its own file.
//
// This file never calls fetch(). Every network call goes through api.js —
// that is the project rule, and it is why the token handling below is a
// one-liner rather than something we repeat on every page.
//
// XSS rule, no exceptions: nothing here ever assigns innerHTML. Server
// messages, student names and email addresses are all attacker-influenced
// text. They go on the page with textContent, which writes text as text — a
// description containing <script> is displayed, not executed. See the comment
// above TOKEN_KEY in api.js for why that matters so much when the token lives
// in localStorage.

import { register, login, ApiError } from './api.js';

// Where a successful sign-in lands. Only the student workspace exists today;
// other roles get a role-aware holding page instead of seeing student-only UI.
const ROLE_HOME_URLS = {
  student: './dashboard.html',
  responder: './responder-dashboard.html',
  campus_control: './control-dashboard.html',
  gbv_officer: './gbv-officer.html',
  admin: './gbv-officer.html',
};

function homeUrlForRole(role) {
  return ROLE_HOME_URLS[role] ?? './role-dashboard.html';
}

// Minimum password length we enforce in the browser.
//
// IMPORTANT: this is a convenience check, not a security control. The backend
// validates independently (contract section 2 returns 400 for "password too
// short") because anything in the browser can be bypassed. If the backend
// picks a different minimum, change this number to match so a student is not
// told 8 is fine and then rejected by the server.
const MIN_PASSWORD_LENGTH = 8;

// ---------------------------------------------------------------------------
// Small DOM helpers
// ---------------------------------------------------------------------------

/**
 * Shows a message in the page-level banner slot.
 *
 * Builds the element fresh each time rather than toggling a hidden one:
 * .banner sets display:flex in styles.css, and an author rule beats the
 * browser's built-in [hidden] { display: none }, so a hidden attribute would
 * silently do nothing. Adding and removing the node sidesteps that entirely.
 *
 * @param {'error'|'success'} kind
 * @param {string} title    Short heading, written by us.
 * @param {string} message  Body text, often from the server.
 */
function showBanner(kind, title, message) {
  const slot = document.getElementById('banner-slot');
  if (!slot) return;

  clearBanner();

  const banner = document.createElement('div');
  banner.className = kind === 'error' ? 'banner banner-error' : 'banner banner-success';
  // role="alert" makes a screen reader announce this the moment it appears,
  // which matters when the student is not looking at the top of the page.
  banner.setAttribute('role', 'alert');

  const body = document.createElement('div');

  const heading = document.createElement('span');
  heading.className = 'banner-title';
  heading.textContent = title;

  const text = document.createElement('span');
  // textContent, never innerHTML — this string comes from the server.
  text.textContent = message;

  body.appendChild(heading);
  body.appendChild(text);
  banner.appendChild(body);
  slot.appendChild(banner);

  // The banner is above the form, so on a phone it may be off screen when a
  // long form fails validation. Bring it into view.
  banner.scrollIntoView({ block: 'nearest' });
}

/** Removes the banner if one is showing. */
function clearBanner() {
  const slot = document.getElementById('banner-slot');
  if (slot) slot.replaceChildren();
}

/**
 * Marks one input invalid and writes the message underneath it, using the
 * .field-error pattern from styles.css. The ⚠ glyph comes from CSS, so the
 * message is not identified by colour alone.
 *
 * @param {string} inputId  The id of the input, which matches the contract's
 *                          `field` value on a validation error.
 * @param {string} message
 */
function setFieldError(inputId, message) {
  const input = document.getElementById(inputId);
  if (!input) return;

  const field = input.closest('.field');
  if (!field) return;

  // Do not stack two errors on the same input.
  clearFieldError(inputId);

  input.classList.add('input-invalid');
  input.setAttribute('aria-invalid', 'true');

  const error = document.createElement('p');
  error.className = 'field-error';
  error.id = `${inputId}-error`;
  error.textContent = message;

  // Tie the message to the input so a screen reader reads them together.
  input.setAttribute('aria-describedby', error.id);

  field.appendChild(error);
}

/** Clears the invalid state and message for one input. */
function clearFieldError(inputId) {
  const input = document.getElementById(inputId);
  if (!input) return;

  input.classList.remove('input-invalid');
  input.removeAttribute('aria-invalid');
  input.removeAttribute('aria-describedby');

  const existing = document.getElementById(`${inputId}-error`);
  if (existing) existing.remove();
}

/** Clears every field error on a form. Call before re-validating. */
function clearAllFieldErrors(form) {
  form.querySelectorAll('.input').forEach((input) => clearFieldError(input.id));
}

/**
 * Puts the submit button into or out of its loading state.
 *
 * Disabling it is what stops a double-click sending two registrations. The
 * .btn:disabled rule in styles.css handles the appearance; the spinner gives
 * a visible "something is happening", and the visually-hidden text gives a
 * screen reader the same information.
 *
 * @param {HTMLButtonElement} button
 * @param {boolean} isLoading
 * @param {string} loadingLabel  e.g. 'Signing in…'
 * @param {string} idleLabel     e.g. 'Sign in'
 */
function setLoading(button, isLoading, loadingLabel, idleLabel) {
  button.disabled = isLoading;

  if (!isLoading) {
    button.textContent = idleLabel;
    return;
  }

  button.replaceChildren();

  const spinner = document.createElement('span');
  spinner.className = 'spinner';

  const label = document.createElement('span');
  label.textContent = loadingLabel;

  button.appendChild(spinner);
  button.appendChild(label);
}

/** Reads an input and trims surrounding whitespace. */
function valueOf(id) {
  const input = document.getElementById(id);
  return input ? input.value.trim() : '';
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------
//
// Deliberately forgiving. The job of these checks is to save the student a
// round trip on an obvious typo, not to be the authority on what is valid —
// the backend decides that, and validates again regardless (project rule:
// validate server-side even if the frontend already validated).
//
// A too-strict rule here is worse than a loose one: it locks a legitimate
// student out of a safety app, and they cannot argue with it.

/**
 * Is this plausibly an email address?
 *
 * Intentionally not an RFC 5322 regex. Those are hundreds of characters long,
 * nobody on the team can maintain them, and they still get edge cases wrong.
 * "Something, then @, then something with a dot" catches real typos, which is
 * all a client-side check should try to do.
 */
function looksLikeEmail(value) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
}

/**
 * Validates the login form.
 * @returns {Array<{field: string, message: string}>} Empty when valid.
 */
function validateLogin({ email, password }) {
  const errors = [];

  if (!email) {
    errors.push({ field: 'email', message: 'Enter your email address.' });
  } else if (!looksLikeEmail(email)) {
    errors.push({ field: 'email', message: 'That does not look like an email address.' });
  }

  if (!password) {
    errors.push({ field: 'password', message: 'Enter your password.' });
  }

  // Note we do NOT check password length when signing in. An existing account
  // may predate a rule change, and telling someone their password is "too
  // short" at the login screen leaks how long it is.

  return errors;
}

/**
 * Validates the registration form.
 * @returns {Array<{field: string, message: string}>} Empty when valid.
 */
function validateRegister({ studentNumber, fullName, email, password, phone }) {
  const errors = [];

  if (!studentNumber) {
    errors.push({ field: 'studentNumber', message: 'Enter your student number.' });
  } else if (!/^\d+$/.test(studentNumber)) {
    // Digits-only is the one rule we can be confident about from the contract's
    // example ("202512345"). We deliberately do NOT check the length here —
    // see the note at the bottom of this file, this is an open question for
    // the backend team.
    errors.push({ field: 'studentNumber', message: 'Student number should contain digits only.' });
  }

  if (!fullName) {
    errors.push({ field: 'fullName', message: 'Enter your full name.' });
  }

  if (!email) {
    errors.push({ field: 'email', message: 'Enter your email address.' });
  } else if (!looksLikeEmail(email)) {
    errors.push({ field: 'email', message: 'That does not look like an email address.' });
  }

  if (!password) {
    errors.push({ field: 'password', message: 'Choose a password.' });
  } else if (password.length < MIN_PASSWORD_LENGTH) {
    errors.push({
      field: 'password',
      message: `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`,
    });
  }

  if (!phone) {
    errors.push({ field: 'phone', message: 'Enter a phone number.' });
  } else if (!/^\+?[\d\s-]{9,15}$/.test(phone)) {
    errors.push({ field: 'phone', message: 'That does not look like a phone number.' });
  }

  return errors;
}

/**
 * Paints a list of validation errors onto the form and focuses the first one,
 * so a student on a phone is taken straight to the field that needs fixing.
 */
function showValidationErrors(errors) {
  errors.forEach(({ field, message }) => setFieldError(field, message));

  const first = document.getElementById(errors[0].field);
  if (first) first.focus();
}

/**
 * Turns a failed request into something the student can read.
 *
 * api.js throws an ApiError carrying the server's standard error shape
 * { error, message, field }. When `field` is set it names the input at fault,
 * so we mark that input as well as showing the banner — otherwise the student
 * has to guess which of five boxes is wrong.
 */
function handleRequestFailure(err, bannerTitle) {
  if (err instanceof ApiError) {
    if (err.field) {
      setFieldError(err.field, err.message);
      const input = document.getElementById(err.field);
      if (input) input.focus();
    }
    showBanner('error', bannerTitle, err.message);
    return;
  }

  // Not an ApiError — a genuine bug in our own code. Do not show the raw
  // exception to a student; log it for us and show something honest.
  console.error('Unexpected error during authentication:', err);
  showBanner('error', bannerTitle, 'Something went wrong on this page. Please try again.');
}

// ---------------------------------------------------------------------------
// Login page
// ---------------------------------------------------------------------------

function initLoginForm(form) {
  const button = document.getElementById('submit-button');
  let isSubmitting = false;

  form.addEventListener('submit', async (event) => {
    // Stop the browser reloading the page. Everything happens in JS.
    event.preventDefault();

    // Second guard behind the disabled button: a fast double-tap on a phone
    // can fire twice before the disabled attribute is applied.
    if (isSubmitting) return;

    clearBanner();
    clearAllFieldErrors(form);

    const email = valueOf('email');
    // Password is NOT trimmed — a leading or trailing space may be a
    // deliberate part of it, and silently removing it would lock the student
    // out of their own account.
    const password = document.getElementById('password').value;

    const errors = validateLogin({ email, password });
    if (errors.length > 0) {
      showValidationErrors(errors);
      return;
    }

    isSubmitting = true;
    setLoading(button, true, 'Signing in…', 'Sign in');

    try {
      // api.js stores the JWT itself on a successful login, so there is
      // nothing to save here. That is the whole point of keeping token
      // handling in one file.
      const result = await login(email, password);
      window.location.href = homeUrlForRole(result?.user?.role);
    } catch (err) {
      handleRequestFailure(err, 'Could not sign in');
      setLoading(button, false, 'Signing in…', 'Sign in');
      isSubmitting = false;
    }

    // Note there is no `finally` that re-enables the button. On success we are
    // navigating away, and re-enabling it would let an impatient student fire
    // a second login during the redirect.
  });
}

// ---------------------------------------------------------------------------
// Register page
// ---------------------------------------------------------------------------

function initRegisterForm(form) {
  const button = document.getElementById('submit-button');
  let isSubmitting = false;

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (isSubmitting) return;

    clearBanner();
    clearAllFieldErrors(form);

    const studentNumber = valueOf('studentNumber');
    const fullName = valueOf('fullName');
    const email = valueOf('email');
    const password = document.getElementById('password').value;
    const phone = valueOf('phone');

    const errors = validateRegister({ studentNumber, fullName, email, password, phone });
    if (errors.length > 0) {
      showValidationErrors(errors);
      return;
    }

    isSubmitting = true;
    setLoading(button, true, 'Creating account…', 'Create account');

    try {
      // Argument order matches api.js:
      // register(studentNumber, fullName, email, password, phone)
      await register(studentNumber, fullName, email, password, phone);

      // POST /api/auth/register returns { userId, fullName, role } — no token
      // (contract section 2). So registering does not sign you in. To land the
      // student on the dashboard we log in immediately with the credentials
      // they just typed, which is what stores the JWT.
      const result = await login(email, password);
      window.location.href = homeUrlForRole(result?.user?.role);
    } catch (err) {
      // If the account was created but the follow-up login failed, sending
      // them to the login page is better than saying registration failed —
      // it did not, and telling them it did would have them register twice.
      if (err instanceof ApiError && err.status === 401) {
        window.location.href = './login.html';
        return;
      }

      handleRequestFailure(err, 'Could not create your account');
      setLoading(button, false, 'Creating account…', 'Create account');
      isSubmitting = false;
    }
  });
}

// ---------------------------------------------------------------------------
// Start up
// ---------------------------------------------------------------------------
//
// A module script is deferred by default, so the DOM is already parsed by the
// time this runs — no DOMContentLoaded listener needed.

const loginForm = document.getElementById('login-form');
if (loginForm) initLoginForm(loginForm);

const registerForm = document.getElementById('register-form');
if (registerForm) initRegisterForm(registerForm);

// ---------------------------------------------------------------------------
// OPEN QUESTION for the backend team — please confirm and then delete this
// note once the answer is recorded in docs/api-contract.md.
//
// What exactly is a valid student number? The contract shows "202512345",
// which is 9 digits, but it does not state the rule. Right now this file only
// checks "digits only" rather than guessing a length, because a wrong length
// rule would lock out legitimate students. Once the format is confirmed, add
// the length check to validateRegister().
// ---------------------------------------------------------------------------
