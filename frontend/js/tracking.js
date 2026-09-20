/**
 * tracking.js - Live incident GPS tracking service and Leaflet map component.
 *
 * Coordinates live location tracking between campus responders, students,
 * and campus control dispatch. Supports cross-tab synchronization via
 * storage events so changes made in one role (e.g. Responder advances)
 * instantly reflect on Student and Campus Control screens.
 */

const CAMPUS_WAYPOINTS = [
  { lat: -32.78120, lng: 26.84750, label: 'Campus Control Room' },
  { lat: -32.78180, lng: 26.84820, label: 'North Campus Avenue' },
  { lat: -32.78250, lng: 26.84900, label: 'Administration Quad' },
  { lat: -32.78310, lng: 26.84960, label: 'Central Library Walkway' },
  { lat: -32.78380, lng: 26.85030, label: 'Freedom Square Crossing' },
  { lat: -32.78430, lng: 26.85080, label: 'South Residence Path' },
  { lat: -32.78460, lng: 26.85120, label: 'Miriam Makeba Residence' },
];

// A separate, reversed-ish path for demoing student movement (e.g. a student
// walking away from where they reported, or being followed) - distinct from
// the responder's route so the two markers don't just slide along the same
// line. Only used while state.student.isSimulating is on; real device GPS
// (see startLiveLocationWatch below) overrides this the moment it reports a
// position.
const STUDENT_WAYPOINTS = [
  { lat: -32.78460, lng: 26.85120, label: 'Miriam Makeba Residence' },
  { lat: -32.78430, lng: 26.85080, label: 'South Residence Path' },
  { lat: -32.78380, lng: 26.85030, label: 'Freedom Square Crossing' },
  { lat: -32.78330, lng: 26.84965, label: 'Central Library Walkway' },
  { lat: -32.78270, lng: 26.84890, label: 'Sports Complex' },
];

const DEFAULT_STUDENT_POS = {
  lat: -32.78460,
  lng: 26.85120,
  label: 'Student Location (Miriam Makeba Hall)',
};

const STORAGE_KEY = 'ufh.liveTrackingState';

/** Calculate distance in metres between two coordinates using Haversine formula */
export function calculateDistanceMetres(lat1, lon1, lat2, lon2) {
  const R = 6371e3; // Earth radius in metres
  const phi1 = (lat1 * Math.PI) / 180;
  const phi2 = (lat2 * Math.PI) / 180;
  const deltaPhi = ((lat2 - lat1) * Math.PI) / 180;
  const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;

  const a =
    Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
    Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

  return Math.round(R * c);
}

/** Interpolate coordinates between waypoints given progress ratio 0.0 to 1.0 */
export function getInterpolatedPosition(progress, waypoints = CAMPUS_WAYPOINTS) {
  const clamped = Math.max(0, Math.min(1, progress));
  const points = waypoints;
  const totalSegments = points.length - 1;
  const scaled = clamped * totalSegments;
  const index = Math.floor(scaled);
  const fraction = scaled - index;

  if (index >= totalSegments) {
    const last = points[totalSegments];
    return { lat: last.lat, lng: last.lng };
  }

  const p1 = points[index];
  const p2 = points[index + 1];
  return {
    lat: p1.lat + (p2.lat - p1.lat) * fraction,
    lng: p1.lng + (p2.lng - p1.lng) * fraction,
  };
}

