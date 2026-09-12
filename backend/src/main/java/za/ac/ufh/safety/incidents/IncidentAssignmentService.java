package za.ac.ufh.safety.incidents;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.responders.NearestResponderSelector;
import za.ac.ufh.safety.responders.Responder;
import za.ac.ufh.safety.responders.ResponderRepository;
import za.ac.ufh.safety.responders.ResponderStatus;
import za.ac.ufh.safety.user.User;
import za.ac.ufh.safety.user.UserRepository;

/**
 * POST /api/incidents/{incidentId}/assign — decides who is going.
 *
 * Implemented against IncidentAssignmentServiceTest. Owner: nondumisombuli.
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
    @Transactional
    public AssignmentResponse assign(String email, Long incidentId, AssignIncidentRequest request) {
        User caller = requireUser(email);
        requireDispatcher(caller);

        Incident incident = incidents.findById(incidentId)
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "No such incident.", null));

        requireUnassigned(incident);

        Long named = request == null ? null : request.responderId();
        Responder chosen = named == null ? nearestTo(incident) : namedResponder(named);

        // All three writes, in one transaction. The third is the one that is
        // easy to forget: without it the same person is picked for every
        // incident on campus.
        incident.setStatus(IncidentStatus.ASSIGNED);
        incident.setAssignedResponderId(chosen.getResponderId());
        chosen.setStatus(ResponderStatus.ASSIGNED);
        incidents.save(incident);
        responders.save(chosen);

        return new AssignmentResponse(
            incident.getIncidentId(),
            incident.getStatus(),
            new AssignmentResponse.Responder(chosen.getResponderId(), nameOf(chosen)),
            // Null until the C++ shortest-path module is wired in. Null says
            // "no path calculated"; an empty points list would claim a path
            // exists and then draw nothing.
            null);
    }

    /** Campus control and admin only. A student cannot dispatch anyone. */
    private void requireDispatcher(User caller) {
        switch (caller.getRole()) {
            case "campus_control", "admin" -> { }
            default -> throw new ApiException(403, "FORBIDDEN",
                "Only campus control can assign a responder.", null);
        }
    }

    /**
     * An incident already has somebody on the way, or has finished.
     *
     * The second check reuses the transition table rather than listing the
     * finished statuses again here. Two copies of that list would disagree
     * the day somebody adds a status.
     */
    private void requireUnassigned(Incident incident) {
        if (incident.getAssignedResponderId() != null) {
            throw new ApiException(409, "CONFLICT",
                "This incident already has a responder assigned.", null);
        }
        if (!StatusTransitions.isLegal(incident.getStatus(), IncidentStatus.ASSIGNED)) {
            throw new ApiException(409, "CONFLICT",
                "An incident that is " + incident.getStatus().wireValue()
                    + " cannot be assigned.", "status");
        }
    }

    /**
     * The dispatcher named somebody. No distance is calculated, because a
     * human already decided — which is why this path works even when the
     * incident has no coordinates and the auto path does not.
     *
     * Off duty is still refused. That responder is not on campus, and the
     * dispatcher is probably reading a stale roster.
     */
    private Responder namedResponder(Long responderId) {
        Responder responder = responders.findById(responderId)
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND",
                "No such responder.", "responderId"));

        if (responder.getStatus() == ResponderStatus.OFF_DUTY) {
            throw new ApiException(409, "CONFLICT",
                "That responder is off duty.", "responderId");
        }
        if (responder.getStatus() == ResponderStatus.ASSIGNED) {
            throw new ApiException(409, "CONFLICT",
                "That responder is already on another call.", "responderId");
        }
        return responder;
    }

    /**
     * Nobody was named, so the backend chooses.
     *
     * The location check comes first and is the rule a panel will press on.
     * With no coordinates there is no origin to measure from, so "nearest"
     * means nothing; picking anyway would send help to the wrong place while
     * the screen looked correct. The contract sends these to campus control
     * for manual triage, which is the explicit path above.
     */
    private Responder nearestTo(Incident incident) {
        if (incident.getLatitude() == null || incident.getLongitude() == null) {
            throw new ApiException(409, "CONFLICT",
                "This incident has no location, so the nearest responder cannot be "
                    + "worked out. Choose a responder by hand.", null);
        }

        List<Responder> available = responders.findByStatus(ResponderStatus.AVAILABLE);
        return NearestResponderSelector
            .choose(available, incident.getLatitude(), incident.getLongitude())
            .orElseThrow(() -> new ApiException(404, "NOT_FOUND",
                "No responder is available right now.", null));
    }

    /**
     * The responder's name comes from the users table — responder_id is the
     * same number as user_id. A missing row gives null rather than failing
     * the assignment: the response shape allows a null name, and somebody is
     * on the way either way.
     */
    private String nameOf(Responder responder) {
        return users.findById(responder.getResponderId())
            .map(User::getFullName)
            .orElse(null);
    }

    private User requireUser(String email) {
        return users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ApiException(401, "NOT_AUTHENTICATED",
                "Sign in again.", null));
    }
}
