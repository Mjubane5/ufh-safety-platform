package za.ac.ufh.safety.incidents;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.ac.ufh.safety.common.ApiException;
import za.ac.ufh.safety.responders.NearestResponderSelector;
import za.ac.ufh.safety.responders.Responder;
import za.ac.ufh.safety.responders.ResponderRepository;
import za.ac.ufh.safety.responders.ResponderStatus;
import za.ac.ufh.safety.safetywalk.CppRouteEngine;
import za.ac.ufh.safety.safetywalk.GeometricRouteEngine;
import za.ac.ufh.safety.safetywalk.RouteEngine;
import za.ac.ufh.safety.safetywalk.SafeRouteResponse;
import za.ac.ufh.safety.safetywalk.SafetyWalkService;
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
 * The polyline comes from the same RouteEngine pair SafeRouteService uses
 * for Safe Walk: CppRouteEngine (real shortest-path search over the campus
 * footpath/road graph) tried first, GeometricRouteEngine (a straight line)
 * as the fallback when the C++ engine is unavailable - see buildRoute()
 * below and both engines' own class comments. `route` is still null
 * whenever there is no destination (the incident has no coordinates) or no
 * origin (the responder's own location is unknown) - a route genuinely
 * cannot be calculated in either case, C++ engine or not. Null says "no
 * path calculated"; an empty points list would claim a path exists and
 * draw nothing.
 *
 * ---------------------------------------------------------------------------
 * The specification is IncidentAssignmentServiceTest.
 * ---------------------------------------------------------------------------
 */
@Service
public class IncidentAssignmentService {

    // Matches SafeRouteService's own walking-pace assumption - this project
    // has no separate figure for a responder's pace (on foot, bike, or
    // vehicle all differ), so one documented estimate is used everywhere
    // rather than inventing an unverified second one just for this path.
    private static final double METRES_PER_SECOND = 1.33;

    private final IncidentRepository incidents;
    private final ResponderRepository responders;
    private final UserRepository users;
    private final RouteEngine cppRouteEngine;
    private final RouteEngine geometricRouteEngine;

    // Explicit @Autowired, not left implicit: this class also has a
    // package-private constructor for tests (below), and Spring will not
    // reliably pick "the public one" on its own once more than one
    // constructor exists - without this it looks for a genuine no-arg
    // constructor instead and fails to start. Learned the hard way (a
    // production crash loop), not assumed.
    @Autowired
    public IncidentAssignmentService(IncidentRepository incidents,
                                     ResponderRepository responders,
                                     UserRepository users,
                                     CppRouteEngine cppRouteEngine,
                                     GeometricRouteEngine geometricRouteEngine) {
        this.incidents = incidents;
        this.responders = responders;
        this.users = users;
        this.cppRouteEngine = cppRouteEngine;
        this.geometricRouteEngine = geometricRouteEngine;
    }

    /** Used by tests to force a specific pair of engines without a Spring context. */
    IncidentAssignmentService(IncidentRepository incidents,
                              ResponderRepository responders,
                              UserRepository users,
                              RouteEngine cppRouteEngine,
                              RouteEngine geometricRouteEngine) {
        this.incidents = incidents;
        this.responders = responders;
        this.users = users;
        this.cppRouteEngine = cppRouteEngine;
        this.geometricRouteEngine = geometricRouteEngine;
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
            buildRoute(chosen, incident));
    }

    /**
     * Null whenever a route genuinely cannot be calculated: no destination
     * (the incident has no coordinates - the auto-assign path already
     * refuses this earlier, but a dispatcher's explicit responderId can
     * still reach here with one) or no origin (the responder's own
     * location is unknown). Otherwise, the real path from CppRouteEngine,
     * falling back to GeometricRouteEngine's straight line exactly like
     * SafeRouteService does - see both classes' comments.
     */
    private AssignmentResponse.Route buildRoute(Responder responder, Incident incident) {
        if (responder.getLatitude() == null || responder.getLongitude() == null
                || incident.getLatitude() == null || incident.getLongitude() == null) {
            return null;
        }

        double fromLatitude = responder.getLatitude();
        double fromLongitude = responder.getLongitude();
        double toLatitude = incident.getLatitude();
        double toLongitude = incident.getLongitude();

        List<SafeRouteResponse.RoutePoint> path = tryEngine(
            cppRouteEngine, fromLatitude, fromLongitude, toLatitude, toLongitude);
        if (path == null || path.size() < 2) {
            path = tryEngine(geometricRouteEngine, fromLatitude, fromLongitude, toLatitude, toLongitude);
        }
        if (path == null || path.size() < 2) {
            path = List.of(
                new SafeRouteResponse.RoutePoint(fromLatitude, fromLongitude),
                new SafeRouteResponse.RoutePoint(toLatitude, toLongitude));
        }

        double distanceMetres = 0.0;
        List<AssignmentResponse.Point> points = new ArrayList<>(path.size());
        for (int i = 0; i < path.size(); i++) {
            SafeRouteResponse.RoutePoint point = path.get(i);
            points.add(new AssignmentResponse.Point(point.latitude(), point.longitude()));
            if (i > 0) {
                SafeRouteResponse.RoutePoint previous = path.get(i - 1);
                distanceMetres += SafetyWalkService.distanceMetres(
                    previous.latitude(), previous.longitude(), point.latitude(), point.longitude());
            }
        }

        int estimatedSeconds = (int) Math.ceil(distanceMetres / METRES_PER_SECOND);
        return new AssignmentResponse.Route((int) Math.round(distanceMetres), estimatedSeconds, points);
    }

    /** A dispatch must never fail because a routing engine misbehaved - see SafeRouteService's identical guard. */
    private static List<SafeRouteResponse.RoutePoint> tryEngine(
            RouteEngine engine,
            double fromLatitude, double fromLongitude,
            double toLatitude, double toLongitude) {
        try {
            return engine.findPath(fromLatitude, fromLongitude, toLatitude, toLongitude, List.of());
        } catch (RuntimeException e) {
            return null;
        }
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
