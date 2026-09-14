import { createWellnessBooking, getCurrentUser, getWellnessResources, isLoggedIn, logout, ApiError } from './api.js';

const LOGIN_URL = './login.html';
const list = document.getElementById('resource-list');
const message = document.getElementById('wellness-message');
const bookingSection = document.getElementById('booking-section');
const resourceSelect = document.getElementById('resource-id');

function showBanner(title, text, kind = 'error') {
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
  const textNode = document.createElement('span');
  textNode.textContent = text;
  body.appendChild(textNode);
  banner.appendChild(body);
  slot.replaceChildren(banner);
}

function redirectToLogin() {
  logout();
  window.location.replace(LOGIN_URL);
}

function renderResources(resources) {
  list.setAttribute('aria-busy', 'false');
  list.replaceChildren();
  resourceSelect.replaceChildren();
  if (resources.length === 0) {
    list.appendChild(Object.assign(document.createElement('p'), { className: 'empty-state', textContent: 'No wellness resources are available.' }));
    return;
  }

  resources.forEach((resource) => {
    const card = document.createElement('article');
    card.className = 'card';
    const title = document.createElement('h3');
    title.textContent = resource.title ?? 'Unnamed resource';
    card.appendChild(title);
    const description = document.createElement('p');
    description.className = 'text-meta';
    description.textContent = resource.description ?? 'Description unavailable.';
    card.appendChild(description);
    const availability = document.createElement('p');
    availability.textContent = resource.availability ?? 'Availability unavailable.';
    card.appendChild(availability);
    if (resource.contactPhone) {
      const phone = document.createElement('a');
      phone.href = `tel:${encodeURIComponent(resource.contactPhone)}`;
      phone.textContent = resource.contactPhone;
      card.appendChild(phone);
    }
    list.appendChild(card);

    const option = document.createElement('option');
    option.value = resource.resourceId;
    option.textContent = resource.title ?? `Resource ${resource.resourceId}`;
    resourceSelect.appendChild(option);
  });
}

async function load() {
  if (!isLoggedIn()) {
    redirectToLogin();
    return;
  }
  try {
    const [user, result] = await Promise.all([getCurrentUser(), getWellnessResources()]);
    const resources = Array.isArray(result?.items) ? result.items : [];
    renderResources(resources);
    message.textContent = `${resources.length} support resource${resources.length === 1 ? '' : 's'} available.`;
    if (user?.role === 'student' && resources.length > 0) bookingSection.hidden = false;
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    list.setAttribute('aria-busy', 'false');
    message.textContent = 'Wellness resources could not be loaded.';
    list.replaceChildren(Object.assign(document.createElement('p'), { className: 'empty-state', textContent: error instanceof ApiError ? error.message : 'Please refresh and try again.' }));
  }
}

document.getElementById('signout-button').addEventListener('click', redirectToLogin);
document.getElementById('booking-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = document.getElementById('booking-button');
  const date = document.getElementById('preferred-date').value;
  const slot = document.getElementById('preferred-slot').value;
  if (!resourceSelect.value || !date || !slot) {
    showBanner('More information needed', 'Choose a resource, date, and time slot.');
    return;
  }
  button.disabled = true;
  try {
    const result = await createWellnessBooking(Number(resourceSelect.value), date, slot, document.getElementById('booking-note').value.trim() || null);
    showBanner('Booking requested', `Booking ${result?.bookingId ?? ''} is ${result?.status ?? 'requested'}.`, 'success');
    event.target.reset();
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner('Could not request booking', error instanceof ApiError ? error.message : 'Please try again.');
  } finally {
    button.disabled = false;
  }
});

load();