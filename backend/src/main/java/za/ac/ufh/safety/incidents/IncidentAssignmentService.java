package za.ac.ufh.safety.incidents;

import org.springframework.stereotype.Service;
import za.ac.ufh.safety.responders.ResponderRepository;
import za.ac.ufh.safety.user.UserRepository;

/**
 * POST /api/incidents/{incidentId}/assign — decides who is going.
 *
 * SCAFFOLD — not implemented yet. Owner: nondumisombuli.
 *
 * ---------------------------------------------------------------------------
 * The rules, from docs/api-contract.md section 4
 * ---------------------------------------------------------------------------
 *
 * Roles: campus_control and admin only. A student cannot dispatch anyone.
 *
 * Two paths, depending on whether the dispatcher named a responder:
 *
 *   responderId present -> use that person, no distance calculation
 *   responderId absent  -> NearestResponderSelector picks the closest
 *
 * Refusals:
 *
 *   409  the incident is already assigned
 *   409  auto-assignment was asked for on an incident with no coordinates
 *   404  nobody qualified
 *
 * ---------------------------------------------------------------------------
 * The rule to get right, and the one a panel will press on
 * ---------------------------------------------------------------------------
 *
 * An incident with locationSource "none" has no position. For that incident:
 *
 *   - auto-assignment must return 409. There is nothing to measure distance
 *     from, so "nearest" is meaningless. Picking anyway would be choosing at
 *     random and labelling the result "nearest available responder" — help
 *     goes to the wrong place while the screen looks correct. The contract
 *     routes these to campus control for manual triage instead.
 *
 *   - an explicit responderId must still succeed. A dispatcher who has read
 *     the description and worked out roughly where the student is may assign
 *     by hand. The `route` is null in that response, because a route cannot
 *     be calculated to an unknown destination.
 *
 * Those two look contradictory and are not. The difference is that a human
 * decided the second one.
 *
 * ---------------------------------------------------------------------------
 * What assignment has to change
 * ---------------------------------------------------------------------------
 *
 * 1. incident.status      -> ASSIGNED
 * 2. incident.assignedResponderId -> the chosen responder
 * 3. responder.status     -> ASSIGNED, so the next call does not pick the
 *                            same person again
 *
 * Step 3 is easy to forget and it is why the release side matters — see
 * below. Without it one responder gets sent to every incident on campus.
 *
 * ---------------------------------------------------------------------------
 * The release side — do not skip this
 * ---------------------------------------------------------------------------
 *
 * Nothing currently puts a responder back to AVAILABLE. When an incident
 * reaches RESOLVED or CANCELLED, its responder has to be freed in
 * IncidentService.updateStatus and IncidentService.cancel.
 *
 * Leave it out and the roster drains: every assignment removes somebody from
 * the pool permanently, and after a few incidents auto-assignment returns 404
 * for everything. That surfaces during a live demonstration rather than
 * before one.
 *
 * ---------------------------------------------------------------------------
 * Routes
 * ---------------------------------------------------------------------------
 *
 * The real polyline comes from the C++ shortest-path module over the campus
 * footpath graph, which is not wired into the request path yet. Until then
 * return null for `route` in every case. Null says "no path calculated". An
 * empty points list would claim a path exists and draw nothing.
 *
 * ---------------------------------------------------------------------------
 * The specification is IncidentAssignmentServiceTest, nine @Disabled tests.
 * Enable one, watch it fail, make it pass.
 * ---------------------------------------------------------------------------
 */
@Service
public class IncidentAssignmentService {

    private final IncidentRepository incidents;
    private final ResponderRepository responders;
    private final UserRepository users;

    public IncidentAssignmentService(IncidentRepository incidents,
                                     ResponderRepository responders,
                                     UserRepository users) {
        this.incidents = incidents;
        this.responders = responders;
        this.users = users;
    }

    /**
     * @param email       the caller, from the verified token
     * @param incidentId  the incident to dispatch to
     * @param request     may carry a responderId, or not
     */
    public AssignmentResponse assign(String email, Long incidentId, AssignIncidentRequest request) {
        throw new UnsupportedOperationException(
            "IncidentAssignmentService.assign is not implemented yet — see the notes above.");
    }
}