/** Get or initialize live tracking state from localStorage */
export function getTrackingState(incidentId = 41) {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (raw) {
    try {
      const state = JSON.parse(raw);
      if (state && (!incidentId || state.incidentId === incidentId)) {
        // Backfill fields added after some browsers already had a saved
        // state, so an older localStorage value doesn't crash on undefined.
        if (typeof state.student.progress !== 'number') state.student.progress = 0;
        if (typeof state.student.isSimulating !== 'boolean') state.student.isSimulating = false;
        return state;
      }
    } catch {
      // parse failed, regenerate
    }
  }

  const defaultState = {
    incidentId: incidentId || 41,
    incidentType: 'sos',
    reference: '41',
    status: 'en_route',
    responder: {
      id: 5,
      name: 'Nomsa Khumalo',
      team: 'Campus Rapid Response Unit 1',
      phone: '0834567890',
      vehicle: 'Patrol Vehicle Alpha (Golf)',
    },
    student: {
      name: 'Sipho Ndlovu',
      studentNumber: '202512345',
      phone: '0821234567',
      locationDescription: 'Miriam Makeba Residence, Block B entrance',
      lat: DEFAULT_STUDENT_POS.lat,
      lng: DEFAULT_STUDENT_POS.lng,
      // progress/isSimulating: demo-only movement along STUDENT_WAYPOINTS,
      // off by default. Real device GPS (startLiveLocationWatch) turns this
      // off the moment it reports an actual position, so the two never
      // fight over where the marker sits.
      progress: 0,
      isSimulating: false,
    },
    progress: 0.35, // 35% on the way
    speedKmh: 24,
    lastUpdate: new Date().toISOString(),
    isSimulating: true,
  };

  saveTrackingState(defaultState);
  return defaultState;
}

/** Save tracking state and trigger broadcast */
export function saveTrackingState(state) {
  state.lastUpdate = new Date().toISOString();
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  window.dispatchEvent(new CustomEvent('ufh:tracking-updated', { detail: state }));
}

/**
 * Creates and mounts an interactive Live Tracking Map instance
 *
 * @param {HTMLElement|string} target Container element or element ID
 * @param {Object} options Configuration options
 * @param {string} options.role 'student' | 'campus_control' | 'responder'
 * @param {number} options.incidentId Target incident ID
 * @param {boolean} options.showControls Allow manual step simulation
 * @returns {Object} Tracking controller with destroy() method
 */
