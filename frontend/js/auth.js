// auth.js — behaviour for login.html, register.html, staff-login.html,
// forgot-password.html, and reset-password.html.
//
// One module serves all authentication flows. It wires up #login-form,
// #staff-login-form, #register-form, #forgot-password-form, or
// #reset-password-form depending on which is present on the page.
//
// This file never calls fetch(). Every network call goes through api.js —
// that is the project rule, and it is why token and session handling are
// centralized rather than repeated across pages.
//
// XSS rule, no exceptions: nothing here ever assigns innerHTML. Server
// messages, student names and email addresses are all attacker-influenced
// text. They go on the page with textContent.

import { register, login, logout, ApiError, requestPasswordReset, resetPassword } from './api.js';

// Where a successful sign-in lands based on user role.
const ROLE_HOME_URLS = {
  student: './dashboard.html',
  responder: './responder-dashboard.html',
  campus_control: './control-dashboard.html',
  gbv_officer: './gbv-officer.html',
  admin: './control-dashboard.html',
};

function homeUrlForRole(role) {
  return ROLE_HOME_URLS[role] ?? './dashboard.html';
}

const MIN_PASSWORD_LENGTH = 8;

// Student self-service (login.html, register.html) is restricted to real
// university addresses. Staff accounts are provisioned by an admin, not
// self-registered, so staff-login.html deliberately does not use this check -
// applying it there would also lock out the seeded demo staff accounts,
// which use @example.ac.za on purpose (see docs/development-challenges.md).
const UFH_EMAIL_DOMAIN = '@ufh.ac.za';

function isUfhEmail(value) {
  return value.toLowerCase().endsWith(UFH_EMAIL_DOMAIN);
}

// ---------------------------------------------------------------------------
// Small DOM helpers
// ---------------------------------------------------------------------------

/**
 * Shows a message in the page-level banner slot.
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
  banner.setAttribute('role', 'alert');

  const body = document.createElement('div');

  const heading = document.createElement('span');
  heading.className = 'banner-title';
  heading.textContent = title;

  const text = document.createElement('span');
  text.textContent = message;

  body.appendChild(heading);
  body.appendChild(text);
  banner.appendChild(body);
  slot.appendChild(banner);

  banner.scrollIntoView({ block: 'nearest' });
}

/** Removes the banner if one is showing. */
function clearBanner() {
  const slot = document.getElementById('banner-slot');
  if (slot) slot.replaceChildren();
}

/**
 * Marks one input invalid and writes the message underneath it.
 */
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

function looksLikeEmail(value) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
}

function validateLogin({ email, password }, { requireUfhDomain = false } = {}) {
  const errors = [];

  if (!email) {
    errors.push({ field: 'email', message: 'Enter your email address.' });
  } else if (!looksLikeEmail(email)) {
    errors.push({ field: 'email', message: 'That does not look like an email address.' });
  } else if (requireUfhDomain && !isUfhEmail(email)) {
    errors.push({ field: 'email', message: `Use your ${UFH_EMAIL_DOMAIN} student email.` });
  }

  if (!password) {
    errors.push({ field: 'password', message: 'Enter your password.' });
  }

  return errors;
}

