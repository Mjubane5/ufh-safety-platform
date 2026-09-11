package za.ac.ufh.safety.incidents;

import java.util.Map;
import java.util.Set;

/**
 * Which status changes are legal, from docs/api-contract.md section 3:
 *
 *   reported -> triaged -> assigned -> en_route -> on_scene -> resolved
 *                                                           -> cancelled
 *
 * Kept in its own class because it is the rule a panel is most likely to ask
 * about, and because a table of allowed moves is easier to read and to test
 * than the same rule spread through if-statements in the service.
 *
 * Two deliberate decisions:
 *
 *  - CANCELLED is reachable from every live status. A false alarm can be
 *    discovered at any point before the incident closes, and the alternative
 *    is a responder driving to something everyone already knows is over.
 *
 *  - RESOLVED and CANCELLED go nowhere. They are terminal. Reopening would
 *    lose the record of what actually happened, and incidents are never
 *    hard-deleted, so the history has to stay truthful.
 */
final class StatusTransitions {

    private static final Map<IncidentStatus, Set<IncidentStatus>> ALLOWED = Map.of(
        IncidentStatus.REPORTED, Set.of(IncidentStatus.TRIAGED, IncidentStatus.ASSIGNED, IncidentStatus.CANCELLED),
        IncidentStatus.TRIAGED,  Set.of(IncidentStatus.ASSIGNED, IncidentStatus.CANCELLED),
        IncidentStatus.ASSIGNED, Set.of(IncidentStatus.EN_ROUTE, IncidentStatus.CANCELLED),
        IncidentStatus.EN_ROUTE, Set.of(IncidentStatus.ON_SCENE, IncidentStatus.CANCELLED),
        IncidentStatus.ON_SCENE, Set.of(IncidentStatus.RESOLVED, IncidentStatus.CANCELLED),
        IncidentStatus.RESOLVED, Set.of(),
        IncidentStatus.CANCELLED, Set.of());

    private StatusTransitions() {}

    /** True when an incident at `from` is allowed to move to `to`. */
    static boolean isLegal(IncidentStatus from, IncidentStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** True when the incident has closed and can no longer change. */
    static boolean isTerminal(IncidentStatus status) {
        return ALLOWED.getOrDefault(status, Set.of()).isEmpty();
    }
}
