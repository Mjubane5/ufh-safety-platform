import {
  getCurrentUser,
  getHotspots,
  getRecentPatrols,
  getSafeRoute,
  startSafeWalk,
  updateSafeWalkLocation,
  markSafeWalkArrived,
  cancelSafeWalk,
  isLoggedIn,
  logout,
  ApiError,
} from './api.js';
import { calculateDistanceMetres } from './tracking.js';

// Shared by students, campus_control and responders (see shell.js's
// NAV_BY_ROLE) - a single hardcoded login page here sent staff straight to
// the student form, which cannot sign a staff account back in at all
// (docs/api-contract.md's two-step flow is student-only). redirectToLogin()
// below picks the right one from logout()'s own return value instead.
const LOGIN_URL = './login.html';
const STAFF_LOGIN_URL = './staff-login.html';
const CAMPUS_CENTER = [-32.78331, 26.84971];
const ARRIVAL_RADIUS_METRES = 40;
const WALK_LOCATION_PUSH_MS = 5000; // matches the interval used everywhere else in this app
const mapElement = document.getElementById('safety-map');
const routeStatus = document.getElementById('route-status');
const routeSummary = document.getElementById('route-summary');
const walkStatusEl = document.getElementById('walk-status');
const walkRemainingEl = document.getElementById('walk-remaining');
const walkAccuracyEl = document.getElementById('walk-accuracy');
const startWalkButton = document.getElementById('start-walk-button');
const simulateWalkButton = document.getElementById('simulate-walk-button');
const arrivedButton = document.getElementById('arrived-button');
const cancelWalkButton = document.getElementById('cancel-walk-button');
let map;
let routeLine;
let origin;
let originMarker;
let destination;
let lastRoute;
let currentWalkId = null;
let walkWatchId = null;
let simulationTimer = null;
let lastLocationPushAt = 0;
let destinationReached = false;

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
  const lastRole = logout();
  window.location.replace(lastRole && lastRole !== 'student' ? STAFF_LOGIN_URL : LOGIN_URL);
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

async function requestRoute(chosenDestination) {
  if (!origin) {
    routeStatus.textContent = 'Use your location before choosing a destination.';
    return;
  }
  if (currentWalkId) {
    routeStatus.textContent = 'A Safe Walk is active. Cancel it before choosing another destination.';
    return;
  }

  routeStatus.textContent = 'Finding a safer route...';
  try {
    const route = await getSafeRoute(origin, chosenDestination);
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

    destination = chosenDestination;
    lastRoute = route;
    startWalkButton.disabled = false;
    simulateWalkButton.disabled = false;
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
      if (originMarker) map.removeLayer(originMarker);
      originMarker = window.L.marker([coords.latitude, coords.longitude]).addTo(map).bindPopup('Your location').openPopup();
      map.setView([coords.latitude, coords.longitude], 16);
      routeStatus.textContent = 'Location set. Choose a destination on the map.';
    },
    () => { routeStatus.textContent = 'Location was unavailable. Choose a destination after trying again.'; },
    { enableHighAccuracy: true, timeout: 10000 },
  );
});

// ---------------------------------------------------------------------------
// Safe Walk - live location sharing for a route already found above.
// Always a deliberate tap to start and to confirm arrival; live tracking
// stops the moment the student taps Arrived safe, cancels, or leaves this
// page - never left running in the background.
// ---------------------------------------------------------------------------

function resetWalkUi() {
  walkStatusEl.textContent = 'Not started';
  walkRemainingEl.textContent = '--';
  walkAccuracyEl.textContent = '--';
  startWalkButton.hidden = false;
  startWalkButton.disabled = !destination;
  simulateWalkButton.hidden = false;
  simulateWalkButton.disabled = !destination;
  arrivedButton.hidden = true;
  cancelWalkButton.hidden = true;
}

function stopWalkTracking() {
  if (walkWatchId !== null) {
    navigator.geolocation.clearWatch(walkWatchId);
    walkWatchId = null;
  }
  if (simulationTimer !== null) {
    clearInterval(simulationTimer);
    simulationTimer = null;
  }
}

async function handleWalkPosition(position) {
  const { latitude, longitude, accuracy } = position.coords;
  walkAccuracyEl.textContent = `${Math.round(accuracy)} m`;

  if (originMarker) originMarker.setLatLng([latitude, longitude]);

  const remaining = calculateDistanceMetres(latitude, longitude, destination.latitude, destination.longitude);
  walkRemainingEl.textContent = remaining < 1000 ? `${Math.round(remaining)} m` : `${(remaining / 1000).toFixed(2)} km`;

  if (remaining <= ARRIVAL_RADIUS_METRES && !destinationReached) {
    destinationReached = true;
    walkStatusEl.textContent = 'Destination reached - tap "Arrived safe"';
  }

  // Push to campus control roughly every 5s rather than on every GPS tick,
  // which can fire much more often than that.
  const now = Date.now();
  if (now - lastLocationPushAt < WALK_LOCATION_PUSH_MS) return;
  lastLocationPushAt = now;

  try {
    await updateSafeWalkLocation(currentWalkId, { latitude, longitude });
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      stopWalkTracking();
      redirectToLogin();
    }
    // Any other failure: keep walking, try again on the next tick.
  }
}