function validateRegister({ studentNumber, fullName, email, password, phone }) {
  const errors = [];

  if (!studentNumber) {
    errors.push({ field: 'studentNumber', message: 'Enter your student number.' });
  } else if (!/^\d+$/.test(studentNumber)) {
    errors.push({ field: 'studentNumber', message: 'Student number should contain digits only.' });
  }

  if (!fullName) {
    errors.push({ field: 'fullName', message: 'Enter your full name.' });
  }

  if (!email) {
    errors.push({ field: 'email', message: 'Enter your email address.' });
  } else if (!looksLikeEmail(email)) {
    errors.push({ field: 'email', message: 'That does not look like an email address.' });
  } else if (!isUfhEmail(email)) {
    errors.push({ field: 'email', message: `Use your ${UFH_EMAIL_DOMAIN} student email.` });
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

function showValidationErrors(errors) {
  errors.forEach(({ field, message }) => setFieldError(field, message));
  const first = document.getElementById(errors[0].field);
  if (first) first.focus();
}

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

  console.error('Unexpected error during authentication:', err);
  showBanner('error', bannerTitle, 'Something went wrong on this page. Please try again.');
}

// ---------------------------------------------------------------------------
// Login page (Student)
// ---------------------------------------------------------------------------

function initLoginForm(form) {
  const button = document.getElementById('submit-button');
  let isSubmitting = false;

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (isSubmitting) return;

    clearBanner();
    clearAllFieldErrors(form);

    const email = valueOf('email');
    const password = document.getElementById('password').value;

    const errors = validateLogin({ email, password }, { requireUfhDomain: true });
    if (errors.length > 0) {
      showValidationErrors(errors);
      return;
    }

    isSubmitting = true;
    setLoading(button, true, 'Signing in…', 'Sign in');

    try {
      const result = await login(email, password, 'student');
      window.location.href = homeUrlForRole(result?.user?.role);
    } catch (err) {
      handleRequestFailure(err, 'Could not sign in');
      setLoading(button, false, 'Signing in…', 'Sign in');
      isSubmitting = false;
    }
  });
}

// ---------------------------------------------------------------------------
// Staff Login page (Campus Control / GBV Officer)
// ---------------------------------------------------------------------------

function initStaffLoginForm(form) {
  const button = document.getElementById('staff-submit-button');
  let isSubmitting = false;

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (isSubmitting) return;

    clearBanner();
    clearAllFieldErrors(form);

    const email = valueOf('staff-email');
    const password = document.getElementById('staff-password').value;
    const errors = validateLogin({ email, password })
      .map((error) => ({ ...error, field: `staff-${error.field}` }));
    if (errors.length > 0) {
      showValidationErrors(errors);
      return;
    }

    const requestedRole = document.querySelector('input[name="staff-role"]:checked')?.value || 'campus_control';
    isSubmitting = true;
    setLoading(button, true, 'Checking access…', 'Sign in to staff portal');

    try {
      // Pass requestedRole so mock mode signs in as the chosen role cleanly
      const result = await login(email, password, requestedRole);
      const actualRole = result?.user?.role;
      if (!['campus_control', 'gbv_officer', 'responder', 'admin'].includes(actualRole)
          || (requestedRole && actualRole !== requestedRole && actualRole !== 'admin')) {
        logout();
        showBanner('error', 'Staff access not available', 'Choose the access area that matches your account, or contact an administrator.');
        setLoading(button, false, 'Checking access…', 'Sign in to staff portal');
        isSubmitting = false;
        return;
      }
      window.location.href = homeUrlForRole(actualRole);
    } catch (err) {
      handleRequestFailure(err, 'Could not sign in to staff portal');
      setLoading(button, false, 'Checking access…', 'Sign in to staff portal');
      isSubmitting = false;
    }
  });
}

// ---------------------------------------------------------------------------
// Register page (Student self-registration)
// ---------------------------------------------------------------------------

// The note is context for a responder in the middle of an emergency, not a
// medical file. Capped well short of the textarea's own maxlength so the
// count and the actual hard limit never disagree.
const HEALTH_NOTE_MAX_LENGTH = 200;

function initHealthNoteCounter(form) {
  const note = document.getElementById('healthNote');
  const count = document.getElementById('health-note-count');
  if (!note || !count) return;

  const update = () => {
    count.textContent = `${note.value.length} / ${HEALTH_NOTE_MAX_LENGTH} characters`;
  };
  note.addEventListener('input', update);
  update();
}

function collectHealthInfo(form) {
  const conditions = Array.from(form.querySelectorAll('input[name="healthCondition"]:checked'))
    .map((input) => input.value);
  const note = valueOf('healthNote');

  if (conditions.length === 0 && !note) return null;
  return { conditions, note: note || null };
}

