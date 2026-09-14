import { getCurrentUser, getHotspots, getRecentPatrols, getSafeRoute, isLoggedIn, logout, ApiError } from './api.js';

const LOGIN_URL = './login.html';
const CAMPUS_CENTER = [-32.78331, 26.84971];
const mapElement = document.getElementById('safety-map');
const routeStatus = document.getElementById('route-status');
const routeSummary = document.getElementById('route-summary');
let map;
let routeLine;
let origin;

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

function redirectToLogin() {
  logout();
  window.location.replace(LOGIN_URL);
}

function validCoordinate(value) {
  return Number.isFinite(value);
}

function addHotspots(items) {
  items.forEach((hotspot) => {
    if (!validCoordinate(hotspot.latitude) || !validCoordinate(hotspot.longitude)) return;
    const radius = Number.isFinite(hotspot.radiusMetres) ? hotspot.radiusMetres : 100;
    window.L.circle([hotspot.latitude, hotspot.longitude], {
      radius,
      color: '#b3001b',
      fillColor: '#fdecee',
      fillOpacity: 0.55,
    }).addTo(map).bindPopup(`${hotspot.name ?? 'Risk area'}: ${hotspot.riskLevel ?? 'unknown'} risk`);
  });
}

function addPatrols(items) {
  items.forEach((patrol) => {
    if (!validCoordinate(patrol.latitude) || !validCoordinate(patrol.longitude)) return;
    window.L.marker([patrol.latitude, patrol.longitude])
      .addTo(map)
      .bindPopup(`Recent patrol: ${patrol.zoneName ?? 'Unknown zone'}`);
  });
}

function setRouteSummary(route) {
  routeSummary.replaceChildren();
  const text = document.createElement('p');
  const distance = Number.isFinite(route?.distanceMetres) ? `${route.distanceMetres} m` : 'Distance unavailable';
  const minutes = Number.isFinite(route?.estimatedSeconds)
    ? `${Math.ceil(route.estimatedSeconds / 60)} min`
    : 'time unavailable';
  const safety = Number.isFinite(route?.safetyScore) ? `Safety score: ${route.safetyScore}` : 'Safety score unavailable';
  text.textContent = `${distance} · ${minutes}. ${safety}`;
  routeSummary.appendChild(text);
}

async function requestRoute(destination) {
  if (!origin) {
    routeStatus.textContent = 'Use your location before choosing a destination.';
    return;
  }

  routeStatus.textContent = 'Finding a safer route...';
  try {
    const route = await getSafeRoute(origin, destination);
    if (!Array.isArray(route?.points) || route.points.length < 2) {
      routeStatus.textContent = 'No route was returned.';
      return;
    }
    if (routeLine) routeLine.remove();
    routeLine = window.L.polyline(route.points.map((point) => [point.latitude, point.longitude]), {
      color: '#00447c',
      weight: 5,
    }).addTo(map);
    map.fitBounds(routeLine.getBounds(), { padding: [24, 24] });
    routeStatus.textContent = 'Safer route found.';
    setRouteSummary(route);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    routeStatus.textContent = error instanceof ApiError ? error.message : 'Could not calculate a route.';
  }
}

async function loadMapData() {
  if (!isLoggedIn()) {
    redirectToLogin();
    return;
  }
  if (!window.L) {
    showBanner('Map unavailable', 'The map provider could not be loaded.');
    return;
  }

  map = window.L.map(mapElement).setView(CAMPUS_CENTER, 16);
  window.L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors',
    maxZoom: 19,
  }).addTo(map);
  map.on('click', (event) => requestRoute({ latitude: event.latlng.lat, longitude: event.latlng.lng }));

  try {
    const [hotspots, patrols] = await Promise.all([
      getHotspots(),
      getRecentPatrols(CAMPUS_CENTER[0], CAMPUS_CENTER[1]),
    ]);
    addHotspots(Array.isArray(hotspots?.items) ? hotspots.items : []);
    addPatrols(Array.isArray(patrols?.items) ? patrols.items : []);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    showBanner('Could not load map data', error instanceof ApiError ? error.message : 'Please refresh and try again.');
  }
}

document.getElementById('signout-button').addEventListener('click', redirectToLogin);
document.getElementById('location-button').addEventListener('click', () => {
  if (!navigator.geolocation) {
    routeStatus.textContent = 'Location is not available in this browser.';
    return;
  }
  routeStatus.textContent = 'Requesting your location...';
  navigator.geolocation.getCurrentPosition(
    ({ coords }) => {
      origin = { latitude: coords.latitude, longitude: coords.longitude };
      window.L.marker([coords.latitude, coords.longitude]).addTo(map).bindPopup('Your location').openPopup();
      map.setView([coords.latitude, coords.longitude], 16);
      routeStatus.textContent = 'Location set. Choose a destination on the map.';
    },
    () => { routeStatus.textContent = 'Location was unavailable. Choose a destination after trying again.'; },
    { enableHighAccuracy: true, timeout: 10000 },
  );
});

loadMapData();