/** Starts the walk session server-side and puts the shared UI into "active" state. Returns the created walk. */
async function beginWalk() {
  const walk = await startSafeWalk({ origin, destination, route: lastRoute });
  currentWalkId = walk.walkId;
  destinationReached = false;
  lastLocationPushAt = 0;
  walkStatusEl.textContent = 'Safe Walk active - sharing your live location';
  startWalkButton.hidden = true;
  simulateWalkButton.hidden = true;
  arrivedButton.hidden = false;
  cancelWalkButton.hidden = false;
  return walk;
}

startWalkButton.addEventListener('click', async () => {
  if (!origin || !destination) return;

  startWalkButton.disabled = true;
  simulateWalkButton.disabled = true;
  try {
    await beginWalk();

    if (navigator.geolocation) {
      walkWatchId = navigator.geolocation.watchPosition(
        handleWalkPosition,
        () => { walkAccuracyEl.textContent = 'GPS unavailable'; },
        { enableHighAccuracy: true, maximumAge: 2000, timeout: 15000 },
      );
    } else {
      walkAccuracyEl.textContent = 'Location is not available in this browser.';
    }
  } catch (error) {
    startWalkButton.disabled = false;
    simulateWalkButton.disabled = false;
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    routeStatus.textContent = error instanceof ApiError ? error.message : 'Could not start the Safe Walk.';
  }
});

// ---------------------------------------------------------------------------
// Simulated Safe Walk - for demonstrating the feature (to a panel, or on
// control-dashboard.html on another screen) without actually walking
// anywhere. Starts a real walk and feeds it synthetic positions stepped
// along the already-computed route through the exact same
// handleWalkPosition() a real GPS reading goes through, so campus control's
// dashboard cannot tell the difference - which is the point.
// ---------------------------------------------------------------------------

const SIMULATION_STEP_MS = 1500;
// The real route from POST /routes/safe is only 2-3 waypoints (origin,
// maybe one detour, destination) - fine for drawing a line, too sparse to
// watch move. Spread a fixed step budget across those waypoints,
// proportional to each leg's share of the total distance, so a short leg
// gets fewer steps than a long one instead of every leg getting the same
// count regardless of length.
const SIMULATION_TOTAL_STEPS = 18;

function interpolateRoutePoints(points, totalSteps) {
  if (points.length < 2) return points;

  const legDistances = [];
  let totalDistance = 0;
  for (let i = 0; i < points.length - 1; i += 1) {
    const d = calculateDistanceMetres(
      points[i].latitude, points[i].longitude,
      points[i + 1].latitude, points[i + 1].longitude,
    );
    legDistances.push(d);
    totalDistance += d;
  }

  const path = [points[0]];
  for (let i = 1; i < points.length; i += 1) {
    const previous = points[i - 1];
    const point = points[i];
    const legSteps = totalDistance > 0
      ? Math.max(1, Math.round((legDistances[i - 1] / totalDistance) * totalSteps))
      : Math.ceil(totalSteps / (points.length - 1));
    for (let step = 1; step <= legSteps; step += 1) {
      const t = step / legSteps;
      path.push({
        latitude: previous.latitude + (point.latitude - previous.latitude) * t,
        longitude: previous.longitude + (point.longitude - previous.longitude) * t,
      });
    }
  }
  return path;
}

simulateWalkButton.addEventListener('click', async () => {
  if (!origin || !destination || !Array.isArray(lastRoute?.points) || lastRoute.points.length < 2) return;

  startWalkButton.disabled = true;
  simulateWalkButton.disabled = true;
  try {
    await beginWalk();
    walkAccuracyEl.textContent = 'Simulated';

    const path = interpolateRoutePoints(lastRoute.points, SIMULATION_TOTAL_STEPS);
    let index = 0;

    simulationTimer = setInterval(async () => {
      index += 1;
      if (index >= path.length) {
        stopWalkTracking();
        arrivedButton.click();
        return;
      }
      const point = path[index];
      await handleWalkPosition({ coords: { latitude: point.latitude, longitude: point.longitude, accuracy: 5 } });
    }, SIMULATION_STEP_MS);
  } catch (error) {
    startWalkButton.disabled = false;
    simulateWalkButton.disabled = false;
    if (error instanceof ApiError && error.status === 401) {
      redirectToLogin();
      return;
    }
    routeStatus.textContent = error instanceof ApiError ? error.message : 'Could not start the simulated Safe Walk.';
  }
});

arrivedButton.addEventListener('click', async () => {
  stopWalkTracking();
  try {
    await markSafeWalkArrived(currentWalkId);
  } catch {
    // The walk still ends locally even if this one call fails - the
    // student should never be stuck unable to stop sharing their location.
  }
  currentWalkId = null;
  resetWalkUi();
  walkStatusEl.textContent = '✓ Arrived safe';
  startWalkButton.disabled = true;
});

cancelWalkButton.addEventListener('click', async () => {
  stopWalkTracking();
  try {
    await cancelSafeWalk(currentWalkId);
  } catch {
    // Same as above - always stop sharing locally regardless.
  }
  currentWalkId = null;
  resetWalkUi();
  walkStatusEl.textContent = 'Cancelled';
});

// Leaving the GPS watch running after navigating away would be exactly the
// silent, always-on tracking this feature is not - same rule as
// distress-detection.js's mic/camera in incident.js.
window.addEventListener('beforeunload', () => {
  if (currentWalkId) stopWalkTracking();
});

loadMapData();