export function createTrackingMap(target, options = {}) {
  const container = typeof target === 'string' ? document.getElementById(target) : target;
  if (!container) return null;

  if (!window.L) {
    container.innerHTML = '<div class="banner banner-error">Leaflet map library could not be loaded.</div>';
    return null;
  }

  let state = getTrackingState(options.incidentId);

  // Setup DOM scaffold
  container.innerHTML = `
    <div class="tracking-card">
      <div class="tracking-header">
        <div class="tracking-title-wrap">
          <div class="tracking-live-pill">
            <span class="tracking-live-dot" aria-hidden="true"></span>
            <span>LIVE DISPATCH FEED</span>
          </div>
          <h3 class="tracking-title">
            ${options.role === 'student' ? 'Responder En Route to Your Location' : 'Live Incident Navigation & Unit Tracking'}
          </h3>
          <p class="tracking-meta">Incident #${state.incidentId} (${state.incidentType.toUpperCase()}) &middot; Case approved for dispatch</p>
        </div>
        <div class="tracking-badge-eta" id="track-eta-badge">
          <span class="eta-label">ESTIMATED ARRIVAL</span>
          <span class="eta-time" id="track-eta-time">Calculating...</span>
        </div>
      </div>

      <div class="tracking-map-wrapper">
        <div id="leaflet-track-map-${options.incidentId || 'default'}" class="tracking-leaflet-canvas"></div>
        <div class="tracking-map-overlay-badge" id="track-overlay-status">
          <span class="overlay-pulse"></span>
          <span id="track-overlay-text">Syncing GPS coordinates...</span>
        </div>
      </div>

      <div class="tracking-telemetry-grid">
        <div class="telemetry-card">
          <span class="telemetry-label">Assigned Officer</span>
          <span class="telemetry-val" id="track-officer">${state.responder.name}</span>
          <small class="telemetry-sub" id="track-team">${state.responder.team}</small>
        </div>

        <div class="telemetry-card">
          <span class="telemetry-label">Distance to Scene</span>
          <span class="telemetry-val text-primary" id="track-distance">-- m</span>
          <small class="telemetry-sub" id="track-speed">Cruising ~${state.speedKmh} km/h</small>
        </div>

        <div class="telemetry-card">
          <span class="telemetry-label">Meeting Point</span>
          <span class="telemetry-val" id="track-student-target">${state.student.locationDescription}</span>
          <small class="telemetry-sub">Student: ${state.student.name} (${state.student.studentNumber})</small>
        </div>

        <div class="telemetry-card telemetry-actions-card">
          <span class="telemetry-label">Emergency Comms</span>
          <div class="telemetry-btn-row">
            <a href="tel:${state.responder.phone}" class="btn btn-secondary btn-sm">
              <span aria-hidden="true">&phone;</span> Call ${options.role === 'student' ? 'Responder' : 'Unit'}
            </a>
            ${options.role === 'student'
              ? `<button type="button" class="btn btn-ghost btn-sm" id="btn-update-loc">&target; Send My Exact GPS</button>`
              : `<a href="tel:${state.student.phone}" class="btn btn-secondary btn-sm">&phone; Call Student</a>`
            }
          </div>
        </div>
      </div>

      <div class="tracking-sim-bar">
        <div class="tracking-sim-status">
          <span class="sim-dot active"></span>
          <span>Simulation Mode: Responder vehicle is actively moving towards scene</span>
        </div>
        <div class="tracking-sim-controls">
          <button type="button" class="btn btn-ghost btn-sm" id="btn-step-progress">Step forward (+15%)</button>
          <button type="button" class="btn btn-ghost btn-sm" id="btn-toggle-sim">Pause live feed</button>
          <button type="button" class="btn btn-ghost btn-sm" id="btn-reset-sim">Reset to dispatch</button>
          <button type="button" class="btn btn-ghost btn-sm" id="btn-toggle-student-sim">${state.student.isSimulating ? 'Pause student movement' : 'Simulate student movement'}</button>
        </div>
      </div>
    </div>
  `;

  const mapCanvasId = `leaflet-track-map-${options.incidentId || 'default'}`;
  const mapElement = container.querySelector(`#${mapCanvasId}`);

  // Create Leaflet map centered at campus
  const map = window.L.map(mapElement, {
    zoomControl: true,
    attributionControl: false,
  }).setView([-32.78331, 26.84971], 16);

  window.L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
  }).addTo(map);

  // Custom Icon Makers
  const studentIcon = window.L.divIcon({
    className: 'custom-leaflet-marker student-marker-wrapper',
    html: `
      <div class="student-radar-pulse"></div>
      <div class="student-beacon-pin">
        <span class="beacon-icon">&bull;</span>
      </div>
      <div class="marker-tooltip-label">Student (You)</div>
    `,
    iconSize: [36, 36],
    iconAnchor: [18, 18],
  });

  const responderIcon = window.L.divIcon({
    className: 'custom-leaflet-marker responder-marker-wrapper',
    html: `
      <div class="responder-vehicle-beacon">
        <span class="responder-badge-symbol">&#128737;</span>
      </div>
      <div class="marker-tooltip-label responder-label">Security Unit</div>
    `,
    iconSize: [38, 38],
    iconAnchor: [19, 19],
  });

  // Markers & Layers
  const studentMarker = window.L.marker([state.student.lat, state.student.lng], {
    icon: studentIcon,
    zIndexOffset: 1000,
  }).addTo(map).bindPopup(`<strong>${state.student.name}</strong><br>${state.student.locationDescription}`);

  const initialRespPos = getInterpolatedPosition(state.progress);
  const responderMarker = window.L.marker([initialRespPos.lat, initialRespPos.lng], {
    icon: responderIcon,
    zIndexOffset: 1500,
  }).addTo(map).bindPopup(`<strong>${state.responder.name}</strong><br>${state.responder.team}`);

  // Waypoints Polyline
  const fullRoutePoints = CAMPUS_WAYPOINTS.map((w) => [w.lat, w.lng]);
  const routeBackground = window.L.polyline(fullRoutePoints, {
    color: '#767e8a',
    weight: 4,
    dashArray: '6, 8',
    opacity: 0.6,
  }).addTo(map);

  const activePath = window.L.polyline([[initialRespPos.lat, initialRespPos.lng], [state.student.lat, state.student.lng]], {
    color: '#00447c',
    weight: 5,
    opacity: 0.9,
  }).addTo(map);

  // Fit bounds to show both entities
  function adjustView() {
    const group = window.L.featureGroup([studentMarker, responderMarker]);
    map.fitBounds(group.getBounds(), { padding: [40, 40], maxZoom: 17 });
  }
  adjustView();

  // DOM references for live telemetry
  const etaTimeEl = container.querySelector('#track-eta-time');
  const distanceEl = container.querySelector('#track-distance');
  const overlayStatusEl = container.querySelector('#track-overlay-text');
  const toggleSimBtn = container.querySelector('#btn-toggle-sim');
  const stepBtn = container.querySelector('#btn-step-progress');
  const resetBtn = container.querySelector('#btn-reset-sim');
  const updateLocBtn = container.querySelector('#btn-update-loc');
  const toggleStudentSimBtn = container.querySelector('#btn-toggle-student-sim');

  function renderTelemetry() {
    const currentRespPos = getInterpolatedPosition(state.progress);
    responderMarker.setLatLng([currentRespPos.lat, currentRespPos.lng]);
    studentMarker.setLatLng([state.student.lat, state.student.lng]);

    // Update connecting line
    activePath.setLatLngs([[currentRespPos.lat, currentRespPos.lng], [state.student.lat, state.student.lng]]);

    const distance = calculateDistanceMetres(
      currentRespPos.lat,
      currentRespPos.lng,
      state.student.lat,
      state.student.lng
    );

    if (distanceEl) distanceEl.textContent = `${distance} metres`;

    if (state.progress >= 0.98 || distance < 20) {
      if (etaTimeEl) etaTimeEl.textContent = 'ARRIVED ON SCENE';
      if (overlayStatusEl) overlayStatusEl.textContent = 'Responder has arrived at your location';
      container.querySelector('#track-eta-badge')?.classList.add('arrived');
    } else {
      const minutes = Math.max(1, Math.ceil((distance / 1000) / (state.speedKmh / 60)));
      if (etaTimeEl) etaTimeEl.textContent = `~${minutes} MINS (${distance}m)`;
      if (overlayStatusEl) overlayStatusEl.textContent = `Unit advancing &middot; ${distance}m away &middot; Speed ~${state.speedKmh} km/h`;
      container.querySelector('#track-eta-badge')?.classList.remove('arrived');
    }
  }

  renderTelemetry();

  // Timer loop for simulation. Responder and student movement are
  // independent - either, both, or neither can be running at once.
  let intervalId = null;
  function startSimulation() {
    if (intervalId) return;
    intervalId = setInterval(() => {
      let changed = false;

      if (state.isSimulating) {
        if (state.progress < 0.98) {
          state.progress = Math.min(1.0, state.progress + 0.025);
          state.speedKmh = Math.floor(20 + Math.random() * 8);
        } else {
          state.progress = 1.0;
          state.status = 'on_scene';
        }
        changed = true;
      }

      if (state.student.isSimulating && state.student.progress < 1) {
        state.student.progress = Math.min(1, state.student.progress + 0.04);
        const studentPos = getInterpolatedPosition(state.student.progress, STUDENT_WAYPOINTS);
        state.student.lat = studentPos.lat;
        state.student.lng = studentPos.lng;
        changed = true;
      }

      if (changed) {
        saveTrackingState(state);
        renderTelemetry();
      }
    }, 2000);
  }

  function stopSimulation() {
    if (intervalId) {
      clearInterval(intervalId);
      intervalId = null;
    }
  }

  startSimulation();

  // Control handlers
  if (toggleSimBtn) {
    toggleSimBtn.addEventListener('click', () => {
      state.isSimulating = !state.isSimulating;
      toggleSimBtn.textContent = state.isSimulating ? 'Pause live feed' : 'Resume live feed';
      saveTrackingState(state);
    });
  }

  if (stepBtn) {
    stepBtn.addEventListener('click', () => {
      state.progress = Math.min(1.0, state.progress + 0.15);
      saveTrackingState(state);
      renderTelemetry();
      adjustView();
    });
  }

  if (resetBtn) {
    resetBtn.addEventListener('click', () => {
      state.progress = 0.05;
      state.status = 'en_route';
      saveTrackingState(state);
      renderTelemetry();
      adjustView();
    });
  }

  if (toggleStudentSimBtn) {
    toggleStudentSimBtn.addEventListener('click', () => {
      state.student.isSimulating = !state.student.isSimulating;
      toggleStudentSimBtn.textContent = state.student.isSimulating ? 'Pause student movement' : 'Simulate student movement';
      if (state.student.isSimulating && state.student.progress >= 1) {
        state.student.progress = 0; // restart the walk rather than sitting at the end
      }
      saveTrackingState(state);
    });
  }

  if (updateLocBtn) {
    updateLocBtn.addEventListener('click', () => {
      if (navigator.geolocation) {
        updateLocBtn.textContent = 'Updating...';
        navigator.geolocation.getCurrentPosition(
          (pos) => {
            state.student.lat = pos.coords.latitude;
            state.student.lng = pos.coords.longitude;
            state.student.locationDescription = 'Current device GPS position';
            state.student.isSimulating = false; // a real position takes over from any demo walk
            saveTrackingState(state);
            renderTelemetry();
            adjustView();
            updateLocBtn.textContent = '✓ GPS Synced';
            setTimeout(() => { updateLocBtn.textContent = '🎯 Send My Exact GPS'; }, 3000);
          },
          () => {
            // Slight jitter if denied or unavailable for demo
            state.student.lat += 0.0001;
            saveTrackingState(state);
            renderTelemetry();
            adjustView();
            updateLocBtn.textContent = '✓ Position Updated';
            setTimeout(() => { updateLocBtn.textContent = '🎯 Send My Exact GPS'; }, 3000);
          }
        );
      }
    });
  }

  // Cross-tab synchronization via storage event
  function handleStorageEvent(e) {
    if (e.key === STORAGE_KEY && e.newValue) {
      try {
        state = JSON.parse(e.newValue);
        renderTelemetry();
      } catch {}
    }
  }
  window.addEventListener('storage', handleStorageEvent);

  // Custom internal event synchronization
  function handleInternalUpdate(e) {
    if (e.detail) {
      state = e.detail;
      renderTelemetry();
    }
  }
  window.addEventListener('ufh:tracking-updated', handleInternalUpdate);

  // Resize map properly on container render
  setTimeout(() => {
    map.invalidateSize();
    adjustView();
  }, 250);

  return {
    map,
    destroy() {
      stopSimulation();
      window.removeEventListener('storage', handleStorageEvent);
      window.removeEventListener('ufh:tracking-updated', handleInternalUpdate);
      map.remove();
    },
    advance(percent = 0.1) {
      state.progress = Math.min(1.0, state.progress + percent);
      saveTrackingState(state);
      renderTelemetry();
    },
    reset() {
      state.progress = 0.05;
      saveTrackingState(state);
      renderTelemetry();
    },
  };
}

