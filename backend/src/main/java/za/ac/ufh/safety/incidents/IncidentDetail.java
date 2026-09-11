package za.ac.ufh.safety.incidents;

import java.time.Instant;
import za.ac.ufh.safety.user.User;

/**
 * The full incident shape returned by GET /api/incidents/{incidentId},
 * PATCH .../status and POST .../cancel. Contract sections 3 and 4.
 *
 * Unlike IncidentSummary this carries the description and the coordinates,
 * because by the time somebody opens one incident they need to know what
 * happened and where.
 */
public record IncidentDetail(
    Long incidentId,
    IncidentType type,
    String description,
    IncidentStatus status,
    Integer priority,
    Double latitude,
    Double longitude,
    Double accuracy,
    String locationSource,
    Boolean anonymous,
    Instant createdAt,
    Instant updatedAt,
    Reporter reporter,
    AssignedResponder assignedResponder
) {

    /** Null in the response when the incident was reported anonymously. */
    public record Reporter(Long userId, String fullName) {}

    /**
     * Null until the incident is assigned.
     *
     * latitude and longitude are always null for now: a responder's live
     * position needs a responders table that does not exist yet, and the
     * contract says an absent value is null rather than 0 or -1. Sending 0
     * would put every responder off the coast of Ghana.
     */
    public record AssignedResponder(Long responderId, String fullName,
                                    Double latitude, Double longitude) {}

    static IncidentDetail of(Incident incident, User reporter, User responder) {
        return new IncidentDetail(
            incident.getIncidentId(),
            incident.getType(),
            incident.getDescription(),
            incident.getStatus(),
            incident.getPriority(),
            incident.getLatitude(),
            incident.getLongitude(),
            incident.getAccuracy(),
            incident.getLocationSource(),
            incident.getAnonymous(),
            incident.getCreatedAt(),
            incident.getUpdatedAt(),
            // Anonymous is a promise to the student, so it is enforced here on
            // the way out rather than trusted to every caller to respect.
            Boolean.TRUE.equals(incident.getAnonymous()) || reporter == null
                ? null
                : new Reporter(reporter.getUserId(), reporter.getFullName()),
            responder == null
                ? null
                : new AssignedResponder(responder.getUserId(), responder.getFullName(), null, null));
    }
}
