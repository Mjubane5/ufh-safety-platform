/**
 * One incident, in full. Reads the id from ?id= in the query string.
 *
 * SCAFFOLD - the rendering is not written yet. Owner: Awethu.
 *
 * ---------------------------------------------------------------------------
 * What this page is for
 * ---------------------------------------------------------------------------
 *
 * dashboard.js lists incidents in the summary shape - no description, no
 * coordinates. This page fetches the detail shape for one of them, and is the
 * only place a student can cancel a false alarm.
 *
 * It uses three functions that PR #20 added to api.js and that nothing has
 * called until now:
 *
 *   getIncident(incidentId)            the detail shape
 *   cancelIncident(incidentId, reason) the false-alarm path
 *   updateIncidentStatus(...)          responder and campus control only,
 *                                      so NOT used on this student-facing page
 *
 * ---------------------------------------------------------------------------
 * The rules that matter here
 * ---------------------------------------------------------------------------
 *
 * 1. Every field can be null. The contract says so and the backend enforces
 *    it. Specifically:
 *
 *      reporter           null when the report was anonymous
 *      assignedResponder  null until somebody is assigned
 *      description        null when the student did not type one
 *      latitude/longitude/accuracy   null when there was no GPS fix
 *
 *    Never write incident.reporter.fullName without checking reporter first.
 *    dashboard.js already does this properly - copy its habits.
 *
 * 2. A loading state on every request and a readable error when one fails.
 *    Not a spinner that hides what went wrong.
 *
 * 3. Mobile first. A student reads this on a phone, probably in a hurry.
 *
 * 4. Cancelling is destructive from the student's point of view, so confirm
 *    before calling the API. It is not destructive in the database - the row
 *    is kept and the status set to cancelled - but the student does not know
 *    that, and an accidental cancel on a real emergency is the worst bug this
 *    page could have.
 *
 * ---------------------------------------------------------------------------
 * When cancel should be offered
 * ---------------------------------------------------------------------------
 *
 * The backend allows cancel from any live status and refuses it once the
 * incident is resolved or cancelled. Mirror that here so the button is not
 * offered when the server is going to refuse it:
 *
 *   reported, triaged, assigned, en_route, on_scene  -> show the button
 *   resolved, cancelled                              -> hide it
 *
 * Mirror, do not trust. The server check is the real one; this is only so the
 * student is not shown a control that cannot work.
 *
 * ---------------------------------------------------------------------------
 * Errors worth handling by name
 * ---------------------------------------------------------------------------
 *
 *   401  session expired      -> redirect to login, same as dashboard.js
 *   403  not your incident    -> readable message, not a raw code
 *   404  no such incident     -> the backend also returns this when the
 *                                incident exists but belongs to somebody
 *                                else, so word the message carefully:
 *                                "We could not find that report" is honest,
 *                                "it was deleted" is not
 *   409  already resolved     -> the cancel was refused; re-fetch and
 *                                re-render so the page shows the real state
 *
 * ---------------------------------------------------------------------------
 * A decision to make before writing much
 * ---------------------------------------------------------------------------
 *
 * dashboard.js has el(), labelFor(), relativeTime(), exactTime(),
 * pillClassFor() and the STATUS_LABELS / TYPE_LABELS maps. This page needs
 * most of them.
 *
 * Copying them means two copies drifting apart - the day somebody adds a
 * status, one page renders it and the other shows a blank pill.
 *
 * The alternative is pulling them into a shared module, say js/ui.js, and
 * importing from both. That is the better answer but it edits dashboard.js,
 * so decide it deliberately rather than by accident. Your call.
 *
 * ---------------------------------------------------------------------------
 * Suggested order
 * ---------------------------------------------------------------------------
 *
 *   1. Read the id from the query string, reject a missing or non-numeric one
 *      before making any request
 *   2. Loading state
 *   3. Happy path render with every field present
 *   4. Null cases - anonymous reporter, no responder, no coordinates
 *   5. Error states, 401 / 403 / 404
 *   6. Cancel, with confirmation
 *   7. 409 on cancel
 *
 * Each of those is a commit.
 */

import { getIncident, cancelIncident, isLoggedIn, logout, ApiError } from './api.js';

// ---------------------------------------------------------------------------
// Wiring that is already done
// ---------------------------------------------------------------------------

const detail = document.getElementById('incident-detail');
const heading = document.getElementById('incident-heading');
const bannerSlot = document.getElementById('banner-slot');
const cancelSlot = document.getElementById('cancel-slot');
const signOutButton = document.getElementById('signout-button');

if (signOutButton) {
  signOutButton.addEventListener('click', () => {
    logout();
    window.location.href = './login.html';
  });
}

/**
 * The incident id from ?id= in the address bar, or null when it is missing or
 * not a number.
 *
 * Returns null rather than NaN so the caller has one thing to check. A NaN id
 * would sail into the URL and produce a confusing 404 from the server instead
 * of a clear message here.
 */
export function incidentIdFromUrl(search = window.location.search) {
  const raw = new URLSearchParams(search).get('id');
  if (raw === null || raw.trim() === '') return null;

  // Number() rather than parseInt(): parseInt('42abc') is 42, which would
  // accept a malformed url as if it were fine.
  const id = Number(raw);
  return Number.isInteger(id) && id > 0 ? id : null;
}

// ---------------------------------------------------------------------------
// TODO Awethu - everything below here
// ---------------------------------------------------------------------------

async function load() {
  if (!isLoggedIn()) {
    window.location.href = './login.html';
    return;
  }

  const incidentId = incidentIdFromUrl();
  if (incidentId === null) {
    // TODO: show a readable message. The student arrived without an id,
    // usually from a stale bookmark. Offer a link back to the dashboard.
    return;
  }

  // TODO: loading state, then getIncident(incidentId), then render.
  // Catch ApiError and branch on err.status - see the notes at the top.
}

load();