// ---------------------------------------------------------------------------
// Live device GPS - continuously updates the student's real position while
// an alert is active (started automatically alongside live listening, see
// incident.js), instead of relying on the one-shot "Send My Exact GPS"
// button. Cross-tab sync (the storage/ufh:tracking-updated events above)
// carries each update straight through to any open student, responder, or
// campus control tracking map - no separate plumbing needed here.
// ---------------------------------------------------------------------------

let liveWatchId = null;

export function startLiveLocationWatch(incidentId) {
  if (liveWatchId !== null || !navigator.geolocation) return false;

  liveWatchId = navigator.geolocation.watchPosition(
    (pos) => {
      const state = getTrackingState(incidentId);
      state.student.lat = pos.coords.latitude;
      state.student.lng = pos.coords.longitude;
      state.student.locationDescription = 'Live device GPS';
      state.student.isSimulating = false; // a real position always wins over the demo walk
      saveTrackingState(state);
    },
    () => {
      // Permission denied or position unavailable - the last known/reported
      // position just stays put. Not fatal to the rest of the alert.
    },
    { enableHighAccuracy: true, maximumAge: 5000 },
  );
  return true;
}

export function stopLiveLocationWatch() {
  if (liveWatchId !== null) {
    navigator.geolocation.clearWatch(liveWatchId);
    liveWatchId = null;
  }
}
