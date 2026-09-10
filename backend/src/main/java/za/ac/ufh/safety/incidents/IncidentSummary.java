package za.ac.ufh.safety.incidents;

import java.time.Instant;

/**
 * One row of GET /api/incidents.
 *
 * Summary shape on purpose: no description and no coordinates. The dashboard
 * lists these, and pulling a thousand-character description per row to render
 * a list nobody has opened yet is what makes a dashboard slow. Detail comes
 * from the single-incident endpoint.
 */
public record IncidentSummary(
    Long incidentId,
    IncidentType type,
    IncidentStatus status,
    Integer priority,
    Instant createdAt
) {
    static IncidentSummary of(Incident incident) {
        return new IncidentSummary(
            incident.getIncidentId(),
            incident.getType(),
            incident.getStatus(),
            incident.getPriority(),
            incident.getCreatedAt());
    }
}