function initRegisterForm(form) {
  const button = document.getElementById('submit-button');
  let isSubmitting = false;

  initHealthNoteCounter(form);

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
    const healthInfo = collectHealthInfo(form);

    const errors = validateRegister({ studentNumber, fullName, email, password, phone });
    if (errors.length > 0) {
      showValidationErrors(errors);
      return;
    }

    isSubmitting = true;
    setLoading(button, true, 'Creating account…', 'Create account');

    try {
      await register(studentNumber, fullName, email, password, phone, healthInfo);
      const result = await login(email, password, 'student');
      window.location.href = homeUrlForRole(result?.user?.role);
    } catch (err) {
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
// Forgot password page
// ---------------------------------------------------------------------------

function initForgotPasswordForm(form) {
  const button = document.getElementById('submit-button');
  let isSubmitting = false;

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (isSubmitting) return;

    clearBanner();
    clearAllFieldErrors(form);

    const email = valueOf('email');
    // Domain-restricted like login/register: this system only issues resets
    // for its own accounts, and every real account is a @ufh.ac.za address.
    const errors = validateLogin({ email, password: 'placeholder' }, { requireUfhDomain: true })
      .filter((error) => error.field === 'email');
    if (errors.length > 0) {
      showValidationErrors(errors);
      return;
    }

    isSubmitting = true;
    setLoading(button, true, 'Sending…', 'Send reset link');

    try {
      const result = await requestPasswordReset(email);
      // Same message shown regardless of whether the email is registered -
      // that's the whole point, see the field hint in the response shape.
      form.reset();
      showBanner('success', 'Check your email',
        result?.message ?? "If that email is registered, we've sent a reset link.");
    } catch (err) {
      handleRequestFailure(err, 'Could not send the reset link');
    } finally {
      setLoading(button, false, 'Sending…', 'Send reset link');
      isSubmitting = false;
    }
  });
}

// ---------------------------------------------------------------------------
// Reset password page
// ---------------------------------------------------------------------------

function initResetPasswordForm(form) {
  const button = document.getElementById('submit-button');
  const context = document.getElementById('reset-context');
  let isSubmitting = false;

  const token = new URLSearchParams(window.location.search).get('token');
  if (context) {
    context.textContent = token
      ? 'Enter a new password below.'
      : "This link is missing its token, it won't work. Request a new one from the forgot password page.";
  }
  if (!token) {
    button.disabled = true;
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (isSubmitting || !token) return;

    clearBanner();
    clearAllFieldErrors(form);

    const password = document.getElementById('password').value;
    const confirmPassword = document.getElementById('confirmPassword').value;

    const errors = [];
    if (!password) {
      errors.push({ field: 'password', message: 'Choose a new password.' });
    } else if (password.length < MIN_PASSWORD_LENGTH) {
      errors.push({ field: 'password', message: `Password must be at least ${MIN_PASSWORD_LENGTH} characters.` });
    }
    if (password && confirmPassword !== password) {
      errors.push({ field: 'confirmPassword', message: 'Passwords do not match.' });
    }
    if (errors.length > 0) {
      showValidationErrors(errors);
      return;
    }

    isSubmitting = true;
    setLoading(button, true, 'Updating…', 'Set new password');

    try {
      await resetPassword(token, password);
      showBanner('success', 'Password updated', 'Sign in with your new password.');
      form.reset();
      button.disabled = true;
    } catch (err) {
      handleRequestFailure(err, 'Could not update your password');
      setLoading(button, false, 'Updating…', 'Set new password');
      isSubmitting = false;
    }
  });
}

// ---------------------------------------------------------------------------
// Start up
// ---------------------------------------------------------------------------

const loginForm = document.getElementById('login-form');
if (loginForm) initLoginForm(loginForm);

const staffLoginForm = document.getElementById('staff-login-form');
if (staffLoginForm) initStaffLoginForm(staffLoginForm);

const forgotPasswordForm = document.getElementById('forgot-password-form');
if (forgotPasswordForm) initForgotPasswordForm(forgotPasswordForm);

const resetPasswordForm = document.getElementById('reset-password-form');
if (resetPasswordForm) initResetPasswordForm(resetPasswordForm);

const registerForm = document.getElementById('register-form');
if (registerForm) initRegisterForm(registerForm);
