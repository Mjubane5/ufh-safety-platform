package za.ac.ufh.safety.incidents;

import java.util.List;

/**
 * Response of POST /api/incidents/{incidentId}/assign. Contract section 4.
 *
 * Note this is NOT the full incident shape the other lifecycle endpoints
 * return — assignment answers "who is going, and by what path", so it carries
 * the responder and the route instead of the incident's own detail.
 */
public record AssignmentResponse(
    Long incidentId,
    IncidentStatus status,
    Responder responder,
    Route route
) {

    public record Responder(Long responderId, String fullName) {}

    /**
     * The path the responder should take.
     *
     * Null when no route could be calculated — which is every case where the
     * incident has no coordinates, because there is no destination to route
     * to. Null, not an empty route: an empty points list would draw nothing
     * on the map while claiming a path exists.
     */
    public record Route(
        Integer distanceMetres,
        Integer estimatedSeconds,
        List<Point> points
    ) {}

    /** One vertex of the polyline the frontend draws. */
    public record Point(Double latitude, Double longitude) {}
}
